package com.tvcast.dlna.soap

import com.tvcast.dlna.ProtocolInfoList

/** ConnectionManager:1：宽泛静态 sink 列表（spec §4.9） */
class ConnectionManagerService : UpnpSoapService {

    override val serviceType: String = "urn:schemas-upnp-org:service:ConnectionManager:1"

    override fun invoke(action: String, args: Map<String, String>): SoapResult {
        return when (action) {
            "GetProtocolInfo" -> SoapResult.Ok(
                "GetProtocolInfo",
                mapOf(
                    "Source" to "",
                    "Sink" to ProtocolInfoList.sinkProtocolInfo(),
                ),
            )
            "GetCurrentConnectionIDs" -> SoapResult.Ok(
                "GetCurrentConnectionIDs",
                mapOf("ConnectionIDs" to "0"),
            )
            "GetCurrentConnectionInfo" -> SoapResult.Ok(
                "GetCurrentConnectionInfo",
                mapOf(
                    "RcsID" to "0",
                    "AVTransportID" to "0",
                    "ProtocolInfo" to "",
                    "PeerConnectionManager" to "",
                    "PeerConnectionID" to "-1",
                    "Direction" to "Input",
                    "Status" to "OK",
                ),
            )
            else -> SoapResult.Fault(401, "Invalid Action")
        }
    }
}
