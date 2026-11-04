package io.github.m1omg.opuscast.core

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

val SUB_EXTS = listOf(".srt", ".ass", ".ssa", ".vtt", ".sub", ".smi", ".sami")
val SUB_DIRS = setOf("subs", "sub", "subtitles", "titulky", "subtitle")

/** One subtitle choice for a video: a file next to it, or a track inside it. */
data class SubtitleEntry(
    /** Stable id, remembered per video: "file:<ref>" or "track:<number>". */
    val id: String,
    val kind: Kind,
    /** FILE: how the app finds the file again (a path, or a document URI on Android). */
    val ref: String? = null,
    /** FILE: the file name, e.g. "Movie.sk.srt". */
    val fileName: String? = null,
    /** TRACK: the track number inside the video. */
    val track: Int? = null,
    /** ISO 639-1 code ("sk"), or "" when unknown. */
    val lang: String = "",
    /** TRACK: the track's own title, if any. */
    val title: String = "",
    val forced: Boolean = false,
    /** Can be sent to the TV. */
    val ok: Boolean = true,
    /** Why not, when [ok] is false. */
    val why: Why? = null,
) {
    enum class Kind { FILE, TRACK }
    enum class Why { PICTURE, UNSUPPORTED }
}

private val FILENAME_LANGS = mapOf(
    "sk" to listOf("sk", "slk", "slo", "slovak", "slovensky", "slovencina"),
    "cs" to listOf("cs", "cz", "cze", "ces", "czech", "cesky", "cestina"),
    "en" to listOf("en", "eng", "english"), "de" to listOf("de", "ger", "deu", "german"),
    "hu" to listOf("hu", "hun", "hungarian"), "pl" to listOf("pl", "pol", "polish"),
    "fr" to listOf("fr", "fre", "fra", "french"), "es" to listOf("es", "spa", "spanish"),
    "it" to listOf("it", "ita", "italian"), "ru" to listOf("ru", "rus", "russian"),
    "uk" to listOf("uk", "ukr", "ukrainian"), "pt" to listOf("pt", "por", "portuguese"),
    "nl" to listOf("nl", "dut", "nld", "dutch"), "hr" to listOf("hr", "hrv", "croatian"),
    "sr" to listOf("sr", "srp", "serbian"), "sl" to listOf("sl", "slv", "slovenian"),
    "ro" to listOf("ro", "rum", "ron", "romanian"), "ja" to listOf("ja", "jpn", "japanese"),
    "zh" to listOf("zh", "chi", "zho", "chinese"), "ko" to listOf("ko", "kor", "korean"),
)
private val FILENAME_CODES = FILENAME_LANGS.flatMap { (code, words) -> words.map { it to code } }.toMap()

// ISO 639-2 bibliographic codes, which differ from the terminology ones Locale knows
private val BIBLIOGRAPHIC = mapOf(
    "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs", "dut" to "nl",
    "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi",
    "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
)
private val ISO3_TO_ISO2: Map<String, String> by lazy {
    val m = HashMap<String, String>()
    for (code in Locale.getISOLanguages()) {
        runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { m[it] = code }
    }
    m + BIBLIOGRAPHIC
}

/** A language tag from a video track ("slo", "sk", "sk-SK", "slovak") -> ISO 639-1 ("sk"), or "". */
fun languageCode(tag: String?): String {
    val t = (tag ?: "").trim().lowercase().replace('_', '-').substringBefore('-')
    if (t.isEmpty() || t == "und" || t == "zxx" || t == "mul") return ""
    FILENAME_CODES[t]?.let { return it }
    if (t.length == 2 && t in Locale.getISOLanguages()) return t
    if (t.length == 3) ISO3_TO_ISO2[t]?.let { return it }
    return ""
}

private val TOKEN_SPLIT = Regex("""[.\-_ \[\]()]+""")

internal fun langFromFilename(stem: String): String {
    for (token in stem.lowercase().split(TOKEN_SPLIT).asReversed()) FILENAME_CODES[token]?.let { return it }
    return ""
}

/** Can OpusCast turn this subtitle file into SRT for the TV? (.sub can be MicroDVD or SubViewer text,
 *  or VobSub pictures when an .idx sits next to it.) */
fun subtitleExtSupported(name: String) = extensionOf(name) in SUB_EXTS

/** A subtitle file found for a video: [dir] is null for the video's own folder, else the name of
 *  its Subs/Subtitles/Titulky subfolder. */
data class FoundSubtitle(val dir: String?, val name: String, val lang: String, val pictureBased: Boolean)

/** Pick out the subtitle files that belong to [videoName] from a listing of its folder ([siblings], file
 *  names) and of its subtitle subfolders ([subDirs]: folder name -> file names).
 *
 *  A file belongs to the video whose name it starts with - the longest such name, so "Movie 2.sk.srt"
 *  goes with "Movie 2.mkv", not "Movie.mkv". With several videos in the folder, files named after none
 *  of them are left out; in a Subs folder they're kept (it often holds just "English.srt"). */
fun findSubtitleFiles(videoName: String, siblings: List<String>, subDirs: Map<String, List<String>> = emptyMap()): List<FoundSubtitle> {
    val base = stemOf(videoName).lowercase()
    val videoStems = siblings.filter { extensionOf(it) in VIDEO_EXTS }.map { stemOf(it).lowercase() }.toMutableSet()
    videoStems += base
    val severalVideos = videoStems.size > 1
    fun owner(stem: String) = videoStems.filter { stem.startsWith(it) }.maxByOrNull { it.length }
    val out = ArrayList<FoundSubtitle>()
    val dirs = listOf<Pair<String?, List<String>>>(null to siblings) +
        subDirs.filterKeys { it.lowercase() in SUB_DIRS }.toList().sortedBy { it.first.lowercase() }
    for ((dir, names) in dirs) {
        val here = names.toSet()
        for (f in names.sorted()) {
            val ext = extensionOf(f)
            if (ext !in SUB_EXTS) continue
            val stem = stemOf(f)
            val who = owner(stem.lowercase())
            val mine = who == base
            if (dir == null && severalVideos && !mine) continue
            if (dir != null && who != null && !mine) continue // named after another video
            val lang = langFromFilename(if (mine) stem.substring(base.length) else stem)
            val picture = ext == ".sub" && (here.contains("$stem.idx") || here.any { it.equals("$stem.idx", ignoreCase = true) })
            out += FoundSubtitle(dir, f, lang, picture)
        }
    }
    return out
}

/** Best subtitle when none was chosen: the first [preferred] language that has one (files before
 *  tracks, as listed), else the first usable one. */
fun autoSubtitle(entries: List<SubtitleEntry>, preferred: List<String>): SubtitleEntry? {
    val usable = entries.filter { it.ok }
    for (lang in preferred) usable.firstOrNull { it.lang == lang }?.let { return it }
    return usable.firstOrNull()
}

// ---------------------------------------------------------------- text encodings

private fun strictDecode(raw: ByteArray, charset: Charset): String? = try {
    charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(raw)).toString()
} catch (e: CharacterCodingException) {
    null
}

private val CYRILLIC = setOf("ru", "uk", "bg", "sr", "mk", "be")

/** Subtitle bytes -> text: UTF-16 (with or without a BOM), UTF-8, else Windows-1250 (the usual encoding
 *  of Slovak and Czech subtitles; Windows-1251 first for Cyrillic languages), else Latin-1. */
fun decodeText(raw: ByteArray, lang: String = ""): String {
    if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) return String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
    if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) return String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
    if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte())
        return strictDecode(raw.copyOfRange(3, raw.size), Charsets.UTF_8) ?: String(raw, 3, raw.size - 3, Charsets.UTF_8)
    if (raw.size >= 4) { // UTF-16 without a BOM: every other byte is zero in Latin text
        val n = minOf(raw.size and 1.inv(), 400)
        var even = 0
        var odd = 0
        for (i in 0 until n step 2) {
            if (raw[i] == 0.toByte()) even++
            if (raw[i + 1] == 0.toByte()) odd++
        }
        val pairs = n / 2
        if (odd > pairs * 0.4 && even < pairs * 0.05) return String(raw, 0, raw.size and 1.inv(), Charsets.UTF_16LE)
        if (even > pairs * 0.4 && odd < pairs * 0.05) return String(raw, 0, raw.size and 1.inv(), Charsets.UTF_16BE)
    }
    strictDecode(raw, Charsets.UTF_8)?.let { return it }
    val order = if (lang in CYRILLIC) listOf("windows-1251", "windows-1250") else listOf("windows-1250")
    for (name in order) {
        val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
        strictDecode(raw, cs)?.let { return it }
    }
    return String(raw, Charsets.ISO_8859_1)
}

// ---------------------------------------------------------------- SRT and conversions

data class Cue(val startMs: Long, val endMs: Long, val text: String)

private fun srtTime(ms: Long): String {
    val t = maxOf(0L, ms)
    return "%02d:%02d:%02d,%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
}

/** Cues -> UTF-8 SRT bytes, numbered from 1, sorted by start time. Empty cues are dropped. */
fun toSrt(cues: List<Cue>): ByteArray {
    val sb = StringBuilder()
    var n = 0
    for (c in cues.sortedBy { it.startMs }) {
        val text = c.text.replace("\r\n", "\n").replace('\r', '\n').trim('\n').lines()
            .filter { it.isNotBlank() }.joinToString("\n")
        if (text.isEmpty()) continue
        n++
        val end = if (c.endMs > c.startMs) c.endMs else c.startMs + 2000
        sb.append(n).append('\n').append(srtTime(c.startMs)).append(" --> ").append(srtTime(end)).append('\n')
            .append(text).append("\n\n")
    }
    return sb.toString().toByteArray(Charsets.UTF_8)
}

private val TIMESTAMP = Regex("""(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[.,:](\d{1,3}))?""")

/** "01:02:03,456", "1:02:03.45" (ASS centiseconds) or "02:03.456" (VTT) -> milliseconds. */
internal fun parseCueTime(text: String): Long? {
    val m = TIMESTAMP.matchEntire(text.trim()) ?: return null
    val (h, mi, s, frac) = m.destructured
    val ms = when (frac.length) {
        0 -> 0L
        1 -> frac.toLong() * 100
        2 -> frac.toLong() * 10
        else -> frac.toLong()
    }
    return ((h.ifEmpty { "0" }.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + ms
}

private val ARROW = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")

/** Read SRT (and the WebVTT cue layout, which is the same apart from the header and dots). */
private fun parseSrtLike(text: String, vtt: Boolean): List<Cue> {
    val cues = ArrayList<Cue>()
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF').split('\n')
    var i = 0
    while (i < lines.size) {
        val m = ARROW.find(lines[i])
        if (m == null) {
            if (vtt && (lines[i].startsWith("NOTE") || lines[i].startsWith("STYLE") || lines[i].startsWith("REGION"))) {
                while (i < lines.size && lines[i].isNotBlank()) i++ // skip the whole block
            }
            i++
            continue
        }
        val start = parseCueTime(m.groupValues[1])
        val end = parseCueTime(m.groupValues[2])
        i++
        val body = ArrayList<String>()
        while (i < lines.size && lines[i].isNotBlank()) {
            if (ARROW.find(lines[i]) != null) break // a cue with no blank line before the next one
            body += lines[i]
            i++
        }
        // a number line right before the next cue belongs to it, not to this text
        if (!vtt && body.isNotEmpty() && body.last().trim().all { it.isDigit() } && i < lines.size && ARROW.find(lines[i]) != null) {
            body.removeAt(body.size - 1)
        }
        if (start != null && end != null) cues += Cue(start, end, if (vtt) cleanVttText(body.joinToString("\n")) else body.joinToString("\n"))
    }
    return cues
}

private val VTT_TAG = Regex("""<(/?)([a-zA-Z0-9]+)[^>]*>""")
private val VTT_TIME_TAG = Regex("""<\d[\d:.]*>""")

internal fun cleanVttText(text: String): String {
    var t = VTT_TIME_TAG.replace(text, "")
    t = VTT_TAG.replace(t) { m ->
        val tag = m.groupValues[2].lowercase()
        when (tag) {
            "i", "b", "u" -> "<${m.groupValues[1]}$tag>"
            else -> ""
        }
    }
    return xmlUnescape(t)
}

private val ASS_OVERRIDE = Regex("""\{([^}]*)\}""")

/** ASS/SSA dialogue text -> SRT text: line breaks, italics/bold/underline kept, everything else dropped. */
internal fun assText(text: String): String {
    val sb = StringBuilder()
    var drawing = false
    var last = 0
    for (m in ASS_OVERRIDE.findAll(text)) {
        if (!drawing) sb.append(text, last, m.range.first)
        last = m.range.last + 1
        for (tag in m.groupValues[1].split('\\').drop(1)) {
            when {
                tag == "i1" -> sb.append("<i>")
                tag == "i0" -> sb.append("</i>")
                tag == "b1" -> sb.append("<b>")
                tag == "b0" -> sb.append("</b>")
                tag == "u1" -> sb.append("<u>")
                tag == "u0" -> sb.append("</u>")
                tag.startsWith("p") && tag.length > 1 && tag.substring(1).all { it.isDigit() } -> drawing = tag != "p0"
            }
        }
    }
    if (!drawing) sb.append(text, last, text.length)
    return sb.toString().replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
}

private fun parseAss(text: String): List<Cue> {
    val cues = ArrayList<Cue>()
    var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
    var inEvents = false
    for (raw in text.replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF').split('\n')) {
        val line = raw.trim()
        if (line.startsWith("[")) {
            inEvents = line.equals("[Events]", ignoreCase = true)
            continue
        }
        if (!inEvents) continue
        val colon = line.indexOf(':')
        if (colon < 0) continue
        val key = line.substring(0, colon).trim().lowercase()
        val value = line.substring(colon + 1).trimStart()
        if (key == "format") {
            fields = value.split(',').map { it.trim().lowercase() }
        } else if (key == "dialogue") {
            val parts = value.split(',', limit = fields.size)
            if (parts.size < fields.size) continue
            val get = { name: String -> fields.indexOf(name).takeIf { it >= 0 }?.let { parts[it] } }
            val start = get("start")?.let { parseCueTime(it) } ?: continue
            val end = get("end")?.let { parseCueTime(it) } ?: continue
            cues += Cue(start, end, assText(get("text") ?: ""))
        }
    }
    return cues
}

private val SYNC = Regex("""<sync\s+start\s*=\s*"?(\d+)"?[^>]*>""", RegexOption.IGNORE_CASE)
private val HTML_BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("""<(/?)([a-zA-Z]+)[^>]*>""")

private fun parseSami(text: String): List<Cue> {
    val syncs = SYNC.findAll(text).toList()
    val cues = ArrayList<Cue>()
    for ((k, m) in syncs.withIndex()) {
        val bodyEnd = if (k + 1 < syncs.size) syncs[k + 1].range.first else text.length
        var body = text.substring(m.range.last + 1, bodyEnd)
        body = body.substringBefore("</body>").substringBefore("</BODY>")
        body = HTML_BR.replace(body.replace("\r", "").replace("\n", " "), "\n")
        body = HTML_TAG.replace(body) { t ->
            when (val tag = t.groupValues[2].lowercase()) {
                "i", "b", "u" -> "<${t.groupValues[1]}$tag>"
                else -> ""
            }
        }
        body = xmlUnescape(body).replace('\u00a0', ' ').lines().joinToString("\n") { it.trim() }.trim()
        if (body.isEmpty()) continue
        val start = m.groupValues[1].toLong()
        val end = if (k + 1 < syncs.size) syncs[k + 1].groupValues[1].toLong() else start + 4000
        cues += Cue(start, end, body)
    }
    return cues
}

private val MICRODVD = Regex("""^\{(\d+)\}\{(\d*)\}(.*)$""")

private fun parseMicroDvd(text: String, defaultFps: Double): List<Cue> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF').split('\n')
    var fps = defaultFps
    val cues = ArrayList<Cue>()
    for (line in lines) {
        val m = MICRODVD.find(line.trim()) ?: continue
        val (a, b, body) = m.destructured
        if (cues.isEmpty() && (a == "0" || a == "1")) { // "{1}{1}23.976" says the frame rate
            val rate = body.trim().replace(',', '.').toDoubleOrNull()
            if (rate != null) {
                if (rate in 5.0..121.0) fps = rate
                continue
            }
        }
        val start = (a.toLong() * 1000 / fps).toLong()
        val end = b.toLongOrNull()?.let { (it * 1000 / fps).toLong() } ?: (start + 3000)
        var t = body.replace('|', '\n')
        val italic = Regex("""\{[yY]:i\}""").containsMatchIn(t)
        t = Regex("""\{[^}]*\}""").replace(t, "")
        if (italic) t = "<i>$t</i>"
        cues += Cue(start, end, t)
    }
    return cues
}

private val SUBVIEWER = Regex("""^(\d{1,2}:\d{2}:\d{2}\.\d{1,3}),(\d{1,2}:\d{2}:\d{2}\.\d{1,3})\s*$""")

private fun parseSubViewer(text: String): List<Cue> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF').split('\n')
    val cues = ArrayList<Cue>()
    var i = 0
    while (i < lines.size) {
        val m = SUBVIEWER.find(lines[i].trim())
        i++
        if (m == null) continue
        val body = ArrayList<String>()
        while (i < lines.size && lines[i].isNotBlank()) body += lines[i++]
        val start = parseCueTime(m.groupValues[1]) ?: continue
        val end = parseCueTime(m.groupValues[2]) ?: continue
        cues += Cue(start, end, body.joinToString("\n").replace("[br]", "\n", ignoreCase = true))
    }
    return cues
}

/** A subtitle file ([name] says the format) -> UTF-8 SRT bytes for the TV. [fps] is used for
 *  frame-based MicroDVD files that don't say their frame rate. Throws TvException(SUBTITLE_BAD). */
fun convertSubtitle(name: String, raw: ByteArray, fps: Double = 23.976): ByteArray {
    val text = decodeText(raw, langFromFilename(stemOf(name)))
    val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    val cues = when {
        head.startsWith("WEBVTT") || extensionOf(name) == ".vtt" -> parseSrtLike(text, vtt = true)
        Regex("""^\[script info]""", RegexOption.IGNORE_CASE).containsMatchIn(head) ||
            extensionOf(name) in setOf(".ass", ".ssa") -> parseAss(text)
        head.startsWith("<SAMI", ignoreCase = true) || extensionOf(name) in setOf(".smi", ".sami") -> parseSami(text)
        MICRODVD.containsMatchIn(head.lineSequence().firstOrNull()?.trim() ?: "") -> parseMicroDvd(text, fps)
        head.lineSequence().take(40).any { SUBVIEWER.matches(it.trim()) } && ARROW.find(head) == null -> parseSubViewer(text)
        else -> parseSrtLike(text, vtt = false)
    }
    if (cues.isEmpty()) {
        if (extensionOf(name) == ".srt" && text.isNotBlank()) { // not what we expected; let the TV try
            return (text.replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF').trimEnd('\n') + "\n").toByteArray()
        }
        throw TvException(TvException.Reason.SUBTITLE_BAD, name)
    }
    return toSrt(cues)
}
