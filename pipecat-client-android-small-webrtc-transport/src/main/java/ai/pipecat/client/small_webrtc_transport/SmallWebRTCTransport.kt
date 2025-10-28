package ai.pipecat.client.small_webrtc_transport

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
import ai.pipecat.client.types.MediaDeviceId
import ai.pipecat.client.types.MediaDeviceInfo
import ai.pipecat.client.types.Participant
import ai.pipecat.client.types.ParticipantId
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import ai.pipecat.client.utils.ThreadRef
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement


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

class SmallWebRTCTransport(
    context: Context,
) : Transport<SmallWebRTCTransportConnectParams>() {

    companion object {
        private const val TAG = "SmallWebRTCTransport"
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

    object Cameras {
        val Front = MediaDeviceInfo(id = MediaDeviceId("cam-front"), name = "Front Camera")
        val Rear = MediaDeviceInfo(id = MediaDeviceId("cam-rear"), name = "Rear Camera")
    }

    private lateinit var transportContext: TransportContext
    private lateinit var thread: ThreadRef

    private var state = TransportState.Disconnected

    private val appContext = context.applicationContext

    private var client: WebRTCClient? = null
    private var selectedCam = CameraMode.Front

    override fun initialize(ctx: TransportContext) {
        transportContext = ctx
        thread = ctx.thread
    }

    override fun initDevices(): Future<Unit, RTVIError> = resolvedPromiseOk(thread, Unit)

    @SuppressLint("MissingPermission")
    override fun connect(
        transportParams: SmallWebRTCTransportConnectParams
    ): Future<Unit, RTVIError> =
        thread.runOnThreadReturningFuture {

            Log.i(TAG, "connect(${transportParams})")

            if (client != null) {
                return@runOnThreadReturningFuture resolvedPromiseErr(
                    thread,
                    RTVIError.OtherError("Connection already active")
                )
            }

            setState(TransportState.Connecting)

            try {
                client = WebRTCClient(
                    onIncomingEvent = { msgJson ->

                        val msgWithType =
                            JSON_INSTANCE.decodeFromJsonElement<MessageWithType>(msgJson)

                        if (msgWithType.type == "signalling") {
                            val msg = JSON_INSTANCE.decodeFromJsonElement<SignallingEvent>(msgJson)

                            when (msg.message.type) {
                                "peerLeft" -> {
                                    Log.i(TAG, "Peer left, disconnecting")
                                    disconnect()
                                }

                                "renegotiate" -> negotiate(transportParams)
                            }

                        } else {
                            transportContext.onMessage(
                                JSON_INSTANCE.decodeFromJsonElement<MsgServerToClient>(msgJson)
                            )
                        }
                    },
                    onTracksUpdated = { tracks ->
                        transportContext.callbacks.onTracksUpdated(tracks)
                    },
                    onInputsUpdated = { cam, mic ->
                        transportContext.callbacks.onInputsUpdated(
                            camera = cam,
                            mic = mic
                        )
                    },
                    context = appContext,
                    thread = transportContext.thread,
                    initialCamMode = if (transportContext.options.enableCam) {
                        selectedCam
                    } else {
                        null
                    },
                    initialMicEnabled = transportContext.options.enableMic,
                    rtviProtocolVersion = transportContext.protocolVersion
                )
            } catch (e: Exception) {
                return@runOnThreadReturningFuture resolvedPromiseErr(
                    thread,
                    RTVIError.ExceptionThrown(e)
                )
            }

            negotiate(transportParams)
        }

    private fun negotiate(
        connectParams: SmallWebRTCTransportConnectParams
    ) = withPromise<Unit, RTVIError>(thread) { promise ->

        MainScope().launch {

            try {
                client?.negotiateConnection(connectParams)

                val cb = transportContext.callbacks
                setState(TransportState.Connected)
                cb.onConnected()
                cb.onParticipantJoined(LOCAL_PARTICIPANT)
                cb.onParticipantJoined(BOT_PARTICIPANT)
                promise.resolveOk(Unit)

            } catch (e: Exception) {
                promise.resolveErr(RTVIError.ExceptionThrown(e))
            }
        }
    }

    override fun deserializeConnectParams(
        json: String,
        startBotRequest: APIRequest
    ): SmallWebRTCTransportConnectParams {
        val startBotResult = JSON_INSTANCE.decodeFromString<SmallWebRTCStartBotResult>(json)

        return SmallWebRTCTransportConnectParams(
            webrtcRequestParams = APIRequest(
                endpoint = startBotRequest.endpoint.replace(
                    "/start",
                    "/sessions/${startBotResult.sessionId}/api/offer"
                ),
                requestData = Value.Object(),
                headers = startBotRequest.headers
            )
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
                        transportContext.onConnectionEnd()
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
        client?.sendDataMessage(MsgClientToServer.serializer(), message)
        return resolvedPromiseOk(thread, Unit)
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
        resolvedPromiseOk(thread, listOf(Cameras.Front, Cameras.Rear))

    override fun getAllMics(): Future<List<MediaDeviceInfo>, RTVIError> =
        resolvedPromiseOk(thread, listOf(AudioDevices.Earpiece, AudioDevices.Speakerphone))

    override fun updateMic(micId: MediaDeviceId): Future<Unit, RTVIError> {

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setSpeakerphoneOn(micId == AudioDevices.Speakerphone.id)

        return resolvedPromiseOk(thread, Unit)
    }

    override fun updateCam(camId: MediaDeviceId): Future<Unit, RTVIError> {
        selectedCam = CameraMode.from(camId) ?: CameraMode.Front

        val result = if (isCamEnabled()) {
            client?.setCamMode(selectedCam)
        } else {
            null
        }

        return result ?: resolvedPromiseOk(thread, Unit)
    }

    override fun selectedMic(): MediaDeviceInfo {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return when (audioManager.isSpeakerphoneOn) {
            true -> AudioDevices.Speakerphone
            false -> AudioDevices.Earpiece
        }
    }

    override fun selectedCam() = selectedCam.info

    override fun isCamEnabled() = client?.camMode != null

    override fun isMicEnabled() = client?.micEnabled ?: false

    override fun enableMic(enable: Boolean): Future<Unit, RTVIError> = client?.setMicEnabled(enable)
        ?: resolvedPromiseErr(thread, RTVIError.TransportNotInitialized)

    override fun enableCam(enable: Boolean): Future<Unit, RTVIError> =
        client?.setCamMode(if (enable) selectedCam else null)
            ?: resolvedPromiseErr(thread, RTVIError.TransportNotInitialized)

    override fun tracks() = client?.getTracks() ?: EMPTY_TRACKS

    override fun release() {
        disconnect().logError(TAG, "Disconnect triggered by release() failed")
    }
}
