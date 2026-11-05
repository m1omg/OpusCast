package io.github.m1omg.opuscast.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveHubTest {
    private val hub = LiveHub(maxBacklog = 64 * 1024)
    private val joins = AtomicInteger()
    private val mux = TsMuxer { b, o, l -> hub.write(b, o, l) }
    private var frame = 0

    init {
        hub.onJoin = { joins.incrementAndGet() }
        mux.setVideoConfig(FAKE_CONFIG)
    }

    private fun frames(n: Int) = repeat(n) {
        val (f, key) = fakeFrame(frame, gop = 10, size = 1000)
        mux.writeVideo(f, 0, f.size, frame * 33_333L, key)
        frame++
    }

    private fun drain(c: LiveHub.Client): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = c.read(50) ?: break
            if (b.isEmpty()) break
            out.write(b)
        }
        return out.toByteArray()
    }

    /** (PID, random access) of each packet. */
    private fun packets(b: ByteArray) = (0 until b.size step 188).map { at ->
        val rai = (b.u(at + 3) shr 4) and 2 != 0 && b.u(at + 4) > 0 && b.u(at + 5) and 0x40 != 0
        pidOf(b, at) to rai
    }

    @Test fun aLateReaderStartsAtTheLatestKeyframe() {
        frames(14) // keyframes at 0 and 10
        val c = hub.open()
        assertEquals(1, joins.get())
        val got = packets(drain(c))
        assertEquals(0, got[0].first) // PAT
        assertEquals(TsMuxer.PMT_PID, got[1].first)
        assertEquals(TsMuxer.VIDEO_PID to true, got[2]) // the keyframe from frame 10, not 0
        assertEquals(1, got.count { it.second })
        frames(1)
        val more = packets(drain(c))
        assertTrue(more.isNotEmpty() && more.none { it.second })
    }

    @Test fun anEarlyReaderWaitsForTheFirstKeyframe() {
        val c = hub.open()
        assertEquals(0, c.read(20)!!.size)
        frames(3)
        val got = packets(drain(c))
        assertEquals(listOf(0, TsMuxer.PMT_PID, TsMuxer.VIDEO_PID), got.take(3).map { it.first })
        assertTrue(got[2].second)
    }

    @Test fun aReaderThatFallsBehindSkipsToTheNextKeyframe() {
        frames(1)
        val slow = hub.open()
        joins.set(0)
        frames(9) // ~9 frames of 1 kB: still below the limit
        frames(80) // far more than 64 kB unread
        assertTrue(joins.get() >= 1, "asked for a keyframe")
        val got = packets(drain(slow))
        assertEquals(0, got[0].first)
        assertTrue(got[2].second, "starts again at a keyframe")
        assertTrue(got.size * 188 <= 64 * 1024 + 20 * 188)
    }

    @Test fun readersSeeTheEnd() {
        frames(2)
        val c = hub.open()
        hub.close()
        assertTrue(c.read(100)!!.isNotEmpty()) // what was there still comes
        assertNull(c.read(100))
        c.close()
        assertEquals(0, hub.readers)
    }
}
