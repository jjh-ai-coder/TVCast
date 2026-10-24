package com.tvcast.sender

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tvcast.dlna.cp.DeviceDescriptionParser
import com.tvcast.dlna.cp.DlnaDevice
import com.tvcast.dlna.cp.GenaState
import com.tvcast.dlna.cp.GenaSubscriber
import com.tvcast.dlna.cp.SoapControlPoint
import kotlin.concurrent.thread

/**
 * 投屏会话单例：当前设备 + SOAP 客户端 + GENA 订阅（状态实时回传）。
 * UI 通过 [stateListeners] 收 GENA 状态（播放/暂停/音量）。
 */
object CastSession {

  const val STATE_PLAYING = "PLAYING"
  const val STATE_PAUSED = "PAUSED_PLAYBACK"
  const val STATE_STOPPED = "STOPPED"

  /** 电视端粘性端口段（与 dlna DmrStack 的 61000 起步 + MiniHttpServer preferred..+20 对应） */
  private val STICKY_PORTS = 61000..61050

  var device: DlnaDevice? = null
    private set
  val soap = SoapControlPoint { Log.d(TAG, it) }

  @Volatile var lastGenaState: GenaState = GenaState(transportState = null)
    private set
  @Volatile private var reconnecting = false

  private var subscriber: GenaSubscriber? = null
  private val stateListeners = ArrayList<(GenaState) -> Unit>()
  private val main = Handler(Looper.getMainLooper())

  val connected: Boolean get() = device != null

  fun connect(target: DlnaDevice) {
    disconnect()
    device = target
    // 订阅含阻塞网络 IO，禁止在主线程执行（NetworkOnMainThreadException）
    thread(name = "cast-subscribe") {
      val eventUrl = target.avTransportEventUrl ?: return@thread
      val sub = GenaSubscriber(eventUrl, { state ->
        lastGenaState = state
        main.post {
          synchronized(stateListeners) {
            for (l in stateListeners) l(state)
          }
        }
      }, { Log.d(TAG, it) })
      if (sub.subscribe()) {
        subscriber = sub
      } else {
        // 订阅失败不阻塞投屏：轮询 GetTransportInfo 兜底
        Log.w(TAG, "GENA subscribe failed, fallback to polling only")
      }
    }
  }

  fun disconnect() {
    subscriber?.close()
    subscriber = null
    device = null
    lastGenaState = GenaState()
  }

  fun addStateListener(listener: (GenaState) -> Unit) {
    synchronized(stateListeners) { stateListeners += listener }
  }

  fun removeStateListener(listener: (GenaState) -> Unit) {
    synchronized(stateListeners) { stateListeners -= listener }
  }

  /** 推新媒体后清空缓存，让轮询接管状态显示 */
  fun resetGenaState() {
    lastGenaState = GenaState()
  }

  /**
   * 自动重连：电视端栈自愈/重启后端口可能变化（旧版电视端口是随机的），缓存地址
   * 全部失联。按 原端口 → 49152 起的粘性端口段 重取 dd.xml；拿到即重连（含重订阅）。
   * @return true 表示已恢复连接
   */
  @Synchronized
  fun reconnectAsync(onDone: (Boolean) -> Unit) {
    val stale = device ?: run { onDone(false); return }
    val host = Uri.parse(stale.locationUrl).host
    if (host == null || reconnecting) {
      onDone(false)
      return
    }
    reconnecting = true
    thread(name = "cast-reconnect") {
      var result: DlnaDevice? = null
      val originalPort = Uri.parse(stale.locationUrl).port.takeIf { it > 0 } ?: 80
      val ports = buildList {
        add(originalPort)
        addAll(STICKY_PORTS.filter { it != originalPort })
      }
      for (port in ports) {
        val candidate = try {
          DeviceDescriptionParser.fetchAndParse("http://$host:$port/dd.xml")
        } catch (_: Exception) {
          null
        }
        if (candidate != null && candidate.castable && candidate.udn == stale.udn) {
          result = candidate
          break
        }
      }
      main.post {
        reconnecting = false
        val found = result
        if (found != null) {
          Log.i(TAG, "reconnected to $host via ${Uri.parse(found.locationUrl).port}")
          connect(found)
        }
        onDone(found != null)
      }
    }
  }

  /** @return 设备主机地址（重连/展示用），未连接返回 null */
  fun host(): String? = device?.let { Uri.parse(it.locationUrl).host }

  private const val TAG = "TVCast/Sender"
}
