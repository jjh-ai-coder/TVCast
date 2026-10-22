package com.tvcast.dlna.soap

import com.tvcast.dlna.VolumeControl

/** RenderingControl:1 → 系统音量（只处理 Master 声道，spec §4.6） */
class RenderingControlService(private val volume: VolumeControl) : UpnpSoapService {

    override val serviceType: String = "urn:schemas-upnp-org:service:RenderingControl:1"

    override fun invoke(action: String, args: Map<String, String>): SoapResult {
        return when (action) {
            "SetVolume" -> {
                val desired = args["desiredvolume"]?.toIntOrNull()
                    ?: return SoapResult.Fault(402, "DesiredVolume required")
                if (desired !in 0..100) return SoapResult.Fault(402, "DesiredVolume out of range")
                volume.setVolume(desired)
                SoapResult.Ok("SetVolume")
            }
            "GetVolume" -> SoapResult.Ok(
                "GetVolume",
                mapOf("CurrentVolume" to volume.volumePercent.toString()),
            )
            "SetMute" -> {
                val desired = args["desiredmute"]?.lowercase()
                    ?: return SoapResult.Fault(402, "DesiredMute required")
                volume.setMuted(desired == "1" || desired == "true")
                SoapResult.Ok("SetMute")
            }
            "GetMute" -> SoapResult.Ok(
                "GetMute",
                mapOf("CurrentMute" to if (volume.muted) "1" else "0"),
            )
            else -> SoapResult.Fault(401, "Invalid Action")
        }
    }
}
