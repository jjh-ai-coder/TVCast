package com.tvcast.dlna.ssdp

import com.tvcast.dlna.NetInfo
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * SSDP 组播服务：NOTIFY alive 周期宣告（30s）、M-SEARCH 监听与 MX 随机延迟单播响应、
 * bybye 主动下线。LOCATION 由 locationProvider 现取——IP 变化后重宣告即指向新描述地址。
 */
class SsdpServer(
    private val udn: String,
    private val locationProvider: () -> String,
    private val serverString: String,
    private val log: (String) -> Unit = {},
) {

    private val groupAddress: InetAddress = InetAddress.getByName("239.255.255.250")
    private val groupPort = 1900

  @Volatile private var socket: MulticastSocket? = null
  @Volatile private var scheduler: ScheduledExecutorService? = null
  @Volatile private var running = false

    fun start() {
        running = true
        val sock = try {
            val s = MulticastSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(groupPort))
            val nif = NetInfo.multicastInterface()
            if (nif != null) {
                try {
                    s.networkInterface = nif
                } catch (_: Exception) {
                }
                try {
                    s.joinGroup(InetSocketAddress(groupAddress, groupPort), nif)
                } catch (e: Exception) {
                    log("SSDP joinGroup failed: $e")
                }
            } else {
                try {
                    @Suppress("DEPRECATION")
                    s.joinGroup(groupAddress)
                } catch (e: Exception) {
                    log("SSDP joinGroup(no iface) failed: $e")
                }
            }
            s
        } catch (e: Exception) {
            log("SSDP bind 1900 failed: $e")
            null
        }
        socket = sock
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "tvcast-ssdp").apply { isDaemon = true }
        }
        if (sock != null) {
            Thread({ receiveLoop(sock) }, "tvcast-ssdp-rx").apply { isDaemon = true }.start()
        }
        announceAlive()
        scheduler?.scheduleWithFixedDelay(
            { announceAlive() },
            SsdpMessages.ALIVE_PERIOD_SECONDS,
            SsdpMessages.ALIVE_PERIOD_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun messages(): SsdpMessages =
        SsdpMessages(udn, locationProvider(), serverString)

    fun announceAlive() {
        val s = socket ?: return
        for (message in messages().aliveMessages()) {
            multicast(s, message)
        }
    }

    fun announceByebye() {
        val s = socket ?: return
        for (message in messages().byebyeMessages()) {
            multicast(s, message)
        }
    }

    fun shutdown() {
        running = false
        scheduler?.shutdownNow()
        scheduler = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun multicast(sock: MulticastSocket, message: String) {
        try {
            val bytes = message.toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(bytes, bytes.size, groupAddress, groupPort))
        } catch (e: Exception) {
            log("SSDP send failed: ${e.message}")
        }
    }

    private fun receiveLoop(sock: MulticastSocket) {
        val buffer = ByteArray(4096)
        while (running && !sock.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: Exception) {
                break
            }
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val parsed = try {
                SsdpMessages.parse(text)
            } catch (_: Exception) {
                null
            } ?: continue
            if (!parsed.isMSearch) continue
            handleSearch(parsed, packet.address, packet.port)
        }
    }

    private fun handleSearch(parsed: SsdpMessages.ParsedSsdp, from: InetAddress, fromPort: Int) {
        val st = parsed.st ?: return
        val responses = messages().searchResponses(st)
        if (responses.isEmpty()) return
        // MX 随机延迟，避免网络风暴
        val delayMs = if (parsed.mx <= 0) 0 else Random.nextInt(parsed.mx * 1000 + 1)
        val sched = scheduler ?: return
        val sock = socket ?: return
        try {
            sched.schedule({
                for (response in responses) {
                    try {
                        val bytes = response.toByteArray(Charsets.UTF_8)
                        sock.send(DatagramPacket(bytes, bytes.size, from, fromPort))
                    } catch (e: Exception) {
                        log("SSDP M-SEARCH response failed: ${e.message}")
                    }
                }
            }, delayMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }
}
