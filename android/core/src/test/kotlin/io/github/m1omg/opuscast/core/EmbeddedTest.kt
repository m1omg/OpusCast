package io.github.m1omg.opuscast.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbeddedTest {
    private fun fixture(name: String) = File(javaClass.getResource("/$name")!!.toURI())

    private fun srt(b: ByteArray) = String(b, Charsets.UTF_8)

    @Test fun mkvFromFfmpeg() {
        FileRandomAccess(fixture("tracks.mkv")).use { f ->
            val tracks = listEmbeddedSubtitles(f)
            assertEquals(listOf(2, 3), tracks.map { it.number })
            assertEquals(listOf("sk", "en"), tracks.map { it.lang })
            assertEquals("Slovak full", tracks[0].title)
            assertEquals(listOf(false, true), tracks.map { it.forced })
            assertEquals("1\n00:00:00,500 --> 00:00:01,500\nAhoj <i>svet</i>\n\n2\n00:00:02,000 --> 00:00:03,250\nDruhý riadok\na ďalší\n\n",
                srt(extractEmbeddedSubtitle(f, 2)))
            assertEquals("1\n00:00:00,500 --> 00:00:01,500\n<i>Hello</i>, world\nSecond line\n\n2\n00:00:02,000 --> 00:00:03,000\nBye\n\n",
                srt(extractEmbeddedSubtitle(f, 3)))
            val entries = embeddedEntries(f)
            assertEquals(listOf("track:2", "track:3"), entries.map { it.id })
            assertTrue(entries.all { it.ok })
        }
    }

    @Test fun mp4FromFfmpeg() {
        FileRandomAccess(fixture("tracks.mp4")).use { f ->
            val tracks = listEmbeddedSubtitles(f)
            assertEquals(1, tracks.size)
            assertEquals("cs", tracks[0].lang)
            assertEquals("tx3g", tracks[0].codec)
            assertEquals("1\n00:00:00,500 --> 00:00:01,500\nAhoj <i>svet</i>\n\n2\n00:00:02,000 --> 00:00:03,250\nDruhý riadok\na ďalší\n\n",
                srt(extractEmbeddedSubtitle(f, tracks[0].number)))
        }
    }

    @Test fun notAVideo() {
        val f = File.createTempFile("notvideo", ".bin").apply { writeText("hello, this is not a video at all"); deleteOnExit() }
        FileRandomAccess(f).use { assertEquals(emptyList(), listEmbeddedSubtitles(it)) }
    }

    // ------------------------------------------------ a hand-built Matroska file

    private object Ebml {
        fun id(v: Long): ByteArray {
            val n = when { v > 0xFFFFFF -> 4; v > 0xFFFF -> 3; v > 0xFF -> 2; else -> 1 }
            return ByteArray(n) { (v shr (8 * (n - 1 - it))).toByte() }
        }
        fun size(n: Long) = byteArrayOf(0x01) + ByteArray(7) { (n shr (8 * (6 - it))).toByte() }
        val UNKNOWN = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)
        fun el(id: Long, vararg children: ByteArray): ByteArray {
            val body = children.fold(ByteArray(0)) { a, b -> a + b }
            return id(id) + size(body.size.toLong()) + body
        }
        fun unknown(id: Long, vararg children: ByteArray) = id(id) + UNKNOWN + children.fold(ByteArray(0)) { a, b -> a + b }
        fun uint(id: Long, v: Long) = el(id, ByteArray(8) { (v shr (8 * (7 - it))).toByte() })
        fun str(id: Long, s: String) = el(id, s.toByteArray())
        fun block(track: Int, rel: Int, payload: ByteArray, simple: Boolean) =
            byteArrayOf((0x80 or track).toByte(), (rel shr 8).toByte(), rel.toByte(), if (simple) 0x80.toByte() else 0) + payload
    }

    private fun zlib(b: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(b)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return out.toByteArray()
    }

    /** EBML header + Segment(SeekHead?, Info, Tracks, Cluster(BlockGroup), unknown-size Cluster(SimpleBlock), Cues?). */
    private fun buildMkv(withCues: Boolean, compression: Int?): File {
        val e = Ebml
        val header = e.el(0x1A45DFA3, e.str(0x4282, "matroska"))
        val encoding = when (compression) {
            0 -> e.el(0x6D80, e.el(0x6240, e.el(0x5034, e.uint(0x4254, 0))))
            3 -> e.el(0x6D80, e.el(0x6240, e.el(0x5034, e.uint(0x4254, 3), e.el(0x4255, "Ah".toByteArray()))))
            else -> ByteArray(0)
        }
        val tracks = e.el(0x1654AE6B, e.el(0xAE, e.uint(0xD7, 1), e.uint(0x83, 0x11), e.str(0x86, "S_TEXT/UTF8"),
            e.str(0x22B59C, "cze"), encoding))
        val info = e.el(0x1549A966, e.uint(0x2AD7B1, 1_000_000))
        fun payload(text: String) = when (compression) {
            0 -> zlib(text.toByteArray())
            3 -> text.toByteArray().let { it.copyOfRange(2, it.size) }
            else -> text.toByteArray()
        }
        val ts1 = e.uint(0xE7, 1000)
        val group = e.el(0xA0, e.el(0xA1, e.block(1, 500, payload("Ahoj prvý"), simple = false)), e.uint(0x9B, 1500))
        val cluster1 = e.el(0x1F43B675, ts1, group)
        val ts2 = e.uint(0xE7, 5000)
        val simple = e.el(0xA3, e.block(1, 200, payload("Ahoj druhý"), simple = true))
        val cluster2 = e.unknown(0x1F43B675, ts2, simple)
        fun seekHead(cuesPos: Long) = e.el(0x114D9B74, e.el(0x4DBB, e.el(0x53AB, e.id(0x1C53BB6B)), e.uint(0x53AC, cuesPos)))
        val seekLen = if (withCues) seekHead(0).size else 0
        val c1Pos = (seekLen + info.size + tracks.size).toLong()
        val c2Pos = c1Pos + cluster1.size
        val cuesPos = c2Pos + cluster2.size
        val cues = e.el(0x1C53BB6B,
            e.el(0xBB, e.uint(0xB3, 1500), e.el(0xB7, e.uint(0xF7, 1), e.uint(0xF1, c1Pos), e.uint(0xF0, ts1.size.toLong()))),
            e.el(0xBB, e.uint(0xB3, 5200), e.el(0xB7, e.uint(0xF7, 1), e.uint(0xF1, c2Pos))))
        val body = (if (withCues) seekHead(cuesPos) else ByteArray(0)) + info + tracks + cluster1 + cluster2 + (if (withCues) cues else ByteArray(0))
        val file = File.createTempFile("built", ".mkv").apply { deleteOnExit() }
        file.writeBytes(header + e.el(0x18538067, body))
        return file
    }

    private val expected = "1\n00:00:01,500 --> 00:00:03,000\nAhoj prvý\n\n2\n00:00:05,200 --> 00:00:08,200\nAhoj druhý\n\n"

    @Test fun scanWithoutCues() {
        FileRandomAccess(buildMkv(withCues = false, compression = null)).use { f ->
            assertEquals("cs", listEmbeddedSubtitles(f).single().lang)
            assertEquals(expected, srt(extractEmbeddedSubtitle(f, 1)))
        }
    }

    @Test fun throughTheCues() {
        FileRandomAccess(buildMkv(withCues = true, compression = null)).use { f ->
            assertEquals(expected, srt(extractEmbeddedSubtitle(f, 1)))
        }
    }

    @Test fun zlibCompressed() {
        FileRandomAccess(buildMkv(withCues = true, compression = 0)).use { f -> assertEquals(expected, srt(extractEmbeddedSubtitle(f, 1))) }
    }

    @Test fun headerStripped() {
        FileRandomAccess(buildMkv(withCues = false, compression = 3)).use { f -> assertEquals(expected, srt(extractEmbeddedSubtitle(f, 1))) }
    }

    @Test fun cancel() {
        val c = Cancel().apply { cancelled = true }
        FileRandomAccess(buildMkv(withCues = false, compression = null)).use { f ->
            var threw = false
            try {
                extractEmbeddedSubtitle(f, 1, c)
            } catch (e: CancelledException) {
                threw = true
            }
            assertTrue(threw)
        }
        assertFalse(Cancel().cancelled)
    }
}

class FaststartTest {
    @Test fun movesTheIndexForward() {
        val original = File(javaClass.getResource("/tracks.mp4")!!.toURI()).readBytes()
        fun order(b: ByteArray): List<String> {
            val out = ArrayList<String>()
            var p = 0
            while (p + 8 <= b.size) {
                val size = java.nio.ByteBuffer.wrap(b, p, 4).int
                out += String(b, p + 4, 4)
                p += size
            }
            return out
        }
        assertTrue(order(original).indexOf("moov") > order(original).indexOf("mdat"), "fixture has the index at the end")
        val fast = mp4Faststart(original)
        assertEquals(original.size, fast.size)
        assertTrue(order(fast).indexOf("moov") < order(fast).indexOf("mdat"))
        assertTrue(mp4Faststart(fast) === fast || mp4Faststart(fast).contentEquals(fast))
        val f = File.createTempFile("fast", ".mp4").apply { writeBytes(fast); deleteOnExit() }
        FileRandomAccess(f).use { ra ->
            assertEquals("1\n00:00:00,500 --> 00:00:01,500\nAhoj <i>svet</i>\n\n2\n00:00:02,000 --> 00:00:03,250\nDruhý riadok\na ďalší\n\n",
                String(extractEmbeddedSubtitle(ra, 2), Charsets.UTF_8))
        }
    }
}
