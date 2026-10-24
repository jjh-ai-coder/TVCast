package com.tvcast.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.tvcast.dlna.DmrStack
import com.tvcast.dlna.TransportState
import com.tvcast.playback.PlayerController
import com.tvcast.playback.SystemVolumeControl
import com.tvcast.ui.MainActivity
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 前台服务：DLNA 栈常驻编排。
 * - 持有 MulticastLock（不持锁 WiFi 上收不到 M-SEARCH 组播，手机永远搜不到电视）
 * - 播放状态变化 → 更新通知 + 唤起播放界面
 * - NetworkWatchdog 断网/换网 → DmrStack 重建重宣告
 */
class CastService : Service() {

  private val settings by lazy { Settings(this) }
  lateinit var playerController: PlayerController
    private set
  lateinit var mirrorReceiver: MirrorReceiver
    private set
  private lateinit var volumeControl: SystemVolumeControl
  private lateinit var stack: DmrStack
  private lateinit var watchdog: NetworkWatchdog
  private var multicastLock: WifiManager.MulticastLock? = null
  private val uiListeners = CopyOnWriteArrayList<Runnable>()
  private var lastLaunchedUri: String? = null

  val deviceName: String get() = settings.deviceName

  inner class LocalBinder : Binder() {
    val service: CastService get() = this@CastService
  }

  private val binder = LocalBinder()

  override fun onCreate() {
    super.onCreate()
    playerController = PlayerController(this)
    volumeControl = SystemVolumeControl(this)
    stack = DmrStack(
      renderer = playerController,
      volume = volumeControl,
      udn = settings.udn,
      friendlyName = settings.deviceName,
      serverString = "Android/${Build.VERSION.RELEASE} UPnP/1.0 TVCast/1.0",
      log = { Log.d(TAG, it) },
    )
    acquireMulticastLock()
    createNotificationChannel()
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } else {
      startForeground(NOTIFICATION_ID, buildNotification())
    }
    playerController.addListener(rendererListener)
    if (!stack.start()) {
      // 无网络：watchdog 挂着，网络恢复后由 onIpChanged 触发 start()
      Log.w(TAG, "no network at boot, waiting for NetworkWatchdog")
    }
    watchdog = NetworkWatchdog(this) { ip -> stack.onIpChanged(ip) }
    watchdog.start()
    // 屏幕镜像接收：常驻监听 UDP 9420，会话开始即唤起 UI。
    // 镜像属附加能力，任何异常不得拖垮 DLNA 主服务（真机教训：曾致投屏整体不可用）
    mirrorReceiver = try {
      MirrorReceiver(this) {
        notifyUi()
        if (mirrorReceiver.active) launchUi()
      }.also { it.start() }
    } catch (e: Exception) {
      Log.e(TAG, "mirror receiver init failed (DLNA continues)", e)
      MirrorReceiver(this) { } // 空壳占位：未 start，不绑端口，UI 判 active=false 即不启用
    }
  }

  private fun launchUi() {
    val intent = Intent(this, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    try {
      startActivity(intent)
    } catch (e: Exception) {
      Log.w(TAG, "launch MainActivity blocked: ${e.message}")
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

  override fun onBind(intent: Intent): IBinder = binder

  override fun onDestroy() {
    playerController.removeListener(rendererListener)
    try {
      mirrorReceiver.release()
    } catch (_: Exception) {
    }
    try {
      watchdog.stop()
    } catch (_: Exception) {
    }
    stack.shutdown()
    releaseMulticastLock()
    playerController.release()
    super.onDestroy()
  }

  // ---- UI 桥 ----

  fun addUiListener(listener: Runnable) {
    uiListeners += listener
  }

  fun removeUiListener(listener: Runnable) {
    uiListeners -= listener
  }

  /** 待机页展示：如 http://192.168.1.10:49152 */
  fun endpointText(): String? {
    val ip = stack.boundIp ?: return null
    return "http://$ip:${stack.httpPort}"
  }

  fun renameDevice(name: String) {
    settings.setDeviceName(name)
    stack.setFriendlyName(settings.deviceName)
    updateNotification()
    notifyUi()
  }

  fun setAutoStart(enabled: Boolean) {
    settings.autoStart = enabled
  }

  // ---- 内部 ----

  private val rendererListener = Runnable {
    updateNotification()
    maybeLaunchUi()
    notifyUi()
  }

  private fun maybeLaunchUi() {
    val uri = playerController.uri
    if (uri != null && uri != lastLaunchedUri && playerController.state != TransportState.STOPPED) {
      lastLaunchedUri = uri
      val intent = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      try {
        startActivity(intent)
      } catch (e: Exception) {
        Log.w(TAG, "launch MainActivity blocked: ${e.message}")
      }
    }
  }

  private fun notifyUi() {
    for (l in uiListeners) l.run()
  }

  private fun acquireMulticastLock() {
    try {
      val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      multicastLock = wifi?.createMulticastLock("tvcast:ssdp")?.apply {
        setReferenceCounted(false)
        acquire()
      }
    } catch (e: Exception) {
      Log.w(TAG, "MulticastLock acquire failed: ${e.message}")
    }
  }

  private fun releaseMulticastLock() {
    try {
      multicastLock?.takeIf { it.isHeld }?.release()
    } catch (_: Exception) {
    }
    multicastLock = null
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        getString(com.tvcast.R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      )
      (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
  }

  private fun buildNotification(): Notification {
    val launch = PendingIntent.getActivity(
      this, 0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0,
    )
    val playing = playerController.uri != null && playerController.state == TransportState.PLAYING
    val text = when {
      playing -> getString(com.tvcast.R.string.notification_playing, playerController.title ?: "")
      playerController.uri != null -> getString(com.tvcast.R.string.notification_connected)
      else -> getString(com.tvcast.R.string.notification_waiting)
    }
    val builder = if (Build.VERSION.SDK_INT >= 26) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }
    return builder
      .setSmallIcon(com.tvcast.R.drawable.ic_tv)
      .setContentTitle(settings.deviceName)
      .setContentText(text)
      .setContentIntent(launch)
      .setOngoing(true)
      .build()
  }

  private fun updateNotification() {
    try {
      (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .notify(NOTIFICATION_ID, buildNotification())
    } catch (_: Exception) {
    }
  }

  companion object {
    private const val TAG = "TVCast/CastService"
    private const val CHANNEL_ID = "tvcast_cast"
    private const val NOTIFICATION_ID = 1001

    fun start(context: Context) {
      val intent = Intent(context, CastService::class.java)
      if (Build.VERSION.SDK_INT >= 26) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }
}
