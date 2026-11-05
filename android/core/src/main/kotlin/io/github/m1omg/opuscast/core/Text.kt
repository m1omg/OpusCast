package io.github.m1omg.opuscast.core

/** Seconds -> "1:02:03". */
fun fmtTime(seconds: Double?): String {
    val s = maxOf(0L, (seconds ?: 0.0).toLong())
    return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

private val TIME = Regex("""(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:\.\d+)?""")

/** "1:02:03", "1:02:03.500" or "62:03" -> seconds; anything else (e.g. NOT_IMPLEMENTED) -> null. */
fun parseTime(text: String?): Int? {
    val m = TIME.matchEntire((text ?: "").trim()) ?: return null
    val h = m.groupValues[1].ifEmpty { "0" }.toInt()
    return h * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
}

/** Milliseconds -> "0:03:25.000", the DIDL-Lite res@duration form. */
fun didlDuration(ms: Long): String {
    val t = maxOf(0L, ms)
    return "%d:%02d:%02d.%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
}

/** Seconds -> "HH:MM:SS" as UPnP Seek wants it. */
fun upnpTime(seconds: Int): String {
    val s = maxOf(0, seconds)
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

fun xmlEscape(text: String): String {
    val sb = StringBuilder(text.length + 16)
    for (c in text) when (c) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        '"' -> sb.append("&quot;")
        '\'' -> sb.append("&#x27;")
        else -> sb.append(c)
    }
    return sb.toString()
}

private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""")
private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0")

fun xmlUnescape(text: String): String = ENTITY.replace(text) { m ->
    val e = m.groupValues[1]
    when {
        e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
        e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
        else -> NAMED[e]
    } ?: m.value
}

/** Text of the first <name>…</name> in a SOAP answer, unescaped. */
fun xmlTag(xml: String, name: String): String? {
    val m = Regex("<$name>([^<]*)</$name>").find(xml) ?: return null
    return xmlUnescape(m.groupValues[1])
}
