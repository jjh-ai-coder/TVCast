package com.tvcast.dlna.cp

import com.tvcast.dlna.Xml
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URL

/**
 * 设备描述（DDD）解析：friendlyName/UDN/各服务 controlURL·eventSubURL。
 * URL 规范允许相对路径，统一以 LOCATION 为 base 解析为绝对地址。
 */
object DeviceDescriptionParser {

  fun fetchAndParse(locationUrl: String): DlnaDevice? = try {
    val body = fetch(locationUrl)
    parse(locationUrl, body)
  } catch (_: Exception) {
    null
  }

  fun fetch(locationUrl: String): String {
    val bytes = URL(locationUrl).openStream().use { it.readBytes() }
    return String(bytes, Charsets.UTF_8)
  }

  fun parse(locationUrl: String, xml: String): DlnaDevice? = try {
    val doc = Xml.safeDocumentBuilderFactory().newDocumentBuilder()
      .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    val device = doc.getElementsByTagName("device").let {
      if (it.length > 0) it.item(0) as Element else return null
    }
    val friendlyName = childText(device, "friendlyName") ?: return null
    val udn = childText(device, "UDN") ?: ""
    val deviceType = childText(device, "deviceType")
    val base = URL(locationUrl)

    var avtControl: String? = null
    var avtEvent: String? = null
    var rcsControl: String? = null
    val services = device.getElementsByTagName("service")
    for (i in 0 until services.length) {
      val service = services.item(i) as? Element ?: continue
      val serviceType = childText(service, "serviceType") ?: continue
      val control = childText(service, "controlURL")?.let { resolve(base, it) }
      val event = childText(service, "eventSubURL")?.let { resolve(base, it) }
      when {
        serviceType.contains("AVTransport") -> {
          avtControl = control
          avtEvent = event
        }
        serviceType.contains("RenderingControl") -> rcsControl = control
      }
    }
    DlnaDevice(friendlyName, udn, locationUrl, avtControl, avtEvent, rcsControl, deviceType)
  } catch (_: Exception) {
    null
  }

  /** 相对/绝对 controlURL 统一解析为绝对地址 */
  fun resolve(base: URL, path: String): String = try {
    URL(base, path).toString()
  } catch (_: Exception) {
    path
  }

  private fun childText(parent: Element, name: String): String? {
    val nodes = parent.getElementsByTagName(name)
    if (nodes.length > 0) {
      val value = nodes.item(0).textContent?.trim()
      if (!value.isNullOrEmpty()) return value
    }
    return null
  }
}
