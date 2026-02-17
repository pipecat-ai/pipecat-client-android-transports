package ai.pipecat.client.openai_realtime_webrtc

import ai.pipecat.client.types.LLMContextMessage
import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class OpenAIServiceOptions(
    @SerialName("api_key")
    val apiKey: String,
    @SerialName("session_config")
    val sessionConfig: OpenAIRealtimeSessionConfig,
    val model: String? = null,
    @SerialName("initial_messages")
    val initialMessages: List<LLMContextMessage> = listOf()
)
