package ai.pipecat.client.gemini_live_websocket

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
import ai.pipecat.client.types.BotReadyData
import ai.pipecat.client.types.LLMFunctionCallData
import ai.pipecat.client.types.LLMFunctionCallResult
import ai.pipecat.client.types.MediaDeviceId
import ai.pipecat.client.types.MediaDeviceInfo
import ai.pipecat.client.types.Participant
import ai.pipecat.client.types.ParticipantId
import ai.pipecat.client.types.ParticipantTracks
import ai.pipecat.client.types.Tracks
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import ai.pipecat.client.utils.ThreadRef
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
    androidContext: Context,
) : Transport<GeminiServiceOptions>() {

    companion object {
        private const val TAG = "GeminiLiveWebsocketTransport"
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

    private var client: GeminiClient? = null

    override fun initialize(ctx: TransportContext) {
        transportContext = ctx
        thread = ctx.thread
    }

    override fun deserializeConnectParams(
        json: String,
        startBotRequest: APIRequest
    ) = JSON.decodeFromString<GeminiServiceOptions>(json)

    override fun initDevices(): Future<Unit, RTVIError> = resolvedPromiseOk(thread, Unit)

    @SuppressLint("MissingPermission")
    override fun connect(transportParams: GeminiServiceOptions): Future<Unit, RTVIError> =
        thread.runOnThreadReturningFuture {

            Log.i(TAG, "connect()")

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

            val modelName =
                ((transportParams.modelConfig as? Value.Object)
                    ?.value?.get("model") as? Value.Str)?.value ?: "unknown"

            withPromise(thread) { promise ->
                client = GeminiClient.connect(
                    GeminiClient.Config(
                        apiKey = transportParams.apiKey,
                        modelConfig = transportParams.modelConfig,
                        initialMessage = transportParams.initialUserMessage,
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
                                cb.onBotReady(BotReadyData(version = modelName))
                                promise.resolveOk(Unit)
                            }
                        }

                        override fun onSessionEnded(reason: String, t: Throwable?) {
                            thread.runOnThread {
                                Log.i(TAG, "onSessionEnded: $reason", t)
                                setState(TransportState.Disconnected)
                                transportContext.onConnectionEnd()
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

                        override fun onFunctionCall(
                            id: String,
                            name: String,
                            args: Value.Array
                        ) {
                            val data = LLMFunctionCallData(
                                functionName = name,
                                toolCallID = id,
                                args = JSON.encodeToJsonElement(args)
                            )

                            transportContext.onMessage(
                                MsgServerToClient(
                                    id = null,
                                    label = "rtvi-ai",
                                    type = MsgServerToClient.Type.LlmFunctionCall,
                                    data = JSON.encodeToJsonElement(data)
                                )
                            )
                        }

                        override fun onUserTalking(isTalking: Boolean) {
                            thread.runOnThread {
                                transportContext.callbacks.apply {
                                    if (isTalking) {
                                        onUserStartedSpeaking()
                                    } else {
                                        onUserStoppedSpeaking()
                                    }
                                }
                            }
                        }

                        override fun onUserAudioLevel(level: Float) {
                            thread.runOnThread {
                                transportContext.callbacks.onUserAudioLevel(
                                    level
                                )
                            }
                        }
                    }
                )
            }
        }

    override fun disconnect(): Future<Unit, RTVIError> = thread.runOnThreadReturningFuture {
        client?.close()
        resolvedPromiseOk(thread, Unit)
    }

    override fun sendMessage(message: MsgClientToServer): Future<Unit, RTVIError> {

        when (message.type) {
            MsgClientToServer.Type.SendText -> {

                val data =
                    JSON.decodeFromJsonElement<MsgClientToServer.Data.SendText>(message.data!!)

                client?.sendUserMessage(role = "user", content = data.content)

                return resolvedPromiseOk(thread, Unit)
            }

            MsgClientToServer.Type.LlmFunctionCallResult -> {

                val messageData = message.data ?: return resolvedPromiseErr(
                    thread,
                    RTVIError.OtherError("Function call result must not be null")
                )

                val data: LLMFunctionCallResult = JSON.decodeFromJsonElement(messageData)

                val result = JSON.decodeFromJsonElement<Value>(data.result) as? Value.Object
                    ?: return resolvedPromiseErr(
                        thread,
                        RTVIError.OtherError("Function call result must be an object")
                    )

                client?.sendFunctionResponse(
                    id = data.toolCallID,
                    name = data.functionName,
                    response = result
                )

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

    override fun isMicEnabled() = client?.micMuted?.not() ?: false

    override fun enableMic(enable: Boolean): Future<Unit, RTVIError> {
        client?.micMuted = !enable
        thread.runOnThread {
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
        thread.assertCurrent()
        client?.close()
        client = null
    }

    private fun <E> operationNotSupported(): Future<E, RTVIError> =
        resolvedPromiseErr(thread, RTVIError.OtherError("Operation not supported"))
}
