package ai.pipecat.client.openai_realtime_webrtc

import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAIEvent(
    val type: String,
    val delta: String? = null,
    val error: Error? = null,
    val transcript: String? = null
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