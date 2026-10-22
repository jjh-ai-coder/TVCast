package com.tvcast.dlna.gena

import com.tvcast.dlna.ProtocolInfoList
import com.tvcast.dlna.RendererPlayer
import com.tvcast.dlna.UpnpTime
import com.tvcast.dlna.VolumeControl
import com.tvcast.dlna.Xml

/**
 * GENA NOTIFY 消息体：AVT/RCS 走 LastChange（嵌套 Event XML 整体转义后包进 propertyset），
 * CM 走直属属性（SourceProtocolInfo/SinkProtocolInfo），spec §4.5。
 */
object LastChangeXml {

    private const val AVT_NS = "urn:schemas-upnp-org:metadata-1-0/AVT/"
    private const val RCS_NS = "urn:schemas-upnp-org:metadata-1-0/RCS/"

    fun avtSnapshot(r: RendererPlayer): String =
        propertyset(event(AVT_NS, avtFields(r)))

    fun rcsSnapshot(v: VolumeControl): String =
        propertyset(event(RCS_NS, rcsFields(v)))

    fun cmSnapshot(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">")
        append("<e:property><SourceProtocolInfo></SourceProtocolInfo></e:property>")
        append("<e:property><SinkProtocolInfo>${Xml.escape(ProtocolInfoList.sinkProtocolInfo())}</SinkProtocolInfo></e:property>")
        append("</e:propertyset>")
    }

    private fun propertyset(innerEventXml: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">")
        append("<e:property><LastChange>${Xml.escape(innerEventXml)}</LastChange></e:property>")
        append("</e:propertyset>")
    }

    private fun event(namespace: String, fields: String): String =
        "<Event xmlns=\"$namespace\"><InstanceID val=\"0\">$fields</InstanceID></Event>"

    private fun attr(name: String, value: String): String =
        "<$name val=\"${Xml.escape(value)}\"/>"

    private fun channelAttr(name: String, value: String): String =
        "<$name channel=\"Master\" val=\"${Xml.escape(value)}\"/>"

    /** 全量快照：CP 拿到即可刷新整屏状态（进度条由 GetPositionInfo 轮询） */
    private fun avtFields(r: RendererPlayer): String = buildString {
        append(attr("TransportState", r.state.name))
        append(attr("TransportStatus", if (r.lastError != null) "ERROR_OCCURRED" else "OK"))
        append(attr("TransportPlaySpeed", "1"))
        append(attr("NumberOfTracks", if (r.uri != null) "1" else "0"))
        append(attr("CurrentTrack", if (r.uri != null) "1" else "0"))
        append(attr("CurrentTrackDuration", UpnpTime.formatMs(r.durationMs)))
        append(attr("CurrentMediaDuration", UpnpTime.formatMs(r.durationMs)))
        append(attr("CurrentTrackMetaData", r.currentMetaData ?: ""))
        append(attr("CurrentTrackURI", r.uri ?: ""))
        append(attr("AVTransportURI", r.uri ?: ""))
        append(attr("AVTransportURIMetaData", r.currentMetaData ?: ""))
    }

    private fun rcsFields(v: VolumeControl): String = buildString {
        append(channelAttr("Volume", v.volumePercent.toString()))
        append(channelAttr("Mute", if (v.muted) "1" else "0"))
    }
}
