package io.github.m1omg.opuscast.core

/**
 * Writes an MPEG-2 transport stream with one H.264 video and one AAC (ADTS) audio stream: what DLNA TVs
 * play as a live stream, here the phone's screen. Hands whole 188-byte packets to [out].
 *
 * Timestamps are microseconds on any clock shared by both streams (the encoders' presentation times).
 * Every keyframe is preceded by the PAT and PMT and carries SPS/PPS, so a TV can start decoding at any
 * keyframe - the [LiveHub] starts every request at one.
 */
class TsMuxer(private val out: (ByteArray, Int, Int) -> Unit) {
    companion object {
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
        /** PTS runs this far ahead of the PCR: the decoder's buffer (ffmpeg's default mux delay too). */
        private const val DELAY = 63_000L
        private const val PCR_EVERY = 40 * 90L
        private const val PSI_EVERY = 100 * 90L
        private const val MASK33 = (1L shl 33) - 1
        private val AUD = byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte())
    }

    private val pkt = ByteArray(188)
    private val cc = HashMap<Int, Int>()
    private var config: ByteArray? = null
    private var t0 = Long.MIN_VALUE
    /** The newest media time written, 90 kHz: what the PCR says. */
    private var newest = 0L
    private var lastPcr = Long.MIN_VALUE
    private var lastPsi = Long.MIN_VALUE

    /** The encoder's codec-config buffer (SPS and PPS with start codes), put before every keyframe. */
    @Synchronized
    fun setVideoConfig(spsPps: ByteArray) {
        config = spsPps.copyOf()
    }

    /** One encoded frame (Annex B, start codes included) as the encoder gave it. */
    @Synchronized
    fun writeVideo(frame: ByteArray, off: Int, len: Int, ptsUs: Long, keyframe: Boolean) {
        val pts = clock(ptsUs)
        if (keyframe || lastPsi == Long.MIN_VALUE || newest - lastPsi >= PSI_EVERY) writePsi()
        val cfg = config?.takeIf { keyframe && firstNalType(frame, off, len) != 7 }
        val payload = ByteArray(AUD.size + (cfg?.size ?: 0) + len)
        AUD.copyInto(payload)
        cfg?.copyInto(payload, AUD.size)
        frame.copyInto(payload, AUD.size + (cfg?.size ?: 0), off, off + len)
        lastPcr = maxOf(newest, if (lastPcr == Long.MIN_VALUE) 0 else lastPcr)
        writePes(VIDEO_PID, 0xE0, payload, pts, rai = keyframe, pcr = lastPcr, bounded = false)
    }

    /** One AAC frame with its 7-byte ADTS header. */
    @Synchronized
    fun writeAudio(adts: ByteArray, off: Int, len: Int, ptsUs: Long) {
        val pts = clock(ptsUs)
        if (lastPsi == Long.MIN_VALUE || newest - lastPsi >= PSI_EVERY) writePsi()
        // while the screen stands still few video frames come, but the TV's clock must keep ticking
        if (lastPcr == Long.MIN_VALUE || newest - lastPcr >= PCR_EVERY) writePcrOnly()
        writePes(AUDIO_PID, 0xC0, adts.copyOfRange(off, off + len), pts, rai = false, pcr = null, bounded = true)
    }

    private fun clock(us: Long): Long {
        if (t0 == Long.MIN_VALUE) t0 = us
        val media = (us - t0) * 9 / 100
        if (media > newest) newest = media
        return (maxOf(0L, media + DELAY)) and MASK33
    }

    private fun nextCc(pid: Int): Int {
        val c = cc[pid] ?: 0
        cc[pid] = (c + 1) and 0xF
        return c
    }

    private fun writePsi() {
        lastPsi = newest
        section(0, byteArrayOf(
            0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte(),
        ))
        section(PMT_PID, byteArrayOf(
            0x02, 0xB0.toByte(), 0x17, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), 0xF0.toByte(), 0x00,
            0x1B, (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), 0xF0.toByte(), 0x00,
            0x0F, (0xE0 or (AUDIO_PID shr 8)).toByte(), (AUDIO_PID and 0xFF).toByte(), 0xF0.toByte(), 0x00,
        ))
    }

    /** A PSI table in one packet: pointer field, the section, its CRC, then 0xFF filling. */
    private fun section(pid: Int, body: ByteArray) {
        pkt.fill(0xFF.toByte())
        pkt[0] = 0x47
        pkt[1] = (0x40 or (pid shr 8)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or nextCc(pid)).toByte()
        pkt[4] = 0
        body.copyInto(pkt, 5)
        val crc = crc32Mpeg(body, 0, body.size)
        for (i in 0 until 4) pkt[5 + body.size + i] = (crc ushr (24 - 8 * i)).toByte()
        out(pkt, 0, 188)
    }

    /** Only a PCR (no payload, so the continuity counter stays). */
    private fun writePcrOnly() {
        lastPcr = maxOf(newest, if (lastPcr == Long.MIN_VALUE) 0 else lastPcr)
        pkt.fill(0xFF.toByte())
        pkt[0] = 0x47
        pkt[1] = (VIDEO_PID shr 8).toByte()
        pkt[2] = (VIDEO_PID and 0xFF).toByte()
        pkt[3] = (0x20 or (((cc[VIDEO_PID] ?: 0) - 1) and 0xF)).toByte()
        pkt[4] = 183.toByte()
        pkt[5] = 0x10
        putPcr(pkt, 6, lastPcr)
        out(pkt, 0, 188)
    }

    private fun writePes(pid: Int, streamId: Int, payload: ByteArray, pts: Long, rai: Boolean, pcr: Long?, bounded: Boolean) {
        val head = ByteArray(14)
        head[2] = 1
        head[3] = streamId.toByte()
        val length = if (bounded) 8 + payload.size else 0 // video PES of any size: length 0 ("unbounded")
        head[4] = (length shr 8).toByte()
        head[5] = length.toByte()
        head[6] = 0x84.toByte() // '10', data aligned: each PES starts an access unit
        head[7] = 0x80.toByte() // PTS only - no B-frames, so no DTS
        head[8] = 5
        putPts(head, 9, pts)
        var pos = 0
        val total = head.size + payload.size
        var first = true
        while (pos < total) {
            val left = total - pos
            var af = -1 // adaptation field length, not counting its length byte (-1: none)
            if (first && (rai || pcr != null)) af = 1 + (if (pcr != null) 6 else 0)
            var room = 184 - (if (af >= 0) af + 1 else 0)
            if (left < room) { // the last packet: fill it up with stuffing in the adaptation field
                val stuff = room - left
                af = if (af < 0) stuff - 1 else af + stuff
                room = left
            }
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            pkt[2] = (pid and 0xFF).toByte()
            pkt[3] = ((if (af >= 0) 0x30 else 0x10) or nextCc(pid)).toByte()
            var i = 4
            if (af >= 0) {
                pkt[i++] = af.toByte()
                if (af > 0) {
                    var flags = 0
                    if (first && rai) flags = flags or 0x40
                    if (first && pcr != null) flags = flags or 0x10
                    pkt[i++] = flags.toByte()
                    if (first && pcr != null) {
                        putPcr(pkt, i, pcr)
                        i += 6
                    }
                    while (i < 5 + af) pkt[i++] = 0xFF.toByte()
                }
            }
            var n = room
            var at = pos
            while (n > 0) { // header bytes first, then the payload
                if (at < head.size) {
                    val k = minOf(n, head.size - at)
                    head.copyInto(pkt, i, at, at + k)
                    i += k; at += k; n -= k
                } else {
                    val p = at - head.size
                    payload.copyInto(pkt, i, p, p + n)
                    i += n; at += n; n = 0
                }
            }
            pos = at
            out(pkt, 0, 188)
            first = false
        }
    }
}

private fun putPts(b: ByteArray, at: Int, pts: Long) {
    b[at] = (0x20 or (((pts shr 30) and 0x07).toInt() shl 1) or 1).toByte()
    b[at + 1] = (pts shr 22).toByte()
    b[at + 2] = ((((pts shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
    b[at + 3] = (pts shr 7).toByte()
    b[at + 4] = (((pts and 0x7F).toInt() shl 1) or 1).toByte()
}

private fun putPcr(b: ByteArray, at: Int, base: Long) {
    val v = base and ((1L shl 33) - 1)
    b[at] = (v shr 25).toByte()
    b[at + 1] = (v shr 17).toByte()
    b[at + 2] = (v shr 9).toByte()
    b[at + 3] = (v shr 1).toByte()
    b[at + 4] = (((v and 1).toInt() shl 7) or 0x7E).toByte() // extension 0
    b[at + 5] = 0
}

/** The NAL unit type of the first NAL in an Annex B buffer (-1 without a start code). */
internal fun firstNalType(b: ByteArray, off: Int, len: Int): Int {
    var i = off
    val end = off + len
    while (i + 3 < end) {
        if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
            if (b[i + 2].toInt() == 1) return b[i + 3].toInt() and 0x1F
            if (b[i + 2].toInt() == 0 && i + 4 < end && b[i + 3].toInt() == 1) return b[i + 4].toInt() and 0x1F
        }
        i++
        if (i - off > 64) return -1 // a start code comes first, or not at all
    }
    return -1
}

/** CRC-32/MPEG-2, as PSI sections carry it. */
internal fun crc32Mpeg(b: ByteArray, off: Int, len: Int): Int {
    var crc = -1
    for (i in off until off + len) {
        crc = crc xor ((b[i].toInt() and 0xFF) shl 24)
        repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
    }
    return crc
}

private val ADTS_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

/** The 7-byte ADTS header for one AAC-LC frame of [aacLength] bytes (MediaCodec gives raw frames). */
fun adtsHeader(aacLength: Int, sampleRate: Int = 48000, channels: Int = 2): ByteArray {
    val rate = ADTS_RATES.indexOf(sampleRate).let { if (it < 0) 3 else it }
    val frame = aacLength + 7
    return byteArrayOf(
        0xFF.toByte(), 0xF1.toByte(), // MPEG-4, no CRC
        ((1 shl 6) or (rate shl 2) or (channels shr 2)).toByte(), // profile LC (2) - 1
        (((channels and 3) shl 6) or (frame shr 11)).toByte(),
        (frame shr 3).toByte(),
        (((frame and 7) shl 5) or 0x1F).toByte(),
        0xFC.toByte(),
    )
}
