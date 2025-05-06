package ai.pipecat.client.openai_realtime_webrtc

import ai.pipecat.client.types.Value
import kotlinx.serialization.Serializable

@Serializable
@ConsistentCopyVisibility
internal data class OpenAISessionUpdate private constructor(
    val type: String,
    val session: Value
) {
    companion object {
        fun of(session: Value) = OpenAISessionUpdate(
            type = "session.update",
            session = session
        )
    }
}
