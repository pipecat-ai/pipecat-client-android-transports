package ai.pipecat.client.openai_realtime_webrtc

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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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

class OpenAIRealtimeWebRTCTransport(
    private val transportContext: TransportContext,
    androidContext: Context
) : Transport() {

    companion object {
        private const val TAG = "OpenAIRealtimeWebRTCTransport"

        private const val SERVICE_LLM = "llm"
        private const val OPTION_API_KEY = "api_key"
        private const val OPTION_INITIAL_USER_MESSAGE = "initial_user_message"
        private const val OPTION_MODEL = "model"

        fun buildConfig(
            apiKey: String,
            model: String = "gpt-4o-realtime-preview-latest",
            initialUserMessage: String? = null, // TODO
        ): List<ServiceConfig> = listOf(
            ServiceConfig(
                SERVICE_LLM, listOf(
                    Option(OPTION_API_KEY, apiKey),
                    Option(
                        OPTION_INITIAL_USER_MESSAGE,
                        initialUserMessage?.let { Value.Str(it) } ?: Value.Null),
                    Option(OPTION_MODEL, model)
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
            return OpenAIRealtimeWebRTCTransport(context, androidContext)
        }
    }

    private var state = TransportState.Disconnected

    private val appContext = androidContext.applicationContext
    private val thread = transportContext.thread

    private var client: WebRTCClient? = null


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

            // TODO ???
            transportContext.callbacks.onInputsUpdated(
                camera = false,
                mic = transportContext.options.enableMic
            )

            setState(TransportState.Connecting)

            try {
                client = WebRTCClient(appContext)
            } catch (e: Exception) {
                return@runOnThreadReturningFuture resolvedPromiseErr(
                    thread,
                    RTVIError.ExceptionThrown(e)
                )
            }

            val options = transportContext.options.params.config.getOptionsFor(SERVICE_LLM)

            val apiKey = (options?.getValueFor(OPTION_API_KEY) as? Value.Str)?.value
            val model = (options?.getValueFor(OPTION_MODEL) as? Value.Str)?.value
            // TODO
            val initialUserMessage =
                (options?.getValueFor(OPTION_INITIAL_USER_MESSAGE) as? Value.Str)?.value

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

                        // TODO
                        val cb = transportContext.callbacks
                        setState(TransportState.Connected)
                        cb.onConnected()
                        cb.onParticipantJoined(LOCAL_PARTICIPANT)
                        cb.onParticipantJoined(BOT_PARTICIPANT)
                        setState(TransportState.Ready)
                        cb.onBotReady("local", emptyList())
                        promise.resolveOk(Unit)

                    } catch(e: Exception) {
                        promise.resolveErr(RTVIError.ExceptionThrown(e))
                    }
                }
            }
        }

    override fun disconnect(): Future<Unit, RTVIError> = thread.runOnThreadReturningFuture {
        withPromise(thread) { promise ->
            MainScope().launch {

                val clientRef = client
                client = null

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

                                // TODO client?.sendUserMessage(role = role.value, content = content.value)
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

    override fun isMicEnabled() = false // TODO client?.micMuted?.not() ?: false

    override fun enableMic(enable: Boolean): Future<Unit, RTVIError> {
        // TODO
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
        disconnect().logError(TAG, "Disconnect triggered by release() failed")
    }

    private fun <E> operationNotSupported(): Future<E, RTVIError> =
        resolvedPromiseErr(thread, RTVIError.OtherError("Operation not supported"))
}
