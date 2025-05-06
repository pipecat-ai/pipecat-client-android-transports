package ai.pipecat.client.small_webrtc_transport

import kotlinx.serialization.Serializable

@Serializable
internal data class MessageWithType(
    val type: String? = null
)