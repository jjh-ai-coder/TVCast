package com.tvcast.dlna.soap

import com.tvcast.dlna.MediaKindResolver
import com.tvcast.dlna.RendererPlayer
import com.tvcast.dlna.TransportState
import com.tvcast.dlna.UpnpTime
import com.tvcast.dlna.didl.DidlParser

/**
 * AVTransport:1 动作实现。
 * 隐式播放：SetAVTransportURI 即准备并自动起播（多数国内 App 不再发 Play，spec §4.4）。
 * Next/Previous 空实现返回成功；未知动作（X_SetAVTransportURI 等）401。
 */
class AvTransportService(private val renderer: RendererPlayer) : UpnpSoapService {

    override val serviceType: String = "urn:schemas-upnp-org:service:AVTransport:1"

    override fun invoke(action: String, args: Map<String, String>): SoapResult {
        return when (action) {
            "SetAVTransportURI" -> setUri(args)
            "Play" -> {
                renderer.play()
                SoapResult.Ok("Play")
            }
            "Pause" -> {
                renderer.pause()
                SoapResult.Ok("Pause")
            }
            "Stop" -> {
                renderer.stop()
                SoapResult.Ok("Stop")
            }
            "Seek" -> seek(args)
            "Next" -> SoapResult.Ok("Next") // 空实现：返回成功即可
            "Previous" -> SoapResult.Ok("Previous")
            "GetPositionInfo" -> SoapResult.Ok("GetPositionInfo", positionInfo())
            "GetTransportInfo" -> SoapResult.Ok("GetTransportInfo", transportInfo())
            "GetMediaInfo" -> SoapResult.Ok("GetMediaInfo", mediaInfo())
            "GetCurrentTransportActions" -> SoapResult.Ok(
                "GetCurrentTransportActions",
                mapOf("Actions" to "Play,Pause,Stop,Seek,Next,Previous"),
            )
            else -> SoapResult.Fault(401, "Invalid Action")
        }
    }

    private fun setUri(args: Map<String, String>): SoapResult {
        val uri = args["currenturi"]
        if (uri.isNullOrEmpty()) return SoapResult.Fault(402, "CurrentURI required")
        val metaData = args["currenturimetadata"]
        // DIDL 宽容解析：任何失败只影响标题展示，不影响播放
        val didl = DidlParser.parse(metaData)
        val kind = MediaKindResolver.resolve(uri, didl.upnpClass)
        val title = didl.title ?: MediaKindResolver.fallbackTitle(uri)
        renderer.setMedia(uri, title, kind, metaData)
        return SoapResult.Ok("SetAVTransportURI")
    }

    private fun seek(args: Map<String, String>): SoapResult {
        val unit = args["unit"] ?: return SoapResult.Fault(402, "Unit required")
        val target = args["target"] ?: return SoapResult.Fault(402, "Target required")
        return when (unit.uppercase()) {
            "REL_TIME" -> {
                val ms = UpnpTime.parseToMs(target)
                if (ms == null) {
                    SoapResult.Fault(402, "Invalid seek target")
                } else {
                    renderer.seekTo(ms)
                    SoapResult.Ok("Seek")
                }
            }
            "ABS_TIME" -> {
                val ms = UpnpTime.parseToMs(target)
                if (ms == null) {
                    SoapResult.Fault(402, "Invalid seek target")
                } else {
                    renderer.seekTo(ms)
                    SoapResult.Ok("Seek")
                }
            }
            "TRACK_NR" -> SoapResult.Ok("Seek") // 单曲目设备：切曲目即无操作
            else -> SoapResult.Fault(710, "Seek mode not supported")
        }
    }

    private fun positionInfo(): Map<String, String> {
        val duration = if (renderer.kind == com.tvcast.dlna.MediaKind.IMAGE) {
            "0:00:00"
        } else {
            UpnpTime.formatMs(renderer.durationMs)
        }
        val relTime = if (renderer.kind == com.tvcast.dlna.MediaKind.IMAGE) {
            "0:00:00"
        } else {
            UpnpTime.formatMs(renderer.positionMs)
        }
        return mapOf(
            "Track" to "1",
            "TrackDuration" to duration,
            "TrackMetaData" to (renderer.currentMetaData ?: "NOT_IMPLEMENTED"),
            "TrackURI" to (renderer.uri ?: ""),
            "RelTime" to relTime,
            "AbsTime" to relTime,
            "RelCount" to "2147483647",
            "AbsCount" to "2147483647",
        )
    }

    private fun transportInfo(): Map<String, String> = mapOf(
        "CurrentTransportState" to renderer.state.name,
        "CurrentTransportStatus" to if (renderer.lastError != null) "ERROR_OCCURRED" else "OK",
        "CurrentSpeed" to "1",
    )

    private fun mediaInfo(): Map<String, String> = mapOf(
        "NrTracks" to if (renderer.uri != null) "1" else "0",
        "MediaDuration" to UpnpTime.formatMs(renderer.durationMs),
        "CurrentURI" to (renderer.uri ?: ""),
        "CurrentURIMetaData" to (renderer.currentMetaData ?: ""),
    )
}
