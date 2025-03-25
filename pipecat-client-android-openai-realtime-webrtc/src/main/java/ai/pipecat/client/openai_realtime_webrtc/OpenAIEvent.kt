package ai.pipecat.client.openai_realtime_webrtc

import ai.pipecat.client.types.Value
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAIEvent(
    val type: String,
    val delta: String? = null,
    val error: Error? = null,
    val transcript: String? = null,
    val name: String? = null,
    @SerialName("call_id")
    val callId: String? = null,
    val arguments: Value? = null,
) {
    @Serializable
    internal data class Error(
        val type: String? = null,
        val code: String? = null,
        val message: String? = null
    ) {
        fun describe() = message ?: code ?: type
    }
}