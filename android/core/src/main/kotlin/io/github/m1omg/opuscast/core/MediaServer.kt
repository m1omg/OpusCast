package io.github.m1omg.opuscast.core

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
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

/** What the TV is fed: a file on disk, a document from the system file picker (Android), or bytes made here. */
interface MediaSource {
    /** The file name, e.g. "Movie.mkv". */
    val name: String
    val size: Long
    /** Length in milliseconds when known (Samsung TVs ask for it). */
    val durationMs: Long?
    /** The bytes from [offset] on. */
    fun openAt(offset: Long): InputStream
    val title: String get() = stemOf(name)
    val mime: String get() = mimeFor(name)
    val kind: MediaKind get() = kindOf(name, mime) ?: MediaKind.VIDEO
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

/** Bytes made here, e.g. a photo converted to a JPEG the TV can show. */
class BytesSource(
    override val name: String,
    val bytes: ByteArray,
    override val mime: String = mimeFor(name),
    override val kind: MediaKind = kindOf(name, mime) ?: MediaKind.IMAGE,
    title: String? = null,
) : MediaSource {
    override val size = bytes.size.toLong()
    override val durationMs: Long? = null
    override val title: String = title ?: stemOf(name)
    override fun openAt(offset: Long): InputStream = ByteArrayInputStream(bytes, offset.toInt(), (size - offset).toInt())
}

/** Stands in for the file of a live stream: there is none, the bytes come from a [LiveHub]. */
private class LiveSource(override val title: String) : MediaSource {
    override val name = "live.ts"
    override val size = -1L
    override val durationMs: Long? = null
    override val mime = "video/mpeg"
    override val kind = MediaKind.LIVE
    override fun openAt(offset: Long): InputStream = throw IOException("a live stream has no file")
}

/** One published video, song, photo or live stream, and the files that go with it (subtitles, album cover). */
class Media internal constructor(
    val source: MediaSource,
    val token: String,
    /** The MIME type as the TV is told it (its own spelling where it lists one). */
    val mime: String,
    /** The DLNA features announced with it (protocolInfo, contentFeatures.dlna.org). */
    val features: String,
    /** Where a live stream's bytes come from; null for files. */
    val live: LiveHub? = null,
) {
    val kind: MediaKind get() = if (live != null) MediaKind.LIVE else source.kind
    private val ext = urlExtension(source.name, mime)
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
    /** URL path -> (MIME type, bytes) of other files that go with it, e.g. the album cover. */
    val extras: MutableMap<String, Pair<String, ByteArray>> = ConcurrentHashMap()
    /** Music: shown by the TV next to the title. */
    @Volatile var artist: String? = null
    @Volatile var album: String? = null
    /** URL path of the album cover (one of [extras]). */
    @Volatile var coverPath: String? = null
    /** Length announced to the TV up front, when known (music). */
    @Volatile var knownDurationMs: Long? = null
    /** The title from the song's tags, shown instead of the file name. */
    @Volatile var tagTitle: String? = null
    val title get() = tagTitle ?: source.title
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

    /** Serve a video, song or photo, optionally with a subtitle (SRT bytes) announced to the TV with it.
     *  [mime] is what the TV is told (see [SinkFormats.spell]); [features] the DLNA features to go with it. */
    fun publish(source: MediaSource, subtitle: ByteArray? = null, mime: String = source.mime,
                features: String = Dlna.forKind(source.kind, mime)): Media {
        val media = Media(source, token(8), mime, features)
        media.subs[media.blankPath] = BLANK_SRT
        if (subtitle != null) {
            media.subPath = "/media/${media.token}.srt"
            media.subs[media.subPath!!] = subtitle
        }
        current = media
        return media
    }

    /** Serve a live MPEG-TS stream: every request for it gets the stream from [hub], from a keyframe on. */
    fun publishLive(hub: LiveHub, title: String, mime: String = "video/mpeg"): Media {
        val media = Media(LiveSource(title), token(8), mime, Dlna.LIVE, hub)
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

    /** Serve a file that goes with [media] (e.g. its album cover, [ext] like ".jpg"); returns its URL path. */
    fun addExtra(media: Media, ext: String, mime: String, data: ByteArray): String {
        val path = "/media/${media.token}-${token(3)}$ext"
        media.extras[path] = mime to data
        return path
    }

    /** This machine's address as the TV at the given host should use it (network I/O: not on a UI thread). */
    @Volatile var addressFor: (String) -> String = ::localIpTowards

    fun urlFor(tvHost: String, path: String) = "http://${addressFor(tvHost)}:$port$path"
    fun url(media: Media, tvHost: String) = urlFor(tvHost, media.urlPath)
    fun subUrl(media: Media, tvHost: String) = media.subPath?.let { urlFor(tvHost, it) }
    fun eventUrl(tvHost: String, service: String) = "http://${addressFor(tvHost)}:$port/events/$service"

    /** Open connections, closed with the server (a TV's kept-alive one would otherwise still be answered). */
    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    fun close() {
        closed = true
        runCatching { socket.close() }
        connections.forEach { runCatching { it.close() } }
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
                connections += c
                pool.execute { serve(c) }
            } catch (e: Exception) {
                connections -= c
                runCatching { c.close() }
            }
        }
    }

    private fun serve(c: Socket) {
        try {
            c.use {
                if (closed) return
                c.soTimeout = 120_000
                c.tcpNoDelay = true
                val input = BufferedInputStream(c.getInputStream(), 16384)
                val output = c.getOutputStream()
                while (!closed) {
                    val head = readHead(input) ?: return
                    if (closed) return
                    val lines = head.split("\r\n")
                    val parts = lines[0].split(" ")
                    if (parts.size < 2) return
                    val headers = HashMap<String, String>()
                    for (line in lines.drop(1)) {
                        val i = line.indexOf(':')
                        if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                    }
                    val version = parts.getOrNull(2) ?: "HTTP/1.0"
                    val keepAlive = when {
                        headers["connection"]?.lowercase() == "close" -> false
                        version == "HTTP/1.0" -> headers["connection"]?.lowercase() == "keep-alive"
                        else -> true
                    }
                    if (!handle(parts[0].uppercase(), parts[1], version, headers, input, output, c)) return
                    output.flush()
                    if (!keepAlive) return
                }
            }
        } catch (e: IOException) {
            // the TV drops connections all the time (seeking, buffering)
        } finally {
            connections -= c
        }
    }

    /** Returns false when the connection can't be reused. */
    private fun handle(method: String, target: String, version: String, headers: Map<String, String>, input: InputStream,
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
        val extra = media?.extras?.get(path)
        if (extra != null) {
            val (mime, data) = extra
            respond(out, 200, "OK", listOf("Content-Type" to mime, "Content-Length" to data.size.toString(),
                "transferMode.dlna.org" to (headers["transfermode.dlna.org"] ?: "Interactive"),
                "contentFeatures.dlna.org" to "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=${Dlna.FLAGS_IMAGE}"))
            if (body) out.write(data)
            return true
        }
        if (media == null || path != media.urlPath) {
            val msg = "Not found".toByteArray()
            respond(out, 404, "Not Found", listOf("Content-Type" to "text/plain", "Content-Length" to msg.size.toString()))
            if (body) out.write(msg)
            return true
        }
        media.requested = true
        // a TV asks for the way it means to fetch; answering in kind keeps strict ones (Sony) happy
        val transferMode = headers["transfermode.dlna.org"] ?: if (media.kind == MediaKind.IMAGE) "Interactive" else "Streaming"
        val hub = media.live
        if (hub != null) return serveLive(hub, media, body, version == "HTTP/1.0", transferMode, out)
        val size = media.source.size
        val range = try {
            parseRange(headers["range"], size)
        } catch (e: IllegalArgumentException) {
            respond(out, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
            return true
        }
        val (start, end) = range ?: (0L to size - 1)
        val h = mutableListOf(
            "Content-Type" to media.mime,
            "Content-Length" to (end - start + 1).toString(),
            "Accept-Ranges" to "bytes",
        )
        if (range != null) h += "Content-Range" to "bytes $start-$end/$size"
        h += "transferMode.dlna.org" to transferMode
        h += "contentFeatures.dlna.org" to media.features
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

    /** A live stream has no length and no ranges: 200 and then bytes for as long as it runs, chunked for
     *  HTTP/1.1 and ended by closing the connection for HTTP/1.0. Returns false: the connection is done. */
    private fun serveLive(hub: LiveHub, media: Media, body: Boolean, http10: Boolean, transferMode: String, out: OutputStream): Boolean {
        val h = mutableListOf("Content-Type" to media.mime, "Cache-Control" to "no-cache",
            "transferMode.dlna.org" to transferMode, "contentFeatures.dlna.org" to media.features)
        h += if (http10) "Connection" to "close" else "Transfer-Encoding" to "chunked"
        respond(out, 200, "OK", h)
        if (!body) return !http10
        out.flush()
        val client = hub.open()
        try {
            while (!closed) {
                val chunk = client.read(10_000) ?: break
                if (chunk.isEmpty()) continue // nothing new yet: keep the TV waiting rather than end its stream
                if (!http10) out.write("${Integer.toHexString(chunk.size)}\r\n".toByteArray(Charsets.ISO_8859_1))
                out.write(chunk)
                if (!http10) out.write(CRLF)
                out.flush()
            }
            if (!http10) {
                out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
            }
        } finally {
            client.close()
        }
        return false
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

private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

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
