package io.github.m1omg.opuscast.core

import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerTest {
    private val server = MediaServer(18765)
    private val bytes = ByteArray(100_000) { (it % 251).toByte() }
    private val file = File.createTempFile("Movie", ".mkv").apply { writeBytes(bytes); deleteOnExit() }

    @AfterTest fun close() = server.close()

    private fun open(path: String, method: String = "GET", headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val c = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    @Test fun servesRangesAndSamsungHeaders() {
        val media = server.publish(FileSource(file, durationMs = 3000), "1\n00:00:01,000 --> 00:00:02,000\nx\n".toByteArray())
        assertFalse(media.requested)
        assertTrue(media.urlPath.endsWith(".mkv"))
        val c = open(media.urlPath, headers = mapOf("Range" to "bytes=10-19", "getMediaInfo.sec" to "1", "getCaptionInfo.sec" to "1"))
        assertEquals(206, c.responseCode)
        assertEquals("bytes 10-19/100000", c.getHeaderField("Content-Range"))
        assertEquals(bytes.copyOfRange(10, 20).toList(), c.inputStream.readBytes().toList())
        assertEquals("SEC_Duration=3000;", c.getHeaderField("MediaInfo.sec"))
        assertEquals("http://127.0.0.1:${server.port}${media.subPath}", c.getHeaderField("CaptionInfo.sec"))
        assertEquals(DLNA_FEATURES, c.getHeaderField("contentFeatures.dlna.org"))
        assertEquals("Streaming", c.getHeaderField("transferMode.dlna.org"))
        assertEquals("video/x-mkv", c.contentType)
        assertTrue(media.requested)

        val whole = open(media.urlPath)
        assertEquals(200, whole.responseCode)
        assertEquals(bytes.toList(), whole.inputStream.readBytes().toList())

        val head = open(media.urlPath, "HEAD")
        assertEquals(200, head.responseCode)
        assertEquals("100000", head.getHeaderField("Content-Length"))

        val sub = open(media.subPath!!)
        assertEquals("text/srt; charset=utf-8", sub.contentType)
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nx\n", sub.inputStream.readBytes().decodeToString())
        assertEquals(String(BLANK_SRT), open(media.blankPath).inputStream.readBytes().decodeToString())

        assertEquals(416, open(media.urlPath, headers = mapOf("Range" to "bytes=200000-")).responseCode)
        assertEquals(404, open("/media/deadbeef.mkv").responseCode)
        server.unpublish()
        assertEquals(404, open(media.urlPath).responseCode)
    }

    @Test fun keepAliveAndEvents() {
        val media = server.publish(FileSource(file))
        val got = CountDownLatch(1)
        var event: Triple<String, String?, String>? = null
        server.onEvent = { service, sid, body -> event = Triple(service, sid, body); got.countDown() }
        Socket("127.0.0.1", server.port).use { s ->
            val out = s.getOutputStream()
            val input = BufferedInputStream(s.getInputStream())
            for (range in listOf("0-9", "90-99")) {
                out.write("GET ${media.urlPath} HTTP/1.1\r\nHost: x\r\nRange: bytes=$range\r\n\r\n".toByteArray())
                out.flush()
                val head = readHead(input)!!
                assertTrue(head.startsWith("HTTP/1.1 206"), head)
                val body = ByteArray(10)
                var n = 0
                while (n < 10) n += input.read(body, n, 10 - n)
                val start = range.substringBefore('-').toInt()
                assertEquals(bytes.copyOfRange(start, start + 10).toList(), body.toList())
            }
            val notify = "<e:propertyset/>"
            out.write("NOTIFY /events/AVTransport HTTP/1.1\r\nHost: x\r\nSID: uuid:1\r\nContent-Length: ${notify.length}\r\n\r\n$notify".toByteArray())
            out.flush()
            assertTrue(readHead(input)!!.startsWith("HTTP/1.1 200"))
        }
        assertTrue(got.await(5, TimeUnit.SECONDS))
        assertEquals(Triple("AVTransport", "uuid:1", "<e:propertyset/>"), event)
    }

    @Test fun noSubtitleNoCaptionHeader() {
        val media = server.publish(FileSource(file))
        assertNull(media.subPath)
        val c = open(media.urlPath, headers = mapOf("getCaptionInfo.sec" to "1", "getMediaInfo.sec" to "1"))
        assertEquals(200, c.responseCode)
        assertNull(c.getHeaderField("CaptionInfo.sec"))
        assertNull(c.getHeaderField("MediaInfo.sec")) // duration unknown
        c.inputStream.readBytes()
        val extra = server.addSubtitle(media, "x".toByteArray())
        assertEquals("x", open(extra).inputStream.readBytes().decodeToString())
    }

    @Test fun photosCoversAndTheTvsSpelling() {
        val jpeg = ByteArray(5000) { it.toByte() }
        val media = server.publish(BytesSource("IMG_1.jpg", jpeg, kind = MediaKind.IMAGE), mime = "image/jpg",
            features = Dlna.image("image/jpeg", 800, 600, converted = true))
        assertTrue(media.urlPath.endsWith(".jpg"))
        val c = open(media.urlPath)
        assertEquals(200, c.responseCode)
        assertEquals("image/jpg", c.contentType) // as this TV spells it
        assertEquals("Interactive", c.getHeaderField("transferMode.dlna.org"))
        assertTrue(c.getHeaderField("contentFeatures.dlna.org").startsWith("DLNA.ORG_PN=JPEG_MED;DLNA.ORG_OP=00;DLNA.ORG_CI=1"))
        assertEquals(jpeg.toList(), c.inputStream.readBytes().toList())
        // a TV that says how it fetches gets the same back
        assertEquals("Background", open(media.urlPath, headers = mapOf("transferMode.dlna.org" to "Background"))
            .getHeaderField("transferMode.dlna.org"))
        val song = server.publish(FileSource(file.copyTo(File(file.parentFile, "Song-${System.nanoTime()}.flac")).apply { deleteOnExit() }))
        val cover = server.addExtra(song, ".png", "image/png", byteArrayOf(9, 8, 7))
        val cc = open(cover)
        assertEquals("image/png", cc.contentType)
        assertEquals(listOf<Byte>(9, 8, 7), cc.inputStream.readBytes().toList())
        val sc = open(song.urlPath, "HEAD")
        assertEquals("audio/flac", sc.contentType)
        assertEquals("Streaming", sc.getHeaderField("transferMode.dlna.org"))
    }

    private fun liveStream(): Pair<LiveHub, Media> {
        val hub = LiveHub()
        val mux = TsMuxer { b, o, l -> hub.write(b, o, l) }
        mux.setVideoConfig(FAKE_CONFIG)
        for (n in 0 until 5) {
            val (f, key) = fakeFrame(n, gop = 30, size = 2000)
            mux.writeVideo(f, 0, f.size, n * 33_333L, key)
        }
        return hub to server.publishLive(hub, "Phone screen")
    }

    @Test fun liveStreamOverHttp11IsChunked() {
        val (hub, media) = liveStream()
        val head = open(media.urlPath, "HEAD")
        assertEquals(200, head.responseCode)
        assertNull(head.getHeaderField("Content-Length"))
        assertEquals(Dlna.LIVE, head.getHeaderField("contentFeatures.dlna.org"))
        val c = open(media.urlPath, headers = mapOf("Range" to "bytes=0-"))
        assertEquals(200, c.responseCode) // never 206: there's nothing to range over
        assertEquals("chunked", c.getHeaderField("Transfer-Encoding"))
        assertEquals("video/mpeg", c.contentType)
        assertEquals("no-cache", c.getHeaderField("Cache-Control"))
        val input = c.inputStream
        val first = ByteArray(188)
        var n = 0
        while (n < 188) n += input.read(first, n, 188 - n)
        assertEquals(0x47, first.u(0))
        assertEquals(0, pidOf(first)) // the PAT first
        assertTrue(media.requested)
        hub.close()
        val rest = input.readBytes() // ends with the stream (the last chunk)
        assertEquals(0, (188 + rest.size) % 188)
    }

    @Test fun liveStreamOverHttp10EndsWithTheConnection() {
        val (hub, media) = liveStream()
        Socket("127.0.0.1", server.port).use { s ->
            s.getOutputStream().write("GET ${media.urlPath} HTTP/1.0\r\n\r\n".toByteArray())
            val input = BufferedInputStream(s.getInputStream())
            val head = readHead(input)!!
            assertTrue(head.startsWith("HTTP/1.1 200"), head)
            assertTrue("Connection: close" in head && "chunked" !in head && "Content-Length" !in head, head)
            val b = input.read()
            assertEquals(0x47, b)
            hub.close()
            val rest = input.readBytes()
            assertEquals(0, (1 + rest.size) % 188)
        }
    }
}
