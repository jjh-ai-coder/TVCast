package com.tvcast.dlna.soap

import com.tvcast.dlna.Xml
import com.tvcast.dlna.http.HttpRequest
import com.tvcast.dlna.http.HttpResponse
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream

/** 服务动作执行结果 */
sealed interface SoapResult {
    data class Ok(val actionName: String, val args: Map<String, String> = emptyMap()) : SoapResult
    data class Fault(val errorCode: Int, val description: String) : SoapResult
}

interface UpnpSoapService {
    val serviceType: String
    fun invoke(action: String, args: Map<String, String>): SoapResult
}

/**
 * SOAP 控制入口：解析 SOAPACTION 头与 Body（动作名 + 参数，参数名大小写不敏感），
 * 分发到对应服务；未知动作/服务返回标准 UPnP 401 fault，绝不抛异常（spec §4.6）。
 */
class SoapDispatcher(private val services: List<UpnpSoapService>) {

    private val byType: Map<String, UpnpSoapService> = services.associateBy { it.serviceType }

    fun handle(expectedServiceType: String, request: HttpRequest): HttpResponse {
        val soapAction = request.header("soapaction")?.trim()?.removeSurrounding("\"")
        val headerService = soapAction?.takeIf { it.contains('#') }?.substringBeforeLast('#')
        val headerAction = soapAction?.takeIf { it.contains('#') }?.substringAfterLast('#')
            ?.takeIf { it.isNotBlank() }

        val serviceType = if (headerService != null && byType.containsKey(headerService)) {
            headerService
        } else {
            expectedServiceType
        }
        val service = byType[serviceType] ?: return faultResponse(401, "Invalid Action")

        if (request.body.isEmpty()) return HttpResponse.text(400, "Bad Request")

        val parsed = try {
            parseBody(request.bodyText)
        } catch (_: Exception) {
            null
        } ?: return HttpResponse.text(400, "Bad Request")

        val action = headerAction ?: parsed.first
        val result = try {
            service.invoke(action, parsed.second)
        } catch (t: Throwable) {
            SoapResult.Fault(501, "Action Failed")
        }
        return when (result) {
            is SoapResult.Ok ->
                HttpResponse.xml(200, "OK", successEnvelope(service.serviceType, result))
            is SoapResult.Fault ->
                faultResponse(result.errorCode, result.description)
        }
    }

    private fun successEnvelope(serviceType: String, result: SoapResult.Ok): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:${result.actionName}Response xmlns:u=\"$serviceType\">")
        for ((name, value) in result.args) {
            append("<$name>${Xml.escape(value)}</$name>")
        }
        append("</u:${result.actionName}Response>")
        append("</s:Body></s:Envelope>")
    }

    companion object {
        fun faultXml(errorCode: Int, description: String): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body><s:Fault>")
            append("<faultcode>s:Client</faultcode>")
            append("<faultstring>UPnPError</faultstring>")
            append("<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">")
            append("<errorCode>$errorCode</errorCode>")
            append("<errorDescription>${Xml.escape(description)}</errorDescription>")
            append("</UPnPError></detail>")
            append("</s:Fault></s:Body></s:Envelope>")
        }

        fun faultResponse(errorCode: Int, description: String): HttpResponse =
            HttpResponse.xml(500, "Internal Server Error", faultXml(errorCode, description))

        /** @return Pair(动作名, 小写参数名 → 值) */
        fun parseBody(bodyText: String): Pair<String, Map<String, String>> {
            val builder = Xml.safeDocumentBuilderFactory().newDocumentBuilder()
            val doc: Document = builder.parse(ByteArrayInputStream(bodyText.toByteArray(Charsets.UTF_8)))
            val body = findElement(doc.documentElement, "Body")
                ?: throw IllegalArgumentException("no SOAP Body")
            var actionElement: Element? = null
            var child = body.firstChild
            while (child != null) {
                if (child is Element) {
                    actionElement = child
                    break
                }
                child = child.nextSibling
            }
            val action = actionElement?.localName
                ?: actionElement?.tagName?.substringAfterLast(':')
                ?: throw IllegalArgumentException("no action element")
            val args = HashMap<String, String>()
            var arg = actionElement!!.firstChild
            while (arg != null) {
                if (arg is Element) {
                    val name = arg.localName ?: arg.tagName.substringAfterLast(':')
                    args[name.lowercase()] = arg.textContent?.trim() ?: ""
                }
                arg = arg.nextSibling
            }
            return action to args
        }

        private fun findElement(root: Element?, localName: String): Element? {
            if (root == null) return null
            val children = root.getElementsByTagNameNS("*", localName)
            if (children.length > 0) return children.item(0) as Element
            // 兼容未带命名空间的实现
            val fallback = root.getElementsByTagName(localName)
            if (fallback.length > 0) return fallback.item(0) as Element
            return null
        }
    }
}
