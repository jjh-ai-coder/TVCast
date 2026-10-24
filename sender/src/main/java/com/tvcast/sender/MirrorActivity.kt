package com.tvcast.sender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tvcast.sender.mirror.MirrorService
import com.tvcast.transport.MirrorLinkConst

/**
 * 屏幕镜像页：输入 TV 端地址 → 系统授权屏幕录制 → 启动 [MirrorService]。
 * 空闲/运行/错误三态：运行中显示 2×2 统计网格（分辨率/帧率/码率/累计帧）。
 *
 * 调试/自动化入口：adb am start --es debug_mirror_target 10.0.2.2:9420（自动发起授权）
 */
class MirrorActivity : AppCompatActivity() {

  private val main = Handler(Looper.getMainLooper())
  private lateinit var targetEdit: EditText
  private lateinit var statusText: TextView
  private lateinit var startButton: com.google.android.material.button.MaterialButton
  private lateinit var stopButton: com.google.android.material.button.MaterialButton
  private lateinit var statsGrid: View
  private var pendingTarget: Pair<String, Int>? = null

  private val statusLoop = object : Runnable {
    override fun run() {
      renderStatus()
      main.postDelayed(this, 1000)
    }
  }

  private val projectionLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
      val target = pendingTarget
      pendingTarget = null
      if (res.resultCode == Activity.RESULT_OK && res.data != null && target != null) {
        startMirrorService(res.resultCode, res.data!!, target)
      } else {
        statusText.text = getString(R.string.mirror_denied)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.error))
      }
    }

  private val notifPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {
      // 通知权限只是让前台通知可见，与镜像功能本身无关：无论结果都继续走授权流程
      requestProjectionIntent()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_mirror)
    targetEdit = findViewById(R.id.mirror_target)
    statusText = findViewById(R.id.mirror_status)
    startButton = findViewById(R.id.btn_mirror_start)
    stopButton = findViewById(R.id.btn_mirror_stop)
    statsGrid = findViewById(R.id.stats_grid)

    // 预填优先级：已连接设备的 IP > 上次手输地址（镜像端口与 DLNA 端口无关，只取主机地址）
    CastSession.device?.locationUrl?.let { url ->
      Uri.parse(url).host?.let { host ->
        if (targetEdit.text.isNullOrBlank()) targetEdit.setText(host)
      }
    }
    if (targetEdit.text.isNullOrBlank()) {
      prefs.getString(KEY_LAST_TARGET, null)?.let { targetEdit.setText(it) }
    }
    startButton.setOnClickListener { requestProjection() }
    // 图标统一代码设置（XML android:icon 在 MaterialButton 自动 inflation 下不稳定）
    startButton.setIconResource(R.drawable.ic_cast)
    stopButton.setIconResource(R.drawable.ic_stop)
    stopButton.setOnClickListener {
      ContextCompat.startForegroundService(
        this, Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
    }
    // 调试/自动化：带 target 参数自动发起授权弹窗
    intent?.getStringExtra(EXTRA_DEBUG_TARGET)?.let { raw ->
      targetEdit.setText(raw)
      main.postDelayed({ if (!MirrorService.running) requestProjection() }, 500)
    }
  }

  override fun onResume() {
    super.onResume()
    statusLoop.run()
  }

  override fun onPause() {
    super.onPause()
    main.removeCallbacks(statusLoop)
  }

  /** Android 13+ 先请求通知权限（前台服务通知可见性），随后进入屏幕录制授权 */
  private fun requestProjection() {
    val target = parseTarget(targetEdit.text?.toString()?.trim().orEmpty())
    if (target == null) {
      statusText.text = getString(R.string.mirror_bad_target)
      statusText.setTextColor(ContextCompat.getColor(this, R.color.error))
      return
    }
    prefs.edit().putString(KEY_LAST_TARGET, targetEdit.text?.toString()?.trim()).apply()
    pendingTarget = target
    if (
      Build.VERSION.SDK_INT >= 33 &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
      !prefs.getBoolean(KEY_NOTIF_ASKED, false) &&
      intent?.getStringExtra(EXTRA_DEBUG_TARGET) == null // 自动化路径不打断授权流程
    ) {
      prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
      notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      return
    }
    requestProjectionIntent()
  }

  private fun requestProjectionIntent() {
    val manager = getSystemService(MediaProjectionManager::class.java)
    // Android 14+ 默认弹"单个应用"选择器：投的是单个 App（如 TVCast 自己），切到别的
    // 界面内容即停止 → 电视超时断开（表现为"切界面就断"）。强制整屏模式，杜绝误选。
    val intent = if (Build.VERSION.SDK_INT >= 34) {
      manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
      manager.createScreenCaptureIntent()
    }
    projectionLauncher.launch(intent)
  }

  private fun startMirrorService(resultCode: Int, data: Intent, target: Pair<String, Int>) {
    val intent = Intent(this, MirrorService::class.java)
      .setAction(MirrorService.ACTION_START)
      .putExtra(MirrorService.EXTRA_HOST, target.first)
      .putExtra(MirrorService.EXTRA_PORT, target.second)
      .putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
      .putExtra(MirrorService.EXTRA_PROJECTION_DATA, data)
    ContextCompat.startForegroundService(this, intent)
    statusText.text = getString(R.string.mirror_starting)
    statusText.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
  }

  private fun parseTarget(text: String): Pair<String, Int>? {
    if (text.isEmpty()) return null
    val host: String
    var port = MirrorLinkConst.DEFAULT_PORT
    if (text.contains(':')) {
      val idx = text.lastIndexOf(':')
      host = text.substring(0, idx)
      port = text.substring(idx + 1).toIntOrNull() ?: return null
    } else {
      host = text
    }
    return host to port
  }

  private fun renderStatus() {
    val running = MirrorService.running
    startButton.isEnabled = !running
    startButton.visibility = if (running) View.GONE else View.VISIBLE
    stopButton.visibility = if (running) View.VISIBLE else View.GONE
    statsGrid.visibility = if (running) View.VISIBLE else View.GONE
    val err = MirrorService.lastError
    when {
      running -> {
        findViewById<TextView>(R.id.stat_resolution_value).text =
          "${MirrorService.encodeWidth}×${MirrorService.encodeHeight}"
        findViewById<TextView>(R.id.stat_fps_value).text = "${MirrorService.fps} fps"
        findViewById<TextView>(R.id.stat_bitrate_value).text = "${MirrorService.bitrateMbps} Mbps"
        findViewById<TextView>(R.id.stat_frames_value).text =
          String.format("%,d", MirrorService.sentFrames)
        statusText.text = getString(R.string.mirror_running)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.success))
      }
      err != null -> {
        statusText.text = getString(R.string.mirror_error, err)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.error))
      }
      else -> {
        statusText.text = getString(R.string.mirror_idle)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
      }
    }
  }

  private val prefs by lazy { getSharedPreferences("mirror_ui", MODE_PRIVATE) }

  private companion object {
    const val EXTRA_DEBUG_TARGET = "debug_mirror_target"
    const val KEY_LAST_TARGET = "last_target"
    const val KEY_NOTIF_ASKED = "notif_permission_asked"
  }
}
