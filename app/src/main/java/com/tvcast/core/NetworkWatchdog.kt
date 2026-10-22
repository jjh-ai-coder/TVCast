package com.tvcast.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import com.tvcast.dlna.NetInfo

/**
 * 网络监听：断网/换网/IP 变化（DHCP 续租）→ 防抖后回调，
 * CastService 据此驱动 DmrStack bybye→重建描述→重宣告。
 */
class NetworkWatchdog(
  context: Context,
  private val onChange: (ip: String?) -> Unit,
) {

  private val connectivityManager =
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val handler = Handler(Looper.getMainLooper())
  private var lastIp: String? = null
  private var registered = false

  private val pendingCheck = Runnable { checkNow() }

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = scheduleCheck()

    override fun onLost(network: Network) = scheduleCheck()

    override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
      scheduleCheck()
  }

  fun start() {
    if (registered) return
    val request = NetworkRequest.Builder()
      .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()
    try {
      connectivityManager.registerNetworkCallback(request, callback)
      registered = true
    } catch (_: Exception) {
    }
    checkNow()
  }

  fun stop() {
    if (!registered) return
    try {
      connectivityManager.unregisterNetworkCallback(callback)
    } catch (_: Exception) {
    }
    registered = false
    handler.removeCallbacks(pendingCheck)
  }

  private fun scheduleCheck() {
    handler.removeCallbacks(pendingCheck)
    handler.postDelayed(pendingCheck, DEBOUNCE_MS)
  }

  private fun checkNow() {
    val ip = NetInfo.pickLocalIp()
    if (ip != lastIp) {
      lastIp = ip
      onChange(ip)
    }
  }

  companion object {
    private const val DEBOUNCE_MS = 1500L
  }
}
