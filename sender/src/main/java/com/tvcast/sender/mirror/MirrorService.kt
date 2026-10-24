package com.tvcast.sender.mirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.tvcast.sender.R
import com.tvcast.transport.MirrorLinkConst
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * 屏幕镜像前台服务：MediaProjection 授权后采集屏幕 → MirrorEncoder 硬编 → MirrorSession 发 UDP。
 * - Android 14+ 要求先以 mediaProjection 类型 startForeground 再拿 projection
 * - 旋转重建：分辨率/宽高比变了就重建编码器与 VirtualDisplay（发出新 CSD+关键帧，接收端自适应）
 * - 运行状态写入伴生字段，MirrorActivity 轮询展示
 */
class MirrorService : Service() {

  companion object {
    const val ACTION_START = "com.tvcast.sender.mirror.START"
    const val ACTION_STOP = "com.tvcast.sender.mirror.STOP"
    const val EXTRA_HOST = "host"
    const val EXTRA_PORT = "port"
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_PROJECTION_DATA = "projection_data"
    private const val NOTIF_ID = 2001
    private const val CHANNEL_ID = "mirror"
    private const val TAG = "TVCast/MirrorSvc"

    /** 运行状态（UI 轮询显示） */
    @Volatile var running = false
      private set
    @Volatile var encodeWidth = 0
    @Volatile var encodeHeight = 0
    @Volatile var fps = 0
    @Volatile var bitrateMbps = 8
    @Volatile var sentFrames = 0L
    @Volatile var sentBytes = 0L
    @Volatile var lastError: String? = null
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  /** 会话标识：接收端据此区分"继续投屏"与"新会话重启" */
  private val sessionId = java.util.Random().nextInt()
  private var projection: MediaProjection? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var encoder: MirrorEncoder? = null
  private var session: MirrorSession? = null
  private var displayListener: DisplayManager.DisplayListener? = null
  private var lastRotation = -1
  private val framesThisSecond = AtomicInteger()
  private var zeroFpsSeconds = 0

  /** 目标上限 1080p 量级（宽高对齐 2，绝大多数 AVC 编码器的要求） */
  private fun encodeSize(srcW: Int, srcH: Int, maxW: Int = 1920, maxH: Int = 1088): Pair<Int, Int> {
    val scale = min(1f, min(maxW.toFloat() / srcW, maxH.toFloat() / srcH))
    fun align2(v: Float): Int = ((v.toInt() / 2) * 2).coerceAtLeast(2)
    return align2(srcW * scale) to align2(srcH * scale)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        // 停止请求可能经 startForegroundService 投递（Activity 按钮/通知栏），
        // Android 8+ 要求该路径必须先 startForeground，否则抛异常；随即自清理
        startInForeground()
        stopMirror()
        stopSelf()
      }
      ACTION_START -> {
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, MirrorLinkConst.DEFAULT_PORT)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        if (host.isNullOrBlank() || data == null) {
          lastError = "missing host or projection grant"
          stopSelf()
          return START_NOT_STICKY
        }
        lastError = null // 新尝试清掉旧错误
        startInForeground()
        stopMirror() // 防御：上会话未完全停止时先彻底回收（projection/display/encoder）
        startMirror(host, port, resultCode, data)
      }
    }
    return START_NOT_STICKY
  }

  private fun startInForeground() {
    val nm = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, getString(R.string.mirror_notif_channel), NotificationManager.IMPORTANCE_LOW)
      )
    }
    val stopIntent = PendingIntent.getService(
      this, 1,
      Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val builder = if (Build.VERSION.SDK_INT >= 26) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(this)
    }
    val notification = builder
      .setContentTitle(getString(R.string.mirror_notif_title))
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setOngoing(true)
      .addAction(Notification.Action.Builder(null, getString(R.string.mirror_stop), stopIntent).build())
      .build()
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    } else {
      startForeground(NOTIF_ID, notification)
    }
  }

  private fun friendlyError(e: Exception): String = when {
    e is SecurityException && e.message?.contains("resultData") == true ->
      "屏幕录制授权已失效（系统限制一次授权只允许一路采集），请重新点击\"开始镜像\""
    e is SecurityException ->
      "屏幕录制被系统拒绝，请重新授权"
    else -> e.message ?: e.javaClass.simpleName
  }

  private fun startMirror(host: String, port: Int, resultCode: Int, data: Intent) {
    try {
      val manager = getSystemService(MediaProjectionManager::class.java)
      projection = manager.getMediaProjection(resultCode, data).also { p ->
        p.registerCallback(object : MediaProjection.Callback() {
          override fun onStop() {
            Log.i(TAG, "projection stopped (user revoked or system)")
            mainHandler.post { stopMirror(); stopSelf() }
          }
        }, mainHandler)
      }
      val s = MirrorSession(
        remote = InetSocketAddress(host, port),
        sessionId = sessionId,
        onBitrateMbps = { mbps ->
          bitrateMbps = mbps
          encoder?.setBitrate(mbps * 1_000_000)
        },
        onSecond = { nacks ->
          fps = framesThisSecond.getAndSet(0)
          // 自愈（M-D）：旋转/系统抽风后 display 可能永久停供帧（真机 Android 9 复现：
          // fps=0 永不恢复）。连续 8 秒零帧 → 重挂 Surface 强踢内容通路；仍无帧则继续重试。
          if (fps == 0) zeroFpsSeconds++ else zeroFpsSeconds = 0
          if (zeroFpsSeconds == 8 || (zeroFpsSeconds > 8 && zeroFpsSeconds % 10 == 0)) {
            Log.i(TAG, "no frames for ${zeroFpsSeconds}s, kicking display surface")
            kickDisplay()
          }
          Log.d(TAG, "stats fps=$fps bitrate=${bitrateMbps}Mbps frames=$sentFrames nacks=$nacks")
        },
        onBye = {
          Log.i(TAG, "receiver sent BYE (addr=${session?.link?.remoteAddress()})")
          mainHandler.post { stopMirror(); stopSelf() }
        },
      )
      s.start()
      session = s
      bitrateMbps = s.currentBitrateMbps
      openDisplayAndEncoder()
      registerRotationWatch()
      running = true
      Log.i(TAG, "mirror started -> $host:$port at ${encodeWidth}x${encodeHeight}")
    } catch (e: Exception) {
      Log.e(TAG, "startMirror failed", e)
      lastError = e.message ?: e.javaClass.simpleName
      stopMirror()
      stopSelf()
    }
  }

  private fun realMetrics(): DisplayMetrics {
    val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") display.getRealMetrics(metrics)
    return metrics
  }

  /** 停帧自愈：把当前编码器 Surface 重新挂到 display 上，触发 SurfaceFlinger 重启内容通路 */
  private fun kickDisplay() {
    val display = virtualDisplay ?: return
    val surface = encoder?.inputSurface ?: return
    try {
      display.setSurface(null)
      Thread.sleep(50)
      display.setSurface(surface)
      Log.i(TAG, "display surface re-attached")
    } catch (e: Exception) {
      Log.w(TAG, "kickDisplay failed: ${e.message}")
    }
  }

  /**
   * 开显示与编码器。VirtualDisplay 全生命周期只创建一次（Android 14+/多数厂商 ROM
   * 一次授权只允许建一个，二次 createVirtualDisplay 直接抛异常——旋转重建必须复用，
   * 用 resize + setSurface 切到新编码器 Surface）。
   */
  private fun openDisplayAndEncoder() {
    val projection = this.projection ?: return
    val metrics = realMetrics()
    val (w, h) = encodeSize(metrics.widthPixels, metrics.heightPixels)
    encodeWidth = w
    encodeHeight = h
    val oldEncoder = encoder
    val enc = MirrorEncoder(
      width = w,
      height = h,
      initialBitrateBps = (session?.currentBitrateMbps ?: 8) * 1_000_000,
      onFrame = { tsMs, keyframe, bytes ->
        val s = session ?: return@MirrorEncoder
        sentFrames++
        sentBytes += bytes.size
        framesThisSecond.incrementAndGet()
        s.send(tsMs, keyframe, bytes)
      },
      onError = { e ->
        lastError = e.message ?: e.javaClass.simpleName
        mainHandler.post { stopMirror(); stopSelf() }
      },
    )
    try {
      enc.start()
      val display = virtualDisplay
      if (display != null) {
        // 旋转/分辨率变化：复用 display（Android 14+ 一次授权只允许一个 VirtualDisplay）。
        // 兼容性：先 setSurface 后 resize——Android 9 真机上"先 resize 后 setSurface"
        // 会出现 display 不再供帧的死态（M-D 真机验证复现：fps=0 永久停帧），
        // 先挂上新 Surface 再改尺寸让 SurfaceFlinger 重启内容通路。
        display.setSurface(enc.inputSurface)
        display.resize(w, h, metrics.densityDpi)
        encoder = enc
        session?.updateVideoInfo(w, h, 30)
        Log.i(TAG, "display reused at ${w}x$h")
        // 旧编码器后台释放（release 内 join 排空线程最长 1s，主线程做会卡旋转）
        if (oldEncoder != null) {
          Thread({ oldEncoder.release() }, "tvcast-enc-release").apply {
            isDaemon = true
            start()
          }
        }
      } else {
        virtualDisplay = projection.createVirtualDisplay(
          "TVCastMirror", w, h, metrics.densityDpi,
          DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
          enc.inputSurface, null, mainHandler,
        ) ?: throw IllegalStateException("createVirtualDisplay returned null")
        encoder = enc
        session?.updateVideoInfo(w, h, 30)
      }
    } catch (e: Exception) {
      Log.e(TAG, "open display/encoder failed at ${w}x$h", e)
      enc.release()
      if (virtualDisplay == null && oldEncoder == null) {
        // 会话起步阶段失败：无链路可保留，干净停止并给出可读错误
        lastError = friendlyError(e)
        mainHandler.post { stopMirror(); stopSelf() }
      } else {
        // 旋转重建失败：保留旧编码器/display 继续推流（旧分辨率），等下次旋转事件重试
        Log.w(TAG, "rebuild failed, keeping old encoder stream")
      }
    }
  }

  private fun closeDisplayAndEncoder() {
    virtualDisplay?.release()
    virtualDisplay = null
    encoder?.release()
    encoder = null
  }

  private fun registerRotationWatch() {
    val dm = getSystemService(DisplayManager::class.java)
    val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
    lastRotation = display.rotation
    val listener = object : DisplayManager.DisplayListener {
      override fun onDisplayChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY) return
        val rotation = display.rotation
        if (rotation != lastRotation) {
          lastRotation = rotation
          mainHandler.removeCallbacks(recreateRunnable)
          mainHandler.postDelayed(recreateRunnable, 500)
        }
      }

      override fun onDisplayAdded(displayId: Int) {}
      override fun onDisplayRemoved(displayId: Int) {}
    }
    displayListener = listener
    dm.registerDisplayListener(listener, mainHandler)
  }

  private val recreateRunnable = Runnable {
    if (!running) return@Runnable
    Log.i(TAG, "rotation changed, rebuilding encoder")
    // 不释放 VirtualDisplay（Android 14+/厂商 ROM 一次授权只允许建一个）：
    // openDisplayAndEncoder 内部对已存在的 display 走 setSurface+resize 复用
    openDisplayAndEncoder()
  }

  private fun stopMirror() {
    running = false
    fps = 0
    mainHandler.removeCallbacks(recreateRunnable)
    displayListener?.let {
      try { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) } catch (_: Exception) {}
    }
    displayListener = null
    closeDisplayAndEncoder()
    session?.stop()
    session = null
    projection?.stop()
    projection = null
  }

  override fun onDestroy() {
    stopMirror()
    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    super.onDestroy()
  }
}
