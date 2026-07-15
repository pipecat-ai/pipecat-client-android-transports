package ai.pipecat.client.openai_realtime_webrtc

import ai.pipecat.client.types.Value
import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class OpenAIRealtimeSessionConfig(
    val modalities: List<String>? = null,
    val instructions: String? = null,
    val voice: String? = null,
    @SerialName("turn_detection")
    val turnDetection: Value? = null,
    @SerialName("input_audio_noise_reduction")
    val inputAudioNoiseReduction: Value? = null,
    val tools: Value? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    @Deprecated("Not supported by the GA Realtime API, this value is ignored")
    val temperature: Float? = null,
    @SerialName("input_audio_transcription")
    val inputAudioTranscription: Value? = null
) {
    /**
     * Converts this config to the session object shape expected by the GA
     * Realtime API (https://platform.openai.com/docs/api-reference/realtime),
     * which nests the audio settings under `audio.input`/`audio.output` and
     * requires an explicit session `type`.
     */
    internal fun toGaSessionConfig(model: String?): Value {

        val audioInput = buildMap {
            inputAudioTranscription?.let { put("transcription", it) }
            turnDetection?.let { put("turn_detection", it) }
            inputAudioNoiseReduction?.let { put("noise_reduction", it) }
        }

        val audioOutput = buildMap {
            voice?.let { put("voice", Value.Str(it)) }
        }

        return Value.Object(buildMap {
            put("type", Value.Str("realtime"))
            model?.let { put("model", Value.Str(it)) }
            modalities?.let { put("output_modalities", Value.Array(it.map(Value::Str))) }
            instructions?.let { put("instructions", Value.Str(it)) }
            tools?.let { put("tools", it) }
            toolChoice?.let { put("tool_choice", Value.Str(it)) }
            if (audioInput.isNotEmpty() || audioOutput.isNotEmpty()) {
                put("audio", Value.Object(buildMap {
                    if (audioInput.isNotEmpty()) put("input", Value.Object(audioInput))
                    if (audioOutput.isNotEmpty()) put("output", Value.Object(audioOutput))
                }))
            }
        })
    }
}
