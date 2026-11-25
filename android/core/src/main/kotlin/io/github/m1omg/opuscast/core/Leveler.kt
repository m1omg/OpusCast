package io.github.m1omg.opuscast.core

import java.io.Closeable
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/*
 * Evening out the sound of what the TV plays, without touching the file: the phone reads the sound's
 * loudness ahead of the TV (it decodes it and nothing more - nothing is re-encoded or sent) and turns the
 * TV's own volume down a moment before loud scenes and a little up in quiet ones. The same rules as
 * opuscast_core.LevelCurve / Leveler in the desktop app.
 */

/** How much quieter or louder the TV should be at a moment, from the loudness read ahead (EBU R128
 *  momentary loudness, one value per 0.1 s). Loud moments above the programme's level are turned down
 *  3:1, long quiet passages up a little (at most 6 dB); silence doesn't count. */
class LevelCurve {
    private val loud = HashMap<Int, Double>()
    private val hist = HashMap<Int, Int>() // loudness in 0.5 LU bins -> count
    private var counted = 0
    /** The programme's own level: the median loudness of what isn't silence (mostly speech in a film). */
    var level: Double? = null
        private set
    var gain = 0.0

    /** Momentary loudness at [t] seconds (the middle of its 0.4 s window). */
    fun add(t: Double, lufs: Double) {
        val i = (t / STEP).roundToInt()
        if (loud.containsKey(i)) return
        loud[i] = lufs
        if (lufs > SILENCE) {
            val b = (lufs * 2).roundToInt()
            hist[b] = (hist[b] ?: 0) + 1
            counted++
            if (counted < 200 || counted % 50 == 0) level = median()
        }
    }

    private fun median(): Double? {
        val total = hist.entries.filter { it.key / 2.0 > SILENCE }.sumOf { it.value }
        if (total < 20) return null
        var half = total / 2.0
        for (b in hist.keys.sorted()) {
            if (b / 2.0 <= SILENCE) continue
            half -= hist.getValue(b)
            if (half <= 0) return b / 2.0
        }
        return null
    }

    fun covers(t: Double) = loud.containsKey((t / STEP).roundToInt())

    /** dB for loudness [rel] LU above (+) or under (-) the programme's level. */
    fun curve(rel: Double): Double = when {
        rel > THRESHOLD -> -min(CUT, (rel - THRESHOLD) * (1 - 1 / RATIO))
        rel < QUIET -> min(BOOST, (QUIET - rel) * LIFT)
        else -> 0.0
    }

    /** dB wanted at [pos] seconds; null when nothing around it is known (yet). */
    fun target(pos: Double): Double? {
        val lv = level ?: return null
        var best: Double? = null
        for (i in ((pos - LOOKBACK) / STEP).roundToInt()..((pos + LOOKAHEAD) / STEP).roundToInt()) {
            val v = loud[i] ?: continue
            if (v <= SILENCE) continue
            val g = curve(v - lv)
            if (best == null || g < best) best = g
        }
        return best
    }

    /** Moves [gain] towards the target at [pos], [dt] seconds after the last step: down at once (the
     *  look-ahead already started it early), up at [RELEASE] dB a second. */
    fun step(pos: Double, dt: Double): Double {
        val want = target(pos) ?: if (gain > 0) 0.0 else gain // unknown: no boost, hold a cut
        gain = if (want < gain) want else min(want, gain + RELEASE * max(0.0, dt))
        return gain
    }

    companion object {
        const val STEP = 0.1
        const val THRESHOLD = 2.0
        const val RATIO = 3.0
        const val QUIET = -10.0
        const val LIFT = 0.5
        const val BOOST = 6.0
        const val CUT = 18.0
        const val SILENCE = -60.0
        const val LOOKAHEAD = 1.2
        const val LOOKBACK = 0.6
        const val RELEASE = 2.5
    }
}

/** The TV's volume for the user's volume [base] changed by [gain] dB: TV volume steps follow the ear,
 *  roughly - 10 dB is twice (or half) the number. */
fun volumeFor(base: Int?, gain: Double): Int {
    if (base == null || base <= 0) return 0
    return (base * 2.0.pow(gain / 10)).roundToInt().coerceIn(1, 100)
}

/** EBU R128 momentary loudness (K-weighted, 0.4 s windows every 0.1 s) of interleaved PCM: [emit] gets the
 *  middle of each window (seconds since the first sample) and its loudness in LUFS. */
class LoudnessMeter(private val rate: Int, private val channels: Int, private val emit: (Double, Double) -> Unit) {
    private val filters = Array(channels) { KWeighting(rate.toDouble()) }
    // 5.1 (L R C LFE Ls Rs): no LFE, the surrounds count 1.41 times
    private val weights = DoubleArray(channels) { c -> if (channels == 6 && c == 3) 0.0 else if (channels == 6 && c >= 4) 1.41 else 1.0 }
    private val hop = max(1, rate / 10)
    private var inHop = 0
    private val acc = DoubleArray(channels)
    private val hops = ArrayDeque<Double>()
    private var hopsDone = 0L

    fun feed(pcm: FloatArray, frames: Int) {
        var k = 0
        for (n in 0 until frames) {
            for (c in 0 until channels) {
                val y = filters[c].process(pcm[k++].toDouble())
                acc[c] += y * y
            }
            if (++inHop == hop) endHop()
        }
    }

    fun feed(pcm: ShortArray, frames: Int) {
        var k = 0
        for (n in 0 until frames) {
            for (c in 0 until channels) {
                val y = filters[c].process(pcm[k++] / 32768.0)
                acc[c] += y * y
            }
            if (++inHop == hop) endHop()
        }
    }

    private fun endHop() {
        var e = 0.0
        for (c in 0 until channels) {
            e += weights[c] * acc[c] / hop
            acc[c] = 0.0
        }
        inHop = 0
        hops.addLast(e)
        if (hops.size > 4) hops.removeFirst()
        hopsDone++
        if (hops.size == 4) {
            val mean = hops.sum() / 4
            emit(hopsDone * hop.toDouble() / rate - 0.2, if (mean > 1e-12) -0.691 + 10 * log10(mean) else -120.0)
        }
    }
}

/** R128's K-weighting: a high shelf (the head) and a high-pass, for any sample rate (as libebur128). */
private class KWeighting(fs: Double) {
    private val b = DoubleArray(5)
    private val a = DoubleArray(5)
    private val v = DoubleArray(5)

    init {
        var f0 = 1681.974450955533
        val g = 3.999843853973347
        var q = 0.7071752369554196
        var k = tan(PI * f0 / fs)
        val vh = 10.0.pow(g / 20)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1 + k / q + k * k
        val pb = doubleArrayOf((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0)
        val pa = doubleArrayOf(1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        f0 = 38.13547087602444
        q = 0.5003270373238773
        k = tan(PI * f0 / fs)
        val rb = doubleArrayOf(1.0, -2.0, 1.0)
        val ra = doubleArrayOf(1.0, 2 * (k * k - 1) / (1 + k / q + k * k), (1 - k / q + k * k) / (1 + k / q + k * k))
        b[0] = pb[0] * rb[0]
        b[1] = pb[0] * rb[1] + pb[1] * rb[0]
        b[2] = pb[0] * rb[2] + pb[1] * rb[1] + pb[2] * rb[0]
        b[3] = pb[1] * rb[2] + pb[2] * rb[1]
        b[4] = pb[2] * rb[2]
        a[0] = pa[0] * ra[0]
        a[1] = pa[0] * ra[1] + pa[1] * ra[0]
        a[2] = pa[0] * ra[2] + pa[1] * ra[1] + pa[2] * ra[0]
        a[3] = pa[1] * ra[2] + pa[2] * ra[1]
        a[4] = pa[2] * ra[2]
    }

    fun process(x: Double): Double {
        v[4] = v[3]; v[3] = v[2]; v[2] = v[1]; v[1] = v[0]
        v[0] = x - a[1] * v[1] - a[2] * v[2] - a[3] * v[3] - a[4] * v[4]
        val y = b[0] * v[0] + b[1] * v[1] + b[2] * v[2] + b[3] * v[3] + b[4] * v[4]
        if (kotlin.math.abs(v[0]) < 1e-30) v[0] = 0.0 // no denormals in silence
        return y
    }
}

/** The TV's volume, as the leveler needs it ([Tv] is one). */
interface VolumeControl {
    fun getVolume(): Int?
    fun setVolume(value: Int)
}

/** Reads a file's sound loudness from [start] seconds on, as fast as [sink] lets it. */
fun interface LoudnessSource {
    fun read(file: String, start: Double, sink: LoudnessSink)
}

interface LoudnessSink {
    /** Loudness [lufs] at [t] seconds of the file. */
    fun add(t: Double, lufs: Double)
    /** Before reading on at [t] seconds: waits while the reading is far enough ahead of the TV; false: stop. */
    fun keepGoing(t: Double): Boolean
}

/**
 * Rides the TV's volume by the loudness of what it plays ([LevelCurve]). The user's volume stays the
 * user's: what they set (on the phone or the remote) is what everything is measured from, and it's back
 * on the TV when the leveler lets go.
 *
 * The app tells it what plays ([follow] / [unfollow]), where the TV is ([saw]) and the volume the TV
 * reports ([observed]: returns the user's volume to show). Its own thread sends the volume, at most twice
 * a second; the reading keeps [ahead] seconds in front of the TV (a phone's battery).
 */
class Leveler(private val source: LoudnessSource, private val ahead: Double = 600.0) : Closeable {
    private val lock = Object()
    @Volatile private var closed = false
    private var tv: VolumeControl? = null
    /** The user's volume (null until known). */
    @Volatile var base: Int? = null
        private set
    private var file: String? = null
    private var curve = LevelCurve()
    private var gen = 0
    private var spanStart = 0.0
    private var spanLast = 0.0
    private var running = false
    private var endedAt: Double? = null
    private var failed = false
    private var seenPos = 0.0
    private var seenAt = 0.0
    private var seenPlaying = false
    private var seenAny = false
    private val sent = ArrayList<Pair<Int, Double>>()
    private var sentVolume: Int? = null
    private var lastSend = 0.0
    private var force = false

    val active: Boolean get() = synchronized(lock) { file != null }
    /** The gain now, dB (for tests and the curious). */
    val gain: Double get() = synchronized(lock) { curve.gain }

    init {
        thread(isDaemon = true, name = "opuscast-leveler") { run() }
    }

    /** Even out this file's sound from [start] seconds on (a new video or song). */
    fun follow(tv: VolumeControl, file: String, start: Double = 0.0) {
        synchronized(lock) {
            gen++
            if (tv !== this.tv) {
                val old = this.tv
                val b = base
                if (old != null && b != null && sentVolume != b) thread(isDaemon = true) { quietlySet(old, b) }
                this.tv = tv
                base = null
                sentVolume = null
            }
            this.file = file
            curve = LevelCurve()
            seenAny = false
            endedAt = null
            failed = false
            force = true
            analyse(max(0.0, start))
            lock.notifyAll()
        }
    }

    /** Nothing to even out (stopped, a photo...): the user's volume goes back on the TV. */
    fun unfollow() {
        synchronized(lock) {
            gen++
            running = false
            file = null
            curve = LevelCurve()
            force = true
            lock.notifyAll()
        }
    }

    /** The TV's position (seconds, null if it didn't say) and whether it's playing this. */
    fun saw(pos: Double?, playing: Boolean) {
        synchronized(lock) {
            val now = clock()
            if (pos == null) {
                if (!playing) seenAny = false
                return
            }
            val est = position(now)
            var p = pos
            if (playing && est != null && est - pos >= 0 && est - pos < 1.3) p = est // TVs report whole seconds
            else if (playing && p == Math.floor(p)) p += 0.5
            seenPos = p
            seenAt = now
            seenPlaying = playing
            seenAny = true
        }
    }

    /** The volume the TV reports; returns the user's volume (what to show). */
    fun observed(volume: Int?): Int? {
        if (volume == null) return base
        synchronized(lock) {
            val now = clock()
            sent.removeAll { now - it.second >= OWN }
            if (base == null) {
                base = volume
            } else if (volume != sentVolume && sent.none { it.first == volume }) {
                // changed on the remote: that's the user's volume now, as it would be without the leveler
                base = (volume / 2.0.pow(curve.gain / 10)).roundToInt().coerceIn(0, 100)
                sentVolume = volume
            }
            return base
        }
    }

    /** The user set this volume on the phone. */
    fun setBase(volume: Int) {
        synchronized(lock) {
            base = volume.coerceIn(0, 100)
            force = true
            lock.notifyAll()
        }
    }

    /** Stop; with [restore], the user's volume goes back on the TV ([wait]: before this returns). */
    fun close(restore: Boolean, wait: Boolean) {
        val t: VolumeControl?
        val b: Int?
        val s: Int?
        synchronized(lock) {
            gen++
            closed = true
            t = tv
            b = base
            s = sentVolume
            lock.notifyAll()
        }
        if (restore && t != null && b != null && s != b) {
            if (wait) quietlySet(t, b) else thread(isDaemon = true) { quietlySet(t, b) }
        }
    }

    override fun close() = close(restore = true, wait = false)

    private fun quietlySet(tv: VolumeControl, volume: Int) {
        runCatching { tv.setVolume(volume) }
    }

    private fun clock() = System.nanoTime() / 1e9

    private fun position(now: Double): Double? =
        if (!seenAny) null else if (seenPlaying) seenPos + min(now - seenAt, 2.0) else seenPos

    /** Read the loudness from [start] on (under the lock). */
    private fun analyse(start: Double) {
        gen++
        val g = gen
        val f = file ?: return
        val c = curve
        spanStart = start
        spanLast = start
        running = true
        thread(isDaemon = true, name = "opuscast-loudness") {
            var last = start
            val sink = object : LoudnessSink {
                override fun add(t: Double, lufs: Double) {
                    synchronized(lock) {
                        if (g != gen) return
                        c.add(t, lufs)
                        last = t
                        spanLast = t
                    }
                }

                override fun keepGoing(t: Double): Boolean {
                    synchronized(lock) {
                        while (true) {
                            if (g != gen || closed) return false
                            val p = position(clock()) ?: start
                            if (t < p + ahead) return true
                            lock.wait(1000)
                        }
                    }
                }
            }
            val ok = runCatching { source.read(f, start, sink) }.isSuccess
            synchronized(lock) {
                if (g == gen) {
                    running = false
                    if (ok) endedAt = last else if (last <= start) failed = true
                }
            }
        }
    }

    /** A seek outside what's read: read from there (under the lock). */
    private fun needsAnalysis(pos: Double): Boolean {
        if (curve.covers(pos) || curve.covers(pos + 1) || failed) return false
        endedAt?.let { if (pos >= it - 1) return false } // past the end of the sound
        if (running && spanStart - 1 <= pos && spanLast < pos + 60) return false // it gets there in a moment
        return true
    }

    private fun run() {
        var last = clock()
        while (!closed) {
            var t: VolumeControl?
            var needBase: Boolean
            synchronized(lock) {
                lock.wait((TICK * 1000).toLong())
                t = tv
                needBase = base == null
            }
            if (closed) return
            val target = t ?: continue
            if (needBase) {
                val v = runCatching { target.getVolume() }.getOrNull()
                synchronized(lock) { if (base == null && target === tv && v != null) { base = v; sentVolume = v } }
                if (v == null) {
                    Thread.sleep(1000)
                    continue
                }
            }
            var send: Int? = null
            synchronized(lock) {
                val now = clock()
                val dt = now - last
                last = now
                if (closed || target !== tv) return@synchronized
                val pos = position(now)
                if (file == null) {
                    curve.gain = 0.0
                } else if (pos != null && seenPlaying) {
                    if (needsAnalysis(pos)) analyse(max(0.0, pos - 1))
                    curve.step(pos, dt)
                }
                val want = volumeFor(base, curve.gain)
                if (want != sentVolume && (force || now - lastSend >= MIN_GAP)) {
                    send = want
                    sentVolume = want
                    lastSend = now
                    sent.add(want to now)
                }
                force = false
            }
            send?.let { v ->
                if (runCatching { target.setVolume(v) }.isFailure) synchronized(lock) { sentVolume = null }
            }
        }
    }

    companion object {
        const val TICK = 0.2
        const val MIN_GAP = 0.5
        /** A volume the TV reports this soon after it was set is ours. */
        const val OWN = 4.0
    }
}

/** Evening out live sound on its way to the encoder (the phone's screen): a compressor, 3:1 above
 *  -20 dBFS with a soft knee, 5 dB up, and a limiter so nothing clips. Interleaved 16-bit PCM, in place. */
class Compressor(rate: Int, private val channels: Int) {
    private val attack = kotlin.math.exp(-1.0 / (0.010 * rate))
    private val release = kotlin.math.exp(-1.0 / (0.300 * rate))
    private var env = 0.0 // the level followed, dB
    private var limit = 1.0 // the limiter's gain
    private var scratch = ShortArray(0)

    /** 16-bit little-endian PCM bytes, as a recorder gives them. */
    fun process(pcm: ByteArray, len: Int) {
        val n = len / 2
        if (scratch.size < n) scratch = ShortArray(n)
        for (i in 0 until n) scratch[i] = ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
        process(scratch, n / channels)
        for (i in 0 until n) {
            val v = scratch[i].toInt()
            pcm[2 * i] = v.toByte()
            pcm[2 * i + 1] = (v shr 8).toByte()
        }
    }

    fun process(pcm: ShortArray, frames: Int) {
        var k = 0
        for (n in 0 until frames) {
            var peak = 0.0
            for (c in 0 until channels) peak = max(peak, kotlin.math.abs(pcm[k + c] / 32768.0))
            val db = if (peak > 1e-6) 20 * log10(peak) else -120.0
            env = if (db > env) attack * env + (1 - attack) * db else release * env + (1 - release) * db
            val over = env - THRESHOLD
            val reduce = when {
                over <= -KNEE / 2 -> 0.0
                over >= KNEE / 2 -> over * (1 - 1 / RATIO)
                else -> (1 - 1 / RATIO) * (over + KNEE / 2) * (over + KNEE / 2) / (2 * KNEE)
            }
            var g = 10.0.pow((MAKEUP - reduce) / 20)
            val out = peak * g
            limit = if (out * limit > CEILING) CEILING / out else min(1.0, limit + 0.0005)
            g *= limit
            for (c in 0 until channels) {
                pcm[k + c] = (pcm[k + c] * g).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            k += channels
        }
    }

    companion object {
        const val THRESHOLD = -20.0
        const val RATIO = 3.0
        const val KNEE = 4.0
        const val MAKEUP = 5.0
        const val CEILING = 0.95
    }
}
