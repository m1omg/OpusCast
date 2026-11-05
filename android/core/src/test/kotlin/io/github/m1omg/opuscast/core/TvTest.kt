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

/** A pretend Samsung TV: device description, SCPD, SOAP control and GENA. Or another make:
 *  [sink] is its format list (null: no ConnectionManager, like the Samsung this started from), Play
 *  answers 701 [playBusy] times first, [relTime] = false refuses REL_TIME seeks, [startsBySelf] starts
 *  playing on SetAVTransportURI. */
class FakeTv(samsung: Boolean = true, sink: String? = null, @Volatile var playBusy: Int = 0, relTime: Boolean = true,
             startsBySelf: Boolean = false) : AutoCloseable {
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
            ${if (sink == null) "" else "<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>" +
                "<controlURL>/upnp/control/ConnectionManager1</controlURL><SCPDURL>/cm.xml</SCPDURL></service>"}
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
                    if (startsBySelf) state = "PLAYING"
                    // like a TV: fetch the start of the file
                    runCatching {
                        val c = java.net.URL(uri).openConnection()
                        c.setRequestProperty("Range", "bytes=0-99")
                        c.getInputStream().use { it.readBytes() }
                        fetchedMedia = true
                    }
                    ""
                }
                "Play" -> if (playBusy > 0) { playBusy -= 1; "busy" } else { state = "PLAYING"; "" }
                "Stop" -> { state = "STOPPED"; "" }
                "Pause" -> { state = "PAUSED_PLAYBACK"; "" }
                "Seek" -> if (!relTime && "<Unit>REL_TIME</Unit>" in body) "badseek" else ""
                "GetProtocolInfo" -> "<Source></Source><Sink>${xmlEscape(sink ?: "")}</Sink>"
                "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState>"
                "GetPositionInfo" -> "<TrackDuration>0:10:00</TrackDuration><RelTime>0:00:42</RelTime><TrackURI>${xmlEscape(uri)}</TrackURI>"
                "GetVolume" -> "<CurrentVolume>23</CurrentVolume>"
                "SetVolume" -> ""
                "X_GetCaptionState" -> "<Captions>" + xmlEscape("<captionFileInfo uri=\"${uri.substringBeforeLast('.')}.srt\"/>") + "</Captions><EnabledCaptions></EnabledCaptions>"
                "X_ControlCaption" -> ""
                "Explode" -> null
                else -> null
            }
            fun fault(code: Int, text: String) = reply(ex, 500, "<s:Envelope><s:Body><s:Fault><detail><UPnPError>" +
                "<errorCode>$code</errorCode><errorDescription>$text</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>")
            if (out == null) {
                fault(401, "Invalid Action")
            } else if (out == "busy") {
                fault(701, "Transition not available")
            } else if (out == "badseek") {
                fault(710, "Seek mode not supported")
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

    private fun didlOf(fake: FakeTv) =
        xmlUnescape(Regex("<CurrentURIMetaData>([^<]*)</CurrentURIMetaData>").find(fake.body("SetAVTransportURI"))!!.groupValues[1])

    @Test fun readsTheTvsFormatList() {
        val sink = "http-get:*:video/x-matroska:*,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_HP_HD_AAC,http-get:*:audio/x-flac:*," +
            "http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM,http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG,rtsp-rtp-udp:*:video/x-ms-wmv:*"
        FakeTv(samsung = false, sink = sink).use { philips ->
            val tv = Tv.fromLocation(philips.location)
            val formats = tv.sink()
            assertEquals(true, tv.playsVideo())
            assertEquals("video/x-matroska", formats.spell("video/x-mkv"))
            assertEquals("audio/x-flac", formats.spell("audio/flac"))
            assertEquals(false, formats.accepts("audio/wav")) // L16 is raw PCM, not a WAV file
            assertEquals(false, formats.accepts("video/x-ms-wmv")) // only over RTSP
            assertEquals(true, formats.accepts("image/jpeg"))
            assertTrue(tv.sink() === formats) // asked once
            assertEquals(1, philips.actions().count { it == "GetProtocolInfo" })
        }
        FakeTv(samsung = false, sink = "http-get:*:audio/mpeg:*,http-get:*:audio/x-flac:*").use { speaker ->
            assertEquals(false, Tv.fromLocation(speaker.location).playsVideo())
        }
    }

    @Test fun playIsRetriedWhileTheTvIsBusy() {
        FakeTv(playBusy = 2).use { busy ->
            val tv = Tv.fromLocation(busy.location)
            val file = File.createTempFile("Film", ".mp4").apply { writeBytes(ByteArray(100)); deleteOnExit() }
            startPlayback(tv, server, FileSource(file))
            assertEquals(3, busy.actions().count { it == "Play" })
            assertEquals("PLAYING", busy.state)
        }
        FakeTv(playBusy = 100, startsBySelf = true).use { self -> // busy, but it started by itself: that counts
            val tv = Tv.fromLocation(self.location)
            val file = File.createTempFile("Film", ".mp4").apply { writeBytes(ByteArray(100)); deleteOnExit() }
            startPlayback(tv, server, FileSource(file))
            assertEquals(1, self.actions().count { it == "Play" })
        }
        FakeTv(playBusy = 100).use { stuck ->
            val tv = Tv.fromLocation(stuck.location)
            val e = assertFailsWith<TvException> { playAfterSet(tv, pauseMs = 5) }
            assertEquals(701, e.upnpCode)
            assertEquals(5, stuck.actions().count { it == "Play" })
        }
    }

    @Test fun seekFallsBackToAbsTime() {
        FakeTv(relTime = false).use { old ->
            val tv = Tv.fromLocation(old.location)
            tv.seek(90.0)
            assertTrue("<Unit>ABS_TIME</Unit>" in old.body("Seek"))
            assertTrue("<Target>00:01:30</Target>" in old.body("Seek"))
        }
        tvAt(fake).seek(5.0)
        assertEquals(1, fake.actions().count { it == "Seek" }) // REL_TIME worked
    }

    private fun tvAt(f: FakeTv) = Tv.fromLocation(f.location)

    @Test fun musicGoesWithArtistAlbumAndCover() {
        val tv = tvAt(fake)
        val file = File.createTempFile("01 Song", ".mp3").apply { writeBytes(ByteArray(300)); deleteOnExit() }
        val media = server.publish(FileSource(file))
        assertEquals(MediaKind.AUDIO, media.kind)
        assertTrue(media.urlPath.endsWith(".mp3"))
        media.artist = "Hudba & Spol."
        media.album = "Album"
        media.knownDurationMs = 205_000
        media.coverPath = server.addExtra(media, ".jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        playMedia(tv, server, media, startAt = 0.0)
        val d = didlOf(fake)
        assertTrue("<upnp:class>object.item.audioItem.musicTrack</upnp:class>" in d, d)
        assertTrue("<upnp:artist>Hudba &amp; Spol.</upnp:artist>" in d, d)
        assertTrue("<dc:creator>Hudba &amp; Spol.</dc:creator>" in d)
        assertTrue("<upnp:album>Album</upnp:album>" in d)
        assertTrue("<upnp:albumArtURI dlna:profileID=\"JPEG_TN\">${server.urlFor(tv.host, media.coverPath!!)}</upnp:albumArtURI>" in d, d)
        assertTrue("duration=\"0:03:25.000\"" in d)
        assertTrue("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01" in d)
        assertTrue("pv:subtitleFileUri" !in d)
    }

    @Test fun photosGoFromOneToTheNextWithoutStopping() {
        val tv = tvAt(fake)
        val photo = BytesSource("IMG_0001.jpg", ByteArray(2000), mime = "image/jpeg", kind = MediaKind.IMAGE)
        val media = server.publish(photo, mime = "image/jpeg", features = Dlna.image("image/jpeg", 4000, 3000, converted = true))
        playMedia(tv, server, media, startAt = 30.0, stopFirst = false)
        val acts = fake.actions()
        assertEquals("SetAVTransportURI", acts.first { it in setOf("Stop", "SetAVTransportURI") })
        assertTrue("Seek" !in acts)
        val d = didlOf(fake)
        assertTrue("<upnp:class>object.item.imageItem.photo</upnp:class>" in d, d)
        assertTrue("http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=00f0" in d, d)
        assertTrue("size=\"2000\"" in d)
    }

    @Test fun liveStreamIsAnnouncedWithoutSizeOrSeeking() {
        val tv = tvAt(fake)
        val hub = LiveHub()
        val media = server.publishLive(hub, "Phone screen")
        assertTrue(media.urlPath.endsWith(".ts"))
        assertEquals(MediaKind.LIVE, media.kind)
        // the fake TV reads what it fetches to the end: end the stream so it gets there
        kotlin.concurrent.thread { Thread.sleep(300); hub.close() }
        playMedia(tv, server, media)
        val d = didlOf(fake)
        assertTrue("<upnp:class>object.item.videoItem</upnp:class>" in d)
        assertTrue("http-get:*:video/mpeg:DLNA.ORG_OP=00;DLNA.ORG_CI=1;" in d, d)
        assertTrue("size=" !in d)
        hub.close()
    }
}
