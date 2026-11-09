package io.github.m1omg.opuscast.core

import org.w3c.dom.Element
import java.io.Closeable
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// ---------------------------------------------------------------- the phone among the TV's sources

const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

/** OpusCast's version as servers announce it (the app sets it from its package). */
@Volatile var productVersion = "1.0"

/** What OpusCast says it is in the Server header of its answers and announcements, the way DLNA servers do. */
val SERVER_STRING: String
    get() = "${System.getProperty("os.name") ?: "OS"}/${(System.getProperty("os.version") ?: "1").substringBefore(' ')} " +
        "UPnP/1.0 DLNADOC/1.50 OpusCast/$productVersion"

/** An answer to a request for the [TvSource] (the [MediaServer] sends it). */
class Reply(val code: Int, val reason: String, val headers: List<Pair<String, String>>, val body: ByteArray = ByteArray(0))

private const val SOAP_OPEN = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
private const val SOAP_CLOSE = "</s:Body></s:Envelope>"
private const val DIDL_OPEN = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
    "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">"
/** Samsung TVs ask which folder to open for their video, music and photo views. */
private const val SAMSUNG_FEATURES = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Features xmlns=\"urn:schemas-upnp-org:av:avs\" " +
    "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"urn:schemas-upnp-org:av:avs " +
    "http://www.upnp.org/schemas/av/avs.xsd\"><Feature name=\"samsung.com_BASICVIEW\" version=\"1\">" +
    "<container id=\"0\" type=\"object.item.audioItem\"/><container id=\"0\" type=\"object.item.videoItem\"/>" +
    "<container id=\"0\" type=\"object.item.imageItem\"/></Feature></Features>"

private class Arg(val name: String, val out: Boolean, val variable: String)
private class StateVar(val name: String, val type: String, val evented: Boolean = false, val allowed: List<String> = emptyList())

/** A UPnP service description. */
private fun scpd(actions: Map<String, List<Arg>>, variables: List<StateVar>): String {
    val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
        "<specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
    for ((name, args) in actions) {
        sb.append("<action><name>$name</name><argumentList>")
        for (a in args) sb.append("<argument><name>${a.name}</name><direction>${if (a.out) "out" else "in"}</direction>" +
            "<relatedStateVariable>${a.variable}</relatedStateVariable></argument>")
        sb.append("</argumentList></action>")
    }
    sb.append("</actionList><serviceStateTable>")
    for (v in variables) {
        sb.append("<stateVariable sendEvents=\"${if (v.evented) "yes" else "no"}\"><name>${v.name}</name><dataType>${v.type}</dataType>")
        if (v.allowed.isNotEmpty()) sb.append("<allowedValueList>").append(v.allowed.joinToString("") { "<allowedValue>$it</allowedValue>" })
            .append("</allowedValueList>")
        sb.append("</stateVariable>")
    }
    return sb.append("</serviceStateTable></scpd>").toString()
}

private val CDS_SCPD = scpd(linkedMapOf(
    "Browse" to listOf(Arg("ObjectID", false, "A_ARG_TYPE_ObjectID"), Arg("BrowseFlag", false, "A_ARG_TYPE_BrowseFlag"),
        Arg("Filter", false, "A_ARG_TYPE_Filter"), Arg("StartingIndex", false, "A_ARG_TYPE_Index"),
        Arg("RequestedCount", false, "A_ARG_TYPE_Count"), Arg("SortCriteria", false, "A_ARG_TYPE_SortCriteria"),
        Arg("Result", true, "A_ARG_TYPE_Result"), Arg("NumberReturned", true, "A_ARG_TYPE_Count"),
        Arg("TotalMatches", true, "A_ARG_TYPE_Count"), Arg("UpdateID", true, "A_ARG_TYPE_UpdateID")),
    "GetSearchCapabilities" to listOf(Arg("SearchCaps", true, "SearchCapabilities")),
    "GetSortCapabilities" to listOf(Arg("SortCaps", true, "SortCapabilities")),
    "GetSystemUpdateID" to listOf(Arg("Id", true, "SystemUpdateID")),
), listOf(
    StateVar("A_ARG_TYPE_ObjectID", "string"), StateVar("A_ARG_TYPE_BrowseFlag", "string", allowed = listOf("BrowseMetadata", "BrowseDirectChildren")),
    StateVar("A_ARG_TYPE_Filter", "string"), StateVar("A_ARG_TYPE_Index", "ui4"), StateVar("A_ARG_TYPE_Count", "ui4"),
    StateVar("A_ARG_TYPE_SortCriteria", "string"), StateVar("A_ARG_TYPE_Result", "string"), StateVar("A_ARG_TYPE_UpdateID", "ui4"),
    StateVar("SearchCapabilities", "string"), StateVar("SortCapabilities", "string"), StateVar("SystemUpdateID", "ui4", evented = true),
))

private val CMS_SCPD = scpd(linkedMapOf(
    "GetProtocolInfo" to listOf(Arg("Source", true, "SourceProtocolInfo"), Arg("Sink", true, "SinkProtocolInfo")),
    "GetCurrentConnectionIDs" to listOf(Arg("ConnectionIDs", true, "CurrentConnectionIDs")),
    "GetCurrentConnectionInfo" to listOf(Arg("ConnectionID", false, "A_ARG_TYPE_ConnectionID"), Arg("RcsID", true, "A_ARG_TYPE_RcsID"),
        Arg("AVTransportID", true, "A_ARG_TYPE_AVTransportID"), Arg("ProtocolInfo", true, "A_ARG_TYPE_ProtocolInfo"),
        Arg("PeerConnectionManager", true, "A_ARG_TYPE_ConnectionManager"), Arg("PeerConnectionID", true, "A_ARG_TYPE_ConnectionID"),
        Arg("Direction", true, "A_ARG_TYPE_Direction"), Arg("Status", true, "A_ARG_TYPE_ConnectionStatus")),
), listOf(
    StateVar("SourceProtocolInfo", "string", evented = true), StateVar("SinkProtocolInfo", "string", evented = true),
    StateVar("CurrentConnectionIDs", "string", evented = true),
    StateVar("A_ARG_TYPE_ConnectionStatus", "string", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
    StateVar("A_ARG_TYPE_ConnectionManager", "string"), StateVar("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")),
    StateVar("A_ARG_TYPE_ProtocolInfo", "string"), StateVar("A_ARG_TYPE_ConnectionID", "i4"),
    StateVar("A_ARG_TYPE_AVTransportID", "i4"), StateVar("A_ARG_TYPE_RcsID", "i4"),
))

private class Fault(val code: Int, val text: String) : Exception(text)

/** (action, {argument: value}) of a SOAP request; null if it isn't one (DOCTYPEs are refused by [parseXml]). */
internal fun soapCall(body: ByteArray): Pair<String, Map<String, String>>? {
    val root = runCatching { parseXml(body) }.getOrNull() ?: return null
    val soapBody = root.descendants("Body").firstOrNull() ?: return null
    var n = soapBody.firstChild
    while (n != null && n !is Element) n = n.nextSibling
    val call = n as? Element ?: return null
    val args = LinkedHashMap<String, String>()
    var c = call.firstChild
    while (c != null) {
        if (c is Element) args[c.localName ?: c.nodeName.substringAfter(':')] = c.textContent ?: ""
        c = c.nextSibling
    }
    return (call.localName ?: call.nodeName.substringAfter(':')) to args
}

/**
 * While the phone's screen is shared, OpusCast is also a small DLNA media server, "OpusCast on <this phone>":
 * TVs list it among their sources (Samsung: Source; LG, Philips and others: their media player) with the
 * shared screen in it. After leaving it on the TV (Back, another source...), the TV's own remote brings it
 * back - nothing to start again on the phone.
 *
 * Only the TV it's shared to sees the screen there (the folder is empty for anyone else on the network) and
 * gets answers to its searches and its event callbacks. Announced over SSDP every 50 s (TVs forget it two
 * minutes after the phone goes), described and browsed over the [MediaServer]'s port. Construct it off the
 * main thread (it looks up this phone's address towards the TV).
 */
class TvSource(
    private val server: MediaServer,
    private val tvHost: String,
    private val media: Media,
    /** What the TV lists, e.g. "OpusCast on Pixel 8". */
    val name: String,
    /** The same each time on this phone, so the TV knows it again. */
    val uuid: String,
    private val ssdpPort: Int = SSDP_PORT,
) : Closeable {
    val prefix = "/dms/$uuid"
    private val ip = server.addressFor(tvHost)
    val location = "http://$ip:${server.port}$prefix/description.xml"
    @Volatile var updateId = 1
        private set
    private val stopped = AtomicBoolean(false)
    private val send: MulticastSocket? = runCatching {
        MulticastSocket().apply {
            timeToLive = 2
            runCatching { networkInterface = NetworkInterface.getByInetAddress(InetAddress.getByName(ip)) }
        }
    }.getOrNull()
    /** The TV's searches go to port 1900, which other UPnP apps may use too (shared). Without it, the
     *  announcements alone still get OpusCast listed. */
    private val listen: MulticastSocket? = runCatching {
        MulticastSocket(null as java.net.SocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(ssdpPort))
            soTimeout = 500
            runCatching { // no multicast on this interface: searches sent straight here (UPnP 1.1) still get answers
                val nif = NetworkInterface.getByInetAddress(InetAddress.getByName(ip))
                joinGroup(InetSocketAddress(InetAddress.getByName(SSDP_ADDR), 0), nif)
            }
        }
    }.getOrNull()
    val answersSearches: Boolean get() = listen != null

    init {
        server.source = this
        thread(isDaemon = true, name = "opuscast-source") { run() }
    }

    /** Gone from the TV's sources (and its folder). */
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        if (server.source === this) server.source = null
        repeat(2) { notify("ssdp:byebye") }
        runCatching { send?.close() }
        runCatching { listen?.close() }
    }

    // SSDP

    private fun targets(): List<Pair<String, String>> {
        val u = "uuid:$uuid"
        return listOf("upnp:rootdevice" to "$u::upnp:rootdevice", u to u, MEDIA_SERVER to "$u::$MEDIA_SERVER",
            CONTENT_DIRECTORY to "$u::$CONTENT_DIRECTORY", CONNECTION_MANAGER to "$u::$CONNECTION_MANAGER")
    }

    private fun notify(nts: String) {
        val s = send ?: return
        val group = runCatching { InetSocketAddress(InetAddress.getByName(SSDP_ADDR), ssdpPort) }.getOrNull() ?: return
        for ((nt, usn) in targets()) {
            val msg = if (nts == "ssdp:alive") "NOTIFY * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\nCACHE-CONTROL: max-age=$LIFETIME\r\n" +
                "LOCATION: $location\r\nNT: $nt\r\nNTS: ssdp:alive\r\nSERVER: $SERVER_STRING\r\nUSN: $usn\r\n\r\n"
            else "NOTIFY * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\nNT: $nt\r\nNTS: $nts\r\nUSN: $usn\r\n\r\n"
            val bytes = msg.toByteArray(Charsets.ISO_8859_1)
            runCatching { s.send(DatagramPacket(bytes, bytes.size, group)) }
        }
    }

    private fun run() {
        repeat(3) { // UDP gets lost: say it a few times
            if (stopped.get()) return
            notify("ssdp:alive")
            Thread.sleep(500)
        }
        var last = System.nanoTime()
        val buf = ByteArray(4096)
        while (!stopped.get()) {
            if (System.nanoTime() - last >= 50_000_000_000L) {
                notify("ssdp:alive")
                last = System.nanoTime()
            }
            val l = listen
            if (l == null) {
                Thread.sleep(1000)
                continue
            }
            val p = DatagramPacket(buf, buf.size)
            try {
                l.receive(p)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (stopped.get()) return
                Thread.sleep(1000) // the socket broke: announcing goes on
                continue
            }
            runCatching { answer(String(p.data, 0, p.length, Charsets.ISO_8859_1), p.address, p.port, l) }
        }
    }

    /** Answer an M-SEARCH for a media server (or for everything) from the TV. */
    private fun answer(text: String, from: InetAddress, port: Int, socket: MulticastSocket) {
        if (from.hostAddress != tvHost) return
        val lines = text.split("\r\n")
        if (!lines[0].uppercase().startsWith("M-SEARCH")) return
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().uppercase()] = line.substring(i + 1).trim()
        }
        if (headers["MAN"]?.trim('"')?.lowercase() != "ssdp:discover") return
        val st = headers["ST"] ?: return
        for ((nt, usn) in targets()) {
            if (st != "ssdp:all" && st != nt) continue
            val msg = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=$LIFETIME\r\nDATE: ${httpDateNow()}\r\nEXT:\r\nLOCATION: $location\r\n" +
                "SERVER: $SERVER_STRING\r\nST: $nt\r\nUSN: $usn\r\nContent-Length: 0\r\n\r\n"
            val bytes = msg.toByteArray(Charsets.ISO_8859_1)
            socket.send(DatagramPacket(bytes, bytes.size, from, port))
        }
    }

    // HTTP: description, service descriptions, control, events

    /** A request under [prefix], from [client]. */
    fun handle(method: String, path: String, headers: Map<String, String>, body: ByteArray, client: String): Reply {
        val rest = path.removePrefix(prefix)
        return when (method) {
            "GET", "HEAD" -> when (rest) {
                "/description.xml" -> xml(200, "OK", description())
                "/cds.xml" -> xml(200, "OK", CDS_SCPD)
                "/cms.xml" -> xml(200, "OK", CMS_SCPD)
                else -> Reply(404, "Not Found", emptyList())
            }
            "POST" -> control(rest, body, client)
            "SUBSCRIBE", "UNSUBSCRIBE" -> subscribe(method, rest, headers, client)
            else -> Reply(405, "Method Not Allowed", emptyList())
        }
    }

    private fun xml(code: Int, reason: String, text: String) =
        Reply(code, reason, listOf("Content-Type" to "text/xml; charset=\"utf-8\"", "EXT" to ""), text.toByteArray(Charsets.UTF_8))

    private fun description(): String {
        val e = ::xmlEscape
        val services = listOf(Triple(CONTENT_DIRECTORY, "ContentDirectory", "cds"), Triple(CONNECTION_MANAGER, "ConnectionManager", "cms"))
            .joinToString("") { (type, id, short) ->
                "<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$id</serviceId>" +
                    "<SCPDURL>$prefix/$short.xml</SCPDURL><controlURL>$prefix/$short/control</controlURL>" +
                    "<eventSubURL>$prefix/$short/event</eventSubURL></service>"
            }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\" " +
            "xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><device>" +
            "<deviceType>$MEDIA_SERVER</deviceType><dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC><friendlyName>${e(name)}</friendlyName>" +
            "<manufacturer>OpusCast</manufacturer><manufacturerURL>https://github.com/m1omg/OpusCast</manufacturerURL>" +
            "<modelDescription>OpusCast screen sharing</modelDescription><modelName>OpusCast</modelName>" +
            "<modelNumber>${e(productVersion)}</modelNumber><serialNumber>${uuid.take(8)}</serialNumber><UDN>uuid:$uuid</UDN>" +
            "<serviceList>$services</serviceList></device></root>"
    }

    private fun control(rest: String, body: ByteArray, client: String): Reply {
        val service = when (rest) {
            "/cds/control" -> CONTENT_DIRECTORY
            "/cms/control" -> CONNECTION_MANAGER
            else -> return Reply(404, "Not Found", emptyList())
        }
        val (action, args) = soapCall(body) ?: return Reply(400, "Bad Request", emptyList())
        return try {
            val out = act(service, action, args, client)
            xml(200, "OK", SOAP_OPEN + "<u:${action}Response xmlns:u=\"$service\">" +
                out.joinToString("") { (k, v) -> "<$k>${xmlEscape(v)}</$k>" } + "</u:${action}Response>" + SOAP_CLOSE)
        } catch (f: Fault) {
            xml(500, "Internal Server Error", SOAP_OPEN + "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>" +
                "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>${f.code}</errorCode>" +
                "<errorDescription>${xmlEscape(f.text)}</errorDescription></UPnPError></detail></s:Fault>" + SOAP_CLOSE)
        }
    }

    private fun protocols(): String = linkedSetOf(media.mime, "video/mpeg", "video/mp2t").joinToString(",") { "http-get:*:$it:*" }

    private fun act(service: String, action: String, args: Map<String, String>, client: String): List<Pair<String, String>> {
        if (service == CONNECTION_MANAGER) return when (action) {
            "GetProtocolInfo" -> listOf("Source" to protocols(), "Sink" to "")
            "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
            "GetCurrentConnectionInfo" -> listOf("RcsID" to "-1", "AVTransportID" to "-1", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Output", "Status" to "OK")
            else -> throw Fault(401, "Invalid Action")
        }
        return when (action) {
            "Browse" -> browse(args, client)
            "GetSystemUpdateID" -> listOf("Id" to updateId.toString())
            "GetSearchCapabilities" -> listOf("SearchCaps" to "")
            "GetSortCapabilities" -> listOf("SortCaps" to "")
            "X_GetFeatureList" -> listOf("FeatureList" to SAMSUNG_FEATURES) // Samsung
            "X_SetBookmark" -> emptyList() // Samsung remembers where it stopped: nothing to remember in a live picture
            else -> throw Fault(401, "Invalid Action")
        }
    }

    private fun browse(args: Map<String, String>, client: String): List<Pair<String, String>> {
        val oid = args["ObjectID"] ?: ""
        val flag = args["BrowseFlag"] ?: ""
        val first = (args["StartingIndex"] ?: "").ifEmpty { "0" }.toIntOrNull() ?: throw Fault(402, "Invalid Args")
        (args["RequestedCount"] ?: "").ifEmpty { "0" }.toIntOrNull() ?: throw Fault(402, "Invalid Args")
        if (flag != "BrowseMetadata" && flag != "BrowseDirectChildren") throw Fault(402, "Invalid Args")
        val shared = client == tvHost // the screen is only for the TV it's shared to
        fun result(didl: String, returned: Int, total: Int) =
            listOf("Result" to didl, "NumberReturned" to returned.toString(), "TotalMatches" to total.toString(), "UpdateID" to updateId.toString())
        if (oid == "0") {
            if (flag == "BrowseMetadata") return result(DIDL_OPEN + "<container id=\"0\" parentID=\"-1\" restricted=\"1\" searchable=\"0\" " +
                "childCount=\"${if (shared) 1 else 0}\"><dc:title>OpusCast</dc:title><upnp:class>object.container.storageFolder</upnp:class>" +
                "</container></DIDL-Lite>", 1, 1)
            val shown = shared && first == 0
            return result(if (shown) item() else "$DIDL_OPEN</DIDL-Lite>", if (shown) 1 else 0, if (shared) 1 else 0)
        }
        if (oid == ITEM && shared) {
            val meta = flag == "BrowseMetadata"
            return result(if (meta) item() else "$DIDL_OPEN</DIDL-Lite>", if (meta) 1 else 0, if (meta) 1 else 0)
        }
        throw Fault(701, "No such object")
    }

    private fun item(): String = didl(server.url(media, tvHost), ItemInfo(media.title, media.mime, media.features, kind = MediaKind.LIVE),
        itemId = ITEM, parentId = "0")

    private fun subscribe(method: String, rest: String, headers: Map<String, String>, client: String): Reply {
        val service = when (rest) {
            "/cds/event" -> CONTENT_DIRECTORY
            "/cms/event" -> CONNECTION_MANAGER
            else -> return Reply(404, "Not Found", emptyList())
        }
        if (method == "UNSUBSCRIBE") return Reply(200, "OK", emptyList())
        val renewing = headers["sid"]
        val sid = renewing ?: "uuid:${UUID.randomUUID()}"
        val callback = Regex("""^\s*<([^>]+)>""").find(headers["callback"] ?: "")?.groupValues?.get(1)
        // events only go back to the TV itself (a callback elsewhere would make OpusCast a way to reach others)
        val back = callback?.let { runCatching { URI(it).host }.getOrNull() }
        if (renewing == null && callback != null && client == tvHost && back == client) {
            thread(isDaemon = true, name = "opuscast-source-event") { firstEvent(service, callback, sid) }
        }
        return Reply(200, "OK", listOf("SID" to sid, "TIMEOUT" to "Second-1800"))
    }

    private fun firstEvent(service: String, url: String, sid: String) {
        val props = if (service == CONTENT_DIRECTORY) listOf("SystemUpdateID" to updateId.toString())
            else listOf("SourceProtocolInfo" to protocols(), "SinkProtocolInfo" to "", "CurrentConnectionIDs" to "0")
        val body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
            props.joinToString("") { (k, v) -> "<e:property><$k>${xmlEscape(v)}</$k></e:property>" } + "</e:propertyset>").toByteArray()
        runCatching {
            rawHttpRequest(url, "NOTIFY", mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "NT" to "upnp:event",
                "NTS" to "upnp:propchange", "SID" to sid, "SEQ" to "0"), 3000, body)
        }
    }

    companion object {
        const val LIFETIME = 120
        const val ITEM = "screen"
    }
}

private fun httpDateNow(): String = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("GMT")
}.format(java.util.Date())

// ---------------------------------------------------------------- following the shared screen on the TV

/** Is the TV showing this live stream? Either its media renderer plays it, or the TV took it from OpusCast
 *  among its sources - then the renderer knows nothing of it, and only the stream shows it's being watched. */
fun showingLive(media: Media, state: String?, uri: String?): Boolean {
    if ((uri.isNullOrEmpty() || media.urlPath in uri) && state in setOf("PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING")) return true
    return media.live?.watched() == true
}

/**
 * Follows the shared screen on the TV (the same rules as the desktop app's). When the TV leaves it (Back,
 * another source...), sharing goes on: the TV can come back by itself (OpusCast among its sources), it can
 * be sent again from the phone, or - if wanted - it's brought back by itself once. Leaving again soon after
 * it came back means it: then it stays off. Nobody watching for 30 minutes ends it.
 */
class ShareWatch(private val media: Media, @Volatile var bringBack: Boolean = false,
                 private val showing: (String?, String?) -> Boolean = { st, uri -> showingLive(media, st, uri) }) {
    enum class Event { SHOWN, LEFT, BACK, SEND, FAILED, IDLE }

    var seen = false
        private set
    /** The TV left it (and the user was told). */
    var away = false
        private set
    /** When it was last sent to the TV again (seconds, [update]'s clock); null when not waiting for that. */
    var sentAt: Double? = null
        private set
    private var autoSent = false
    private var goneSince: Double? = null
    private var backAt: Double? = null

    /** One look at the TV ([state] and [uri] null when it didn't answer), at [now] seconds. */
    fun update(state: String?, uri: String?, now: Double = System.nanoTime() / 1e9): Event? {
        if (showing(state, uri)) {
            goneSince = null
            if (!seen) {
                seen = true
                backAt = now
                return Event.SHOWN
            }
            if (away || sentAt != null) {
                away = false
                sentAt = null
                autoSent = false
                backAt = now
                return Event.BACK
            }
            return null
        }
        if (!seen) return null // still starting: the start logic decides
        val gone = goneSince ?: now.also { goneSince = it }
        sentAt?.let { sent ->
            if (now - sent < GRACE) return null
            sentAt = null
            away = true
            return Event.FAILED
        }
        if (now - gone >= IDLE_LIMIT) return Event.IDLE
        if (!away && now - gone >= LEAVE_AFTER) {
            val deliberate = backAt?.let { gone - it < DELIBERATE } ?: false
            if (bringBack && !deliberate && !autoSent) {
                autoSent = true
                sentAt = now
                return Event.SEND
            }
            away = true
            return Event.LEFT
        }
        return null
    }

    /** It's on the TV: the start (the TV may have asked "Allow?" first) is over. */
    fun started(now: Double = System.nanoTime() / 1e9) {
        seen = true
        backAt = now
    }

    /** It was sent to the TV again (by hand): wait for the TV to take it. */
    fun sent(now: Double = System.nanoTime() / 1e9) {
        sentAt = now
    }

    /** Sending it again failed at once (the TV didn't answer). */
    fun sendFailed() {
        sentAt = null
        away = true
    }

    companion object {
        /** Seconds off the TV before it counts as left (TVs reconnect now and then). */
        const val LEAVE_AFTER = 3.0
        /** After sending it again: time for the TV to take it. */
        const val GRACE = 15.0
        /** Left again this soon after coming back: the viewer means it. */
        const val DELIBERATE = 20.0
        const val IDLE_LIMIT = 30 * 60.0
    }
}
