package com.tvcast.dlna

import javax.xml.parsers.DocumentBuilderFactory

/** XML 转义与安全解析器工厂（禁 DTD/外部实体，防 XXE） */
object Xml {

    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun safeDocumentBuilderFactory(namespaceAware: Boolean = true): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = namespaceAware
        // 恶意/畸形输入是常态（手机端转义错误、伪造报文），解析必须 fail-soft 且不可解析外部实体
        for (feature in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl",
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
        )) {
            try {
                factory.setFeature(feature, true)
            } catch (_: Exception) {
                // 该实现不支持该 feature，忽略
            }
        }
        factory.isExpandEntityReferences = false
        // Android 的 DocumentBuilder 不支持 XInclude，setXIncludeAware 会抛 UnsupportedOperationException
        try {
            factory.setXIncludeAware(false)
        } catch (_: Exception) {
        }
        return factory
    }
}
