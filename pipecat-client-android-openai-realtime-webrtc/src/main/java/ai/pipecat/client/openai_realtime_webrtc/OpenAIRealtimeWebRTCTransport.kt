package ai.pipecat.client.openai_realtime_webrtc

import ai.pipecat.client.helper.LLMContextMessage
import ai.pipecat.client.helper.LLMFunctionCall
import ai.pipecat.client.helper.LLMFunctionCallResult
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
import ai.pipecat.client.types.Transcript
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import ai.pipecat.client.types.getOptionsFor
import ai.pipecat.client.types.getValueFor
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
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

private inline fun <reified E> E.convertToValue(serializer: KSerializer<E>) =
    JSON.decodeFromJsonElement<Value>(JSON.encodeToJsonElement(serializer, this))

private inline fun <reified E> Value.convertFromValue(serializer: KSerializer<E>): E =
    JSON.decodeFromJsonElement(serializer, JSON.encodeToJsonElement(Value.serializer(), this))

class OpenAIRealtimeWebRTCTransport(
    private val transportContext: TransportContext,
    androidContext: Context
) : Transport() {

    companion object {
        private const val TAG = "OpenAIRealtimeWebRTCTransport"

        private const val SERVICE_LLM = "llm"
        private const val OPTION_API_KEY = "api_key"
        private const val OPTION_INITIAL_MESSAGES = "initial_messages"
        private const val OPTION_INITIAL_CONFIG = "initial_config"
        private const val OPTION_MODEL = "model"

        fun buildConfig(
            apiKey: String,
            model: String = "gpt-4o-realtime-preview-2024-12-17",
            initialMessages: List<LLMContextMessage>? = null,
            initialConfig: OpenAIRealtimeSessionConfig? = null
        ): List<ServiceConfig> {

            val options = mutableListOf(
                Option(OPTION_API_KEY, apiKey),
                Option(OPTION_MODEL, model)
            )

            if (initialConfig != null) {
                options.add(
                    Option(
                        OPTION_INITIAL_CONFIG, initialConfig.convertToValue(
                            OpenAIRealtimeSessionConfig.serializer()
                        )
                    )
                )
            }

            if (initialMessages != null) {
                options.add(Option(OPTION_INITIAL_MESSAGES, Value.Array(initialMessages.map {
                    it.convertToValue(LLMContextMessage.serializer())
                })))
            }

            return listOf(ServiceConfig(SERVICE_LLM, options))
        }
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
            return OpenAIRealtimeWebRTCTransport(context, androidContext)
        }
    }

    private var state = TransportState.Disconnected

    private val appContext = androidContext.applicationContext
    private val thread = transportContext.thread

    private var client: WebRTCClient? = null

    private val eventHandler = { msg: OpenAIEvent ->

        thread.runOnThread {
            when (msg.type) {
                "error" -> {
                    if (msg.error != null) {
                        transportContext.callbacks.onBackendError(msg.error.describe() ?: "<null>")
                    }
                }

                "session.created" -> {
                    onSessionCreated()
                }

                "input_audio_buffer.speech_started" -> {
                    transportContext.callbacks.onUserStartedSpeaking()
                }

                "input_audio_buffer.speech_stopped" -> {
                    transportContext.callbacks.onUserStoppedSpeaking()
                }

                "response.audio_transcript.delta" -> {
                    if (msg.delta != null) {
                        transportContext.callbacks.onBotTTSText(
                            MsgServerToClient.Data.BotTTSTextData(
                                msg.delta
                            )
                        )
                    }
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    if (msg.transcript != null) {
                        transportContext.callbacks.onUserTranscript(
                            Transcript(
                                text = msg.transcript,
                                final = true
                            )
                        )
                    }
                }

                "output_audio_buffer.started" -> {
                    transportContext.callbacks.onBotStartedSpeaking()
                }

                "output_audio_buffer.cleared", "output_audio_buffer.stopped" -> {
                    transportContext.callbacks.onBotStoppedSpeaking()
                }

                "response.function_call_arguments.done" -> {

                    if (msg.name == null || msg.callId == null || msg.arguments == null) {
                        Log.e(TAG, "Ignoring function call response with null arguments")
                        return@runOnThread
                    }

                    val data = LLMFunctionCall(
                        functionName = msg.name,
                        toolCallId = msg.callId,
                        args = msg.arguments
                    )

                    transportContext.onMessage(MsgServerToClient(
                        id = null,
                        label = "rtvi-ai",
                        type = "llm-function-call",
                        data = JSON.encodeToJsonElement(data)
                    ))
                }

                else -> {
                    Log.i(TAG, "Ignoring incoming event with type '${msg.type}'")
                }
            }
        }
    }

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

            try {
                client = WebRTCClient(eventHandler, appContext)
            } catch (e: Exception) {
                return@runOnThreadReturningFuture resolvedPromiseErr(
                    thread,
                    RTVIError.ExceptionThrown(e)
                )
            }

            enableMic(transportContext.options.enableMic)

            val options = transportContext.options.params.config.getOptionsFor(SERVICE_LLM)

            val apiKey = (options?.getValueFor(OPTION_API_KEY) as? Value.Str)?.value
            val model = (options?.getValueFor(OPTION_MODEL) as? Value.Str)?.value


            if (apiKey == null) {
                return@runOnThreadReturningFuture resolvedPromiseErr(
                    thread,
                    RTVIError.OtherError("Ensure $OPTION_API_KEY is set in llm service options")
                )
            }

            if (model == null) {
                return@runOnThreadReturningFuture resolvedPromiseErr(
                    thread,
                    RTVIError.OtherError("Ensure $OPTION_MODEL is set in llm service options")
                )
            }

            withPromise(thread) { promise ->

                MainScope().launch {

                    try {
                        client?.negotiateConnection(
                            baseUrl = "https://api.openai.com/v1/realtime",
                            apiKey = apiKey,
                            model = model
                        )

                        val cb = transportContext.callbacks
                        setState(TransportState.Connected)
                        cb.onConnected()
                        cb.onParticipantJoined(LOCAL_PARTICIPANT)
                        cb.onParticipantJoined(BOT_PARTICIPANT)
                        setState(TransportState.Ready)
                        cb.onBotReady("local", emptyList())
                        promise.resolveOk(Unit)

                    } catch (e: Exception) {
                        promise.resolveErr(RTVIError.ExceptionThrown(e))
                    }
                }
            }
        }

    private fun onSessionCreated() {
        val options = transportContext.options.params.config.getOptionsFor(SERVICE_LLM)

        val initialMessages =
            (options?.getValueFor(OPTION_INITIAL_MESSAGES) as? Value.Array)?.value

        val initialConfig = options?.getValueFor(OPTION_INITIAL_CONFIG)

        if (initialConfig != null) {
            sendConfigUpdate(initialConfig)
        }

        if (initialMessages != null) {
            for (message in initialMessages.map { it.convertFromValue(LLMContextMessage.serializer()) }) {
                sendConversationMessage(role = message.role, text = message.content)
            }
            requestResponseFromBot()
        }
    }

    fun sendConfigUpdate(config: Value) {
        client?.sendDataMessage(
            OpenAISessionUpdate.serializer(),
            OpenAISessionUpdate.of(config)
        )
    }

    fun sendConversationMessage(role: String, text: String) {
        client?.sendDataMessage(
            OpenAIConversationItemCreate.serializer(),
            OpenAIConversationItemCreate.of(
                OpenAIConversationItemCreate.Item.message(
                    role = role,
                    text = text
                )
            )
        )
    }

    fun requestResponseFromBot() {
        client?.sendDataMessage(
            OpenAIResponseCreate.serializer(),
            OpenAIResponseCreate.new()
        )
    }

    override fun disconnect(): Future<Unit, RTVIError> = thread.runOnThreadReturningFuture {
        withPromise(thread) { promise ->

            val clientRef = client
            client = null

            MainScope().launch {
                try {
                    if (clientRef != null) {
                        clientRef.dispose()
                        setState(TransportState.Disconnected)
                        transportContext.callbacks.onDisconnected()
                    }
                    promise.resolveOk(Unit)

                } catch (e: Exception) {
                    promise.resolveErr(RTVIError.ExceptionThrown(e))
                }
            }
        }
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

                                sendConversationMessage(role = role.value, text = content.value)
                            }

                            requestResponseFromBot()

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

            "llm-function-call-result" -> {

                val messageData = message.data ?: return resolvedPromiseErr(
                    thread,
                    RTVIError.OtherError("Function call result must not be null")
                )

                val data: LLMFunctionCallResult = JSON.decodeFromJsonElement(messageData)

                client?.sendDataMessage(
                    Value.serializer(),
                    Value.Object(
                        "type" to Value.Str("conversation.item.create"),
                        "item" to Value.Object(
                            "type" to Value.Str("function_call_output"),
                            "call_id" to Value.Str(data.toolCallId),
                            "output" to Value.Str(JSON.encodeToString(data.result))
                        )
                    )
                )

                requestResponseFromBot()

                return resolvedPromiseOk(thread, Unit)
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

    override fun isMicEnabled() = client?.isAudioTrackEnabled() ?: false

    override fun enableMic(enable: Boolean): Future<Unit, RTVIError> {
        thread.runOnThread {
            client?.setAudioTrackEnabled(enable)
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
        disconnect().logError(TAG, "Disconnect triggered by release() failed")
    }

    private fun <E> operationNotSupported(): Future<E, RTVIError> =
        resolvedPromiseErr(thread, RTVIError.OtherError("Operation not supported"))
}
