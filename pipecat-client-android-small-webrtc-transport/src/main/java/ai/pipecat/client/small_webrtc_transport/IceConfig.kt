package ai.pipecat.client.small_webrtc_transport

import kotlinx.serialization.Serializable

@Serializable
data class IceConfig(
    val iceServers: List<IceServer>
)

@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)