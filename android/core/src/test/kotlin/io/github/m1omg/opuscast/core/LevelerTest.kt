package io.github.m1omg.opuscast.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelerTest {
    /** A minute: speech at -24 LUFS, 40-43 s an explosion at -8, 50-56 s a whisper at -38, 20-21 s a pause. */
    private fun film(c: LevelCurve) {
        for (i in 0 until 600) {
            val t = i / 10.0
            c.add(t, if (t >= 40 && t < 43) -8.0 else if (t >= 50 && t < 56) -38.0 else if (t >= 20 && t < 21) -60.5 else -24.0)
        }
    }

    @Test
    fun theCurveDecides() {
        val c = LevelCurve()
        assertNull(c.target(1.0), "nothing known yet")
        film(c)
        assertEquals(-24.0, c.level, "the programme's level is its speech, not its loud scene")
        assertEquals(0.0, c.target(10.0), "speech stays as it is")
        assertTrue(c.target(38.9)!! < -9, "turned down already a moment before the explosion")
        assertEquals(-(16 - LevelCurve.THRESHOLD) * (1 - 1 / LevelCurve.RATIO), c.target(41.0)!!, 0.01)
        assertTrue(c.target(53.0)!! > 0 && c.target(53.0)!! <= LevelCurve.BOOST, "the whisper turned up a little")
        assertEquals(0.0, c.target(20.5), "a pause isn't turned up")
        val g = (380 until 480).map { c.step(it / 10.0, 0.1) }
        assertTrue(g.min() < -9 && g[65] > g.min() && g[65] < 0 && g.last() == 0.0, "after it, back up slowly: $g")
    }

    @Test
    fun volumes() {
        assertEquals(20, volumeFor(20, 0.0))
        assertEquals(10, volumeFor(20, -10.0))
        assertEquals(30, volumeFor(20, 6.0))
        assertEquals(0, volumeFor(0, 6.0), "muted stays muted")
        assertEquals(1, volumeFor(2, -18.0), "never turned all the way off")
        assertEquals(100, volumeFor(90, 6.0))
    }

    @Test
    fun theMeterReadsR128() {
        // R128: a 997 Hz sine at -20 dBFS in both channels is -20 LUFS (and the same at any sample rate)
        for (rate in listOf(44100, 48000)) {
            val got = ArrayList<Pair<Double, Double>>()
            val m = LoudnessMeter(rate, 2) { t, l -> got += t to l }
            val pcm = FloatArray(rate * 2 * 2)
            for (n in 0 until rate * 2) {
                val v = (0.1 * sin(2 * PI * 997 * n / rate)).toFloat()
                pcm[2 * n] = v
                pcm[2 * n + 1] = v
            }
            m.feed(pcm, rate * 2)
            assertEquals(17, got.size, "a value every 0.1 s once 0.4 s are in")
            assertEquals(0.2, got[0].first, 1e-9, "the middle of the first window")
            assertTrue(got.drop(3).all { abs(it.second + 20.0) < 0.1 }, "$rate Hz: ${got.map { it.second }}")
        }
        val quiet = ArrayList<Double>()
        LoudnessMeter(48000, 1) { _, l -> quiet += l }.feed(ShortArray(48000), 48000)
        assertTrue(quiet.all { it < -100 }, "silence")
    }

    private class VolumeTv(@Volatile var now: Int = 20) : VolumeControl {
        val sets: MutableList<Int> = java.util.Collections.synchronizedList(ArrayList())
        override fun getVolume(): Int = now
        override fun setVolume(value: Int) {
            now = value
            sets += value
        }
    }

    /** A file's loudness without decoding anything: speech, 3-5 s a loud scene. */
    private val source = LoudnessSource { _, start, sink ->
        var t = start
        while (t < 12 && sink.keepGoing(t)) {
            sink.add(t, if (t >= 3 && t < 5) -6.0 else -24.0)
            t += 0.1
        }
    }

    @Test
    fun itRidesTheVolume() {
        val tv = VolumeTv()
        val lev = Leveler(source)
        lev.follow(tv, "film", 0.0)
        val start = System.nanoTime() / 1e9
        var lowest = 99
        var during = 0
        while (true) {
            val pos = System.nanoTime() / 1e9 - start
            if (pos > 11.0) break
            lev.saw(Math.floor(pos), true) // whole seconds, as TVs say
            lowest = minOf(lowest, tv.now)
            if (pos in 3.2..4.8) during = maxOf(during, tv.now)
            Thread.sleep(100)
        }
        assertTrue(lowest <= 12 && during <= 12, "turned down for the loud scene: ${tv.sets}")
        assertEquals(20, tv.now, "and back up after it: ${tv.sets}")
        assertTrue(tv.sets.all { it <= 20 }, "speech isn't turned up: ${tv.sets}")
        assertEquals(20, lev.observed(20), "the user's volume")
        lev.close(restore = true, wait = true)
    }

    @Test
    fun theUsersVolumeStaysTheirs() {
        val tv = VolumeTv()
        val lev = Leveler(source)
        lev.follow(tv, "film", 0.0)
        Thread.sleep(500)
        assertEquals(20, lev.observed(20))
        tv.now = 30 // the remote
        assertEquals(30, lev.observed(30), "turned up on the remote: that's the user's now")
        lev.setBase(16)
        Thread.sleep(600)
        assertEquals(16, tv.now, "set on the phone")
        lev.saw(3.0, true) // the loud scene
        Thread.sleep(800)
        assertTrue(tv.now < 16, "evened out around it: ${tv.sets}")
        assertEquals(16, lev.observed(tv.now), "what the leveler set isn't the user's")
        lev.unfollow()
        Thread.sleep(600)
        assertEquals(16, tv.now, "let go: the user's volume is back")
        lev.close(restore = true, wait = true)
    }

    @Test
    fun theCompressorEvensOutAndNeverClips() {
        val rate = 48000
        fun level(amp: Double): Double {
            val pcm = ShortArray(rate * 2)
            for (n in 0 until rate) {
                val v = (amp * 32767 * sin(2 * PI * 300 * n / rate)).toInt().toShort()
                pcm[2 * n] = v
                pcm[2 * n + 1] = v
            }
            Compressor(rate, 2).process(pcm, rate)
            return pcm.drop(rate).maxOf { abs(it.toInt()) } / 32768.0 // the second half, settled
        }
        val quiet = level(0.03)
        val loud = level(1.0)
        val inDb = 20 * kotlin.math.log10(1.0 / 0.03)
        val outDb = 20 * kotlin.math.log10(loud / quiet)
        assertTrue(outDb < inDb - 8, "30 dB in, ${"%.1f".format(outDb)} dB out")
        assertTrue(loud <= Compressor.CEILING + 0.01, "no clipping: $loud")
    }
}
