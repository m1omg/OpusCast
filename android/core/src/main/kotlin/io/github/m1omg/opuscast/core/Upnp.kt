package io.github.m1omg.opuscast.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

class Service(val type: String, val control: String, val event: String?, val scpd: String?)

/** A UPnP device description (any kind of device). */
class DeviceInfo(
    val location: String,
    val host: String,
    val types: List<String>,
    val name: String,
    val manufacturer: String,
    val model: String,
    /** AVTransport / RenderingControl / ConnectionManager of the media renderer, if it is one. */
    val services: Map<String, Service>,
)

internal fun parseXml(bytes: ByteArray): Element {
    val f = DocumentBuilderFactory.newInstance()
    f.isNamespaceAware = true
    f.isExpandEntityReferences = false
    runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    return f.newDocumentBuilder().parse(bytes.inputStream()).documentElement
}

internal fun Element.descendants(local: String): List<Element> {
    val nl = getElementsByTagNameNS("*", local)
    return (0 until nl.length).map { nl.item(it) as Element }
}

/** Text of the first direct child called [local], trimmed. */
internal fun Element.childText(local: String): String? {
    var n: Node? = firstChild
    while (n != null) {
        if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == local) return n.textContent?.trim()
        n = n.nextSibling
    }
    return null
}

fun describe(location: String, timeoutMs: Int = 3000): DeviceInfo {
    val r = httpRequest(location, timeoutMs = timeoutMs)
    if (r.code != 200) throw IOException("HTTP ${r.code}")
    val root = try {
        parseXml(r.body)
    } catch (e: Exception) {
        throw IOException("bad device description", e)
    }
    val base = root.childText("URLBase")?.ifEmpty { null } ?: location
    val devices = root.descendants("device")
    val types = devices.map { it.childText("deviceType") ?: "" }
    val renderers = devices.filter { "MediaRenderer" in (it.childText("deviceType") ?: "") }
    val dev = renderers.firstOrNull() ?: devices.firstOrNull()
    val services = LinkedHashMap<String, Service>()
    if (dev != null && renderers.isNotEmpty()) {
        for (svc in dev.descendants("service")) {
            val stype = svc.childText("serviceType") ?: ""
            val ctl = svc.childText("controlURL") ?: ""
            val ev = svc.childText("eventSubURL") ?: ""
            val scpd = svc.childText("SCPDURL") ?: ""
            for (short in listOf("AVTransport", "RenderingControl", "ConnectionManager")) {
                if (":service:$short:" in stype && short !in services) {
                    services[short] = Service(stype, resolveUrl(base, ctl),
                        ev.ifEmpty { null }?.let { resolveUrl(base, it) }, scpd.ifEmpty { null }?.let { resolveUrl(base, it) })
                }
            }
        }
    }
    return DeviceInfo(location, hostOf(location), types, dev?.childText("friendlyName") ?: "",
        dev?.childText("manufacturer") ?: "", dev?.childText("modelName") ?: "", services)
}

/** One subtitle file the TV knows about (Samsung X_GetCaptionState). */
typealias CaptionEntry = Map<String, String>

private val CAPTION_INFO = Regex("""<captionFileInfo ([^>]*?)/?>""")
private val ATTR = Regex("""(\w+)="([^"]*)"""")

internal fun captionEntries(xml: String?): List<CaptionEntry> =
    CAPTION_INFO.findAll(xml ?: "").map { m ->
        ATTR.findAll(m.groupValues[1]).associate { it.groupValues[1] to xmlUnescape(it.groupValues[2]) }
    }.toList()

fun captionUris(xml: String?): List<String> = captionEntries(xml).mapNotNull { it["uri"] }

/** A DLNA media renderer (a TV). */
class Tv(info: DeviceInfo) {
    val location = info.location
    val host = info.host
    val name = info.name.ifEmpty { "TV" }
    val manufacturer = info.manufacturer
    val model = info.model
    val services = info.services
    private val actionCache = ConcurrentHashMap<String, Set<String>>()

    override fun toString() = "Tv($name at $host)"

    companion object {
        fun fromLocation(location: String, timeoutMs: Int = 3000): Tv {
            val info = describe(location, timeoutMs)
            if ("AVTransport" !in info.services) throw TvException(TvException.Reason.CANT_PLAY_MEDIA)
            return Tv(info)
        }
    }

    fun soap(service: String, action: String, args: List<Pair<String, Any>> = emptyList(), timeoutMs: Int = 6000): String {
        val svc = services[service] ?: throw TvException(TvException.Reason.UNSUPPORTED, service)
        val body = args.joinToString("") { (k, v) -> "<$k>${xmlEscape(v.toString())}</$k>" }
        val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"${svc.type}\">$body</u:$action></s:Body></s:Envelope>"
        val r = try {
            httpRequest(svc.control, "POST", envelope.toByteArray(Charsets.UTF_8), mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"${svc.type}#$action\""), timeoutMs)
        } catch (e: IOException) {
            throw TvException(TvException.Reason.UNREACHABLE, e.message ?: e.javaClass.simpleName, e)
        }
        val text = String(r.body, Charsets.UTF_8)
        if (r.code >= 400) {
            val detail = xmlTag(text, "errorDescription") ?: "HTTP ${r.code}"
            throw TvException(TvException.Reason.REFUSED, "$action: $detail")
        }
        return text
    }

    /** true / false from the TV's supported formats, null if it won't say. */
    fun playsVideo(): Boolean? {
        val sink = try {
            xmlTag(soap("ConnectionManager", "GetProtocolInfo", timeoutMs = 3000), "Sink") ?: ""
        } catch (e: TvException) {
            return null
        }
        return if (sink.isNotEmpty()) "video/" in sink else null
    }

    // transport
    fun setMedia(url: String, title: String, mime: String, size: Long, subUrl: String? = null) {
        soap("AVTransport", "SetAVTransportURI", listOf("InstanceID" to 0, "CurrentURI" to url,
            "CurrentURIMetaData" to didl(url, title, mime, size, subUrl)))
    }

    fun play() { soap("AVTransport", "Play", listOf("InstanceID" to 0, "Speed" to 1)) }
    fun pause() { soap("AVTransport", "Pause", listOf("InstanceID" to 0)) }
    fun stop(timeoutMs: Int = 6000) { soap("AVTransport", "Stop", listOf("InstanceID" to 0), timeoutMs) }

    fun seek(seconds: Double) {
        soap("AVTransport", "Seek", listOf("InstanceID" to 0, "Unit" to "REL_TIME", "Target" to upnpTime(seconds.toInt())))
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING or NO_MEDIA_PRESENT. */
    fun state(): String =
        xmlTag(soap("AVTransport", "GetTransportInfo", listOf("InstanceID" to 0), 4000), "CurrentTransportState") ?: "UNKNOWN"

    class Position(val pos: Int?, val duration: Int?, val uri: String?)

    fun position(): Position {
        val r = soap("AVTransport", "GetPositionInfo", listOf("InstanceID" to 0), 4000)
        return Position(parseTime(xmlTag(r, "RelTime")), parseTime(xmlTag(r, "TrackDuration")), xmlTag(r, "TrackURI"))
    }

    // volume, 0-100
    fun getVolume(): Int? =
        xmlTag(soap("RenderingControl", "GetVolume", listOf("InstanceID" to 0, "Channel" to "Master"), 4000),
            "CurrentVolume")?.toIntOrNull()

    fun setVolume(value: Int) {
        soap("RenderingControl", "SetVolume", listOf("InstanceID" to 0, "Channel" to "Master", "DesiredVolume" to value.coerceIn(0, 100)))
    }

    /** Names of the actions the TV offers for a service (read once from its SCPD). */
    fun actions(service: String): Set<String> {
        actionCache[service]?.let { return it }
        val url = services[service]?.scpd
        var names = emptySet<String>()
        if (url != null) {
            names = try {
                val r = httpRequest(url, timeoutMs = 4000)
                if (r.code != 200) return emptySet()
                parseXml(r.body).descendants("action").mapNotNull { it.childText("name") }.toSet()
            } catch (e: Exception) {
                return emptySet() // try again next time
            }
        }
        actionCache[service] = names
        return names
    }

    // subtitles (Samsung extension)
    fun canSwitchSubtitles() = "X_ControlCaption" in actions("RenderingControl")

    /** (available, enabled) subtitle files the TV knows about. */
    fun captionState(): Pair<List<CaptionEntry>, List<CaptionEntry>> {
        val r = soap("RenderingControl", "X_GetCaptionState", listOf("InstanceID" to 0), 4000)
        return captionEntries(xmlTag(r, "Captions")) to captionEntries(xmlTag(r, "EnabledCaptions"))
    }

    /** Fetch and show this subtitle file now. We only ever switch files - "off" is an empty file -
     *  because Operation=Disable also turns off the TV's own subtitle setting, which the phone can't
     *  turn back on (afterwards Enable is accepted but nothing is drawn). */
    fun showCaption(mediaUrl: String, captionUrl: String, name: String) {
        val resource = if (mediaUrl.startsWith("http")) "dlna" + mediaUrl.substring(4) else mediaUrl
        soap("RenderingControl", "X_ControlCaption", listOf("InstanceID" to 0, "Operation" to "Enable", "Name" to name,
            "ResourceURI" to resource, "CaptionURI" to captionUrl, "CaptionType" to "SRT", "Language" to "",
            "Encoding" to "UTF-8"))
    }

    // change notifications (GENA)
    /** Returns (SID, seconds until it must be renewed). */
    fun subscribe(service: String, callbackUrl: String, timeout: Int = 1800): Pair<String, Int> =
        gena(service, "SUBSCRIBE", mapOf("CALLBACK" to "<$callbackUrl>", "NT" to "upnp:event", "TIMEOUT" to "Second-$timeout"))

    fun renew(service: String, sid: String, timeout: Int = 1800): Pair<String, Int> =
        gena(service, "SUBSCRIBE", mapOf("SID" to sid, "TIMEOUT" to "Second-$timeout"))

    fun unsubscribe(service: String, sid: String) {
        try {
            gena(service, "UNSUBSCRIBE", mapOf("SID" to sid))
        } catch (e: TvException) {
            // gone already
        }
    }

    private fun gena(service: String, method: String, headers: Map<String, String>): Pair<String, Int> {
        val url = services[service]?.event ?: throw TvException(TvException.Reason.UNSUPPORTED, "$service events")
        val r = try {
            rawHttpRequest(url, method, headers)
        } catch (e: IOException) {
            throw TvException(TvException.Reason.UNREACHABLE, e.message ?: e.javaClass.simpleName, e)
        }
        if (r.code != 200) throw TvException(TvException.Reason.REFUSED, "$method: HTTP ${r.code}")
        val timeout = Regex("""(\d+)""").find(r.headers["timeout"] ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 300
        return (r.headers["sid"] ?: headers["SID"] ?: "") to timeout
    }
}

/** The DIDL-Lite metadata sent with the video. Subtitles are announced the Samsung (sec:), LG/Sony/
 *  others (pv:) and plain DLNA ways. */
internal fun didl(url: String, title: String, mime: String, size: Long, subUrl: String?): String {
    val e = ::xmlEscape
    var resExtra = ""
    var subCaption = ""
    if (subUrl != null) {
        resExtra = " pv:subtitleFileType=\"srt\" pv:subtitleFileUri=\"${e(subUrl)}\""
        subCaption = "<res protocolInfo=\"http-get:*:text/srt:*\">${e(subUrl)}</res>" +
            "<sec:CaptionInfoEx sec:type=\"srt\">${e(subUrl)}</sec:CaptionInfoEx>" +
            "<sec:CaptionInfo sec:type=\"srt\">${e(subUrl)}</sec:CaptionInfo>"
    }
    return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
        "xmlns:sec=\"http://www.sec.co.kr/\" xmlns:pv=\"http://www.pv.com/pvns/\">" +
        "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${e(title)}</dc:title>" +
        "<upnp:class>object.item.videoItem</upnp:class>" +
        "<res protocolInfo=\"http-get:*:$mime:$DLNA_FEATURES\" size=\"$size\"$resExtra>${e(url)}</res>" +
        "$subCaption</item></DIDL-Lite>"
}

/** A GENA NOTIFY body -> {variable: value} of what changed.
 *  {} means the TV said "something changed" without details; null means not a LastChange event. */
fun parseLastChange(body: String): Map<String, String>? {
    val root = try {
        parseXml(body.toByteArray(Charsets.UTF_8))
    } catch (e: Exception) {
        return null
    }
    val lc = root.descendants("LastChange").firstOrNull() ?: return null
    val text = lc.textContent?.trim() ?: ""
    if (text.isEmpty()) return emptyMap()
    val event = try {
        parseXml(text.toByteArray(Charsets.UTF_8))
    } catch (e: Exception) {
        return emptyMap()
    }
    val changes = LinkedHashMap<String, String>()
    for (inst in event.descendants("InstanceID")) {
        var n: Node? = inst.firstChild
        while (n != null) {
            if (n is Element) {
                val name = n.localName ?: n.nodeName.substringAfter(':')
                val channel = n.getAttribute("channel").ifEmpty { "Master" }
                if (!(name in setOf("Volume", "Mute") && channel != "Master")) changes[name] = n.getAttribute("val")
            }
            n = n.nextSibling
        }
    }
    return changes
}

/** Find DLNA renderers (TVs) on the LAN, sorted by name. */
fun discover(timeoutMs: Long = 3000, bindAddress: java.net.InetAddress? = null): List<Tv> =
    loadTvs(ssdpSearch(RENDERER, timeoutMs, bindAddress))

internal fun loadTvs(locations: List<String>): List<Tv> {
    val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
    try {
        val found = locations.map { loc -> pool.submit<Tv?> { runCatching { Tv.fromLocation(loc) }.getOrNull() } }
            .mapNotNull { it.get() }
        return found.associateBy { it.location }.values.sortedBy { it.name.lowercase() }
    } finally {
        pool.shutdown()
    }
}

/** Remembered location first (fast), then SSDP, then the Samsung default port. */
fun findTv(location: String? = null, host: String? = null, timeoutMs: Long = 3000,
           bindAddress: java.net.InetAddress? = null): Tv? {
    if (location != null) {
        runCatching { Tv.fromLocation(location, 2500) }.getOrNull()?.let { if (host == null || it.host == host) return it }
    }
    discover(timeoutMs, bindAddress).firstOrNull { host == null || it.host == host }?.let { return it }
    if (host != null) {
        runCatching { Tv.fromLocation("http://$host:9197/dmr", 2500) }.getOrNull()?.let { return it }
    }
    return null
}
