package com.tvcast.sender

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tvcast.sender.mirror.MirrorService
import com.tvcast.transport.MirrorLinkConst

/**
 * 屏幕镜像页：输入 TV 端地址 → 系统授权屏幕录制 → 启动 [MirrorService]。
 * 运行中轮询伴生状态显示 分辨率/fps/码率/帧数。
 *
 * 调试/自动化入口：adb am start --es debug_mirror_target 10.0.2.2:9420（自动发起授权）
 */
class MirrorActivity : AppCompatActivity() {

  private val main = Handler(Looper.getMainLooper())
  private lateinit var targetEdit: EditText
  private lateinit var statusText: TextView
  private lateinit var startButton: Button
  private lateinit var stopButton: Button
  private var pendingTarget: Pair<String, Int>? = null

  private val statusLoop = object : Runnable {
    override fun run() {
      renderStatus()
      main.postDelayed(this, 1000)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_mirror)
    targetEdit = findViewById(R.id.mirror_target)
    statusText = findViewById(R.id.mirror_status)
    startButton = findViewById(R.id.btn_mirror_start)
    stopButton = findViewById(R.id.btn_mirror_stop)

    // 预填已连接设备的 IP（镜像端口与 DLNA 端口无关，只取主机地址）
    CastSession.device?.locationUrl?.let { url ->
      Uri.parse(url).host?.let { host ->
        if (targetEdit.text.isNullOrBlank()) targetEdit.setText(host)
      }
    }
    startButton.setOnClickListener { requestProjection() }
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

  private fun requestProjection() {
    val target = parseTarget(targetEdit.text?.toString()?.trim().orEmpty())
    if (target == null) {
      Toast.makeText(this, R.string.mirror_bad_target, Toast.LENGTH_SHORT).show()
      return
    }
    pendingTarget = target
    val manager = getSystemService(MediaProjectionManager::class.java)
    // Android 14+ 默认弹"单个应用"选择器：投的是单个 App（如 TVCast 自己），切到别的
    // 界面内容即停止 → 电视超时断开（表现为"切界面就断"）。强制整屏模式，杜绝误选。
    val intent = if (Build.VERSION.SDK_INT >= 34) {
      manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
      manager.createScreenCaptureIntent()
    }
    startActivityForResult(intent, REQ_PROJECTION)
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != REQ_PROJECTION) return
    val target = pendingTarget
    pendingTarget = null
    if (resultCode != Activity.RESULT_OK || data == null || target == null) {
      statusText.text = getString(R.string.mirror_denied)
      return
    }
    val intent = Intent(this, MirrorService::class.java)
      .setAction(MirrorService.ACTION_START)
      .putExtra(MirrorService.EXTRA_HOST, target.first)
      .putExtra(MirrorService.EXTRA_PORT, target.second)
      .putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
      .putExtra(MirrorService.EXTRA_PROJECTION_DATA, data)
    ContextCompat.startForegroundService(this, intent)
    statusText.text = getString(R.string.mirror_starting)
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
    stopButton.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
    statusText.text = when {
      running -> getString(
        R.string.mirror_stats,
        MirrorService.encodeWidth, MirrorService.encodeHeight,
        MirrorService.fps, MirrorService.bitrateMbps, MirrorService.sentFrames)
      MirrorService.lastError != null -> getString(R.string.mirror_error, MirrorService.lastError)
      else -> getString(R.string.mirror_idle)
    }
  }

  private companion object {
    const val REQ_PROJECTION = 51
    const val EXTRA_DEBUG_TARGET = "debug_mirror_target"
  }
}
