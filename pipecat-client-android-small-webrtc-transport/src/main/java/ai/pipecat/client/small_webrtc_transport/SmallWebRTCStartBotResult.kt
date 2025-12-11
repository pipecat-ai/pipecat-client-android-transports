package ai.pipecat.client.small_webrtc_transport

import kotlinx.serialization.Serializable

@Serializable
internal data class SmallWebRTCStartBotResult(
    val sessionId: String,
    val iceConfig: IceConfig? = null
)