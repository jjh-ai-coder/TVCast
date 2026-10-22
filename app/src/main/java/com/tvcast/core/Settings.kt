package com.tvcast.core

import android.content.Context
import java.util.UUID

/**
 * 持久化设置：UDN 首次生成后永久不变（变了手机端会缓存成僵尸设备，spec §4.2）、
 * 设备名、开机自启开关。
 */
class Settings(context: Context) {

  private val sp = context.getSharedPreferences("tvcast_settings", Context.MODE_PRIVATE)

  val udn: String by lazy {
    sp.getString(KEY_UDN, null) ?: ("uuid:" + UUID.randomUUID().toString()).also {
      sp.edit().putString(KEY_UDN, it).apply()
    }
  }

  val deviceName: String
    get() = sp.getString(KEY_DEVICE_NAME, null) ?: defaultDeviceName()

  fun setDeviceName(name: String) {
    if (name.isNotBlank()) sp.edit().putString(KEY_DEVICE_NAME, name.trim().take(32)).apply()
  }

  var autoStart: Boolean
    get() = sp.getBoolean(KEY_AUTO_START, true)
    set(value) = sp.edit().putBoolean(KEY_AUTO_START, value).apply()

  private fun defaultDeviceName(): String = "TVCast-" + udn.removePrefix("uuid:").takeLast(4).uppercase()

  companion object {
    private const val KEY_UDN = "udn"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_AUTO_START = "auto_start"
  }
}
