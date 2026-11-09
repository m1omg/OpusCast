package io.github.m1omg.opuscast.core

import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

internal const val SSDP_ADDR = "239.255.255.250"
internal const val SSDP_PORT = 1900

/** This device's address on the network that reaches [host] (no packet is sent). */
fun localIpTowards(host: String): String =
    DatagramSocket().use { s ->
        s.connect(InetSocketAddress(host, 9))
        s.localAddress.hostAddress ?: throw IOException("no route to $host")
    }

/** M-SEARCH the LAN; returns the LOCATION URLs that answered, in the order they answered.
 *  [bindAddress]: send from this local IPv4 address (the Wi-Fi one on a phone), or null for the default. */
fun ssdpSearch(searchTarget: String, timeoutMs: Long, bindAddress: InetAddress? = null): List<String> {
    val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\nST: $searchTarget\r\n\r\n").toByteArray()
    val locations = LinkedHashSet<String>()
    val socket = if (bindAddress != null) MulticastSocket(InetSocketAddress(bindAddress, 0)) else MulticastSocket()
    socket.use { s ->
        if (bindAddress != null) runCatching { s.`interface` = bindAddress }
        s.timeToLive = 2
        s.soTimeout = 300
        val target = InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT)
        repeat(2) { s.send(DatagramPacket(msg, msg.size, target)) }
        val end = System.nanoTime() + timeoutMs * 1_000_000
        val buf = ByteArray(65507)
        while (System.nanoTime() < end) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: SocketTimeoutException) {
                continue
            }
            ssdpLocation(String(p.data, 0, p.length, Charsets.ISO_8859_1))?.let { locations.add(it) }
        }
    }
    return locations.toList()
}

private val LOCATION = Regex("""^location:\s*(\S+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

internal fun ssdpLocation(reply: String): String? = LOCATION.find(reply)?.groupValues?.get(1)

/** Resolve [ref] against [base] like a browser would (and like Python's urljoin). */
internal fun resolveUrl(base: String, ref: String): String {
    if (ref.isEmpty()) return base
    return try {
        URI(base).resolve(URI(ref.replace(" ", "%20"))).toString()
    } catch (e: Exception) {
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref
        else base.trimEnd('/') + "/" + ref.trimStart('/')
    }
}

internal fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: ""

internal class HttpResult(val code: Int, val body: ByteArray, val headers: Map<String, String>)

/** One HTTP request; any status comes back as a result, only network trouble throws (IOException). */
internal fun httpRequest(
    url: String, method: String = "GET", body: ByteArray? = null,
    headers: Map<String, String> = emptyMap(), timeoutMs: Int = 4000,
): HttpResult {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = method
        c.instanceFollowRedirects = false
        c.useCaches = false
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (body != null) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val stream: InputStream? = if (code >= 400) c.errorStream else c.inputStream
        val data = stream?.use { it.readBytes() } ?: ByteArray(0)
        val h = HashMap<String, String>()
        for ((k, v) in c.headerFields) if (k != null && v.isNotEmpty()) h[k.lowercase()] = v.last()
        return HttpResult(code, data, h)
    } finally {
        c.disconnect()
    }
}

/** A request with a method HttpURLConnection refuses to send (SUBSCRIBE, UNSUBSCRIBE, NOTIFY), over a plain socket. */
internal fun rawHttpRequest(url: String, method: String, headers: Map<String, String>, timeoutMs: Int = 5000,
                            body: ByteArray? = null): HttpResult {
    val u = URI(url)
    val port = if (u.port > 0) u.port else 80
    val path = (u.rawPath ?: "").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
    Socket().use { s ->
        s.connect(InetSocketAddress(u.host, port), timeoutMs)
        s.soTimeout = timeoutMs
        val req = StringBuilder("$method $path HTTP/1.1\r\nHOST: ${u.host}:$port\r\n")
        for ((k, v) in headers) req.append("$k: $v\r\n")
        req.append("Content-Length: ${body?.size ?: 0}\r\nConnection: close\r\n\r\n")
        s.getOutputStream().apply {
            write(req.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) write(body)
            flush()
        }
        val input = s.getInputStream()
        val head = readHead(input) ?: throw IOException("no answer")
        val lines = head.split("\r\n")
        val code = lines[0].split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("bad answer: ${lines[0]}")
        val h = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) h[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return HttpResult(code, ByteArray(0), h)
    }
}

/** Read up to and including the blank line that ends HTTP headers; null if the stream ends first. */
internal fun readHead(input: InputStream, limit: Int = 65536): String? {
    val buf = java.io.ByteArrayOutputStream()
    var last4 = 0
    while (true) {
        val b = input.read()
        if (b < 0) return null
        buf.write(b)
        last4 = (last4 shl 8) or b
        if (last4 == 0x0D0A0D0A) return String(buf.toByteArray(), 0, buf.size() - 4, Charsets.ISO_8859_1)
        if (buf.size() > limit) throw IOException("headers too long")
    }
}

internal fun isIpv4(address: InetAddress) = address is Inet4Address
