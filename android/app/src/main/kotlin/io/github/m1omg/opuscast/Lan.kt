package io.github.m1omg.opuscast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import io.github.m1omg.opuscast.core.localIpTowards
import java.net.Inet4Address
import java.net.InetAddress

/** The phone's place on the local network (Wi-Fi or Ethernet), as far as Android tells. */
data class LanInfo(val address: Inet4Address?, val prefix: Int, val gateway: String?) {
    val ip: String? get() = address?.hostAddress

    /** Is [host] on this network? Then the TV must be given this address, even when a VPN would route elsewhere. */
    fun contains(host: String): Boolean {
        val a = address ?: return false
        val h = runCatching { InetAddress.getByName(host) as? Inet4Address }.getOrNull() ?: return false
        val bits = prefix.coerceIn(8, 32)
        val mask = if (bits == 32) -1 else (-1 shl (32 - bits))
        fun Inet4Address.int() = address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        return (a.int() and mask) == (h.int() and mask)
    }

    /** The address the TV should use to reach this phone. Does network I/O: not on the main thread. */
    fun addressFor(tvHost: String): String = if (contains(tvHost)) ip!! else localIpTowards(tvHost)
}

object Lan {
    @Suppress("DEPRECATION")
    fun info(context: Context): LanInfo {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return LanInfo(null, 24, null)
        val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
        for (n in networks.distinct()) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            val link = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: continue
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            return LanInfo(link.address as Inet4Address, link.prefixLength, gw)
        }
        return LanInfo(null, 24, null)
    }

    /** A Wi-Fi multicast lock, held until released (to hear the TV's searches while the phone is among its sources). */
    fun multicastLock(context: Context, tag: String): WifiManager.MulticastLock? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        return runCatching { wifi.createMulticastLock(tag).apply { setReferenceCounted(false); acquire() } }.getOrNull()
    }

    /** Run [block] with a Wi-Fi multicast lock held (some phones drop SSDP traffic without one). */
    fun <T> withMulticast(context: Context, block: () -> T): T {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("opuscast-ssdp")?.apply { setReferenceCounted(false) }
        runCatching { lock?.acquire() }
        try {
            return block()
        } finally {
            runCatching { lock?.release() }
        }
    }
}
