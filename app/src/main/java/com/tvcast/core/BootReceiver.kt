package com.tvcast.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** 开机自启（设置页可关） */
class BootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
    if (!Settings(context).autoStart) return
    val service = Intent(context, CastService::class.java)
    try {
      if (Build.VERSION.SDK_INT >= 26) {
        context.startForegroundService(service)
      } else {
        context.startService(service)
      }
    } catch (_: Exception) {
    }
  }
}
