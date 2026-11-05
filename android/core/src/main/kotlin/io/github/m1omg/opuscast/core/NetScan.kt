package io.github.m1omg.opuscast.core

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A device seen on the network, and what it can play. */
data class NetDevice(
    val ip: String,
    val name: String?,
    /** Maker and model, or the host name. */
    val detail: String,
    val kind: Kind,
    val note: Note,
    val tv: Tv?,
) {
    /** It can be picked: a TV, or a speaker (music only). */
    val canPlay get() = tv != null && (kind == Kind.TV || kind == Kind.SPEAKER)
    val musicOnly get() = kind == Kind.SPEAKER
    enum class Kind { TV, SPEAKER, ROUTER, UPNP, UNKNOWN, THIS_DEVICE }
    enum class Note {
        CAN_PLAY_VIDEO, AUDIO_ONLY, ROUTER, MEDIA_SERVER, NETWORK_DEVICE, NO_CASTING, THIS_DEVICE,
        /** A Philips TV whose DLNA renderer ("Digital Media Renderer - DMR") is switched off. */
        DMR_OFF,
        /** Chromecast, a Google TV streamer, an Nvidia Shield: Google Cast only, no DLNA. */
        CAST_ONLY,
    }
}

/** Why a UPnP device that can't play anything still matters: a TV with DLNA off, or a Google Cast device. */
internal fun hintFor(devices: List<DeviceInfo>): NetDevice.Note? {
    val text = devices.joinToString(" ") { "${it.manufacturer} ${it.model} ${it.name}" }.lowercase()
    if ("philips" in text || "tp vision" in text) return NetDevice.Note.DMR_OFF
    val dial = devices.any { d -> d.types.any { "dial-multiscreen" in it.lowercase() } }
    val cast = listOf("google", "chromecast", "nvidia", "shield").any { it in text }
    return if (dial && cast) NetDevice.Note.CAST_ONLY else null
}

/** Addresses on [myIp]'s /24 (or smaller) network that answer, found by trying to connect to each. */
fun probeHosts(myIp: String, prefix: Int, timeoutMs: Int = 400): Set<String> {
    val me = InetAddress.getByName(myIp) as? Inet4Address ?: return emptySet()
    val bits = maxOf(prefix, 24)
    val ip = me.address.fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) }
    val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
    val net = ip and mask
    val count = (1L shl (32 - bits)) - 2
    val found = ConcurrentHashMap.newKeySet<String>()
    val pool = Executors.newFixedThreadPool(48)
    for (i in 1..count) {
        val addr = net + i
        if (addr == ip) continue
        pool.execute {
            val a = InetAddress.getByAddress(byteArrayOf((addr shr 24).toByte(), (addr shr 16).toByte(), (addr shr 8).toByte(), addr.toByte()))
            if (runCatching { a.isReachable(timeoutMs) }.getOrDefault(false)) a.hostAddress?.let { found += it }
        }
    }
    pool.shutdown()
    pool.awaitTermination(timeoutMs * 8L + 2000, TimeUnit.MILLISECONDS)
    pool.shutdownNow()
    return found
}

/** Reverse-DNS names (first label only) for [hosts], whatever answers within [timeoutMs]. */
fun reverseDns(hosts: Collection<String>, timeoutMs: Long): Map<String, String> {
    val names = ConcurrentHashMap<String, String>()
    val threads = hosts.map { h ->
        Thread {
            val name = runCatching { InetAddress.getByName(h).canonicalHostName }.getOrNull()
            if (name != null && !name.startsWith("_") && name != h) names[h] = name.substringBefore('.')
        }.apply { isDaemon = true; start() }
    }
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    for (t in threads) t.join(maxOf(1L, (deadline - System.nanoTime()) / 1_000_000))
    return HashMap(names)
}

/** Every device we can see: UPnP devices that answer SSDP, plus [otherHosts] (e.g. from [probeHosts]).
 *  [myIp] is this phone, [gateway] the router. Sorted TVs first. */
fun scanNetwork(myIp: String?, gateway: String?, otherHosts: Set<String> = emptySet(), timeoutMs: Long = 3000,
                bindAddress: InetAddress? = null, thisDeviceName: String = ""): List<NetDevice> {
    val locations = ssdpSearch("ssdp:all", timeoutMs, bindAddress)
    val pool = Executors.newFixedThreadPool(8)
    val descs = try {
        locations.map { loc -> pool.submit<DeviceInfo?> { runCatching { describe(loc) }.getOrNull() } }.mapNotNull { it.get() }
    } finally {
        pool.shutdown()
    }
    val hosts = LinkedHashSet<String>()
    descs.forEach { hosts += it.host }
    hosts += otherHosts
    gateway?.let { hosts += it }
    myIp?.let { hosts += it }
    val hostnames = reverseDns(hosts.filter { it != myIp }, 1500)
    val renderers = descs.filter { "AVTransport" in it.services }.distinctBy { it.host }
    val videoCheck = Executors.newFixedThreadPool(4)
    val tvs = renderers.associate { it.host to Tv(it) } // one each, so the format list asked here stays with it
    val plays = try {
        tvs.mapValues { (_, tv) -> videoCheck.submit<Boolean?> { tv.playsVideo() } }.mapValues { it.value.get() }
    } finally {
        videoCheck.shutdown()
    }
    val out = ArrayList<NetDevice>()
    for (h in hosts) {
        val mine = descs.filter { it.host == h }
        val renderer = mine.firstOrNull { "AVTransport" in it.services }
        val dns = hostnames[h]
        out += when {
            h == myIp -> NetDevice(h, null, thisDeviceName, NetDevice.Kind.THIS_DEVICE, NetDevice.Note.THIS_DEVICE, null)
            renderer != null -> {
                val tv = tvs[h] ?: Tv(renderer)
                val detail = listOf(tv.manufacturer, tv.model).filter { it.isNotEmpty() }.joinToString(" ")
                if (plays[h] == false) NetDevice(h, tv.name, detail, NetDevice.Kind.SPEAKER, NetDevice.Note.AUDIO_ONLY, tv)
                else NetDevice(h, tv.name, detail, NetDevice.Kind.TV, NetDevice.Note.CAN_PLAY_VIDEO, tv)
            }
            h == gateway || mine.any { d -> d.types.any { "InternetGatewayDevice" in it } } -> {
                val named = mine.firstOrNull { it.name.isNotEmpty() }
                NetDevice(h, named?.name ?: dns, named?.manufacturer ?: "", NetDevice.Kind.ROUTER, NetDevice.Note.ROUTER, null)
            }
            mine.isNotEmpty() -> {
                val d = mine.firstOrNull { it.name.isNotEmpty() } ?: mine[0]
                val server = d.types.any { "MediaServer" in it }
                val hint = hintFor(mine)
                NetDevice(h, d.name.ifEmpty { dns }, listOf(d.manufacturer, d.model).filter { it.isNotEmpty() }.joinToString(" "),
                    NetDevice.Kind.UPNP, hint ?: if (server) NetDevice.Note.MEDIA_SERVER else NetDevice.Note.NETWORK_DEVICE, null)
            }
            else -> NetDevice(h, dns, "", NetDevice.Kind.UNKNOWN, NetDevice.Note.NO_CASTING, null)
        }
    }
    return out.map { if (it.detail == it.name) it.copy(detail = "") else it }
        .sortedWith(compareBy<NetDevice> { it.kind.ordinal }.thenBy { ipKey(it.ip) })
}

private fun ipKey(ip: String): Long =
    ip.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }?.fold(0L) { a, b -> a * 256 + b } ?: Long.MAX_VALUE
