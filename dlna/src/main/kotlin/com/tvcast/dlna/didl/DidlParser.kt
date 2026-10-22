package com.tvcast.dlna.didl

import com.tvcast.dlna.Xml
import org.w3c.dom.Element
import java.io.ByteArrayInputStream

data class DidlData(val title: String?, val upnpClass: String?)

/**
 * DIDL-Lite 宽容解析（spec §4.7）：
 * - 输入可能被 XML 转义一层甚至多层（手机端常态），先反解
 * - 先按命名空间感知解析（规范文档），失败退回非命名空间解析
 *   （未声明 xmlns:dc 等前缀的脏数据在严格 DOM 上会抛错，这是常态）
 * - 只取 dc:title 与 upnp:class，其余全部忽略
 * - 任何失败返回空数据（fail-soft），不抛异常、不影响播放
 */
object DidlParser {

  private const val MAX_UNESCAPE_ROUNDS = 3

  fun parse(raw: String?): DidlData {
    if (raw.isNullOrBlank()) return DidlData(null, null)
    var text = raw.trim()
    var rounds = 0
    while (rounds < MAX_UNESCAPE_ROUNDS && !text.startsWith("<")) {
      val unescaped = unescape(text)
      if (unescaped == text) break
      text = unescaped.trim()
      rounds++
    }
    if (!text.startsWith("<")) return DidlData(null, null)
    return parseDom(text, namespaceAware = true)
      ?: parseDom(text, namespaceAware = false)
      ?: DidlData(null, null)
  }

  private fun parseDom(text: String, namespaceAware: Boolean): DidlData? = try {
    val factory = Xml.safeDocumentBuilderFactory(namespaceAware)
    val doc = factory.newDocumentBuilder()
      .parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    val item = findFirst(doc.documentElement, "item", namespaceAware)
      ?: findFirst(doc.documentElement, "container", namespaceAware)
      ?: return null
    val title = childText(item, "title", "dc:title", namespaceAware)
    val upnpClass = childText(item, "class", "upnp:class", namespaceAware)
    DidlData(title, upnpClass)
  } catch (_: Exception) {
    null
  }

  private fun findFirst(root: Element?, localName: String, namespaceAware: Boolean): Element? {
    if (root == null) return null
    if (namespaceAware) {
      val all = root.getElementsByTagNameNS("*", localName)
      if (all.length > 0) return all.item(0) as Element
    } else {
      val all = root.getElementsByTagName(localName)
      if (all.length > 0) return all.item(0) as Element
      val prefixed = root.getElementsByTagName("*:$localName")
      if (prefixed.length > 0) return prefixed.item(0) as Element
    }
    return null
  }

  private fun childText(
    item: Element,
    localName: String,
    prefixedName: String,
    namespaceAware: Boolean,
  ): String? {
    val candidates = if (namespaceAware) {
      listOf(
        item.getElementsByTagNameNS("*", localName),
        item.getElementsByTagName(prefixedName),
      )
    } else {
      listOf(
        item.getElementsByTagName(prefixedName),
        item.getElementsByTagName("*:$localName"),
      )
    }
    for (list in candidates) {
      if (list.length > 0) {
        val value = list.item(0).textContent?.trim()
        if (!value.isNullOrEmpty()) return value
      }
    }
    return null
  }

  private fun unescape(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")
}
