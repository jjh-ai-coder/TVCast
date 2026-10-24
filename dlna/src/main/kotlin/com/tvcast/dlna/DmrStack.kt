package com.tvcast.dlna

import com.tvcast.dlna.gena.EventBus
import com.tvcast.dlna.gena.GenaService
import com.tvcast.dlna.gena.LastChangeXml
import com.tvcast.dlna.gena.NotifySender
import com.tvcast.dlna.http.DescriptionServer
import com.tvcast.dlna.http.MiniHttpServer
import com.tvcast.dlna.soap.AvTransportService
import com.tvcast.dlna.soap.ConnectionManagerService
import com.tvcast.dlna.soap.RenderingControlService
import com.tvcast.dlna.soap.SoapDispatcher
import com.tvcast.dlna.ssdp.SsdpServer
import java.util.concurrent.Executor

/**
 * DMR 总编排：MiniHTTP(描述/SOAP/GENA 端点) + SSDP 宣告 + 播放状态 → LastChange 事件。
 * IP 变化：bybye → 重建描述 baseUrl（UDN 不变）→ 重宣告（spec §4.2/§4.3）。
 * dlna 包不依赖 Android API——本类可在 JVM 测试中用 enableSsdp=false 直接驱动。
 */
class DmrStack(
    private val renderer: RendererPlayer,
    volume: VolumeControl,
    private val udn: String,
    friendlyName: String,
    private val serverString: String,
    private val ipProvider: () -> String? = { NetInfo.pickLocalIp() },
    private val enableSsdp: Boolean = true,
    notifySender: NotifySender = com.tvcast.dlna.gena.HttpNotifySender(),
    notifyExecutor: Executor? = null,
    private val log: (String) -> Unit = {},
) {

    val device = DmrDevice(udn, friendlyName)
    val gena = EventBus(notifySender, notifyExecutor, log = log)

    private val avt = AvTransportService(renderer)
    private val soap = SoapDispatcher(
        listOf(
            avt,
            RenderingControlService(volume),
            ConnectionManagerService(),
        ),
    )
    private val httpServer = MiniHttpServer(DescriptionServer(device, soap, gena))
    private val volumeControl = volume

    private var ssdp: SsdpServer? = null

    var httpPort: Int = 0
        private set
    var boundIp: String? = null
        private set

    private var started = false
    private val rendererListener = Runnable {
        if (started) gena.notify(GenaService.AV_TRANSPORT) { LastChangeXml.avtSnapshot(renderer) }
    }
    private val volumeListener = Runnable {
        if (started) gena.notify(GenaService.RENDERING_CONTROL) { LastChangeXml.rcsSnapshot(volumeControl) }
    }

    /**
     * @param preferredHttpPort 上次成功绑定的端口（粘性端口）：控制点缓存 LOCATION，
     *   栈重建（健康自愈/网络恢复）必须沿用同一端口，否则手机端全部请求失联。
     *   0 = 首次启动，默认 61000——必须避开 Linux 临时端口范围（32768-60999），
     *   否则设备自己的出站连接会随机撞占粘性端口，重建时绑不上即漂移。
     * @return false 表示无可用网络
     */
    fun start(preferredHttpPort: Int = 0): Boolean {
        val ip = ipProvider()
        if (ip == null) {
            log("DmrStack: no local IP, start aborted")
            return false
        }
        httpPort = httpServer.start(preferredPort = if (preferredHttpPort > 0) preferredHttpPort else 61000)
        boundIp = ip
        device.baseUrl = "http://$ip:$httpPort"
        gena.setSnapshotProvider(GenaService.AV_TRANSPORT) { LastChangeXml.avtSnapshot(renderer) }
        gena.setSnapshotProvider(GenaService.RENDERING_CONTROL) { LastChangeXml.rcsSnapshot(volumeControl) }
        gena.setSnapshotProvider(GenaService.CONNECTION_MANAGER) { LastChangeXml.cmSnapshot() }
        gena.start()
        renderer.addListener(rendererListener)
        volumeControl.addListener(volumeListener)
        if (enableSsdp) {
            ssdp = SsdpServer(udn, { "${device.baseUrl}/dd.xml" }, serverString, log).also { it.start() }
        }
        started = true
        log("DmrStack started at $ip:$httpPort")
        return true
    }

    fun shutdown() {
        if (!started) return
        started = false
        renderer.removeListener(rendererListener)
        volumeControl.removeListener(volumeListener)
        ssdp?.announceByebye()
        ssdp?.shutdown()
        ssdp = null
        gena.stop()
        httpServer.stop()
        log("DmrStack stopped")
    }

    /**
     * 网络变化（NetworkWatchdog 回调）：
     * null=断网（bybye 停止宣告）；与当前相同=忽略；不同=bybye→重建→重宣告；未启动且有网=尝试启动。
     */
    fun onIpChanged(newIp: String?) {
        if (!started) {
            if (newIp != null) {
                log("DmrStack: network back, try start")
                start()
            }
            return
        }
        if (newIp == null) {
            ssdp?.announceByebye()
            boundIp = null
            log("DmrStack: network lost")
            return
        }
        if (newIp == boundIp) return
        ssdp?.announceByebye()
        boundIp = newIp
        device.baseUrl = "http://$newIp:$httpPort"
        ssdp?.announceAlive()
        log("DmrStack: IP changed -> $newIp:$httpPort, re-announced")
    }

    fun setFriendlyName(name: String) {
        device.friendlyName = name
    }

    /**
     * 自检（M-D）：栈声明 started 但 HTTP 端口已死（ROM 冻结/端口被杀/accept 线程崩）→
     * 返回 false，调用方应 shutdown 后重新 start。真机教训：TCL 上出现过
     * 进程活着但端口全失、重启 App 才恢复的死态。
     * 只做 TCP connect 探测：完整 HTTP 往返会被偶发的线程池繁忙误判成不健康，
     * 触发无谓的整栈重建（重启期间控制点全部失联）。
     */
    fun isHealthy(): Boolean {
        if (!started) return true // 未启动不算病（watchdog 会在有网时拉起）
        val ip = boundIp ?: return false
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(ip, httpPort), 2500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
