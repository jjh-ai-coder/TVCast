package com.tvcast.sender

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.tvcast.dlna.cp.CpResult
import com.tvcast.dlna.cp.GenaState
import com.tvcast.dlna.cp.SoapControlPoint
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
  private lateinit var stateDot: View
  private lateinit var titleText: TextView
  private lateinit var positionText: TextView
  private lateinit var seekBar: SeekBar
  private lateinit var volumeRow: View
  private lateinit var volumeBar: SeekBar
  private lateinit var playPause: MaterialButton
  private lateinit var stopButton: MaterialButton
  private lateinit var pickButton: MaterialButton
  private lateinit var pushButton: MaterialButton
  private lateinit var urlEdit: EditText
  private lateinit var resultText: TextView

  private val clearResult = Runnable { resultText.visibility = View.GONE }

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
      // GENA 快照/事件都带音量：设备支持时顺带点亮音量行
      state.volume?.let { pct ->
        if (pct in 0..100) {
          volumeRow.visibility = View.VISIBLE
          if (!volumeBar.isPressed) volumeBar.progress = pct
        }
      }
    }
  }

  private val pickMedia =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let(::pushLocal)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_remote)
    deviceName = CastSession.device?.friendlyName ?: getString(R.string.app_name)
    titleText = findViewById(R.id.media_title)
    stateText = findViewById(R.id.state_text)
    stateDot = findViewById(R.id.state_dot)
    positionText = findViewById(R.id.position_text)
    seekBar = findViewById(R.id.seek_bar)
    volumeRow = findViewById(R.id.volume_row)
    volumeBar = findViewById(R.id.volume_bar)
    playPause = findViewById(R.id.btn_play_pause)
    stopButton = findViewById(R.id.btn_stop)
    pickButton = findViewById(R.id.btn_pick_local)
    pushButton = findViewById(R.id.btn_push_url)
    urlEdit = findViewById(R.id.media_url)
    resultText = findViewById(R.id.result_text)

    findViewById<TextView>(R.id.device_name).text = getString(R.string.casting_to, deviceName)

    // 图标统一代码设置（XML android:icon 在 MaterialButton 自动 inflation 下不稳定）
    stopButton.setIconResource(R.drawable.ic_stop)
    findViewById<MaterialButton>(R.id.btn_vol_down).setIconResource(R.drawable.ic_volume_down)
    findViewById<MaterialButton>(R.id.btn_vol_up).setIconResource(R.drawable.ic_volume_up)
    playPause.setIconResource(R.drawable.ic_play)

    pickButton.setOnClickListener { pickLocalMedia() }
    pickButton.setIconResource(R.drawable.ic_folder)
    pushButton.setOnClickListener {
      val url = urlEdit.text?.toString()?.trim().orEmpty()
      if (url.isNotEmpty()) pushUrl(url, url.substringAfterLast('/').substringBefore('?'))
    }
    playPause.setOnClickListener {
      val device = CastSession.device ?: return@setOnClickListener
      // GENA 事件优先；无事件时用最近一次轮询到的状态判定
      val current = CastSession.lastGenaState.transportState ?: lastPolledState
      setBusy(true)
      thread {
        val result = if (current == CastSession.STATE_PLAYING) {
          CastSession.soap.pause(device)
        } else {
          CastSession.soap.play(device)
        }
        main.post { setBusy(false); showResult(result) }
      }
    }
    stopButton.setOnClickListener {
      val device = CastSession.device ?: return@setOnClickListener
      setBusy(true)
      thread {
        val result = CastSession.soap.stop(device)
        main.post { setBusy(false); showResult(result) }
      }
    }
    findViewById<MaterialButton>(R.id.btn_disconnect).setOnClickListener {
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
    // 音量：拖动结束下发；设备不支持时该行保持隐藏（GetVolume 失败即不点亮）
    volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {}

      override fun onStartTrackingTouch(bar: SeekBar?) {}

      override fun onStopTrackingTouch(bar: SeekBar?) {
        val device = CastSession.device ?: return
        val target = bar?.progress ?: return
        thread { main.post { showResult(CastSession.soap.setVolume(device, target)) } }
      }
    })
    findViewById<MaterialButton>(R.id.btn_vol_down).setOnClickListener { nudgeVolume(-5) }
    findViewById<MaterialButton>(R.id.btn_vol_up).setOnClickListener { nudgeVolume(+5) }
  }

  private fun nudgeVolume(delta: Int) {
    val device = CastSession.device ?: return
    if (volumeRow.visibility != View.VISIBLE) return
    val target = (volumeBar.progress + delta).coerceIn(0, 100)
    volumeBar.progress = target
    thread { main.post { showResult(CastSession.soap.setVolume(device, target)) } }
  }

  /** 首次查询音量：支持则点亮音量行，不支持保持隐藏 */
  private fun initVolume() {
    val device = CastSession.device ?: return
    thread {
      when (val r = CastSession.soap.getVolume(device)) {
        is CpResult.Ok -> r.args["currentvolume"]?.toIntOrNull()?.let { pct ->
          if (pct in 0..100) {
            main.post {
              volumeRow.visibility = View.VISIBLE
              volumeBar.progress = pct
            }
          }
        }
        else -> Log.d(TAG, "volume not supported: $r")
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // 防御：shell/其他路径把调试意图投递到栈顶的 Remote 时也能生效
    intent.getStringExtra(EXTRA_DEBUG_PUSH)?.let { raw ->
      debugPushDone = false
      pushLocal(Uri.parse(raw))
    }
  }

  override fun onResume() {
    super.onResume()
    CastSession.addStateListener(genaListener)
    pollLoop.run()
    initVolume()
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
    main.removeCallbacks(clearResult)
    CastSession.removeStateListener(genaListener)
  }

  private fun onPolled(info: com.tvcast.dlna.cp.TransportInfo?) {
    if (info == null) {
      stateText.text = getString(R.string.device_offline)
      setDotColor(R.color.error)
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
      "NO_MEDIA_PRESENT", "UNKNOWN" -> getString(R.string.state_no_media)
      else -> state
    }
    // 状态彩点：播放绿/缓冲黄/停止灰/离线红
    setDotColor(
      when (state) {
        CastSession.STATE_PLAYING -> R.color.success
        "TRANSITIONING" -> R.color.warning
        CastSession.STATE_STOPPED, "NO_MEDIA_PRESENT" -> R.color.textSecondary
        else -> R.color.error
      },
    )
    // 图标随状态切换（icon-only 会牺牲可发现性，保留短文字）
    val playing = state == CastSession.STATE_PLAYING
    playPause.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    playPause.text = getString(if (playing) R.string.cd_pause else R.string.cd_play)
    playPause.contentDescription = getString(if (playing) R.string.cd_pause else R.string.cd_play)
  }

  private fun setDotColor(res: Int) {
    stateDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
      ContextCompat.getColor(this, res),
    )
  }

  private fun setBusy(busy: Boolean) {
    playPause.isEnabled = !busy
    stopButton.isEnabled = !busy
    pickButton.isEnabled = !busy
    pushButton.isEnabled = !busy
    urlEdit.isEnabled = !busy
  }

  private fun pickLocalMedia() {
    pickMedia.launch(arrayOf("video/*", "audio/*", "image/*"))
  }

  private fun pushLocal(uri: Uri) {
    val displayName = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
    val title = displayName ?: getString(R.string.now_playing_none)
    // SAF 授权的 URI 直接给 mime；拿不到（如 MediaStore 无权限）就按文件名扩展名推断
    val mime = contentResolver.getType(uri) ?: displayName?.let { mimeFromName(it) } ?: ""
    Log.d(TAG, "pushLocal uri=$uri mime=$mime title=$title")
    // 持久化读权限（本次会话内重复拉流需要）
    try {
      contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {
    }
    // 每次推送生成带唯一序号的流地址（图片切图不能复用同一 URL）
    val url = ContentServer.buildStreamUrl(this, uri, mime.ifEmpty { null }, extForMime(mime))
    if (url == null) {
      showInline(getString(R.string.local_server_failed))
      return
    }
    pushUrl(url, title, SoapControlPoint.upnpClassForMime(mime))
  }

  private fun extForMime(mime: String?): String? = when (mime?.lowercase()) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/bmp" -> "bmp"
    "video/mp4" -> "mp4"
    "video/x-matroska" -> "mkv"
    "video/mpeg" -> "mpeg"
    "video/quicktime" -> "mov"
    "video/x-msvideo" -> "avi"
    "video/x-flv" -> "flv"
    "video/mp2t" -> "ts"
    "video/webm" -> "webm"
    "audio/mpeg" -> "mp3"
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/wav", "audio/x-wav" -> "wav"
    "audio/mp4" -> "m4a"
    "audio/ogg" -> "ogg"
    "audio/aac" -> "aac"
    else -> null
  }

  private fun pushUrl(url: String, title: String, upnpClass: String = "object.item.videoItem") {
    val device = CastSession.device ?: return
    currentTitle = title
    titleText.text = getString(R.string.now_casting, title)
    stateText.text = getString(R.string.pushing)
    Log.d(TAG, "pushUrl url=$url class=$upnpClass")
    setBusy(true)
    thread {
      val result = CastSession.soap.setAvTransportUri(device, url, title, upnpClass)
      main.post {
        setBusy(false)
        showResult(result)
        if (result is CpResult.Ok) {
          CastSession.resetGenaState()
          stateText.text = getString(R.string.state_buffering)
        }
      }
    }
  }

  private fun mimeFromName(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty() || ext.length > 5) return null
    return when (ext) {
      "jpg", "jpeg" -> "image/jpeg"
      "png" -> "image/png"
      "gif" -> "image/gif"
      "webp" -> "image/webp"
      "bmp" -> "image/bmp"
      "mp4", "m4v" -> "video/mp4"
      "mkv" -> "video/x-matroska"
      "mpeg", "mpg" -> "video/mpeg"
      "mov" -> "video/quicktime"
      "avi" -> "video/x-msvideo"
      "flv" -> "video/x-flv"
      "ts", "m2ts" -> "video/mp2t"
      "webm" -> "video/webm"
      "mp3" -> "audio/mpeg"
      "flac" -> "audio/flac"
      "wav" -> "audio/wav"
      "m4a" -> "audio/mp4"
      "ogg" -> "audio/ogg"
      "aac" -> "audio/aac"
      else -> null
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
      is CpResult.Ok -> resultText.visibility = View.GONE
      is CpResult.Fault -> showInline(getString(R.string.action_failed, result.errorCode, result.description))
      is CpResult.Error -> showInline(getString(R.string.action_failed_short, result.message))
    }
  }

  /** inline 错误行：跟在操作区下文，3 秒自动消失（TV/手机都不打断视线） */
  private fun showInline(msg: String) {
    resultText.text = msg
    resultText.visibility = View.VISIBLE
    main.removeCallbacks(clearResult)
    main.postDelayed(clearResult, 3000)
  }

  private fun format(positionSec: Long, durationSec: Long? = null): String {
    fun mmss(total: Long): String = "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
    return if (durationSec != null) "${mmss(positionSec)} / ${mmss(durationSec)}" else mmss(positionSec)
  }

  private companion object {
    const val POLL_MS = 1000L
    const val EXTRA_DEBUG_PUSH = "debug_push_uri"
    private const val TAG = "TVCast/Sender"
  }

  private var debugPushDone = false
}
