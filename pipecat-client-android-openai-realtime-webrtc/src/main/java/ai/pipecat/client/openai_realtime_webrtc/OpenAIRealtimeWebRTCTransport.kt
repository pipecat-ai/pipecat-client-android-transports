package ai.pipecat.client.openai_realtime_webrtc

import ai.pipecat.client.result.Future
import ai.pipecat.client.result.RTVIError
import ai.pipecat.client.result.resolvedPromiseErr
import ai.pipecat.client.result.resolvedPromiseOk
import ai.pipecat.client.result.withPromise
import ai.pipecat.client.transport.MsgClientToServer
import ai.pipecat.client.transport.MsgServerToClient
import ai.pipecat.client.transport.Transport
import ai.pipecat.client.transport.TransportContext
import ai.pipecat.client.types.APIRequest
import ai.pipecat.client.types.BotOutputData
import ai.pipecat.client.types.BotReadyData
import ai.pipecat.client.types.LLMContextMessage
import ai.pipecat.client.types.LLMFunctionCallData
import ai.pipecat.client.types.LLMFunctionCallResult
import ai.pipecat.client.types.MediaDeviceId
import ai.pipecat.client.types.MediaDeviceInfo
import ai.pipecat.client.types.Participant
import ai.pipecat.client.types.ParticipantId
import ai.pipecat.client.types.ParticipantTracks
import ai.pipecat.client.types.Tracks
import ai.pipecat.client.types.Transcript
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import ai.pipecat.client.utils.ThreadRef
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

private inline fun <reified E> E.convertToValue() =
    JSON.decodeFromJsonElement<Value>(JSON.encodeToJsonElement(this))

class OpenAIRealtimeWebRTCTransport(
    androidContext: Context,
) : Transport<OpenAIServiceOptions>() {

    companion object {
        private const val TAG = "OpenAIRealtimeWebRTCTransport"
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

    private var state = TransportState.Disconnected

    private val appContext = androidContext.applicationContext

    private lateinit var transportContext: TransportContext
    private lateinit var thread: ThreadRef

    private var options: OpenAIServiceOptions? = null

    private var client: WebRTCClient? = null

    private val eventHandler = { msgString: String ->

        val msgJson = JSON_INSTANCE.parseToJsonElement(msgString)

        val msg = JSON_INSTANCE.decodeFromJsonElement<OpenAIEvent>(msgJson)

        thread.runOnThread {

            transportContext.callbacks.onServerMessage(
                JSON_INSTANCE.decodeFromJsonElement(
                    Value.serializer(),
                    msgJson
                )
            );

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
                        transportContext.callbacks.apply {
                            onBotTTSText(MsgServerToClient.Data.BotTTSTextData(msg.delta))
                            onBotOutput(BotOutputData(
                                text = msg.delta,
                                spoken = true,
                                aggregatedBy = "word"
                            ))
                        }
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

                    val data = LLMFunctionCallData(
                        functionName = msg.name,
                        toolCallID = msg.callId,
                        args = JSON_INSTANCE.encodeToJsonElement(msg.arguments)
                    )

                    transportContext.onMessage(
                        MsgServerToClient(
                            id = null,
                            label = "rtvi-ai",
                            type = "llm-function-call",
                            data = JSON.encodeToJsonElement(data)
                        )
                    )
                }

                else -> {

                }
            }
        }
    }

    override fun initialize(ctx: TransportContext) {
        transportContext = ctx
        thread = ctx.thread
    }

    override fun deserializeConnectParams(
        json: String,
        startBotRequest: APIRequest
    ) = JSON_INSTANCE.decodeFromString<OpenAIServiceOptions>(json)

    override fun initDevices() = resolvedPromiseOk<Unit, RTVIError>(thread, Unit)

    @SuppressLint("MissingPermission")
    override fun connect(transportParams: OpenAIServiceOptions): Future<Unit, RTVIError> =
        thread.runOnThreadReturningFuture {

            Log.i(TAG, "connect(${transportParams})")

            if (client != null) {
                return@runOnThreadReturningFuture resolvedPromiseErr(
                    thread,
                    RTVIError.OtherError("Connection already active")
                )
            }

            options = transportParams

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

            val apiKey = transportParams.apiKey
            val model = transportParams.model ?: "gpt-realtime"

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
                        cb.onBotReady(BotReadyData(version = model))
                        promise.resolveOk(Unit)

                    } catch (e: Exception) {
                        promise.resolveErr(RTVIError.ExceptionThrown(e))
                    }
                }
            }
        }

    private fun onSessionCreated() {

        options?.let { options ->
            sendConfigUpdate(options.sessionConfig.convertToValue())

            if (options.initialMessages.isNotEmpty()) {
                for (message in options.initialMessages) {
                    sendConversationMessage(role = message.role.value, text = message.content)
                }

                requestResponseFromBot()
            }
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

    override fun sendMessage(message: MsgClientToServer) = thread.runOnThreadReturningFuture {

        if (client == null) {
            return@runOnThreadReturningFuture resolvedPromiseErr(
                thread,
                RTVIError.TransportNotInitialized
            )
        }

        when (message.type) {
            MsgClientToServer.Type.SendText -> {

                val data =
                    JSON.decodeFromJsonElement<MsgClientToServer.Data.SendText>(message.data!!)

                sendConversationMessage(
                    role = LLMContextMessage.Role.User.value,
                    text = data.content
                )

                if (data.options.runImmediately != false) {
                    requestResponseFromBot()
                }

                resolvedPromiseOk(thread, Unit)
            }

            MsgClientToServer.Type.LlmFunctionCallResult -> {

                val messageData =
                    message.data ?: return@runOnThreadReturningFuture resolvedPromiseErr(
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
                            "call_id" to Value.Str(data.toolCallID),
                            "output" to Value.Str(JSON.encodeToString(data.result))
                        )
                    )
                )

                requestResponseFromBot()

                resolvedPromiseOk(thread, Unit)
            }

            "custom-request" -> {
                val messageData =
                    message.data ?: return@runOnThreadReturningFuture resolvedPromiseErr(
                        thread,
                        RTVIError.OtherError("Custom request data is null")
                    )

                client?.sendDataMessage(JsonElement.serializer(), messageData)

                resolvedPromiseOk(thread, Unit)
            }

            else -> {
                operationNotSupported()
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
