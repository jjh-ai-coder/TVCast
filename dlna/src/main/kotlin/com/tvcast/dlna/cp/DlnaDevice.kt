package com.tvcast.dlna.cp

/** 发现到的 DLNA 设备（控制点视角） */
data class DlnaDevice(
  val friendlyName: String,
  val udn: String,
  /** 描述文档地址（LOCATION） */
  val locationUrl: String,
  /** AVTransport 控制地址（绝对） */
  val avTransportControlUrl: String?,
  /** AVTransport 事件订阅地址（绝对） */
  val avTransportEventUrl: String?,
  /** RenderingControl 控制地址（绝对） */
  val renderingControlUrl: String?,
  val deviceType: String?,
) {
  val castable: Boolean get() = avTransportControlUrl != null
}
