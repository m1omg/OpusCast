package io.github.m1omg.opuscast.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Hands one live MPEG-TS stream (whole 188-byte packets, see [TsMuxer]) to every HTTP request for it.
 *
 * TVs often open the stream twice (a peek, then the real thing) and reconnect after a hiccup. A request
 * that joins gets the stream's PAT and PMT, then the packets from the most recent keyframe, so the TV can
 * start decoding straight away. A request that falls far behind (the TV paused, the Wi-Fi stalled) is
 * dropped back to the next keyframe instead of piling up a backlog that would only show old pictures.
 */
class LiveHub(private val maxBacklog: Int = 8 shl 20, private val maxGop: Int = 16 shl 20) {
    /** Called (outside the lock) when a request joins or had to skip ahead: a fresh keyframe helps it start. */
    @Volatile var onJoin: (() -> Unit)? = null

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var pat: ByteArray? = null
    private var pmt: ByteArray? = null
    private var pmtPid = -1
    private var videoPid = -1
    private var videoType = 0
    /** Packets from the latest keyframe on (invalid while it grew past [maxGop]). */
    private val gop = ArrayList<ByteArray>()
    private var gopBytes = 0
    private var gopValid = false
    private val clients = ArrayList<Client>()
    @Volatile var closed = false
        private set

    inner class Client internal constructor() {
        internal val queue = ArrayDeque<ByteArray>()
        internal var queued = 0
        /** Waiting for a keyframe to start (again) from. */
        internal var waiting = true
        internal var gone = false

        internal fun add(p: ByteArray) {
            queue.addLast(p)
            queued += p.size
        }

        /** The next bytes for the TV; empty after [timeoutMs] without any; null once the stream is over. */
        fun read(timeoutMs: Long): ByteArray? = lock.withLock {
            var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (queue.isEmpty() && !closed && !gone && left > 0) left = changed.awaitNanos(left)
            if (queue.isEmpty()) return@withLock if (closed || gone) null else ByteArray(0)
            val n = minOf(queued, 256 * 1024).coerceAtLeast(queue.first().size)
            val out = ByteArray(n)
            var at = 0
            while (queue.isNotEmpty() && at + queue.first().size <= n) {
                val p = queue.removeFirst()
                p.copyInto(out, at)
                at += p.size
            }
            queued -= at
            if (at == n) out else out.copyOf(at)
        }

        fun close() {
            lock.withLock {
                gone = true
                clients.remove(this)
                queue.clear()
                queued = 0
            }
        }
    }

    /** A new reader, starting at the latest keyframe (or the next one, if none has come yet). */
    fun open(): Client {
        val c = Client()
        lock.withLock {
            val pa = pat
            val pm = pmt
            if (gopValid && pa != null && pm != null) {
                c.add(pa)
                c.add(pm)
                gop.forEach(c::add)
                c.waiting = false
            }
            clients += c
            changed.signalAll()
        }
        onJoin?.invoke()
        return c
    }

    val readers: Int get() = lock.withLock { clients.size }

    /** Whole packets from the muxer. */
    fun write(data: ByteArray, off: Int = 0, len: Int = data.size) {
        var skipped = false
        lock.withLock {
            if (closed) return
            var p = off
            while (p + 188 <= off + len) {
                if (data[p].toInt() == 0x47) skipped = packet(data.copyOfRange(p, p + 188)) || skipped
                p += 188
            }
            changed.signalAll()
        }
        if (skipped) onJoin?.invoke()
    }

    /** No more data: readers finish what they have and then see the end. */
    fun close() {
        lock.withLock {
            closed = true
            changed.signalAll()
        }
    }

    /** Returns true when a reader had to skip ahead. */
    private fun packet(p: ByteArray): Boolean {
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val start = p[1].toInt() and 0x40 != 0
        if (pid == 0 && start) {
            parsePat(p)
            pat = p
        } else if (pid == pmtPid && start) {
            parsePmt(p)
            pmt = p
        }
        val key = pid == videoPid && start && isKeyframe(p)
        if (key) {
            gop.clear()
            gopBytes = 0
            gopValid = true
        }
        if (gopValid) {
            if (gopBytes + p.size <= maxGop) {
                gop += p
                gopBytes += p.size
            } else {
                gopValid = false
                gop.clear()
                gopBytes = 0
            }
        }
        var skipped = false
        for (c in clients) {
            if (c.waiting) {
                val pa = pat
                val pm = pmt
                if (key && pa != null && pm != null) {
                    c.add(pa)
                    c.add(pm)
                    c.add(p)
                    c.waiting = false
                }
            } else {
                c.add(p)
                if (c.queued > maxBacklog) {
                    c.queue.clear()
                    c.queued = 0
                    c.waiting = true
                    skipped = true
                }
            }
        }
        return skipped
    }

    /** Where the payload starts (after the adaptation field), or -1. */
    private fun payloadStart(p: ByteArray): Int {
        val afc = (p[3].toInt() shr 4) and 3
        if (afc and 1 == 0) return -1
        val at = if (afc and 2 != 0) 5 + (p[4].toInt() and 0xFF) else 4
        return if (at < 188) at else -1
    }

    private fun parsePat(p: ByteArray) {
        val s = payloadStart(p).takeIf { it >= 0 }?.let { it + 1 + (p[it].toInt() and 0xFF) } ?: return
        if (s + 8 > 188 || p[s].toInt() != 0) return
        val end = minOf(188, s + 3 + (((p[s + 1].toInt() and 0x0F) shl 8) or (p[s + 2].toInt() and 0xFF)) - 4)
        var i = s + 8
        while (i + 4 <= end) {
            val program = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            if (program != 0) {
                pmtPid = ((p[i + 2].toInt() and 0x1F) shl 8) or (p[i + 3].toInt() and 0xFF)
                return
            }
            i += 4
        }
    }

    private fun parsePmt(p: ByteArray) {
        val s = payloadStart(p).takeIf { it >= 0 }?.let { it + 1 + (p[it].toInt() and 0xFF) } ?: return
        if (s + 12 > 188 || p[s].toInt() != 2) return
        val end = minOf(188, s + 3 + (((p[s + 1].toInt() and 0x0F) shl 8) or (p[s + 2].toInt() and 0xFF)) - 4)
        var i = s + 12 + (((p[s + 10].toInt() and 0x0F) shl 8) or (p[s + 11].toInt() and 0xFF))
        while (i + 5 <= end) {
            val type = p[i].toInt() and 0xFF
            val pid = ((p[i + 1].toInt() and 0x1F) shl 8) or (p[i + 2].toInt() and 0xFF)
            if (type in VIDEO_TYPES) {
                videoPid = pid
                videoType = type
                return
            }
            i += 5 + (((p[i + 3].toInt() and 0x0F) shl 8) or (p[i + 4].toInt() and 0xFF))
        }
    }

    /** A video PES that starts with a keyframe: flagged random access, or carrying SPS / an IDR picture. */
    private fun isKeyframe(p: ByteArray): Boolean {
        val afc = (p[3].toInt() shr 4) and 3
        if (afc and 2 != 0 && (p[4].toInt() and 0xFF) > 0 && p[5].toInt() and 0x40 != 0) return true
        var i = payloadStart(p)
        if (i < 0) return false
        if (i + 9 <= 188 && p[i].toInt() == 0 && p[i + 1].toInt() == 0 && p[i + 2].toInt() == 1) i += 9 + (p[i + 8].toInt() and 0xFF)
        while (i + 3 < 188) {
            if (p[i].toInt() == 0 && p[i + 1].toInt() == 0 && p[i + 2].toInt() == 1) {
                val b = p[i + 3].toInt() and 0xFF
                when (videoType) {
                    0x1B -> if ((b and 0x1F) == 5 || (b and 0x1F) == 7) return true
                    0x24 -> {
                        val t = (b shr 1) and 0x3F
                        if (t in 16..21 || t in 32..34) return true
                    }
                    else -> if (b == 0xB3) return true // MPEG-1/2 sequence header
                }
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    private companion object {
        /** H.264, HEVC, MPEG-2 and MPEG-1 video. */
        val VIDEO_TYPES = setOf(0x1B, 0x24, 0x02, 0x01)
    }
}
