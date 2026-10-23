package com.tvcast.sender

import android.os.Handler
import android.os.Looper
import android.util.Log
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

  var device: DlnaDevice? = null
    private set
  val soap = SoapControlPoint { Log.d(TAG, it) }

  @Volatile var lastGenaState: GenaState = GenaState(transportState = null)
    private set

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

  private const val TAG = "TVCast/Sender"
}
