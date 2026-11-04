package io.github.m1omg.opuscast.core

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

const val DEFAULT_PORT = 8765

val MIME = mapOf(
    ".mkv" to "video/x-mkv", ".mp4" to "video/mp4", ".m4v" to "video/mp4",
    ".mov" to "video/quicktime", ".avi" to "video/x-msvideo", ".webm" to "video/webm",
    ".ts" to "video/vnd.dlna.mpeg-tts", ".m2ts" to "video/vnd.dlna.mpeg-tts",
    ".mts" to "video/vnd.dlna.mpeg-tts", ".mpg" to "video/mpeg", ".mpeg" to "video/mpeg",
    ".flv" to "video/x-flv", ".wmv" to "video/x-ms-asf", ".3gp" to "video/3gpp",
)
val VIDEO_EXTS = MIME.keys

/** "Movie.MKV" -> ".mkv" ("" without one). */
fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && dot > name.lastIndexOf('/')) name.substring(dot).lowercase() else ""
}

fun stemOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}

/** What the TV is fed: a file on disk, or (on Android) a document from the system file picker. */
interface MediaSource {
    /** The file name, e.g. "Movie.mkv". */
    val name: String
    val size: Long
    /** Length in milliseconds when known (Samsung TVs ask for it). */
    val durationMs: Long?
    /** The bytes from [offset] on. */
    fun openAt(offset: Long): InputStream
    val title: String get() = stemOf(name)
    val mime: String get() = MIME[extensionOf(name)] ?: "video/mp4"
}

class FileSource(private val file: File, override val durationMs: Long? = null) : MediaSource {
    override val name: String = file.name
    override val size: Long = file.length()
    override fun openAt(offset: Long): InputStream {
        val s = FileInputStream(file)
        s.channel.position(offset)
        return s
    }
}

/** One published video and its subtitle files. */
class Media internal constructor(val source: MediaSource, val token: String) {
    private val ext = extensionOf(source.name).takeIf { it in MIME } ?: ".mp4"
    val urlPath = "/media/$token$ext"
    val blankPath = "/media/$token-off.srt"
    /** The subtitle announced with the video, if any. */
    var subPath: String? = null
        internal set
    /** URL path -> SRT bytes. */
    val subs: MutableMap<String, ByteArray> = ConcurrentHashMap()
    /** Set once the TV asks for the video (if it never does, a firewall or the Wi-Fi is in the way). */
    @Volatile var requested = false
        internal set
    // subtitle commands: a counter so a pending resend knows it's out of date, and when the last went out
    internal val captionLock = Any()
    @Volatile var captionSeq = 0
        internal set
    /** System.nanoTime() of the last subtitle command (0 = none yet). */
    @Volatile var captionSentNanos = 0L
        internal set
    /** What play() returned for the start-up subtitle: "ok", "unsupported" or null. */
    @Volatile var captions: String? = null
    val title get() = source.title
    @Volatile private var duration: Long? = null
    @Volatile private var durationKnown = false

    internal fun durationMs(): Long? {
        if (!durationKnown) synchronized(this) {
            if (!durationKnown) {
                duration = runCatching { source.durationMs }.getOrNull()
                durationKnown = true
            }
        }
        return duration
    }
}

val BLANK_SRT = "1\n99:00:00,000 --> 99:00:01,000\n \n".toByteArray() // what the TV shows for "subtitles off"

/** Serves one video (plus its subtitles) at a time to the TV, and receives the TV's change
 *  notifications. Tries [port], then the next nine, then any free port. */
class MediaServer(port: Int = DEFAULT_PORT) {
    private val socket: ServerSocket
    val port: Int
    @Volatile var current: Media? = null
        private set
    /** Called with (service, SID, body) for each NOTIFY from the TV, on a server thread. */
    @Volatile var onEvent: ((String, String?, String) -> Unit)? = null
    private val random = SecureRandom()
    private val pool = Executors.newCachedThreadPool(object : ThreadFactory {
        private val n = AtomicInteger()
        override fun newThread(r: Runnable) = Thread(r, "opuscast-http-${n.incrementAndGet()}").apply { isDaemon = true }
    })
    @Volatile private var closed = false

    init {
        var last: Exception? = null
        var s: ServerSocket? = null
        for (p in (port until port + 10) + 0) {
            try {
                s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) }
                break
            } catch (e: IOException) {
                last = e
            }
        }
        socket = s ?: throw TvException(TvException.Reason.NO_PORT, last?.message ?: "", last)
        this.port = socket.localPort
        thread(isDaemon = true, name = "opuscast-http") { acceptLoop() }
    }

    private fun token(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Serve a video, optionally with a subtitle (SRT bytes) announced to the TV with it. */
    fun publish(source: MediaSource, subtitle: ByteArray? = null): Media {
        val media = Media(source, token(8))
        media.subs[media.blankPath] = BLANK_SRT
        if (subtitle != null) {
            media.subPath = "/media/${media.token}.srt"
            media.subs[media.subPath!!] = subtitle
        }
        current = media
        return media
    }

    /** Stop serving anything (the TV gets 404s). */
    fun unpublish() { current = null }

    /** Serve one more subtitle file for the playing video; returns its URL path. */
    fun addSubtitle(media: Media, data: ByteArray): String {
        val path = "/media/${media.token}-${token(3)}.srt"
        media.subs[path] = data
        return path
    }

    /** This machine's address as the TV at the given host should use it (network I/O: not on a UI thread). */
    @Volatile var addressFor: (String) -> String = ::localIpTowards

    fun urlFor(tvHost: String, path: String) = "http://${addressFor(tvHost)}:$port$path"
    fun url(media: Media, tvHost: String) = urlFor(tvHost, media.urlPath)
    fun subUrl(media: Media, tvHost: String) = media.subPath?.let { urlFor(tvHost, it) }
    fun eventUrl(tvHost: String, service: String) = "http://${addressFor(tvHost)}:$port/events/$service"

    fun close() {
        closed = true
        runCatching { socket.close() }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed) {
            val c = try {
                socket.accept()
            } catch (e: IOException) {
                if (closed) return else continue
            }
            try {
                pool.execute { serve(c) }
            } catch (e: Exception) {
                runCatching { c.close() }
            }
        }
    }

    private fun serve(c: Socket) {
        try {
            c.use {
                c.soTimeout = 120_000
                c.tcpNoDelay = true
                val input = BufferedInputStream(c.getInputStream(), 16384)
                val output = c.getOutputStream()
                while (!closed) {
                    val head = readHead(input) ?: return
                    val lines = head.split("\r\n")
                    val parts = lines[0].split(" ")
                    if (parts.size < 2) return
                    val headers = HashMap<String, String>()
                    for (line in lines.drop(1)) {
                        val i = line.indexOf(':')
                        if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                    }
                    val keepAlive = when {
                        headers["connection"]?.lowercase() == "close" -> false
                        parts.getOrNull(2) == "HTTP/1.0" -> headers["connection"]?.lowercase() == "keep-alive"
                        else -> true
                    }
                    if (!handle(parts[0].uppercase(), parts[1], headers, input, output, c)) return
                    output.flush()
                    if (!keepAlive) return
                }
            }
        } catch (e: IOException) {
            // the TV drops connections all the time (seeking, buffering)
        }
    }

    /** Returns false when the connection can't be reused. */
    private fun handle(method: String, target: String, headers: Map<String, String>, input: InputStream,
                       out: OutputStream, c: Socket): Boolean {
        val path = runCatching { URI(target).rawPath }.getOrNull() ?: target.substringBefore('?')
        if (method == "NOTIFY") {
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = readExactly(input, length)
            respond(out, 200, "OK", listOf("Content-Length" to "0"))
            out.flush()
            val callback = onEvent
            if (callback != null) {
                try {
                    callback(path.substringAfterLast('/'), headers["sid"], String(body, Charsets.UTF_8))
                } catch (e: Exception) {
                    // never let a bad event kill the server
                }
            }
            return true
        }
        // a body we don't want would get in the way of the next request on this connection
        val length = headers["content-length"]?.toLongOrNull() ?: 0
        if (length > 0) {
            if (length > 1 shl 20) return false
            readExactly(input, length.toInt())
        }
        if (method != "GET" && method != "HEAD") {
            respond(out, 501, "Not Implemented", listOf("Content-Length" to "0"))
            return true
        }
        val body = method == "GET"
        val media = current
        val sub = media?.subs?.get(path)
        if (media != null && sub != null) {
            respond(out, 200, "OK", listOf("Content-Type" to "text/srt; charset=utf-8", "Content-Length" to sub.size.toString()))
            if (body) out.write(sub)
            return true
        }
        if (media == null || path != media.urlPath) {
            val msg = "Not found".toByteArray()
            respond(out, 404, "Not Found", listOf("Content-Type" to "text/plain", "Content-Length" to msg.size.toString()))
            if (body) out.write(msg)
            return true
        }
        media.requested = true
        val size = media.source.size
        val range = try {
            parseRange(headers["range"], size)
        } catch (e: IllegalArgumentException) {
            respond(out, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
            return true
        }
        val (start, end) = range ?: (0L to size - 1)
        val h = mutableListOf(
            "Content-Type" to media.source.mime,
            "Content-Length" to (end - start + 1).toString(),
            "Accept-Ranges" to "bytes",
        )
        if (range != null) h += "Content-Range" to "bytes $start-$end/$size"
        h += "transferMode.dlna.org" to "Streaming"
        h += "contentFeatures.dlna.org" to DLNA_FEATURES
        // Samsung TVs ask for these; answering like other DLNA servers do keeps their player happy
        val subPath = media.subPath
        if (headers["getcaptioninfo.sec"] != null && subPath != null) {
            h += "CaptionInfo.sec" to "http://${c.localAddress.hostAddress}:$port$subPath"
        }
        if (headers["getmediainfo.sec"] != null) {
            media.durationMs()?.let { h += "MediaInfo.sec" to "SEC_Duration=$it;" }
        }
        respond(out, if (range != null) 206 else 200, if (range != null) "Partial Content" else "OK", h)
        if (body && end >= start) {
            out.flush()
            val stream = try {
                media.source.openAt(start)
            } catch (e: Exception) {
                return false // headers promised bytes we can't send: drop the connection
            }
            stream.use { copy(it, out, end - start + 1) }
        }
        return true
    }

    private fun respond(out: OutputStream, code: Int, reason: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\nServer: OpusCast\r\nDate: ${httpDate()}\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun copy(input: InputStream, out: OutputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw SocketException("file ended early")
            out.write(buf, 0, n)
            left -= n
        }
    }
}

private fun readExactly(input: InputStream, n: Int): ByteArray {
    val buf = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = input.read(buf, got, n - got)
        if (r < 0) throw IOException("body ended early")
        got += r
    }
    return buf
}

private fun httpDate(): String {
    val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
    f.timeZone = TimeZone.getTimeZone("GMT")
    return f.format(Date())
}

/** "bytes=a-b" -> (start, end) inclusive; null without a usable Range header. Throws
 *  IllegalArgumentException when the range can't be satisfied (answer 416). */
fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
    if (header == null || !header.startsWith("bytes=")) return null
    val spec = header.substring(6).split(",")[0].trim()
    val dash = spec.indexOf('-')
    if (dash < 0) throw IllegalArgumentException(header)
    val first = spec.substring(0, dash).trim()
    val last = spec.substring(dash + 1).trim()
    val start: Long
    val end: Long
    if (first.isEmpty()) {
        val n = last.toLongOrNull() ?: throw IllegalArgumentException(header)
        start = maxOf(0, size - n)
        end = size - 1
    } else {
        start = first.toLongOrNull() ?: throw IllegalArgumentException(header)
        end = minOf(if (last.isEmpty()) size - 1 else last.toLongOrNull() ?: throw IllegalArgumentException(header), size - 1)
    }
    if (start > end) throw IllegalArgumentException(header)
    return start to end
}
