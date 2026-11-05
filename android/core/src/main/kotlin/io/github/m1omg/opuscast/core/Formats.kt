package io.github.m1omg.opuscast.core

/** What the TV is asked to show. LIVE is the phone's screen, streamed as it happens. */
enum class MediaKind { VIDEO, AUDIO, IMAGE, LIVE }

val AUDIO_MIME = mapOf(
    ".mp3" to "audio/mpeg", ".flac" to "audio/flac", ".m4a" to "audio/mp4", ".m4b" to "audio/mp4", ".alac" to "audio/mp4",
    ".aac" to "audio/aac", ".ogg" to "audio/ogg", ".oga" to "audio/ogg", ".opus" to "audio/ogg", ".wav" to "audio/wav",
    ".wma" to "audio/x-ms-wma", ".aif" to "audio/aiff", ".aiff" to "audio/aiff", ".mka" to "audio/x-matroska",
)
val IMAGE_MIME = mapOf(
    ".jpg" to "image/jpeg", ".jpeg" to "image/jpeg", ".png" to "image/png", ".gif" to "image/gif", ".bmp" to "image/bmp",
    ".webp" to "image/webp", ".heic" to "image/heic", ".heif" to "image/heic", ".avif" to "image/avif",
    ".tif" to "image/tiff", ".tiff" to "image/tiff",
)

/** "audio/L16;rate=44100" -> "audio/l16": what two MIME types are compared by. */
internal fun baseMime(mime: String) = mime.substringBefore(';').trim().lowercase()

/** Video, music or photo by the file name; by the system's MIME type when the name says nothing. */
fun kindOf(name: String, mime: String? = null): MediaKind? {
    val ext = extensionOf(name)
    if (ext in MIME) return MediaKind.VIDEO
    if (ext in AUDIO_MIME) return MediaKind.AUDIO
    if (ext in IMAGE_MIME) return MediaKind.IMAGE
    val m = mime?.let(::baseMime) ?: return null
    return when {
        m.startsWith("video/") || m == "application/x-matroska" -> MediaKind.VIDEO
        m.startsWith("audio/") || m == "application/ogg" -> MediaKind.AUDIO
        m.startsWith("image/") -> MediaKind.IMAGE
        else -> null
    }
}

/** The MIME type to announce for a file name, or the system's [fallback] when the name has no known extension. */
fun mimeFor(name: String, fallback: String? = null): String {
    val ext = extensionOf(name)
    return MIME[ext] ?: AUDIO_MIME[ext] ?: IMAGE_MIME[ext] ?: fallback?.let(::baseMime)?.takeIf { '/' in it } ?: "video/mp4"
}

/** The extension the TV sees at the end of the URL. Some TVs go by it, so it has to match what is sent. */
internal fun urlExtension(name: String, mime: String): String {
    val ext = extensionOf(name)
    if (ext in MIME || ext in AUDIO_MIME || ext in IMAGE_MIME) return ext
    val m = baseMime(mime)
    return listOf(MIME, AUDIO_MIME, IMAGE_MIME).firstNotNullOfOrNull { t -> t.entries.firstOrNull { it.value == m }?.key }
        ?: if (m.startsWith("audio/")) ".mp3" else if (m.startsWith("image/")) ".jpg" else ".mp4"
}

/** Names TVs use for the same format. The first is ours; [ordered] groups always go by this order
 *  (for transport streams the plain MPEG names suit more TVs than the one we'd pick). */
private class AliasGroup(val names: List<String>, val ordered: Boolean = false)

private val ALIASES = listOf(
    AliasGroup(listOf("video/x-matroska", "video/x-mkv", "video/mkv")),
    AliasGroup(listOf("video/mp4", "video/mpeg4")),
    AliasGroup(listOf("video/x-msvideo", "video/avi", "video/x-avi", "video/divx")),
    AliasGroup(listOf("video/quicktime", "video/mov")),
    AliasGroup(listOf("video/mp2t", "video/mpeg", "video/vnd.dlna.mpeg-tts"), ordered = true),
    AliasGroup(listOf("video/x-ms-wmv", "video/x-ms-asf")),
    AliasGroup(listOf("audio/flac", "audio/x-flac")),
    AliasGroup(listOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac")),
    AliasGroup(listOf("audio/aac", "audio/x-aac", "audio/vnd.dlna.adts", "audio/aacp")),
    AliasGroup(listOf("audio/wav", "audio/x-wav", "audio/wave")),
    AliasGroup(listOf("audio/ogg", "application/ogg", "audio/x-ogg")),
    AliasGroup(listOf("audio/aiff", "audio/x-aiff")),
    AliasGroup(listOf("image/jpeg", "image/jpg")),
)

/** audio/aac is both an M4A name and ADTS AAC's own: a group that starts with the type wins. */
private fun groupOf(mime: String): AliasGroup? {
    val m = baseMime(mime)
    return ALIASES.firstOrNull { it.names[0] == m } ?: ALIASES.firstOrNull { m in it.names }
}

/** The formats a TV says it plays: ConnectionManager GetProtocolInfo, "Sink". */
class SinkFormats private constructor(
    /** MIME type (lower case, without parameters) -> the TV's own spelling of it. */
    private val spelled: Map<String, String>,
    /** The TV takes anything (a "*" entry). */
    val any: Boolean,
) {
    /** False when the TV didn't say: then nothing can be ruled out. */
    val known: Boolean get() = any || spelled.isNotEmpty()

    val playsVideo: Boolean? get() = if (!known) null else any || spelled.keys.any { it.startsWith("video/") }
    val playsAudio: Boolean? get() = if (!known) null else any || spelled.keys.any { it.startsWith("audio/") || it == "application/ogg" }

    /** true / false, or null when the TV didn't say. */
    fun accepts(mime: String): Boolean? {
        if (!known) return null
        if (any) return true
        val names = groupOf(mime)?.names ?: listOf(baseMime(mime))
        return baseMime(mime) in spelled || names.any { it in spelled }
    }

    /** [mime] the way this TV writes it, or the name it lists for the same format; [mime] itself when it lists none. */
    fun spell(mime: String): String {
        if (!known || any) return mime
        val m = baseMime(mime)
        val group = groupOf(mime) ?: return spelled[m] ?: mime
        if (!group.ordered) spelled[m]?.let { return it }
        for (n in group.names) spelled[n]?.let { return it }
        return mime
    }

    companion object {
        val UNKNOWN = SinkFormats(emptyMap(), false)

        fun parse(sink: String?): SinkFormats {
            val spelled = LinkedHashMap<String, String>()
            var any = false
            for (raw in (sink ?: "").split(',')) {
                val entry = raw.trim()
                if (entry == "*") {
                    any = true
                    continue
                }
                val parts = entry.split(':', limit = 4)
                if (parts.size < 3) continue
                val protocol = parts[0].trim().lowercase()
                if (protocol != "http-get" && protocol != "*") continue
                val format = parts[2].substringBefore(';').trim()
                if (format == "*" || format == "*/*") {
                    any = true
                    continue
                }
                if ('/' in format) spelled.putIfAbsent(format.lowercase(), format)
            }
            return SinkFormats(spelled, any)
        }
    }
}

/** The 4th protocolInfo field, also sent as the contentFeatures.dlna.org header. */
object Dlna {
    /** Streaming, background and stalling transfers, DLNA 1.5. */
    const val FLAGS_AV = "01700000000000000000000000000000"
    /** Interactive and background transfers, stalling, DLNA 1.5: what photos are fetched with. */
    const val FLAGS_IMAGE = "00f00000000000000000000000000000"
    const val AV = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$FLAGS_AV"
    /** No seeking in something that is being made as it is watched. */
    const val LIVE = "DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=$FLAGS_AV"

    fun audio(mime: String) = (if (baseMime(mime) == "audio/mpeg") "DLNA.ORG_PN=MP3;" else "") + AV

    fun image(mime: String, width: Int, height: Int, converted: Boolean): String {
        val pn = when (baseMime(mime)) {
            "image/jpeg", "image/jpg" -> jpegProfile(width, height)
            "image/png" -> "PNG_LRG"
            else -> null
        }
        return (if (pn != null) "DLNA.ORG_PN=$pn;" else "") +
            "DLNA.ORG_OP=00;DLNA.ORG_CI=${if (converted) 1 else 0};DLNA.ORG_FLAGS=$FLAGS_IMAGE"
    }

    fun jpegProfile(width: Int, height: Int) = when {
        width in 1..640 && height in 1..480 -> "JPEG_SM"
        width in 1..1024 && height in 1..768 -> "JPEG_MED"
        else -> "JPEG_LRG"
    }

    fun forKind(kind: MediaKind, mime: String): String = when (kind) {
        MediaKind.VIDEO -> AV
        MediaKind.AUDIO -> audio(mime)
        MediaKind.IMAGE -> image(mime, 0, 0, false)
        MediaKind.LIVE -> LIVE
    }
}
