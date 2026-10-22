package com.tvcast.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.tvcast.core.CastService
import com.tvcast.dlna.TransportState

class MainActivity : AppCompatActivity() {

  var castService: CastService? = null
    private set
  private var bound = false
  private var settingsMode = false

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      castService = (service as? CastService.LocalBinder)?.service
      castService?.addUiListener(uiListener)
      refreshUi()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      castService?.removeUiListener(uiListener)
      castService = null
      refreshUi()
    }
  }

  private val uiListener = Runnable {
    runOnUiThread { refreshUi() }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(com.tvcast.R.layout.activity_main)
    CastService.start(this)
  }

  override fun onStart() {
    super.onStart()
    bindService(Intent(this, CastService::class.java), connection, Context.BIND_AUTO_CREATE)
    bound = true
    refreshUi()
  }

  override fun onStop() {
    super.onStop()
    if (bound) {
      castService?.removeUiListener(uiListener)
      unbindService(connection)
      bound = false
      castService = null
    }
  }

  // 有意不调 super：super 会结束 Activity，投屏待机页只需退到后台
  @Suppress("MissingSuperCall")
  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (settingsMode) {
      closeSettings()
    } else {
      moveTaskToBack(true)
    }
  }

  fun openSettings() {
    settingsMode = true
    supportFragmentManager.beginTransaction()
      .replace(com.tvcast.R.id.container, SettingsFragment())
      .commitAllowingStateLoss()
  }

  fun closeSettings() {
    settingsMode = false
    refreshUi()
  }

  private fun refreshUi() {
    if (settingsMode) return
    if (supportFragmentManager.isStateSaved) return
    val player = castService?.playerController
    val showPlaying = player?.let {
      it.uri != null && (it.state != TransportState.STOPPED || it.lastError != null)
    } ?: false
    val current = supportFragmentManager.findFragmentById(com.tvcast.R.id.container)
    val targetClass = if (showPlaying) PlayingFragment::class.java else StandbyFragment::class.java
    if (current == null || current.javaClass != targetClass) {
      supportFragmentManager.beginTransaction()
        .replace(com.tvcast.R.id.container, targetClass.getDeclaredConstructor().newInstance())
        .commitAllowingStateLoss()
    } else {
      // Service 晚于 Fragment 就绪时，主动让当前页刷新（否则待机页会一直显示"网络未连接"）
      (current as? StandbyFragment)?.refresh()
      (current as? PlayingFragment)?.refresh()
    }
  }
}
