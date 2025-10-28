package ai.pipecat.client.small_webrtc_transport

import ai.pipecat.client.types.Value
import kotlinx.serialization.Serializable

@Serializable
internal data class SmallWebRTCStartBotResult(
    val sessionId: String,
    val iceConfig: Value = Value.Null
)