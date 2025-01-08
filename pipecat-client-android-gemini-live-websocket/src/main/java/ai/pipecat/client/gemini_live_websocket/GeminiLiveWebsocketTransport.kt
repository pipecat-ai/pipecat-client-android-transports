package ai.pipecat.client.gemini_live_websocket

import ai.pipecat.client.result.Future
import ai.pipecat.client.result.RTVIError
import ai.pipecat.client.result.resolvedPromiseErr
import ai.pipecat.client.result.resolvedPromiseOk
import ai.pipecat.client.result.withPromise
import ai.pipecat.client.transport.AuthBundle
import ai.pipecat.client.transport.MsgClientToServer
import ai.pipecat.client.transport.MsgServerToClient
import ai.pipecat.client.transport.Transport
import ai.pipecat.client.transport.TransportContext
import ai.pipecat.client.transport.TransportFactory
import ai.pipecat.client.types.MediaDeviceId
import ai.pipecat.client.types.MediaDeviceInfo
import ai.pipecat.client.types.Option
import ai.pipecat.client.types.Participant
import ai.pipecat.client.types.ParticipantId
import ai.pipecat.client.types.ParticipantTracks
import ai.pipecat.client.types.ServiceConfig
import ai.pipecat.client.types.Tracks
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import ai.pipecat.client.types.getOptionsFor
import ai.pipecat.client.types.getValueFor
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement


private val JSON = Json { ignoreUnknownKeys = true }

private val BOT_PARTICIPANT = Participant(
    id = ParticipantId("bot"),
    name = null,
    local = false
)

private val LOCAL_PARTICIPANT = Participant(
    id = ParticipantId("local"),
    name = null,
    local = true
)

class GeminiLiveWebsocketTransport(
    private val transportContext: TransportContext,
    androidContext: Context
) : Transport() {

    companion object {
        private const val TAG = "DailyTransport"

        private const val SERVICE_LLM = "llm"
        private const val OPTION_API_KEY = "api_key"
        private const val OPTION_INITIAL_USER_MESSAGE = "initial_user_message"
        private const val OPTION_MODEL_CONFIG = "model_config"

        fun buildConfig(
            apiKey: String,
            model: String = "models/gemini-2.0-flash-exp",
            initialUserMessage: String? = null,
            generationConfig: Value.Object = Value.Object(),
            systemInstruction: String? = null,
            tools: Value.Array = Value.Array()
        ): List<ServiceConfig> = listOf(
            ServiceConfig(
                SERVICE_LLM, listOf(
                    Option(OPTION_API_KEY, apiKey),
                    Option(
                        OPTION_INITIAL_USER_MESSAGE,
                        initialUserMessage?.let { Value.Str(it) } ?: Value.Null),
                    Option(
                        OPTION_MODEL_CONFIG, Value.Object(
                            "model" to Value.Str(model),
                            "generation_config" to generationConfig,
                            "system_instruction" to (systemInstruction?.let { Value.Str(it) }
                                ?: Value.Null),
                            "tools" to tools,
                        )
                    )
                )
            )
        )
    }

    object AudioDevices {
        val Speakerphone = MediaDeviceInfo(
            id = MediaDeviceId("speakerphone"),
            name = "Speakerphone"
        )

        val Earpiece = MediaDeviceInfo(
            id = MediaDeviceId("earpiece"),
            name = "Earpiece"
        )
    }

    class Factory(private val androidContext: Context) : TransportFactory {
        override fun createTransport(context: TransportContext): Transport {
            return GeminiLiveWebsocketTransport(context, androidContext)
        }
    }

    private var state = TransportState.Disconnected

    private val appContext = androidContext.applicationContext
    private val thread = transportContext.thread

    private var client: GeminiClient? = null

    override fun initDevices(): Future<Unit, RTVIError> = resolvedPromiseOk(thread, Unit)

    @SuppressLint("MissingPermission")
    override fun connect(authBundle: AuthBundle?): Future<Unit, RTVIError> =
        thread.runOnThreadReturningFuture {

            Log.i(TAG, "connect(${authBundle})")

            if (client != null) {
                return@runOnThreadReturningFuture resolvedPromiseErr(
                    thread,
                    RTVIError.OtherError("Connection already active")
                )
            }

            transportContext.callbacks.onInputsUpdated(
                camera = false,
                mic = transportContext.options.enableMic
            )

            setState(TransportState.Connecting)

            return@runOnThreadReturningFuture initDevices()
                .withErrorCallback { setState(TransportState.Error) }
                .chain { _ ->

                    val options = transportContext.options.params.config.getOptionsFor(SERVICE_LLM)

                    val apiKey = (options?.getValueFor(OPTION_API_KEY) as? Value.Str)?.value
                    val modelConfig = options?.getValueFor(OPTION_MODEL_CONFIG)
                    val initialUserMessage =
                        (options?.getValueFor(OPTION_INITIAL_USER_MESSAGE) as? Value.Str)?.value

                    if (apiKey == null) {
                        return@chain resolvedPromiseErr(
                            thread,
                            RTVIError.OtherError("Ensure $OPTION_API_KEY is set in llm service options")
                        )
                    }

                    if (modelConfig == null) {
                        return@chain resolvedPromiseErr(
                            thread,
                            RTVIError.OtherError("Ensure $OPTION_MODEL_CONFIG is set in llm service options")
                        )
                    }

                    withPromise(thread) { promise ->
                        client = GeminiClient.connect(
                            GeminiClient.Config(
                                apiKey = apiKey,
                                modelConfig = modelConfig,
                                initialMessage = initialUserMessage,
                                initialMicEnabled = transportContext.options.enableMic
                            ),
                            object : GeminiClient.Listener {
                                override fun onConnected() {
                                    thread.runOnThread {
                                        val cb = transportContext.callbacks
                                        setState(TransportState.Connected)
                                        cb.onConnected()
                                        cb.onParticipantJoined(LOCAL_PARTICIPANT)
                                        cb.onParticipantJoined(BOT_PARTICIPANT)
                                        setState(TransportState.Ready)
                                        cb.onBotReady("local", emptyList())
                                        promise.resolveOk(Unit)
                                    }
                                }

                                override fun onSessionEnded(reason: String, t: Throwable?) {
                                    thread.runOnThread {
                                        Log.i(TAG, "onSessionEnded: $reason", t)
                                        setState(TransportState.Disconnected)
                                        transportContext.callbacks.onDisconnected()
                                        promise.resolveErr(RTVIError.OtherError("Session disconnected: $reason"))
                                        t?.let {
                                            Log.e(TAG, "Session ended with exception", it)
                                        }
                                    }
                                }

                                override fun onBotTalking(isTalking: Boolean) {
                                    thread.runOnThread {
                                        transportContext.callbacks.apply {
                                            if (isTalking) {
                                                onBotStartedSpeaking()
                                            } else {
                                                onBotStoppedSpeaking()
                                            }
                                        }
                                    }
                                }

                                override fun onBotAudioLevel(level: Float) {
                                    thread.runOnThread {
                                        transportContext.callbacks.onRemoteAudioLevel(
                                            level,
                                            BOT_PARTICIPANT
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
        }

    override fun disconnect(): Future<Unit, RTVIError> = thread.runOnThreadReturningFuture {
        client?.close()
        resolvedPromiseOk(thread, Unit)
    }

    override fun sendMessage(message: MsgClientToServer): Future<Unit, RTVIError> {

        when (message.type) {
            "action" -> {
                try {
                    val data =
                        JSON.decodeFromJsonElement<MsgClientToServer.Data.Action>(message.data!!)

                    when (data.action) {

                        "append_to_messages" -> {
                            val messages: List<Value.Object> =
                                (data.arguments.getValueFor("messages") as Value.Array).value.map { it as Value.Object }

                            for (appendedMessage in messages) {

                                val role = appendedMessage.value["role"] as Value.Str
                                val content = appendedMessage.value["content"] as Value.Str

                                Log.i(TAG, "Sending message as ${role.value}: '${content.value}'")

                                client?.sendUserMessage(role = role.value, content = content.value)
                            }

                            transportContext.onMessage(
                                MsgServerToClient(
                                    id = message.id,
                                    label = message.label,
                                    type = MsgServerToClient.Type.ActionResponse,
                                    data = JSON.encodeToJsonElement(
                                        MsgServerToClient.Data.ActionResponse(
                                            Value.Null
                                        )
                                    )
                                )
                            )

                            return resolvedPromiseOk(thread, Unit)
                        }

                        else -> {
                            return operationNotSupported()
                        }
                    }

                } catch (e: Exception) {
                    return resolvedPromiseErr(thread, RTVIError.ExceptionThrown(e))
                }
            }

            else -> {
                return operationNotSupported()
            }
        }
    }

    override fun state(): TransportState {
        return state
    }

    override fun setState(state: TransportState) {
        Log.i(TAG, "setState($state)")
        thread.assertCurrent()
        this.state = state
        transportContext.callbacks.onTransportStateChanged(state)
    }

    override fun getAllCams(): Future<List<MediaDeviceInfo>, RTVIError> =
        resolvedPromiseOk(thread, emptyList())

    override fun getAllMics(): Future<List<MediaDeviceInfo>, RTVIError> =
        resolvedPromiseOk(thread, listOf(AudioDevices.Earpiece, AudioDevices.Speakerphone))

    override fun updateMic(micId: MediaDeviceId): Future<Unit, RTVIError> {

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setSpeakerphoneOn(micId == AudioDevices.Speakerphone.id)

        return resolvedPromiseOk(thread, Unit)
    }

    override fun updateCam(camId: MediaDeviceId) = operationNotSupported<Unit>()

    override fun selectedMic(): MediaDeviceInfo {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return when (audioManager.isSpeakerphoneOn) {
            true -> AudioDevices.Speakerphone
            false -> AudioDevices.Earpiece
        }
    }

    override fun selectedCam() = null

    override fun isCamEnabled() = false

    override fun isMicEnabled() = client?.micMuted?.not() ?: false

    override fun enableMic(enable: Boolean): Future<Unit, RTVIError> {
        client?.micMuted = !enable
        thread.runOnThread {
            transportContext.callbacks.onInputsUpdated(camera = false, mic = enable)
        }
        return resolvedPromiseOk(thread, Unit)
    }

    override fun expiry() = null

    override fun enableCam(enable: Boolean) = operationNotSupported<Unit>()

    override fun tracks() = Tracks(
        local = ParticipantTracks(null, null),
        bot = null,
    )

    override fun release() {
        thread.assertCurrent()
        client?.close()
        client = null
    }

    private fun <E> operationNotSupported(): Future<E, RTVIError> =
        resolvedPromiseErr(thread, RTVIError.OtherError("Operation not supported"))
}
