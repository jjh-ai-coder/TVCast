package com.tvcast.dlna.ssdp

import com.tvcast.dlna.DmrDevice

/**
 * SSDP 报文构造/解析（纯函数，JVM 可测）。
 * alive/bybye 需覆盖六元组 NT/USN：rootdevice、uuid、device 类型、3 个 service 类型。
 */
class SsdpMessages(
    private val udn: String,
    private val location: String,
    private val server: String,
) {

    private val deviceType = DmrDevice.DEVICE_TYPE
    private val serviceTypes = DmrDevice.SERVICES.map { it.type }

    private fun usn(nt: String): String =
        if (nt == udn) udn else "$udn::$nt"

    fun aliveMessages(): List<String> = targets().map { nt ->
        buildString {
            append("NOTIFY * HTTP/1.1\r\n")
            append("HOST: $MULTICAST_HOST\r\n")
            append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
            append("LOCATION: $location\r\n")
            append("NT: $nt\r\n")
            append("NTS: ssdp:alive\r\n")
            append("SERVER: $server\r\n")
            append("USN: ${usn(nt)}\r\n")
            append("\r\n")
        }
    }

    fun byebyeMessages(): List<String> = targets().map { nt ->
        buildString {
            append("NOTIFY * HTTP/1.1\r\n")
            append("HOST: $MULTICAST_HOST\r\n")
            append("NT: $nt\r\n")
            append("NTS: ssdp:byebye\r\n")
            append("USN: ${usn(nt)}\r\n")
            append("\r\n")
        }
    }

    /** M-SEARCH 响应：ssdp:all → 六条各自 ST；精确匹配 → 一条；无关 → 空 */
    fun searchResponses(st: String): List<String> {
        val wanted = st.trim().lowercase()
        val matched: List<String> = when {
            wanted == "ssdp:all" -> targets()
            wanted == "upnp:rootdevice" -> listOf("upnp:rootdevice")
            wanted == udn.lowercase() -> listOf(udn)
            wanted == deviceType.lowercase() -> listOf(deviceType)
            serviceTypes.any { it.lowercase() == wanted } -> serviceTypes.filter { it.lowercase() == wanted }
            else -> emptyList()
        }
        return matched.map { echoSt ->
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
                append("EXT:\r\n")
                append("LOCATION: $location\r\n")
                append("SERVER: $server\r\n")
                append("ST: $echoSt\r\n")
                append("USN: ${usn(echoSt)}\r\n")
                append("\r\n")
            }
        }
    }

    private fun targets(): List<String> =
        listOf("upnp:rootdevice", udn, deviceType) + serviceTypes

    companion object {
        const val MULTICAST_HOST = "239.255.255.250:1900"
        const val MAX_AGE = 1800
        const val ALIVE_PERIOD_SECONDS = 30L

        /** 返回首行 + 小写 key 的头映射；非 SSDP 报文返回 null */
        fun parse(packetText: String): ParsedSsdp? {
            val lines = packetText.split("\r\n", "\n")
            if (lines.isEmpty() || lines[0].isBlank()) return null
            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            return ParsedSsdp(lines[0].trim(), headers)
        }
    }

    data class ParsedSsdp(val startLine: String, val headers: Map<String, String>) {
        val isMSearch: Boolean get() = startLine.uppercase().startsWith("M-SEARCH")
        val st: String? get() = headers["st"]
        val mx: Int get() = headers["mx"]?.trim()?.toIntOrNull()?.coerceIn(0, 5) ?: 1
    }
}
