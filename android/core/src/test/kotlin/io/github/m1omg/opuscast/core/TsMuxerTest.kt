package io.github.m1omg.opuscast.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A pretend encoder's output: SPS/PPS once, then frames with start codes, keyframes every [gop]. */
internal fun fakeFrame(n: Int, gop: Int = 30, size: Int = 3000): Pair<ByteArray, Boolean> {
    val key = n % gop == 0
    val f = ByteArray(size) { ((it * 7 + n) % 200 + 1).toByte() } // no accidental start codes
    f[0] = 0; f[1] = 0; f[2] = 0; f[3] = 1
    f[4] = if (key) 0x65 else 0x41 // IDR / non-IDR slice
    return f to key
}

internal val FAKE_CONFIG = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F, 0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

internal fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF
internal fun pidOf(p: ByteArray, at: Int = 0) = ((p.u(at + 1) and 0x1F) shl 8) or p.u(at + 2)

class TsMuxerTest {
    @Test fun crcAndAdts() {
        assertEquals(0x0376E6E7, crc32Mpeg("123456789".toByteArray(), 0, 9))
        val h = adtsHeader(371)
        assertEquals(7, h.size)
        assertEquals(0xFFF, (h.u(0) shl 4) or (h.u(1) shr 4))
        assertEquals(378, ((h.u(3) and 3) shl 11) or (h.u(4) shl 3) or (h.u(5) shr 5)) // frame length incl. header
        assertEquals(3, (h.u(2) shr 2) and 0xF) // 48 kHz
        assertEquals(1, h.u(2) shr 6) // AAC LC
        assertEquals(2, ((h.u(2) and 1) shl 2) or (h.u(3) shr 6)) // stereo
    }

    @Test fun packetsAreWellFormed() {
        val out = ByteArrayOutputStream()
        val mux = TsMuxer { b, o, l -> out.write(b, o, l) }
        mux.setVideoConfig(FAKE_CONFIG)
        val t0 = 5_000_000_000L
        var audio = 0
        for (n in 0 until 90) { // 3 s at 30 fps, audio frames in between
            val ts = t0 + n * 33_333L
            while (t0 + audio * 21_333L <= ts) {
                val aac = ByteArray(300) { 0x11 }
                val adts = adtsHeader(aac.size) + aac
                mux.writeAudio(adts, 0, adts.size, t0 + audio * 21_333L)
                audio++
            }
            val (f, key) = fakeFrame(n)
            mux.writeVideo(f, 0, f.size, ts, key)
        }
        val ts = out.toByteArray()
        assertEquals(0, ts.size % 188)
        val cc = HashMap<Int, Int>()
        var pcrs = 0
        var lastPcr = -1L
        var psiCount = 0
        var keys = 0
        var lastPat = -1
        for (at in ts.indices step 188) {
            assertEquals(0x47, ts.u(at), "sync byte at $at")
            val pid = pidOf(ts, at)
            val afc = (ts.u(at + 3) shr 4) and 3
            val c = ts.u(at + 3) and 0xF
            if (afc and 1 != 0) { // payload: the counter goes up by one per PID
                cc[pid]?.let { assertEquals((it + 1) and 0xF, c, "continuity on PID $pid at $at") }
                cc[pid] = c
            }
            if (afc and 2 != 0 && ts.u(at + 4) > 0) {
                val flags = ts.u(at + 5)
                if (flags and 0x10 != 0) {
                    val base = (ts.u(at + 6).toLong() shl 25) or (ts.u(at + 7).toLong() shl 17) or (ts.u(at + 8).toLong() shl 9) or
                        (ts.u(at + 9).toLong() shl 1) or (ts.u(at + 10).toLong() shr 7)
                    assertTrue(base >= lastPcr, "PCR goes backwards at $at")
                    assertTrue(lastPcr < 0 || base - lastPcr <= 40 * 90 + 34 * 90, "PCR gap at $at")
                    lastPcr = base
                    pcrs++
                    assertEquals(TsMuxer.VIDEO_PID, pid)
                }
                if (flags and 0x40 != 0) {
                    keys++
                    assertEquals(at - 2 * 188, lastPat, "PAT and PMT right before the keyframe")
                }
            }
            if (pid == 0 || pid == TsMuxer.PMT_PID) {
                psiCount++
                if (pid == 0) lastPat = at
                val s = at + 5
                val len = ((ts.u(s + 1) and 0x0F) shl 8) or ts.u(s + 2)
                val crc = crc32Mpeg(ts, s, 3 + len - 4)
                val stored = (ts.u(s + 3 + len - 4) shl 24) or (ts.u(s + len) shl 16) or (ts.u(s + len + 1) shl 8) or ts.u(s + len + 2)
                assertEquals(crc, stored, "CRC at $at")
            }
        }
        assertEquals(3, keys)
        assertTrue(pcrs >= 70, "PCRs: $pcrs")
        assertTrue(psiCount >= 2 * 25, "PAT/PMT about every 100 ms: $psiCount")
    }

    @Test fun keyframesCarrySpsPps() {
        val out = ByteArrayOutputStream()
        val mux = TsMuxer { b, o, l -> out.write(b, o, l) }
        mux.setVideoConfig(FAKE_CONFIG)
        val (f, _) = fakeFrame(0)
        mux.writeVideo(f, 0, f.size, 0, true)
        val ts = out.toByteArray()
        val first = (0 until ts.size step 188).first { pidOf(ts, it) == TsMuxer.VIDEO_PID && ts.u(it + 1) and 0x40 != 0 }
        val af = ts.u(first + 4)
        val pes = first + 5 + af
        assertEquals(listOf(0, 0, 1, 0xE0), (0..3).map { ts.u(pes + it) })
        val es = pes + 9 + ts.u(pes + 8)
        assertEquals(listOf(0, 0, 0, 1, 9), (0..4).map { ts.u(es + it) }) // access unit delimiter
        assertEquals(0x67, ts.u(es + 6 + 4)) // then SPS
    }

    /** The real thing: ffmpeg's H.264 and AAC, muxed here, must decode without a single error. */
    @Test fun ffmpegDecodesIt() {
        if (!have("ffmpeg") || !have("ffprobe")) return // not on every build machine
        val dir = kotlin.io.path.createTempDirectory("tsmux").toFile()
        try {
            val h264 = File(dir, "v.h264")
            val aac = File(dir, "a.aac")
            run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30", "-t", "3", "-c:v", "libx264",
                "-bf", "0", "-g", "30", "-pix_fmt", "yuv420p", "-bsf:v", "h264_metadata=aud=remove", "-f", "h264", h264.path)
            run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "3", "-ac", "2",
                "-c:a", "aac", "-b:a", "128k", "-f", "adts", aac.path)
            val units = accessUnits(h264.readBytes())
            val config = units.first().first
            val frames = units.drop(1)
            val adts = adtsFrames(aac.readBytes())
            val out = File(dir, "out.ts")
            out.outputStream().buffered().use { os ->
                val mux = TsMuxer { b, o, l -> os.write(b, o, l) }
                mux.setVideoConfig(config)
                var a = 0
                for ((i, f) in frames.withIndex()) {
                    val t = 1_000_000L + i * 33_333L
                    while (a < adts.size && 1_000_000L + a * 21_333L <= t) {
                        mux.writeAudio(adts[a], 0, adts[a].size, 1_000_000L + a * 21_333L)
                        a++
                    }
                    mux.writeVideo(f.first, 0, f.first.size, t, f.second)
                }
            }
            val probe = run("ffprobe", "-v", "error", "-show_entries", "stream=codec_name:format=duration", "-of", "csv=p=0", out.path)
            assertTrue("h264" in probe && "aac" in probe, probe)
            val duration = probe.lines().last { it.isNotBlank() }.toDouble()
            assertTrue(duration in 2.5..3.6, probe)
            val errors = run("ffmpeg", "-v", "error", "-i", out.path, "-f", "null", "-", stderr = true)
            assertEquals("", errors.trim())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun have(tool: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, tool).canExecute() }

    private fun run(vararg cmd: String, stderr: Boolean = false): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), err)
        return if (stderr) err else out
    }

    /** Annex B -> (SPS+PPS) then (access unit, keyframe) - split the way MediaCodec hands them out. */
    private fun accessUnits(b: ByteArray): List<Pair<ByteArray, Boolean>> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                starts += if (i > 0 && b[i - 1].toInt() == 0) i - 1 else i
                i += 3
            } else i++
        }
        val nals = starts.mapIndexed { n, s -> b.copyOfRange(s, if (n + 1 < starts.size) starts[n + 1] else b.size) }
        fun type(nal: ByteArray) = nal[if (nal[2].toInt() == 1) 3 else 4].toInt() and 0x1F
        val config = ByteArrayOutputStream()
        val units = ArrayList<Pair<ByteArray, Boolean>>()
        var cur = ByteArrayOutputStream()
        var key = false
        for (nal in nals) {
            when (type(nal)) {
                7, 8 -> config.write(nal)
                1, 5 -> {
                    cur.write(nal)
                    units += cur.toByteArray() to (type(nal) == 5 || key)
                    cur = ByteArrayOutputStream()
                    key = false
                }
                else -> cur.write(nal) // SEI goes with the next picture
            }
        }
        return listOf(config.toByteArray() to true) + units
    }

    private fun adtsFrames(b: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 7 <= b.size) {
            val len = ((b.u(i + 3) and 3) shl 11) or (b.u(i + 4) shl 3) or (b.u(i + 5) shr 5)
            if (len < 7 || i + len > b.size) break
            out += b.copyOfRange(i, i + len)
            i += len
        }
        return out
    }
}
