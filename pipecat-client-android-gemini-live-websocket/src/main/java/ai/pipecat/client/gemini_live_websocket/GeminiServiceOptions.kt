package ai.pipecat.client.gemini_live_websocket

import ai.pipecat.client.types.Value
import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class GeminiServiceOptions(
    @SerialName("api_key")
    val apiKey: String,
    @SerialName("model_config")
    val modelConfig: Value,
    @SerialName("initial_user_message")
    val initialUserMessage: String? = null
) {
    companion object {
        fun withDefaults(
            apiKey: String,
            model: String = "models/gemini-3.1-flash-live-preview",
            initialUserMessage: String? = null,
            voice: String = "Puck",
            systemInstruction: Value? = null,
            tools: Value.Array = Value.Array()
        ) = GeminiServiceOptions(
            apiKey = apiKey,
            initialUserMessage = initialUserMessage,
            modelConfig = Value.Object(
                "model" to Value.Str(model),
                "generation_config" to Value.Object(
                    // response_modalities is a repeated field, so must be a list
                    "response_modalities" to Value.Array(Value.Str("AUDIO")),
                    "speech_config" to Value.Object(
                        "voice_config" to Value.Object(
                            "prebuiltVoiceConfig" to Value.Object(
                                "voice_name" to Value.Str(voice),
                            )
                        )
                    )
                ),
                "system_instruction" to (systemInstruction ?: Value.Null),
                "tools" to tools,
            )
        )
    }
}
