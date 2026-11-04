package io.github.m1omg.opuscast.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A pretend Samsung TV: device description, SCPD, SOAP control and GENA. */
class FakeTv(samsung: Boolean = true) : AutoCloseable {
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val calls: MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList()) // (action, body)
    @Volatile var state = "STOPPED"
    @Volatile var uri = ""
    @Volatile var fetchedMedia = false
    val location get() = "http://127.0.0.1:${http.address.port}/dmr"

    init {
        val desc = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>[TV] Living Room</friendlyName>
            <manufacturer>Samsung Electronics</manufacturer><modelName>QE55Q80</modelName><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/AVTransport1</controlURL>
              <eventSubURL>/upnp/event/AVTransport1</eventSubURL><SCPDURL>/av.xml</SCPDURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/upnp/control/RenderingControl1</controlURL>
              <eventSubURL>/upnp/event/RenderingControl1</eventSubURL><SCPDURL>/rc.xml</SCPDURL></service>
            </serviceList></device></root>"""
        val rcActions = listOf("GetVolume", "SetVolume") + if (samsung) listOf("X_ControlCaption", "X_GetCaptionState") else emptyList()
        val rc = "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><actionList>" +
            rcActions.joinToString("") { "<action><name>$it</name></action>" } + "</actionList></scpd>"
        fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String, headers: Map<String, String> = emptyMap()) {
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val b = body.toByteArray()
            ex.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong())
            if (b.isNotEmpty()) ex.responseBody.use { it.write(b) } else ex.close()
        }
        http.createContext("/dmr") { reply(it, 200, desc) }
        http.createContext("/rc.xml") { reply(it, 200, rc) }
        http.createContext("/av.xml") { reply(it, 200, "<scpd><actionList><action><name>Play</name></action></actionList></scpd>") }
        http.createContext("/upnp/event/") { ex ->
            calls += ex.requestMethod to (ex.requestHeaders.getFirst("CALLBACK") ?: ex.requestHeaders.getFirst("SID") ?: "")
            reply(ex, 200, "", mapOf("SID" to "uuid:sub-1", "TIMEOUT" to "Second-300"))
        }
        http.createContext("/upnp/control/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val action = ex.requestHeaders.getFirst("SOAPACTION")!!.substringAfter('#').trim('"')
            calls += action to body
            val out = when (action) {
                "SetAVTransportURI" -> {
                    uri = xmlUnescape(Regex("<CurrentURI>([^<]*)</CurrentURI>").find(body)!!.groupValues[1])
                    // like a TV: fetch the start of the file
                    runCatching {
                        val c = java.net.URL(uri).openConnection()
                        c.setRequestProperty("Range", "bytes=0-99")
                        c.getInputStream().use { it.readBytes() }
                        fetchedMedia = true
                    }
                    ""
                }
                "Play" -> { state = "PLAYING"; "" }
                "Stop" -> { state = "STOPPED"; "" }
                "Pause" -> { state = "PAUSED_PLAYBACK"; "" }
                "Seek" -> ""
                "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState>"
                "GetPositionInfo" -> "<TrackDuration>0:10:00</TrackDuration><RelTime>0:00:42</RelTime><TrackURI>${xmlEscape(uri)}</TrackURI>"
                "GetVolume" -> "<CurrentVolume>23</CurrentVolume>"
                "SetVolume" -> ""
                "X_GetCaptionState" -> "<Captions>" + xmlEscape("<captionFileInfo uri=\"${uri.substringBeforeLast('.')}.srt\"/>") + "</Captions><EnabledCaptions></EnabledCaptions>"
                "X_ControlCaption" -> ""
                "Explode" -> null
                else -> null
            }
            if (out == null) {
                reply(ex, 500, "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>401</errorCode>" +
                    "<errorDescription>Invalid Action</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>")
            } else {
                reply(ex, 200, "<?xml version=\"1.0\"?><s:Envelope><s:Body><u:${action}Response>$out</u:${action}Response></s:Body></s:Envelope>")
            }
        }
        http.start()
    }

    fun actions() = calls.map { it.first }
    fun body(action: String) = calls.last { it.first == action }.second
    override fun close() = http.stop(0)
}

class TvTest {
    private val fake = FakeTv()
    private val server = MediaServer(18775)

    @AfterTest fun close() {
        server.close()
        fake.close()
    }

    @Test fun describesAndControls() {
        val tv = Tv.fromLocation(fake.location)
        assertEquals("[TV] Living Room", tv.name)
        assertEquals("127.0.0.1", tv.host)
        assertEquals("Samsung Electronics QE55Q80", "${tv.manufacturer} ${tv.model}")
        assertTrue(tv.services["AVTransport"]!!.control.endsWith("/upnp/control/AVTransport1"))
        tv.play()
        assertEquals("PLAYING", tv.state())
        tv.seek(3723.6)
        assertTrue("<Target>01:02:03</Target>" in fake.body("Seek"))
        val p = tv.position()
        assertEquals(42, p.pos)
        assertEquals(600, p.duration)
        assertEquals(23, tv.getVolume())
        tv.setVolume(140)
        assertTrue("<DesiredVolume>100</DesiredVolume>" in fake.body("SetVolume"))
        assertTrue(tv.canSwitchSubtitles())
        val e = assertFailsWith<TvException> { tv.soap("AVTransport", "Explode") }
        assertEquals(TvException.Reason.REFUSED, e.reason)
        assertEquals("Explode: Invalid Action", e.detail)
        assertEquals(TvException.Reason.UNSUPPORTED, assertFailsWith<TvException> { tv.soap("ConnectionManager", "GetProtocolInfo") }.reason)
        assertEquals(null, tv.playsVideo())
    }

    @Test fun gena() {
        val tv = Tv.fromLocation(fake.location)
        assertEquals("uuid:sub-1" to 300, tv.subscribe("AVTransport", "http://127.0.0.1:1/events/AVTransport"))
        assertEquals("SUBSCRIBE" to "<http://127.0.0.1:1/events/AVTransport>", fake.calls.last())
        assertEquals("uuid:sub-1" to 300, tv.renew("AVTransport", "uuid:sub-1"))
        tv.unsubscribe("AVTransport", "uuid:sub-1")
        assertEquals("UNSUBSCRIBE" to "uuid:sub-1", fake.calls.last())
    }

    @Test fun playsWithSubtitlesTheSamsungWay() {
        val tv = Tv.fromLocation(fake.location)
        val file = File.createTempFile("Film", ".mp4").apply { writeBytes(ByteArray(5000)); deleteOnExit() }
        val media = startPlayback(tv, server, FileSource(file), startAt = 30.0,
            subtitle = "1\n00:00:01,000 --> 00:00:02,000\nAhoj\n".toByteArray(), subsOn = true)
        assertEquals("ok", media.captions)
        assertTrue(fake.fetchedMedia)
        assertTrue(media.requested)
        val set = fake.body("SetAVTransportURI")
        val didlText = xmlUnescape(Regex("<CurrentURIMetaData>([^<]*)</CurrentURIMetaData>").find(set)!!.groupValues[1])
        val subUrl = server.subUrl(media, tv.host)!!
        assertTrue("<sec:CaptionInfoEx sec:type=\"srt\">$subUrl</sec:CaptionInfoEx>" in didlText, didlText)
        assertTrue("pv:subtitleFileUri=\"$subUrl\"" in didlText)
        assertTrue("size=\"5000\"" in didlText)
        val actions = fake.actions()
        assertEquals(listOf("Stop", "SetAVTransportURI", "Play"), actions.filter { it in setOf("Stop", "SetAVTransportURI", "Play") }.take(3))
        assertTrue("<Target>00:00:30</Target>" in fake.body("Seek"))
        val caption = fake.body("X_ControlCaption")
        assertTrue("<ResourceURI>dlna://" in caption, caption)
        assertTrue("<CaptionURI>$subUrl</CaptionURI>" in caption)
        assertTrue("<Operation>Enable</Operation>" in caption)
        assertEquals(1, media.captionSeq)
        // "off" is the empty file
        assertEquals("ok", showSubtitle(tv, server, media, null))
        assertTrue("<CaptionURI>${server.urlFor(tv.host, media.blankPath)}</CaptionURI>" in fake.body("X_ControlCaption"))
    }

    @Test fun otherTvsTakeSubtitlesWithTheVideo() {
        FakeTv(samsung = false).use { lg ->
            val tv = Tv.fromLocation(lg.location)
            val file = File.createTempFile("Film", ".mkv").apply { writeBytes(ByteArray(10)); deleteOnExit() }
            val media = startPlayback(tv, server, FileSource(file), subtitle = "x".toByteArray(), subsOn = true)
            assertEquals("unsupported", media.captions)
            assertTrue("X_ControlCaption" !in lg.actions())
            assertEquals("unsupported", showSubtitle(tv, server, media, media.subPath))
        }
    }
}
