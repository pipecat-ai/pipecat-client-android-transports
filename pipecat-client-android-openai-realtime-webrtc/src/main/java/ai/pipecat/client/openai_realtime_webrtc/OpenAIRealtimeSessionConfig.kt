package ai.pipecat.client.openai_realtime_webrtc

import ai.pipecat.client.types.Value
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenAIRealtimeSessionConfig(
    val modalities: List<String>? = null,
    val instructions: String? = null,
    val voice: String? = null,
    @SerialName("turn_detection")
    val turnDetection: Value? = null,
    val tools: Value? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    val temperature: Float? = null,
)
