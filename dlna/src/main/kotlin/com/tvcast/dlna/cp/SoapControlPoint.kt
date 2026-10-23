package com.tvcast.dlna.cp

import com.tvcast.dlna.Xml
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.w3c.dom.Element

sealed interface CpResult {
  /** 动作执行成功，out 参数为「小写参数名 → 值」 */
  data class Ok(val actionName: String, val args: Map<String, String>) : CpResult
  data class Fault(val errorCode: Int, val description: String) : CpResult
  data class Error(val message: String) : CpResult
}

/** AVTransport/RenderingControl 位置与音量信息（CP 侧查询结果） */
data class TransportInfo(
  val state: String,
  val status: String,
  val positionSeconds: Long,
  val durationSeconds: Long,
  val uri: String?,
)

/**
 * 控制点 SOAP 客户端：标准 POST（Android HttpURLConnection 支持标准动词），
 * 信封构造/响应解析/fault 提取与接收端对称。
 */
class SoapControlPoint(private val log: (String) -> Unit = {}) {

  fun invoke(controlUrl: String, serviceType: String, action: String, args: Map<String, String>): CpResult {
    val body = envelope(serviceType, action, args)
    var conn: HttpURLConnection? = null
    return try {
      conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 4000
        readTimeout = 6000
        doOutput = true
        setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
      }
      val bytes = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      val code = conn.responseCode
      val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
      if (code in 200..299) {
        parseSuccess(action, text)
      } else {
        parseFault(text) ?: CpResult.Error("HTTP $code")
      }
    } catch (e: Exception) {
      CpResult.Error(e.message ?: e.javaClass.simpleName)
    } finally {
      conn?.disconnect()
    }
  }

  fun setAvTransportUri(
    device: DlnaDevice,
    uri: String,
    title: String,
    upnpClass: String = "object.item.videoItem",
    metaData: String = "",
  ): CpResult {
    val control = device.avTransportControlUrl ?: return CpResult.Error("no AVTransport controlURL")
    val didl = didlLite(uri, title, upnpClass)
    return invoke(
      control, AVT, "SetAVTransportURI",
      mapOf(
        "InstanceID" to "0",
        "CurrentURI" to uri,
        "CurrentURIMetaData" to escapeDidl(didl),
      ),
    )
  }

  fun play(device: DlnaDevice): CpResult = avt(device, "Play", mapOf("Speed" to "1"))
  fun pause(device: DlnaDevice): CpResult = avt(device, "Pause")
  fun stop(device: DlnaDevice): CpResult = avt(device, "Stop")

  fun seek(device: DlnaDevice, positionSeconds: Long): CpResult =
    avt(device, "Seek", mapOf("Unit" to "REL_TIME", "Target" to formatTime(positionSeconds)))

  fun getTransportInfo(device: DlnaDevice): CpResult = avt(device, "GetTransportInfo")

  fun getPositionInfo(device: DlnaDevice): CpResult = avt(device, "GetPositionInfo")

  fun setVolume(device: DlnaDevice, percent: Int): CpResult {
    val control = device.renderingControlUrl ?: return CpResult.Error("no RenderingControl")
    return invoke(
      control, RCS, "SetVolume",
      mapOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to percent.coerceIn(0, 100).toString()),
    )
  }

  fun getVolume(device: DlnaDevice): CpResult {
    val control = device.renderingControlUrl ?: return CpResult.Error("no RenderingControl")
    return invoke(control, RCS, "GetVolume", mapOf("InstanceID" to "0", "Channel" to "Master"))
  }

  /** TransportInfo 便捷查询：GetPositionInfo + GetTransportInfo 合并（允许部分失败） */
  fun queryTransport(device: DlnaDevice): TransportInfo? {
    val pos = getPositionInfo(device) as? CpResult.Ok ?: return null
    val transport = getTransportInfo(device) as? CpResult.Ok
    val state = transport?.args?.get("currenttransportstate") ?: "UNKNOWN"
    val status = transport?.args?.get("currenttransportstatus") ?: "OK"
    return TransportInfo(
      state = state,
      status = status,
      positionSeconds = parseTime(pos.args["reltime"]),
      durationSeconds = parseTime(pos.args["trackduration"]),
      uri = pos.args["trackuri"],
    )
  }

  // ---- 构造与解析 ----

  fun envelope(serviceType: String, action: String, args: Map<String, String>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
    append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
    append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
    append("<u:$action xmlns:u=\"$serviceType\">")
    for ((name, value) in args) append("<$name>${Xml.escape(value)}</$name>")
    append("</u:$action></s:Body></s:Envelope>")
  }

  private fun avt(device: DlnaDevice, action: String, args: Map<String, String> = emptyMap()): CpResult {
    val control = device.avTransportControlUrl ?: return CpResult.Error("no AVTransport controlURL")
    return invoke(control, AVT, action, mapOf("InstanceID" to "0") + args)
  }

  private fun parseSuccess(action: String, xml: String): CpResult = try {
    val doc = Xml.safeDocumentBuilderFactory().newDocumentBuilder()
      .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    val all = doc.getElementsByTagNameNS("*", "${action}Response")
    val response = if (all.length > 0) all.item(0) as Element else return CpResult.Ok(action, emptyMap())
    val args = HashMap<String, String>()
    var child = response.firstChild
    while (child != null) {
      if (child is Element) {
        val name = (child.localName ?: child.tagName.substringAfterLast(':')).lowercase()
        args[name] = child.textContent?.trim() ?: ""
      }
      child = child.nextSibling
    }
    CpResult.Ok(action, args)
  } catch (e: Exception) {
    CpResult.Error("bad response: ${e.message}")
  }

  private fun parseFault(xml: String): CpResult.Fault? = try {
    val doc = Xml.safeDocumentBuilderFactory().newDocumentBuilder()
      .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    val errors = doc.getElementsByTagNameNS("*", "UPnPError")
    val error = if (errors.length > 0) errors.item(0) as Element else return null
    val code = error.childNodes.let { nodes ->
      (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        .firstOrNull { (it.localName ?: it.tagName) == "errorCode" }
        ?.textContent?.trim()?.toIntOrNull()
    } ?: -1
    val description = error.childNodes.let { nodes ->
      (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        .firstOrNull { (it.localName ?: it.tagName) == "errorDescription" }
        ?.textContent?.trim()
    } ?: ""
    CpResult.Fault(code, description)
  } catch (_: Exception) {
    null
  }

  companion object {
    const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"

  fun didlLite(uri: String, title: String, upnpClass: String = "object.item.videoItem"): String =
    "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
      "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
      "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
      "<item id=\"tvcast-1\" restricted=\"0\">" +
      "<dc:title>${Xml.escape(title)}</dc:title>" +
      "<upnp:class>${Xml.escape(upnpClass)}</upnp:class>" +
      "<res>${Xml.escape(uri)}</res></item></DIDL-Lite>"

  /** 由 MIME 类型推导 upnp:class（接收端据此分流视频/音频/图片渲染路径） */
  fun upnpClassForMime(mimeType: String?): String {
    val main = mimeType?.substringBefore('/')?.lowercase()
    return when (main) {
      "audio" -> "object.item.audioItem"
      "image" -> "object.item.imageItem"
      else -> "object.item.videoItem"
    }
  }

    /** DIDL 作为 SOAP 参数值需要整体 XML 转义一层 */
    fun escapeDidl(didl: String): String = Xml.escape(didl)

    fun formatTime(totalSeconds: Long): String {
      val s = if (totalSeconds > 0) totalSeconds else 0
      return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    fun parseTime(text: String?): Long {
      if (text.isNullOrBlank()) return 0L
      val parts = text.trim().split(":")
      if (parts.size != 3) return 0L
      val h = parts[0].toLongOrNull() ?: return 0L
      val m = parts[1].toLongOrNull() ?: return 0L
      val s = parts[2].toLongOrNull() ?: return 0L
      return h * 3600 + m * 60 + s
    }
  }
}
