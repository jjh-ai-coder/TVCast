package com.tvcast.sender

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tvcast.dlna.cp.CpResult
import com.tvcast.dlna.cp.GenaState
import kotlin.concurrent.thread

/** 遥控页：推送媒体（本地/URL）+ 传输控制（播放/暂停/停止/进度/音量） */
class RemoteActivity : AppCompatActivity() {

  private val main = Handler(Looper.getMainLooper())
  private var deviceName = ""
  private var currentTitle = ""
  private var seeking = false
  private var durationSec = 0L
  private var positionSec = 0L
  @Volatile private var lastPolledState: String? = null

  private lateinit var stateText: TextView
  private lateinit var titleText: TextView
  private lateinit var positionText: TextView
  private lateinit var seekBar: SeekBar
  private lateinit var playPause: Button

  private val pollLoop = object : Runnable {
    override fun run() {
      val device = CastSession.device
      if (device != null) {
        thread {
          val info = CastSession.soap.queryTransport(device)
          main.post { onPolled(info) }
        }
      }
      main.postDelayed(this, POLL_MS)
    }
  }

  private val genaListener: (GenaState) -> Unit = { state ->
    main.post {
      state.transportState?.let { updateStateText(it) }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_remote)
    deviceName = CastSession.device?.friendlyName ?: getString(R.string.app_name)
    titleText = findViewById(R.id.media_title)
    stateText = findViewById(R.id.state_text)
    positionText = findViewById(R.id.position_text)
    seekBar = findViewById(R.id.seek_bar)
    playPause = findViewById(R.id.btn_play_pause)

    findViewById<TextView>(R.id.device_name).text = getString(R.string.casting_to, deviceName)

    findViewById<Button>(R.id.btn_pick_local).setOnClickListener { pickLocalMedia() }
    findViewById<Button>(R.id.btn_push_url).setOnClickListener {
      val url = findViewById<EditText>(R.id.media_url).text?.toString()?.trim().orEmpty()
      if (url.isNotEmpty()) pushUrl(url, url.substringAfterLast('/').substringBefore('?'))
    }
    playPause.setOnClickListener {
      val device = CastSession.device ?: return@setOnClickListener
      // GENA 事件优先；无事件时用最近一次轮询到的状态判定
      val current = CastSession.lastGenaState.transportState ?: lastPolledState
      thread {
        val result = if (current == CastSession.STATE_PLAYING) {
          CastSession.soap.pause(device)
        } else {
          CastSession.soap.play(device)
        }
        main.post { showResult(result) }
      }
    }
    findViewById<Button>(R.id.btn_stop).setOnClickListener {
      val device = CastSession.device ?: return@setOnClickListener
      thread { main.post { showResult(CastSession.soap.stop(device)) } }
    }
    findViewById<Button>(R.id.btn_disconnect).setOnClickListener {
      thread { CastSession.soap.stop(CastSession.device ?: return@thread) }
      CastSession.disconnect()
      finish()
    }
    seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (fromUser) positionText.text = format(positionSec = progress.toLong())
      }

      override fun onStartTrackingTouch(bar: SeekBar?) {
        seeking = true
      }

      override fun onStopTrackingTouch(bar: SeekBar?) {
        seeking = false
        val device = CastSession.device ?: return
        val target = bar?.progress?.toLong() ?: return
        thread { main.post { showResult(CastSession.soap.seek(device, target)) } }
      }
    })
  }

  override fun onResume() {
    super.onResume()
    CastSession.addStateListener(genaListener)
    pollLoop.run()
    // 调试/自动化入口：MainActivity.debugPushUri（adb am start --es debug_push_uri <content://…>）
    MainActivity.debugPushUri?.let { raw ->
      if (!debugPushDone) {
        debugPushDone = true
        MainActivity.debugPushUri = null
        pushLocal(raw)
      }
    }
  }

  override fun onPause() {
    super.onPause()
    main.removeCallbacks(pollLoop)
    CastSession.removeStateListener(genaListener)
  }

  private fun onPolled(info: com.tvcast.dlna.cp.TransportInfo?) {
    if (info == null) {
      stateText.text = getString(R.string.device_offline)
      return
    }
    lastPolledState = info.state
    durationSec = info.durationSeconds
    positionSec = info.positionSeconds
    if (!seeking && durationSec > 0) {
      seekBar.max = durationSec.toInt()
      seekBar.progress = positionSec.coerceAtMost(durationSec).toInt()
    }
    positionText.text = format(positionSec, durationSec)
    // GENA 优先；无事件时用轮询值刷新状态
    if (CastSession.lastGenaState.transportState == null) {
      updateStateText(info.state)
    }
  }

  private fun updateStateText(state: String) {
    stateText.text = when (state) {
      CastSession.STATE_PLAYING -> getString(R.string.state_playing)
      CastSession.STATE_PAUSED -> getString(R.string.state_paused)
      CastSession.STATE_STOPPED -> getString(R.string.state_stopped)
      "TRANSITIONING" -> getString(R.string.state_buffering)
      "NO_MEDIA_PRESENT" -> getString(R.string.state_no_media)
      else -> state
    }
    playPause.text = if (state == CastSession.STATE_PLAYING) "⏸ 暂停" else "▶ 播放"
  }

  private fun pickLocalMedia() {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
      addCategory(Intent.CATEGORY_OPENABLE)
      type = "*/*"
      putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*", "image/*"))
    }
    startActivityForResult(intent, REQ_PICK)
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQ_PICK && resultCode == Activity.RESULT_OK) {
      val uri: Uri = data?.data ?: return
      pushLocal(uri)
    }
  }

  private fun pushLocal(uri: Uri) {
    val title = queryDisplayName(uri) ?: "本地媒体"
    // 持久化读权限（本次会话内重复拉流需要）
    try {
      contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {
    }
    val port = ContentServer.start(this, uri)
    if (port <= 0) {
      Toast.makeText(this, R.string.local_server_failed, Toast.LENGTH_SHORT).show()
      return
    }
    val url = ContentServer.currentUrl()
    if (url == null) {
      Toast.makeText(this, R.string.local_server_failed, Toast.LENGTH_SHORT).show()
      return
    }
    pushUrl(url, title)
  }

  private fun pushUrl(url: String, title: String) {
    val device = CastSession.device ?: return
    currentTitle = title
    titleText.text = getString(R.string.now_casting, title)
    stateText.text = getString(R.string.pushing)
    thread {
      val result = CastSession.soap.setAvTransportUri(device, url, title)
      main.post {
        showResult(result)
        if (result is CpResult.Ok) {
          CastSession.resetGenaState()
          stateText.text = getString(R.string.state_buffering)
        }
      }
    }
  }

  private fun queryDisplayName(uri: Uri): String? = try {
    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
      }
  } catch (_: Exception) {
    null
  }

  private fun showResult(result: CpResult) {
    when (result) {
      is CpResult.Ok -> Unit
      is CpResult.Fault -> Toast.makeText(this, getString(R.string.action_failed, result.errorCode, result.description), Toast.LENGTH_SHORT).show()
      is CpResult.Error -> Toast.makeText(this, getString(R.string.action_failed_short, result.message), Toast.LENGTH_SHORT).show()
    }
  }

  private fun format(positionSec: Long, durationSec: Long? = null): String {
    fun mmss(total: Long): String = "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
    return if (durationSec != null) "${mmss(positionSec)} / ${mmss(durationSec)}" else mmss(positionSec)
  }

  private companion object {
    const val REQ_PICK = 41
    const val POLL_MS = 1000L
  }

  private var debugPushDone = false
}
