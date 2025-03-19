package ai.pipecat.client.openai_realtime_webrtc

import kotlinx.serialization.Serializable

@Serializable
data class OpenAIResponseCreate private constructor(
    val type: String,
) {
    companion object {
        fun new() = OpenAIResponseCreate(
            type = "response.create",
        )
    }
}
