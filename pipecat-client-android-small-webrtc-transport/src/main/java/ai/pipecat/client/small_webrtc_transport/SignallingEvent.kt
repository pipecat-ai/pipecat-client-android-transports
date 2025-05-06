package ai.pipecat.client.small_webrtc_transport

import kotlinx.serialization.Serializable

@Serializable
data class SignallingEvent(
    val type: String,
    val message: SignallingMessage
)

@Serializable
data class SignallingMessage(
    val type: String
)