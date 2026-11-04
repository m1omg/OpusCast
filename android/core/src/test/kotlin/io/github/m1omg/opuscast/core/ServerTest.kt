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
}
