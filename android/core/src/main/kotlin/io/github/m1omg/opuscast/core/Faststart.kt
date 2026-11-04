package io.github.m1omg.opuscast.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Move an MP4's index (moov) in front of the media data, like ffmpeg's +faststart, so a TV can start
 *  playing without first fetching the end of the file. Returns the input unchanged if it's already
 *  that way or isn't a plain MP4. */
fun mp4Faststart(file: ByteArray): ByteArray {
    data class TopBox(val type: String, val start: Int, val end: Int)
    val boxes = ArrayList<TopBox>()
    var pos = 0
    while (pos + 8 <= file.size) {
        var size = ByteBuffer.wrap(file, pos, 4).int.toLong() and 0xFFFFFFFFL
        val type = String(file, pos + 4, 4, Charsets.ISO_8859_1)
        if (size == 1L) size = ByteBuffer.wrap(file, pos + 8, 8).long
        if (size == 0L) size = (file.size - pos).toLong()
        if (size < 8 || pos + size > file.size) return file
        boxes += TopBox(type, pos, (pos + size).toInt())
        pos += size.toInt()
    }
    val moov = boxes.firstOrNull { it.type == "moov" } ?: return file
    val firstMdat = boxes.indexOfFirst { it.type == "mdat" }
    if (firstMdat < 0 || boxes.indexOf(moov) < firstMdat) return file
    val newMoov = file.copyOfRange(moov.start, moov.end)
    val delta = newMoov.size.toLong()
    if (!shiftChunkOffsets(newMoov, 8, newMoov.size, delta)) return file
    val out = ByteArrayOutputStream(file.size)
    val before = boxes.subList(0, firstMdat)
    for (b in before) out.write(file, b.start, b.end - b.start)
    out.write(newMoov)
    for (b in boxes.subList(firstMdat, boxes.size)) if (b !== moov) out.write(file, b.start, b.end - b.start)
    return out.toByteArray()
}

private val CONTAINERS = setOf("trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "mvex")

/** Add [delta] to every stco/co64 entry under moov[start, end). False if an offset would overflow. */
private fun shiftChunkOffsets(b: ByteArray, start: Int, end: Int, delta: Long): Boolean {
    val buf = ByteBuffer.wrap(b)
    var pos = start
    while (pos + 8 <= end) {
        val size = buf.getInt(pos).toLong() and 0xFFFFFFFFL
        val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
        if (size < 8 || pos + size > end) return true
        val boxEnd = (pos + size).toInt()
        when (type) {
            in CONTAINERS -> if (!shiftChunkOffsets(b, pos + 8, boxEnd, delta)) return false
            "stco" -> {
                val n = buf.getInt(pos + 12)
                for (i in 0 until n) {
                    val p = pos + 16 + i * 4
                    val v = (buf.getInt(p).toLong() and 0xFFFFFFFFL) + delta
                    if (v > 0xFFFFFFFFL) return false
                    buf.putInt(p, v.toInt())
                }
            }
            "co64" -> {
                val n = buf.getInt(pos + 12)
                for (i in 0 until n) {
                    val p = pos + 16 + i * 8
                    buf.putLong(p, buf.getLong(p) + delta)
                }
            }
        }
        pos = boxEnd
    }
    return true
}
