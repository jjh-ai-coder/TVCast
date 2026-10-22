package com.tvcast.dlna

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** 本机网络信息：优先 WiFi/有线 IPv4，避开回环与蜂窝 */
object NetInfo {

    data class Candidate(val host: String, val score: Int, val iface: NetworkInterface)

    fun pickLocalIp(): String? = candidates().minByOrNull { it.score }?.host

    fun bestInterface(): NetworkInterface? = candidates().minByOrNull { it.score }?.iface

    /** 支持组播的候选网卡 */
    fun multicastInterface(): NetworkInterface? {
        val all = candidates()
        val preferred = all.minByOrNull { it.score }?.iface
        if (preferred != null && supportsMulticastQuiet(preferred)) return preferred
        return all.map { it.iface }.firstOrNull { supportsMulticastQuiet(it) }
    }

    private fun supportsMulticastQuiet(nif: NetworkInterface): Boolean = try {
        nif.isUp && !nif.isLoopback && nif.supportsMulticast()
    } catch (_: Exception) {
        false
    }

    private fun candidates(): List<Candidate> {
        val result = ArrayList<Candidate>()
        val enums = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: Exception) {
            return result
        } ?: return result
        for (nif in enums) {
            val up = try {
                nif.isUp && !nif.isLoopback
            } catch (_: Exception) {
                false
            }
            if (!up) continue
            val name = nif.name.lowercase()
            val score = when {
                name.contains("wlan") || name.contains("eth") || name.contains("atb") -> 0
                name.contains("rmnet") || name.contains("ccmni") || name.contains("usb") -> 2
                else -> 1
            }
            for (ia in nif.interfaceAddresses) {
                val addr = ia.address ?: continue
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("127.")) continue
                    result += Candidate(host, score, nif)
                }
            }
        }
        return result
    }

    fun interfaceForIp(ip: String): NetworkInterface? = try {
        NetworkInterface.getByInetAddress(InetAddress.getByName(ip))
    } catch (_: Exception) {
        null
    }
}
