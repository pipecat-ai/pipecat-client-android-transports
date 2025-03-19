package ai.pipecat.client.openai_realtime_webrtc

import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAIEvent(
    val type: String,
    val delta: String? = null,
)