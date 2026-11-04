package io.github.m1omg.opuscast.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.Inflater

/** Positional reads from a video file (a file on disk, or a document opened by the system on Android). */
interface RandomAccessSource {
    val size: Long
    /** Read up to [len] bytes at [pos]; returns the count, or -1 at the end. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
}

class FileRandomAccess(file: File) : RandomAccessSource, AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()
    @Synchronized override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        raf.seek(pos)
        return raf.read(buf, off, len)
    }
    override fun close() = raf.close()
}

/** Thrown from long scans when [Cancel.cancelled] turns true. */
class CancelledException : IOException("cancelled")

class Cancel {
    @Volatile var cancelled = false
}

/** Small reads through a few cached pages (the parsers read element headers a few bytes at a time). */
internal class Reader(private val src: RandomAccessSource, private val cancel: Cancel? = null) {
    val size = src.size
    private val pageSize = 4096
    private val pages = object : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 32
    }

    private fun page(n: Long): ByteArray {
        pages[n]?.let { return it }
        if (cancel?.cancelled == true) throw CancelledException()
        val start = n * pageSize
        val len = minOf(pageSize.toLong(), size - start).toInt()
        val buf = ByteArray(maxOf(0, len))
        var got = 0
        while (got < len) {
            val r = src.read(start + got, buf, got, len - got)
            if (r <= 0) break
            got += r
        }
        val page = if (got == len) buf else buf.copyOf(got)
        pages[n] = page
        return page
    }

    fun byte(pos: Long): Int {
        if (pos < 0 || pos >= size) throw IOException("read past the end")
        val p = page(pos / pageSize)
        val i = (pos % pageSize).toInt()
        if (i >= p.size) throw IOException("read past the end")
        return p[i].toInt() and 0xFF
    }

    fun bytes(pos: Long, n: Int): ByteArray {
        if (n < 0 || pos < 0 || pos + n > size) throw IOException("read past the end")
        if (n > 64 * 1024) { // big payloads straight from the file
            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = src.read(pos + got, buf, got, n - got)
                if (r <= 0) throw IOException("read past the end")
                got += r
            }
            return buf
        }
        return ByteArray(n) { byte(pos + it).toByte() }
    }

    fun u32(pos: Long): Long = (0 until 4).fold(0L) { a, i -> (a shl 8) or byte(pos + i).toLong() }
    fun u64(pos: Long): Long = (0 until 8).fold(0L) { a, i -> (a shl 8) or byte(pos + i).toLong() }
    fun u16(pos: Long): Int = (byte(pos) shl 8) or byte(pos + 1)
}

/** A subtitle track inside a video. */
data class EmbeddedTrack(
    val number: Int, val codec: String, val lang: String, val title: String, val forced: Boolean, val default: Boolean,
) {
    val text: Boolean get() = codec in TEXT_CODECS
    companion object {
        val TEXT_CODECS = setOf("S_TEXT/UTF8", "S_TEXT/ASCII", "S_TEXT/ASS", "S_TEXT/SSA", "S_TEXT/WEBVTT", "tx3g", "text")
    }
}

/** The subtitle tracks inside a video (MKV/WebM or MP4/MOV), or an empty list for other files. */
fun listEmbeddedSubtitles(src: RandomAccessSource): List<EmbeddedTrack> {
    val r = Reader(src)
    return when (containerOf(r)) {
        Container.MKV -> Mkv(r).tracks().filter { it.type == 0x11 }.map { it.toEmbedded() }
        Container.MP4 -> Mp4(r).subtitleTracks().map { it.toEmbedded() }
        null -> emptyList()
    }
}

fun embeddedEntries(src: RandomAccessSource): List<SubtitleEntry> = try {
    listEmbeddedSubtitles(src).map {
        SubtitleEntry(id = "track:${it.number}", kind = SubtitleEntry.Kind.TRACK, track = it.number, lang = it.lang,
            title = it.title, forced = it.forced, ok = it.text,
            why = if (it.text) null else if (it.codec in PICTURE_CODECS) SubtitleEntry.Why.PICTURE else SubtitleEntry.Why.UNSUPPORTED)
    }
} catch (e: IOException) {
    emptyList()
}

private val PICTURE_CODECS = setOf("S_HDMV/PGS", "S_VOBSUB", "S_DVBSUB", "S_IMAGE/BMP", "S_KATE")

/** A text subtitle track -> UTF-8 SRT bytes. [progress] gets 0..1 while the file is scanned. */
fun extractEmbeddedSubtitle(src: RandomAccessSource, track: Int, cancel: Cancel? = null,
                            progress: ((Float) -> Unit)? = null): ByteArray {
    val r = Reader(src, cancel)
    val cues = when (containerOf(r)) {
        Container.MKV -> Mkv(r).cues(track, progress)
        Container.MP4 -> Mp4(r).cues(track)
        null -> throw TvException(TvException.Reason.SUBTITLE_BAD, "not MKV or MP4")
    }
    if (cues.isEmpty()) throw TvException(TvException.Reason.SUBTITLE_BAD, "track $track is empty")
    return toSrt(cues)
}

private enum class Container { MKV, MP4 }

private fun containerOf(r: Reader): Container? {
    if (r.size < 12) return null
    if (r.u32(0) == 0x1A45DFA3L) return Container.MKV
    val type = String(r.bytes(4, 4), Charsets.ISO_8859_1)
    if (type in setOf("ftyp", "moov", "mdat", "free", "wide", "skip")) return Container.MP4
    return null
}

// ---------------------------------------------------------------- Matroska

private const val ID_EBML = 0x1A45DFA3L
private const val ID_SEGMENT = 0x18538067L
private const val ID_SEEKHEAD = 0x114D9B74L
private const val ID_SEEK = 0x4DBBL
private const val ID_SEEKID = 0x53ABL
private const val ID_SEEKPOS = 0x53ACL
private const val ID_INFO = 0x1549A966L
private const val ID_TIMESCALE = 0x2AD7B1L
private const val ID_TRACKS = 0x1654AE6BL
private const val ID_TRACKENTRY = 0xAEL
private const val ID_TRACKNUMBER = 0xD7L
private const val ID_TRACKTYPE = 0x83L
private const val ID_CODECID = 0x86L
private const val ID_CODECPRIVATE = 0x63A2L
private const val ID_LANGUAGE = 0x22B59CL
private const val ID_LANGUAGE_BCP47 = 0x22B59DL
private const val ID_NAME = 0x536EL
private const val ID_FLAGFORCED = 0x55AAL
private const val ID_FLAGDEFAULT = 0x88L
private const val ID_CONTENTENCODINGS = 0x6D80L
private const val ID_CONTENTENCODING = 0x6240L
private const val ID_ENCODINGTYPE = 0x5033L
private const val ID_COMPRESSION = 0x5034L
private const val ID_COMPALGO = 0x4254L
private const val ID_COMPSETTINGS = 0x4255L
private const val ID_ENCRYPTION = 0x5035L
private const val ID_CLUSTER = 0x1F43B675L
private const val ID_TIMESTAMP = 0xE7L
private const val ID_SIMPLEBLOCK = 0xA3L
private const val ID_BLOCKGROUP = 0xA0L
private const val ID_BLOCK = 0xA1L
private const val ID_BLOCKDURATION = 0x9BL
private const val ID_CUES = 0x1C53BB6BL
private const val ID_CUEPOINT = 0xBBL
private const val ID_CUETIME = 0xB3L
private const val ID_CUETRACKPOSITIONS = 0xB7L
private const val ID_CUETRACK = 0xF7L
private const val ID_CUECLUSTERPOS = 0xF1L
private const val ID_CUERELPOS = 0xF0L
private const val ID_CUEDURATION = 0xB2L
private val TOP_LEVEL = setOf(ID_SEEKHEAD, ID_INFO, ID_TRACKS, ID_CLUSTER, ID_CUES, 0x1941A469L, 0x1043A770L, 0x1254C367L)

private class Element(val id: Long, val dataStart: Long, val size: Long /* -1 = unknown */, val end: Long)

private class MkvTrack(
    val number: Int, val type: Int, val codec: String, val lang: String, val title: String,
    val forced: Boolean, val default: Boolean, val codecPrivate: ByteArray?,
    /** null = not compressed; else (algorithm, settings). Algorithm 0 = zlib, 3 = header stripping, -1 = can't read. */
    val compression: Pair<Int, ByteArray>?,
) {
    fun toEmbedded() = EmbeddedTrack(number, codec, lang, title, forced, default)
}

private class Mkv(private val r: Reader) {
    private var seg: Element? = null
    private var segmentStart = 0L
    private var segmentEnd = 0L
    private var timescale = 1_000_000L
    private var tracks: List<MkvTrack>? = null
    private val seek = HashMap<Long, Long>()

    private fun vint(pos: Long, keepMarker: Boolean): Pair<Long, Int> {
        val first = r.byte(pos)
        if (first == 0) throw IOException("bad EBML number")
        var len = 1
        var mask = 0x80
        while (first and mask == 0) {
            len++
            mask = mask shr 1
        }
        var value = (if (keepMarker) first else first and (mask - 1)).toLong()
        var allOnes = (first and (mask - 1)) == mask - 1
        for (i in 1 until len) {
            val b = r.byte(pos + i)
            if (b != 0xFF) allOnes = false
            value = (value shl 8) or b.toLong()
        }
        if (!keepMarker && allOnes) value = -1 // unknown size
        return value to len
    }

    fun element(pos: Long, parentEnd: Long): Element {
        val (id, idLen) = vint(pos, true)
        val (size, sizeLen) = vint(pos + idLen, false)
        val dataStart = pos + idLen + sizeLen
        val end = if (size < 0) parentEnd else minOf(dataStart + size, r.size)
        return Element(id, dataStart, size, end)
    }

    fun children(parent: Element, block: (Element) -> Boolean) {
        var pos = parent.dataStart
        while (pos < parent.end) {
            val e = try {
                element(pos, parent.end)
            } catch (ex: IOException) {
                return
            }
            if (parent.size < 0 && e.id in TOP_LEVEL && parent.id != ID_SEGMENT) return // unknown-size cluster ended
            if (!block(e)) return
            if (e.size < 0 && e.id != ID_CLUSTER) return
            pos = if (e.size < 0) skipUnknown(e) else e.end
        }
    }

    /** End of an unknown-size element: where its first sibling-level element starts. */
    private fun skipUnknown(e: Element): Long {
        var pos = e.dataStart
        while (pos < e.end) {
            val c = try {
                element(pos, e.end)
            } catch (ex: IOException) {
                return e.end
            }
            if (c.id in TOP_LEVEL) return pos
            if (c.size < 0) return e.end
            pos = c.end
        }
        return e.end
    }

    fun uint(e: Element): Long = (0 until e.size.toInt().coerceIn(0, 8)).fold(0L) { a, i -> (a shl 8) or r.byte(e.dataStart + i).toLong() }
    fun string(e: Element): String = String(r.bytes(e.dataStart, e.size.toInt().coerceIn(0, 4096)), Charsets.UTF_8).trimEnd('\u0000')

    private fun segment(): Element {
        val ebml = element(0, r.size)
        if (ebml.id != ID_EBML) throw IOException("not Matroska")
        var pos = ebml.end
        while (pos < r.size) {
            val e = element(pos, r.size)
            if (e.id == ID_SEGMENT) {
                seg = e
                segmentStart = e.dataStart
                segmentEnd = e.end
                return e
            }
            pos = e.end
        }
        throw IOException("no segment")
    }

    private fun readHeaders() {
        if (tracks != null) return
        val seg = segment()
        var found: List<MkvTrack>? = null
        var timescaleSeen = false
        children(seg) { e ->
            when (e.id) {
                ID_SEEKHEAD -> readSeekHead(e)
                ID_INFO -> {
                    children(e) { c -> if (c.id == ID_TIMESCALE) timescale = uint(c); true }
                    timescaleSeen = true
                }
                ID_TRACKS -> found = readTracks(e)
            }
            // stop at the first cluster; what we still miss is found through the seek head
            e.id != ID_CLUSTER && !(found != null && timescaleSeen)
        }
        if (found == null) seek[ID_TRACKS]?.let { pos -> found = runCatching { readTracks(element(segmentStart + pos, segmentEnd)) }.getOrNull() }
        if (!timescaleSeen) seek[ID_INFO]?.let { pos ->
            runCatching { children(element(segmentStart + pos, segmentEnd)) { c -> if (c.id == ID_TIMESCALE) timescale = uint(c); true } }
        }
        if (timescale <= 0) timescale = 1_000_000L
        tracks = found ?: emptyList()
    }

    private fun readSeekHead(e: Element) {
        children(e) { s ->
            if (s.id == ID_SEEK) {
                var id = 0L
                var p = -1L
                children(s) { c ->
                    when (c.id) {
                        ID_SEEKID -> id = (0 until c.size.toInt().coerceIn(0, 4)).fold(0L) { a, i -> (a shl 8) or r.byte(c.dataStart + i).toLong() }
                        ID_SEEKPOS -> p = uint(c)
                    }
                    true
                }
                if (id != 0L && p >= 0 && id !in seek) seek[id] = p
            }
            true
        }
    }

    private fun readTracks(e: Element): List<MkvTrack> {
        val out = ArrayList<MkvTrack>()
        children(e) { t ->
            if (t.id == ID_TRACKENTRY) {
                var number = 0
                var type = 0
                var codec = ""
                var lang = "eng" // the Matroska default
                var bcp47: String? = null
                var name = ""
                var forced = false
                var default = true
                var priv: ByteArray? = null
                var compression: Pair<Int, ByteArray>? = null
                children(t) { c ->
                    when (c.id) {
                        ID_TRACKNUMBER -> number = uint(c).toInt()
                        ID_TRACKTYPE -> type = uint(c).toInt()
                        ID_CODECID -> codec = string(c)
                        ID_LANGUAGE -> lang = string(c)
                        ID_LANGUAGE_BCP47 -> bcp47 = string(c)
                        ID_NAME -> name = string(c)
                        ID_FLAGFORCED -> forced = uint(c) != 0L
                        ID_FLAGDEFAULT -> default = uint(c) != 0L
                        ID_CODECPRIVATE -> if (c.size in 0..1_000_000) priv = r.bytes(c.dataStart, c.size.toInt())
                        ID_CONTENTENCODINGS -> compression = readEncoding(c)
                    }
                    true
                }
                out += MkvTrack(number, type, codec, languageCode(bcp47 ?: lang), name.trim(), forced, default, priv, compression)
            }
            true
        }
        return out
    }

    private fun readEncoding(e: Element): Pair<Int, ByteArray>? {
        var result: Pair<Int, ByteArray>? = null
        children(e) { enc ->
            if (enc.id == ID_CONTENTENCODING) {
                var type = 0
                var algo = 0
                var settings = ByteArray(0)
                var compressed = false
                children(enc) { c ->
                    when (c.id) {
                        ID_ENCODINGTYPE -> type = uint(c).toInt()
                        ID_ENCRYPTION -> type = 1
                        ID_COMPRESSION -> {
                            compressed = true
                            children(c) { cc ->
                                when (cc.id) {
                                    ID_COMPALGO -> algo = uint(cc).toInt()
                                    ID_COMPSETTINGS -> settings = r.bytes(cc.dataStart, cc.size.toInt().coerceIn(0, 65536))
                                }
                                true
                            }
                        }
                    }
                    true
                }
                result = when {
                    type != 0 -> -1 to ByteArray(0) // encrypted
                    compressed && (algo == 0 || algo == 3) -> algo to settings
                    compressed -> -1 to ByteArray(0)
                    else -> result
                }
            }
            true
        }
        return result
    }

    fun tracks(): List<MkvTrack> {
        readHeaders()
        return tracks!!
    }

    private class Block(val timeMs: Long, var durationMs: Long?, val data: ByteArray)

    fun cues(number: Int, progress: ((Float) -> Unit)?): List<Cue> {
        readHeaders()
        val track = tracks!!.firstOrNull { it.number == number } ?: throw TvException(TvException.Reason.SUBTITLE_BAD, "no track $number")
        if (track.compression?.first == -1) throw TvException(TvException.Reason.SUBTITLE_BAD, "encrypted or unknown compression")
        val blocks = blocksFromCues(number) ?: scanClusters(number, progress)
        progress?.invoke(1f)
        val sorted = blocks.sortedBy { it.timeMs }
        val out = ArrayList<Cue>()
        for ((i, b) in sorted.withIndex()) {
            val data = decode(track, b.data) ?: continue
            val text = when (track.codec) {
                "S_TEXT/ASS", "S_TEXT/SSA" -> assText(String(data, Charsets.UTF_8).split(',', limit = 9).getOrElse(8) { "" })
                "S_TEXT/WEBVTT" -> cleanVttText(String(data, Charsets.UTF_8))
                else -> String(data, Charsets.UTF_8)
            }
            val next = sorted.getOrNull(i + 1)?.timeMs
            val end = b.timeMs + (b.durationMs ?: next?.let { minOf(it - b.timeMs, 5000) }?.takeIf { it > 0 } ?: 3000)
            out += Cue(b.timeMs, end, text)
        }
        return out
    }

    private fun decode(track: MkvTrack, data: ByteArray): ByteArray? {
        val (algo, settings) = track.compression ?: return data
        return when (algo) {
            3 -> settings + data
            0 -> try {
                val inf = Inflater()
                inf.setInput(data)
                val out = java.io.ByteArrayOutputStream(data.size * 3)
                val buf = ByteArray(8192)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    out.write(buf, 0, n)
                }
                inf.end()
                out.toByteArray()
            } catch (e: java.util.zip.DataFormatException) {
                null
            }
            else -> null
        }
    }

    private fun ms(ticks: Long) = ticks * timescale / 1_000_000

    /** The block at [pos] if it belongs to [number] ([clusterTime] in ticks). */
    private fun readBlock(e: Element, number: Int, clusterTime: Long, durationTicks: Long?): Block? {
        if (e.size < 4) return null
        val (track, len) = vint(e.dataStart, false)
        if (track != number.toLong()) return null
        val rel = r.u16(e.dataStart + len).toShort().toLong()
        val flags = r.byte(e.dataStart + len + 2)
        if (flags and 0x06 != 0) return null // laced: not used for subtitles
        val head = len + 3
        val data = r.bytes(e.dataStart + head, (e.size - head).toInt())
        return Block(ms(clusterTime + rel), durationTicks?.let { ms(it) }, data)
    }

    private fun blockGroup(g: Element, number: Int, clusterTime: Long): Block? {
        var block: Element? = null
        var duration: Long? = null
        children(g) { c ->
            when (c.id) {
                ID_BLOCK -> block = c
                ID_BLOCKDURATION -> duration = uint(c)
            }
            true
        }
        return block?.let { readBlock(it, number, clusterTime, duration) }
    }

    /** Use the index when it lists this track (mkvmerge indexes every subtitle block); null if it doesn't. */
    private fun blocksFromCues(number: Int): List<Block>? {
        val cuesPos = seek[ID_CUES] ?: return null
        val cues = try {
            element(segmentStart + cuesPos, segmentEnd)
        } catch (e: IOException) {
            return null
        }
        if (cues.id != ID_CUES) return null
        val points = ArrayList<Pair<Long, Long?>>() // (cluster position, relative position)
        children(cues) { p ->
            if (p.id == ID_CUEPOINT) children(p) { tp ->
                if (tp.id == ID_CUETRACKPOSITIONS) {
                    var t = 0L
                    var cluster = -1L
                    var rel: Long? = null
                    children(tp) { c ->
                        when (c.id) {
                            ID_CUETRACK -> t = uint(c)
                            ID_CUECLUSTERPOS -> cluster = uint(c)
                            ID_CUERELPOS -> rel = uint(c)
                        }
                        true
                    }
                    if (t == number.toLong() && cluster >= 0) points += cluster to rel
                }
                true
            }
            true
        }
        if (points.isEmpty()) return null
        val out = LinkedHashMap<Long, Block>()
        val clusterTimes = HashMap<Long, Long>()
        val scanned = HashSet<Long>()
        for ((clusterPos, rel) in points) {
            val c = try {
                element(segmentStart + clusterPos, segmentEnd)
            } catch (e: IOException) {
                continue
            }
            if (c.id != ID_CLUSTER) continue
            val time = clusterTimes.getOrPut(clusterPos) {
                var t = 0L
                children(c) { x -> if (x.id == ID_TIMESTAMP) { t = uint(x); false } else true }
                t
            }
            if (rel != null) {
                val pos = c.dataStart + rel
                if (pos in out) continue
                val e = runCatching { element(pos, c.end) }.getOrNull() ?: continue
                val b = when (e.id) {
                    ID_SIMPLEBLOCK -> readBlock(e, number, time, null)
                    ID_BLOCKGROUP -> blockGroup(e, number, time)
                    else -> null
                }
                if (b != null) out[pos] = b
            } else if (scanned.add(clusterPos)) {
                scanCluster(c, number) { pos, b -> out[pos] = b }
            }
        }
        return if (out.isEmpty()) null else out.values.toList()
    }

    private fun scanCluster(c: Element, number: Int, found: (Long, Block) -> Unit) {
        var time = 0L
        var pos = c.dataStart
        children(c) { e ->
            when (e.id) {
                ID_TIMESTAMP -> time = uint(e)
                ID_SIMPLEBLOCK -> readBlock(e, number, time, null)?.let { found(pos, it) }
                ID_BLOCKGROUP -> blockGroup(e, number, time)?.let { found(pos, it) }
            }
            pos = e.end
            true
        }
    }

    private fun scanClusters(number: Int, progress: ((Float) -> Unit)?): List<Block> {
        val out = ArrayList<Block>()
        val seg = seg ?: segment()
        var lastReport = 0L
        children(seg) { e ->
            if (e.id == ID_CLUSTER) {
                scanCluster(e, number) { _, b -> out += b }
                if (progress != null && e.dataStart - lastReport > (1 shl 24)) {
                    lastReport = e.dataStart
                    progress((e.dataStart.toFloat() / r.size).coerceIn(0f, 1f))
                }
            }
            true
        }
        return out
    }

}

// ---------------------------------------------------------------- MP4 / MOV

private class Box(val type: String, val start: Long, val dataStart: Long, val end: Long)

private class Mp4Track(val id: Int, val codec: String, val lang: String, val timescale: Long, val stbl: Box) {
    fun toEmbedded() = EmbeddedTrack(id, codec, lang, "", false, false)
}

private class Mp4(private val r: Reader) {
    private fun box(pos: Long, parentEnd: Long): Box? {
        if (pos + 8 > parentEnd) return null
        var size = r.u32(pos)
        val type = String(r.bytes(pos + 4, 4), Charsets.ISO_8859_1)
        var header = 8L
        if (size == 1L) {
            size = r.u64(pos + 8)
            header = 16
        } else if (size == 0L) {
            size = parentEnd - pos
        }
        if (size < header) return null
        return Box(type, pos, pos + header, minOf(pos + size, parentEnd))
    }

    private fun children(parent: Box?, start: Long = parent?.dataStart ?: 0, end: Long = parent?.end ?: r.size): List<Box> {
        val out = ArrayList<Box>()
        var pos = start
        while (true) {
            val b = box(pos, end) ?: break
            out += b
            if (b.end <= pos) break
            pos = b.end
        }
        return out
    }

    private fun child(parent: Box, type: String) = children(parent).firstOrNull { it.type == type }

    private fun tracks(): List<Mp4Track> {
        val moov = children(null).firstOrNull { it.type == "moov" } ?: return emptyList()
        val out = ArrayList<Mp4Track>()
        for (trak in children(moov).filter { it.type == "trak" }) {
            val tkhd = child(trak, "tkhd") ?: continue
            val id = r.u32(tkhd.dataStart + if (r.byte(tkhd.dataStart) == 1) 20 else 12).toInt()
            val mdia = child(trak, "mdia") ?: continue
            val hdlr = child(mdia, "hdlr") ?: continue
            val handler = String(r.bytes(hdlr.dataStart + 8, 4), Charsets.ISO_8859_1)
            if (handler !in setOf("text", "sbtl", "subt")) continue
            val mdhd = child(mdia, "mdhd") ?: continue
            val v1 = r.byte(mdhd.dataStart) == 1
            val timescale = r.u32(mdhd.dataStart + if (v1) 20 else 12)
            val packed = r.u16(mdhd.dataStart + if (v1) 32 else 20)
            val lang = String(charArrayOf(((packed shr 10 and 31) + 0x60).toChar(), ((packed shr 5 and 31) + 0x60).toChar(),
                ((packed and 31) + 0x60).toChar()))
            val stbl = child(mdia, "minf")?.let { child(it, "stbl") } ?: continue
            val stsd = child(stbl, "stsd") ?: continue
            val entry = box(stsd.dataStart + 8, stsd.end)
            out += Mp4Track(id, entry?.type ?: "?", languageCode(lang), timescale, stbl)
        }
        return out
    }

    fun subtitleTracks() = tracks()

    fun cues(id: Int): List<Cue> {
        val t = tracks().firstOrNull { it.id == id } ?: throw TvException(TvException.Reason.SUBTITLE_BAD, "no track $id")
        if (t.codec != "tx3g" && t.codec != "text") throw TvException(TvException.Reason.SUBTITLE_BAD, t.codec)
        val stbl = t.stbl
        // sample durations
        val stts = child(stbl, "stts") ?: return emptyList()
        val durations = ArrayList<Long>()
        val sttsCount = r.u32(stts.dataStart + 4).toInt()
        for (i in 0 until sttsCount) {
            val n = r.u32(stts.dataStart + 8 + i * 8L)
            val d = r.u32(stts.dataStart + 12 + i * 8L)
            for (k in 0 until n) durations += d
        }
        // sample sizes
        val sizes = ArrayList<Long>()
        val stsz = child(stbl, "stsz")
        if (stsz != null) {
            val fixed = r.u32(stsz.dataStart + 4)
            val count = r.u32(stsz.dataStart + 8).toInt()
            for (i in 0 until count) sizes += if (fixed != 0L) fixed else r.u32(stsz.dataStart + 12 + i * 4L)
        }
        // chunk offsets and samples per chunk
        val offsets = ArrayList<Long>()
        child(stbl, "stco")?.let { b -> for (i in 0 until r.u32(b.dataStart + 4).toInt()) offsets += r.u32(b.dataStart + 8 + i * 4L) }
        child(stbl, "co64")?.let { b -> for (i in 0 until r.u32(b.dataStart + 4).toInt()) offsets += r.u64(b.dataStart + 8 + i * 8L) }
        val stsc = child(stbl, "stsc") ?: return emptyList()
        val runs = (0 until r.u32(stsc.dataStart + 4).toInt()).map { i ->
            val p = stsc.dataStart + 8 + i * 12L
            r.u32(p).toInt() to r.u32(p + 4).toInt() // (first chunk, 1-based; samples per chunk)
        }
        val cues = ArrayList<Cue>()
        var sample = 0
        var time = 0L
        for (chunk in offsets.indices) {
            val perChunk = runs.lastOrNull { it.first <= chunk + 1 }?.second ?: 0
            var pos = offsets[chunk]
            for (k in 0 until perChunk) {
                if (sample >= sizes.size) break
                val size = sizes[sample]
                val dur = durations.getOrElse(sample) { 0 }
                if (size >= 2) {
                    val len = r.u16(pos)
                    if (len > 0 && 2 + len <= size) {
                        val raw = r.bytes(pos + 2, len)
                        var text = if (len >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte())
                            String(raw, 2, len - 2, Charsets.UTF_16BE) else String(raw, Charsets.UTF_8)
                        if (size > 2 + len + 8) text = tx3gStyled(text, r.bytes(pos + 2 + len, minOf(size - 2 - len, 65536L).toInt()))
                        cues += Cue(time * 1000 / t.timescale, (time + dur) * 1000 / t.timescale, text)
                    }
                }
                pos += size
                time += dur
                sample++
            }
        }
        return cues
    }
}

/** Put a tx3g sample's style records ('styl' box: bold/italic/underline ranges, in characters) back as tags. */
internal fun tx3gStyled(text: String, boxes: ByteArray): String {
    fun u16(i: Int) = ((boxes[i].toInt() and 0xFF) shl 8) or (boxes[i + 1].toInt() and 0xFF)
    fun u32(i: Int) = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
    var i = 0
    while (i + 8 <= boxes.size) {
        val size = u32(i).toInt()
        val type = String(boxes, i + 4, 4, Charsets.ISO_8859_1)
        if (size < 8 || i + size > boxes.size) break
        if (type == "styl" && size >= 10) {
            val cps = text.codePoints().toArray()
            val open = Array(cps.size + 1) { StringBuilder() }
            val close = Array(cps.size + 1) { StringBuilder() }
            val count = u16(i + 8)
            for (k in 0 until count) {
                val p = i + 10 + k * 12
                if (p + 12 > i + size) break
                val start = u16(p).coerceIn(0, cps.size)
                val end = u16(p + 2).coerceIn(start, cps.size)
                val flags = boxes[p + 6].toInt()
                val tags = listOfNotNull("b".takeIf { flags and 1 != 0 }, "i".takeIf { flags and 2 != 0 }, "u".takeIf { flags and 4 != 0 })
                if (tags.isEmpty() || start == end) continue
                tags.forEach { open[start].append("<$it>") }
                tags.asReversed().forEach { close[end].insert(0, "</$it>") }
            }
            val sb = StringBuilder()
            for (c in 0..cps.size) {
                sb.append(close[c]).append(open[c])
                if (c < cps.size) sb.appendCodePoint(cps[c])
            }
            return sb.toString()
        }
        i += size
    }
    return text
}
