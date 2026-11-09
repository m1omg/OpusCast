package io.github.m1omg.opuscast.core

import com.sun.net.httpserver.HttpServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone among the TV's sources: found, described, browsed, played - and only by the TV it's shared to. */
class TvSourceTest {
    private val server = MediaServer(18790)
    private val hub = LiveHub()
    private val media: Media
    private val sources = ArrayList<TvSource>()

    init {
        val mux = TsMuxer { b, o, l -> hub.write(b, o, l) }
        mux.setVideoConfig(FAKE_CONFIG)
        for (n in 0 until 5) {
            val (f, key) = fakeFrame(n, gop = 30, size = 2000)
            mux.writeVideo(f, 0, f.size, n * 33_333L, key)
        }
        media = server.publishLive(hub, "Phone screen")
    }

    @AfterTest fun close() {
        sources.forEach { it.close() }
        hub.close()
        server.close()
    }

    private fun source(tvHost: String, port: Int) =
        TvSource(server, tvHost, media, "OpusCast on Test phone", "5f0c7c2e-1d1a-4c6e-9a43-2b7d1e0f6a11", ssdpPort = port).also { sources += it }

    /** An M-SEARCH sent straight to the phone (UPnP 1.1), from [from]: the answers. */
    private fun search(port: Int, st: String = MEDIA_SERVER, from: String = "127.0.0.1", waitMs: Long = 1500): List<String> {
        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 127.0.0.1:$port\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $st\r\n\r\n".toByteArray()
        val answers = ArrayList<String>()
        DatagramSocket(InetSocketAddress(InetAddress.getByName(from), 0)).use { s ->
            s.soTimeout = 200
            s.send(DatagramPacket(msg, msg.size, InetSocketAddress("127.0.0.1", port)))
            val end = System.nanoTime() + waitMs * 1_000_000
            val buf = ByteArray(4096)
            while (System.nanoTime() < end) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                answers += String(p.data, 0, p.length, Charsets.ISO_8859_1)
            }
        }
        return answers
    }

    private fun soap(url: String, service: String, action: String, args: List<Pair<String, Any>>): Pair<Int, String> {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:$action xmlns:u=\"$service\">" + args.joinToString("") { (k, v) -> "<$k>${xmlEscape(v.toString())}</$k>" } +
            "</u:$action></s:Body></s:Envelope>"
        return post(url, body.toByteArray(), "\"$service#$action\"")
    }

    private fun post(url: String, body: ByteArray, soapAction: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", soapAction)
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes()) } ?: ""
        return code to text
    }

    private fun browse(src: TvSource, oid: String = "0", flag: String = "BrowseDirectChildren") =
        soap("http://127.0.0.1:${server.port}${src.prefix}/cds/control", CONTENT_DIRECTORY, "Browse",
            listOf("ObjectID" to oid, "BrowseFlag" to flag, "Filter" to "*", "StartingIndex" to 0, "RequestedCount" to 0, "SortCriteria" to ""))

    private fun field(xml: String, name: String) = parseXml(xml.toByteArray()).descendants(name).firstOrNull()?.textContent

    @Test fun theTvFindsBrowsesAndPlaysIt() {
        val src = source("127.0.0.1", 19190)
        assertTrue(src.answersSearches)
        val answers = search(19190)
        assertTrue(answers.any { "LOCATION: ${src.location}" in it && "ST: $MEDIA_SERVER" in it }, answers.toString())
        assertEquals(5, search(19190, st = "ssdp:all").size)
        assertTrue(search(19190, st = RENDERER).isEmpty()) // it's not a TV

        val desc = URL("http://127.0.0.1:${server.port}${src.prefix}/description.xml").openConnection() as HttpURLConnection
        assertEquals(200, desc.responseCode)
        assertTrue("DLNADOC/1.50" in desc.getHeaderField("Server"))
        val d = parseXml(desc.inputStream.readBytes())
        assertEquals("OpusCast on Test phone", d.descendants("friendlyName").first().textContent)
        assertEquals(MEDIA_SERVER, d.descendants("deviceType").first().textContent)
        assertEquals(2, d.descendants("service").size)
        assertEquals(200, (URL("http://127.0.0.1:${server.port}${src.prefix}/cds.xml").openConnection() as HttpURLConnection).responseCode)

        val (code, xml) = browse(src)
        assertEquals(200, code, xml)
        assertEquals("1", field(xml, "NumberReturned"))
        val didl = parseXml(field(xml, "Result")!!.toByteArray())
        val item = didl.descendants("item").single()
        assertEquals(TvSource.ITEM, item.getAttribute("id"))
        assertEquals("Phone screen", didl.descendants("title").first().textContent)
        val res = didl.descendants("res").first()
        assertTrue(res.getAttribute("protocolInfo").startsWith("http-get:*:video/mpeg:DLNA.ORG_OP=00;DLNA.ORG_CI=1"))
        assertTrue(res.textContent.endsWith(media.urlPath))
        val (metaCode, meta) = browse(src, TvSource.ITEM, "BrowseMetadata")
        assertEquals(200, metaCode)
        assertEquals("1", field(meta, "TotalMatches"))

        val stream = URL(res.textContent).openConnection() as HttpURLConnection
        stream.setRequestProperty("getcontentFeatures.dlna.org", "1")
        assertEquals(200, stream.responseCode)
        assertEquals(Dlna.LIVE, stream.getHeaderField("contentFeatures.dlna.org"))
        val input = stream.inputStream
        val first = ByteArray(188)
        var n = 0
        while (n < 188) n += input.read(first, n, 188 - n)
        assertEquals(0x47, first.u(0))
        assertTrue(hub.watched()) // the TV is taking it
        stream.disconnect()
        val end = System.nanoTime() + 5_000_000_000L
        while (hub.watched() && System.nanoTime() < end) Thread.sleep(50)
        assertFalse(hub.watched()) // ... and left
    }

    @Test fun samsungsAndBadRequests() {
        val src = source("127.0.0.1", 19191)
        val ctl = "http://127.0.0.1:${server.port}${src.prefix}/cds/control"
        val (fc, features) = soap(ctl, CONTENT_DIRECTORY, "X_GetFeatureList", emptyList())
        assertEquals(200, fc)
        assertTrue("samsung.com_BASICVIEW" in field(features, "FeatureList")!!)
        val (nc, none) = browse(src, "nope", "BrowseMetadata")
        assertEquals(500, nc)
        assertEquals("701", field(none, "errorCode"))
        assertEquals(500, soap(ctl, CONTENT_DIRECTORY, "DeleteEverything", emptyList()).first)
        val bomb = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a \"aaaa\">]><x>&a;</x>".toByteArray()
        assertEquals(400, post(ctl, bomb, "\"$CONTENT_DIRECTORY#Browse\"").first)
        val (pc, protocols) = soap("http://127.0.0.1:${server.port}${src.prefix}/cms/control", CONNECTION_MANAGER, "GetProtocolInfo", emptyList())
        assertEquals(200, pc)
        assertTrue("http-get:*:video/mpeg:*" in field(protocols, "Source")!!)
    }

    @Test fun anotherDeviceSeesNothing() {
        // shared to a TV at 127.0.0.2; these requests come from 127.0.0.1
        val src = source("127.0.0.2", 19192)
        val (code, xml) = browse(src)
        assertEquals(200, code)
        assertEquals("0", field(xml, "NumberReturned"))
        assertEquals("0", field(xml, "TotalMatches"))
        assertEquals("701", field(browse(src, TvSource.ITEM, "BrowseMetadata").second, "errorCode"))
        assertTrue(search(19192).isEmpty())
    }

    @Test fun eventsOnlyGoBackToTheTv() {
        val got = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val events = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        events.createContext("/") { ex ->
            got.set("${ex.requestMethod} ${ex.requestHeaders.getFirst("SID")} ${String(ex.requestBody.readBytes())}")
            ex.sendResponseHeaders(200, -1)
            ex.close()
            latch.countDown()
        }
        events.start()
        try {
            val src = source("127.0.0.1", 19193)
            val port = events.address.port
            fun subscribe(callback: String): HttpURLConnection = rawSubscribe(src, callback)
            val elsewhere = subscribe("<http://127.0.0.2:$port/elsewhere>") // not the TV's own address
            assertEquals(200, elsewhere.responseCode)
            assertFalse(latch.await(1500, TimeUnit.MILLISECONDS))
            val ok = subscribe("<http://127.0.0.1:$port/events>")
            assertEquals(200, ok.responseCode)
            assertNotNull(ok.getHeaderField("SID"))
            assertEquals("Second-1800", ok.getHeaderField("TIMEOUT"))
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            val event = got.get()!!
            assertTrue(event.startsWith("NOTIFY ${ok.getHeaderField("SID")}"), event)
            assertTrue("<SystemUpdateID>1</SystemUpdateID>" in event, event)
        } finally {
            events.stop(0)
        }
    }

    /** SUBSCRIBE isn't a method HttpURLConnection sends: a plain socket. */
    private fun rawSubscribe(src: TvSource, callback: String): HttpURLConnection {
        val r = rawHttpRequest("http://127.0.0.1:${server.port}${src.prefix}/cds/event", "SUBSCRIBE",
            mapOf("CALLBACK" to callback, "NT" to "upnp:event", "TIMEOUT" to "Second-1800"))
        return object : HttpURLConnection(URL("http://127.0.0.1/")) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = r.code
            override fun getHeaderField(name: String) = r.headers[name.lowercase()]
        }
    }

    /** OPUSCAST_SERVE_SOURCE=<this machine's LAN address>: keep a source up for 30 s, for an outside UPnP client
     *  to look at (e.g. async-upnp-client in strict mode). Skipped otherwise. */
    @Test fun serveForAnOutsideClient() {
        val ip = System.getenv("OPUSCAST_SERVE_SOURCE") ?: return
        val src = source(ip, SSDP_PORT)
        println("SOURCE ${src.location}")
        Thread.sleep(30_000)
    }

    @Test fun goneWhenClosed() {
        val src = source("127.0.0.1", 19194)
        assertEquals(src, server.source)
        src.close()
        assertNull(server.source)
        val c = URL("http://127.0.0.1:${server.port}${src.prefix}/description.xml").openConnection() as HttpURLConnection
        assertEquals(404, c.responseCode)
    }

    @Test fun shareWatchDecides() {
        fun run(bringBack: Boolean, steps: List<Triple<Double, Boolean, Boolean>>): List<Pair<Double, ShareWatch.Event>> {
            var on = false
            val w = ShareWatch(media, bringBack) { _, _ -> on }
            val out = ArrayList<Pair<Double, ShareWatch.Event>>()
            for ((t, showing, sentByHand) in steps) {
                on = showing
                if (sentByHand) w.sent(t)
                w.update(null, null, t)?.let { out += t to it }
            }
            return out
        }
        fun at(t: Double, on: Boolean, sent: Boolean = false) = Triple(t, on, sent)
        // the TV leaves, then comes back by itself (from its sources)
        assertEquals(listOf(0.0 to ShareWatch.Event.SHOWN, 13.5 to ShareWatch.Event.LEFT, 30.0 to ShareWatch.Event.BACK),
            run(false, listOf(at(0.0, true), at(10.0, false), at(12.0, false), at(13.5, false), at(30.0, true))))
        // a short reconnect isn't leaving
        assertEquals(listOf(0.0 to ShareWatch.Event.SHOWN), run(false, listOf(at(0.0, true), at(10.0, false), at(12.0, true))))
        // sent again by hand, the TV doesn't take it; nobody watches for 30 minutes
        assertEquals(listOf(0.0 to ShareWatch.Event.SHOWN, 34.0 to ShareWatch.Event.LEFT, 56.0 to ShareWatch.Event.FAILED,
            1830.0 to ShareWatch.Event.IDLE),
            run(false, listOf(at(0.0, true), at(30.0, false), at(34.0, false), at(40.0, false, sent = true), at(50.0, false),
                at(56.0, false), at(1830.0, false))))
        // bring it back: a mistake comes back by itself, leaving again right away keeps it off
        assertEquals(listOf(0.0 to ShareWatch.Event.SHOWN, 63.0 to ShareWatch.Event.SEND, 66.0 to ShareWatch.Event.BACK,
            73.0 to ShareWatch.Event.LEFT),
            run(true, listOf(at(0.0, true), at(60.0, false), at(63.0, false), at(66.0, true), at(70.0, false), at(73.0, false),
                at(80.0, false))))
    }

    @Test fun showingLiveFollowsTheRendererOrTheStream() {
        assertTrue(showingLive(media, "PLAYING", "http://phone:8765${media.urlPath}"))
        assertTrue(showingLive(media, "TRANSITIONING", null))
        assertFalse(showingLive(media, "PLAYING", "http://other/thing.mp4")) // something else on the TV
        assertFalse(showingLive(media, "STOPPED", null)) // nobody reads the stream either
        val c = hub.open()
        c.read(100)
        assertTrue(showingLive(media, "NO_MEDIA_PRESENT", null)) // the TV's own player takes it
        c.close()
        assertFalse(showingLive(media, "NO_MEDIA_PRESENT", null))
    }
}
