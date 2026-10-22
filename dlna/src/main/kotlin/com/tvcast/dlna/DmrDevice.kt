package com.tvcast.dlna

/**
 * MediaRenderer 设备模型：UDN/设备名/描述基地址。
 * 描述 XML 按 baseUrl 每次现生成——IP 变化（DHCP 续租/换网）后旧绝对 URL 全部失效，
 * 必须重建（UDN 保持不变，见 spec §4.2）。
 */
class DmrDevice(val udn: String, initialName: String) {

    @Volatile var friendlyName: String = initialName

    /** 如 http://192.168.1.10:49152，由 DmrStack 启动/网络变化时设置 */
    @Volatile var baseUrl: String = "http://127.0.0.1:80"

    fun deviceDescriptionXml(): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\">")
        append("<specVersion><major>1</major><minor>0</minor></specVersion>")
        append("<device>")
        append("<deviceType>$DEVICE_TYPE</deviceType>")
        append("<friendlyName>${Xml.escape(friendlyName)}</friendlyName>")
        append("<manufacturer>TVCast</manufacturer>")
        append("<modelDescription>DLNA MediaRenderer receiver for Android TV</modelDescription>")
        append("<modelName>TVCast</modelName>")
        append("<modelNumber>1.0</modelNumber>")
        append("<UDN>$udn</UDN>")
        append("<dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">DMR-1.50</dlna:X_DLNADOC>")
        append("<serviceList>")
        for (service in SERVICES) {
            append("<service>")
            append("<serviceType>${service.type}</serviceType>")
            append("<serviceId>${service.id}</serviceId>")
            append("<SCPDURL>$baseUrl/scpd/${service.path}.xml</SCPDURL>")
            append("<controlURL>$baseUrl/control/${service.path}</controlURL>")
            append("<eventSubURL>$baseUrl/event/${service.path}</eventSubURL>")
            append("</service>")
        }
        append("</serviceList>")
        append("</device></root>")
    }

    fun avTransportScpdXml(): String = SCPD_AVTRANSPORT
    fun renderingControlScpdXml(): String = SCPD_RENDERING_CONTROL
    fun connectionManagerScpdXml(): String = SCPD_CONNECTION_MANAGER

    data class ServiceDef(val type: String, val id: String, val path: String)

    companion object {
        const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"

        val AV_TRANSPORT = ServiceDef(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:upnp-org:serviceId:AVTransport",
            "avtransport",
        )
        val RENDERING_CONTROL = ServiceDef(
            "urn:schemas-upnp-org:service:RenderingControl:1",
            "urn:upnp-org:serviceId:RenderingControl",
            "renderingcontrol",
        )
        val CONNECTION_MANAGER = ServiceDef(
            "urn:schemas-upnp-org:service:ConnectionManager:1",
            "urn:upnp-org:serviceId:ConnectionManager",
            "connectionmanager",
        )
        val SERVICES = listOf(AV_TRANSPORT, RENDERING_CONTROL, CONNECTION_MANAGER)

        private fun arg(name: String, direction: String, related: String): String =
            "<argument><name>$name</name><direction>$direction</direction>" +
                "<relatedStateVariable>$related</relatedStateVariable></argument>"

        private fun action(name: String, args: String): String =
            "<action><name>$name</name><argumentList>$args</argumentList></action>"

        private fun stateVariable(
            name: String,
            dataType: String,
            evented: Boolean = false,
            allowedValues: List<String> = emptyList(),
        ): String = buildString {
            append("<stateVariable")
            if (evented) append(" sendEvents=\"yes\"")
            append("><name>$name</name><dataType>$dataType</dataType>")
            if (allowedValues.isNotEmpty()) {
                append("<allowedValueList>")
                for (v in allowedValues) append("<allowedValue>$v</allowedValue>")
                append("</allowedValueList>")
            }
            append("</stateVariable>")
        }

        private fun scpd(actions: String, stateTable: String): String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
                "<specVersion><major>1</major><minor>0</minor></specVersion>" +
                "<actionList>$actions</actionList>" +
                "<serviceStateTable>$stateTable</serviceStateTable></scpd>"

        // ---- AVTransport（AVT 事件经 LastChange 变量交付，spec §4.5） ----
        private val SCPD_AVTRANSPORT = scpd(
            actions = buildString {
                append(action("SetAVTransportURI",
                    arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") +
                        arg("CurrentURI", "in", "AVTransportURI") +
                        arg("CurrentURIMetaData", "in", "AVTransportURIMetaData")))
                append(action("Play",
                    arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") +
                        arg("Speed", "in", "TransportPlaySpeed")))
                append(action("Pause", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")))
                append(action("Stop", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")))
                append(action("Seek",
                    arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") +
                        arg("Unit", "in", "A_ARG_TYPE_SeekMode") +
                        arg("Target", "in", "A_ARG_TYPE_SeekTarget")))
                append(action("Next", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")))
                append(action("Previous", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")))
                append(action("GetPositionInfo",
                    arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") +
                        arg("Track", "out", "CurrentTrack") +
                        arg("TrackDuration", "out", "CurrentTrackDuration") +
                        arg("TrackMetaData", "out", "CurrentTrackMetaData") +
                        arg("TrackURI", "out", "CurrentTrackURI") +
                        arg("RelTime", "out", "RelativeTimePosition") +
                        arg("AbsTime", "out", "AbsoluteTimePosition") +
                        arg("RelCount", "out", "A_ARG_TYPE_RelativeCounterPosition") +
                        arg("AbsCount", "out", "A_ARG_TYPE_AbsoluteCounterPosition")))
                append(action("GetTransportInfo",
                    arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") +
                        arg("CurrentTransportState", "out", "TransportState") +
                        arg("CurrentTransportStatus", "out", "TransportStatus") +
                        arg("CurrentSpeed", "out", "TransportPlaySpeed")))
                append(action("GetMediaInfo",
                    arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") +
                        arg("NrTracks", "out", "NumberOfTracks") +
                        arg("MediaDuration", "out", "CurrentMediaDuration") +
                        arg("CurrentURI", "out", "AVTransportURI") +
                        arg("CurrentURIMetaData", "out", "AVTransportURIMetaData")))
                append(action("GetCurrentTransportActions",
                    arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") +
                        arg("Actions", "out", "A_ARG_TYPE_CurrentTransportActions")))
            },
            stateTable = buildString {
                append(stateVariable("LastChange", "string", evented = true))
                append(stateVariable("TransportState", "string", evented = true,
                    allowedValues = listOf("STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "NO_MEDIA_PRESENT")))
                append(stateVariable("TransportStatus", "string", evented = true,
                    listOf("OK", "ERROR_OCCURRED")))
                append(stateVariable("TransportPlaySpeed", "string", evented = true, allowedValues = listOf("1")))
                append(stateVariable("AVTransportURI", "string", evented = true))
                append(stateVariable("AVTransportURIMetaData", "string", evented = true))
                append(stateVariable("CurrentTrack", "ui4", evented = true))
                append(stateVariable("CurrentTrackDuration", "string", evented = true))
                append(stateVariable("CurrentMediaDuration", "string", evented = true))
                append(stateVariable("CurrentTrackMetaData", "string", evented = true))
                append(stateVariable("CurrentTrackURI", "string", evented = true))
                append(stateVariable("NumberOfTracks", "ui4", evented = true))
                append(stateVariable("A_ARG_TYPE_InstanceID", "ui4"))
                append(stateVariable("A_ARG_TYPE_SeekMode", "string",
                    allowedValues = listOf("REL_TIME", "ABS_TIME", "TRACK_NR")))
                append(stateVariable("A_ARG_TYPE_SeekTarget", "string"))
                append(stateVariable("A_ARG_TYPE_RelativeCounterPosition", "i4"))
                append(stateVariable("A_ARG_TYPE_AbsoluteCounterPosition", "i4"))
                append(stateVariable("A_ARG_TYPE_CurrentTransportActions", "string"))
            },
        )

        // ---- RenderingControl ----
        private val SCPD_RENDERING_CONTROL = scpd(
            actions = buildString {
                append(action("GetVolume",
                    arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") +
                        arg("Channel", "in", "A_ARG_TYPE_Channel") +
                        arg("CurrentVolume", "out", "Volume")))
                append(action("SetVolume",
                    arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") +
                        arg("Channel", "in", "A_ARG_TYPE_Channel") +
                        arg("DesiredVolume", "in", "Volume")))
                append(action("GetMute",
                    arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") +
                        arg("Channel", "in", "A_ARG_TYPE_Channel") +
                        arg("CurrentMute", "out", "Mute")))
                append(action("SetMute",
                    arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") +
                        arg("Channel", "in", "A_ARG_TYPE_Channel") +
                        arg("DesiredMute", "in", "Mute")))
            },
            stateTable = buildString {
                append(stateVariable("LastChange", "string", evented = true))
                append(stateVariable("Volume", "ui2", evented = true))
                append(stateVariable("Mute", "boolean", evented = true))
                append(stateVariable("A_ARG_TYPE_InstanceID", "ui4"))
                append(stateVariable("A_ARG_TYPE_Channel", "string", allowedValues = listOf("Master")))
            },
        )

        // ---- ConnectionManager ----
        private val SCPD_CONNECTION_MANAGER = scpd(
            actions = buildString {
                append(action("GetProtocolInfo",
                    arg("Source", "out", "SourceProtocolInfo") +
                        arg("Sink", "out", "SinkProtocolInfo")))
                append(action("GetCurrentConnectionIDs",
                    arg("ConnectionIDs", "out", "CurrentConnectionIDs")))
                append(action("GetCurrentConnectionInfo",
                    arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID") +
                        arg("RcsID", "out", "A_ARG_TYPE_RcsID") +
                        arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID") +
                        arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo") +
                        arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager") +
                        arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID") +
                        arg("Direction", "out", "A_ARG_TYPE_Direction") +
                        arg("Status", "out", "A_ARG_TYPE_ConnectionStatus")))
            },
            stateTable = buildString {
                append(stateVariable("SourceProtocolInfo", "string", evented = true))
                append(stateVariable("SinkProtocolInfo", "string", evented = true))
                append(stateVariable("CurrentProtocolInfo", "string"))
                append(stateVariable("CurrentConnectionIDs", "string", evented = true))
                append(stateVariable("A_ARG_TYPE_InstanceID", "ui4"))
                append(stateVariable("A_ARG_TYPE_ConnectionID", "i4"))
                append(stateVariable("A_ARG_TYPE_RcsID", "i4"))
                append(stateVariable("A_ARG_TYPE_AVTransportID", "i4"))
                append(stateVariable("A_ARG_TYPE_ProtocolInfo", "string"))
                append(stateVariable("A_ARG_TYPE_ConnectionManager", "string"))
                append(stateVariable("A_ARG_TYPE_Direction", "string", allowedValues = listOf("Input", "Output")))
                append(stateVariable("A_ARG_TYPE_ConnectionStatus", "string",
                    allowedValues = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")))
            },
        )
    }
}
