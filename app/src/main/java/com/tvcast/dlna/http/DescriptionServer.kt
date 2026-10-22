package com.tvcast.dlna.http

import com.tvcast.dlna.DmrDevice
import com.tvcast.dlna.gena.EventBus
import com.tvcast.dlna.gena.GenaService
import com.tvcast.dlna.soap.SoapDispatcher

/**
 * 描述服务与端点路由：
 * GET 设备描述 DDD 与三份 SCPD；POST 控制端点（SOAP）；SUBSCRIBE 与 UNSUBSCRIBE 事件端点（GENA）。
 */
class DescriptionServer(
    private val device: DmrDevice,
    private val soap: SoapDispatcher,
    private val gena: EventBus,
) : HttpHandler {

    override fun handle(request: HttpRequest): HttpResponse {
        val path = request.path.substringBefore('?').ifEmpty { "/" }
        return when {
            request.method == "GET" && path == "/dd.xml" ->
                HttpResponse.xml(200, "OK", device.deviceDescriptionXml())

            request.method == "GET" && path == "/scpd/${DmrDevice.AV_TRANSPORT.path}.xml" ->
                HttpResponse.xml(200, "OK", device.avTransportScpdXml())

            request.method == "GET" && path == "/scpd/${DmrDevice.RENDERING_CONTROL.path}.xml" ->
                HttpResponse.xml(200, "OK", device.renderingControlScpdXml())

            request.method == "GET" && path == "/scpd/${DmrDevice.CONNECTION_MANAGER.path}.xml" ->
                HttpResponse.xml(200, "OK", device.connectionManagerScpdXml())

            request.method == "POST" && path == "/control/${DmrDevice.AV_TRANSPORT.path}" ->
                soap.handle(DmrDevice.AV_TRANSPORT.type, request)

            request.method == "POST" && path == "/control/${DmrDevice.RENDERING_CONTROL.path}" ->
                soap.handle(DmrDevice.RENDERING_CONTROL.type, request)

            request.method == "POST" && path == "/control/${DmrDevice.CONNECTION_MANAGER.path}" ->
                soap.handle(DmrDevice.CONNECTION_MANAGER.type, request)

            request.method == "SUBSCRIBE" && path == "/event/${DmrDevice.AV_TRANSPORT.path}" ->
                gena.handleSubscribe(GenaService.AV_TRANSPORT, request)

            request.method == "SUBSCRIBE" && path == "/event/${DmrDevice.RENDERING_CONTROL.path}" ->
                gena.handleSubscribe(GenaService.RENDERING_CONTROL, request)

            request.method == "SUBSCRIBE" && path == "/event/${DmrDevice.CONNECTION_MANAGER.path}" ->
                gena.handleSubscribe(GenaService.CONNECTION_MANAGER, request)

            request.method == "UNSUBSCRIBE" && path == "/event/${DmrDevice.AV_TRANSPORT.path}" ->
                gena.handleUnsubscribe(GenaService.AV_TRANSPORT, request)

            request.method == "UNSUBSCRIBE" && path == "/event/${DmrDevice.RENDERING_CONTROL.path}" ->
                gena.handleUnsubscribe(GenaService.RENDERING_CONTROL, request)

            request.method == "UNSUBSCRIBE" && path == "/event/${DmrDevice.CONNECTION_MANAGER.path}" ->
                gena.handleUnsubscribe(GenaService.CONNECTION_MANAGER, request)

            else -> HttpResponse.text(404, "Not Found")
        }
    }
}
