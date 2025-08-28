package ai.pipecat.client.daily

import ai.pipecat.client.result.Future
import ai.pipecat.client.result.RTVIError
import ai.pipecat.client.result.resolvedPromiseErr
import ai.pipecat.client.result.resolvedPromiseOk
import ai.pipecat.client.result.withPromise
import ai.pipecat.client.transport.MsgClientToServer
import ai.pipecat.client.transport.MsgServerToClient
import ai.pipecat.client.transport.Transport
import ai.pipecat.client.transport.TransportContext
import ai.pipecat.client.types.MediaDeviceId
import ai.pipecat.client.types.MediaDeviceInfo
import ai.pipecat.client.types.Participant
import ai.pipecat.client.types.ParticipantId
import ai.pipecat.client.types.ParticipantTracks
import ai.pipecat.client.types.Tracks
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import ai.pipecat.client.utils.ThreadRef
import android.content.Context
import android.util.Log
import co.daily.CallClient
import co.daily.CallClientListener
import co.daily.model.CallState
import co.daily.model.MediaState
import co.daily.model.MeetingToken
import co.daily.model.ParticipantLeftReason
import co.daily.model.Recipient
import co.daily.settings.CameraInputSettingsUpdate
import co.daily.settings.FacingModeUpdate
import co.daily.settings.InputSettings
import co.daily.settings.InputSettingsUpdate
import co.daily.settings.MicrophoneInputSettingsUpdate
import co.daily.settings.StateBoolean
import co.daily.settings.VideoMediaTrackSettingsUpdate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

class DailyTransport(
    androidContext: Context
) : Transport() {

    companion object {
        private const val TAG = "DailyTransport"
    }

    private lateinit var transportContext: TransportContext
    private lateinit var thread: ThreadRef

    private val appContext = androidContext.applicationContext
    private var state = TransportState.Disconnected

    private var devicesInitialized = false

    private var call: CallClient? = null

    private var expiry: Long? = null

    private val callListener = object : CallClientListener {

        val participants = mutableMapOf<ParticipantId, Participant>()
        var botUser: Participant? = null
        var clientReady: Boolean = false
        var tracks: Tracks? = null

        override fun onLocalAudioLevel(audioLevel: Float) {
            transportContext.callbacks.onUserAudioLevel(audioLevel)
        }

        override fun onRemoteParticipantsAudioLevel(participantsAudioLevel: Map<co.daily.model.ParticipantId, Float>) {
            participantsAudioLevel.forEach { (id, level) ->

                val rtviId = id.toRtvi()
                val participant = participants[rtviId]

                if (participant != null) {
                    transportContext.callbacks.onRemoteAudioLevel(
                        level = level,
                        participant = participant
                    )
                }
            }
        }

        override fun onParticipantJoined(participant: co.daily.model.Participant) {
            updateParticipant(participant)
            updateBotUserAndTracks()
        }

        override fun onParticipantLeft(
            participant: co.daily.model.Participant,
            reason: ParticipantLeftReason
        ) {
            participants.remove(participant.id.toRtvi())
            updateBotUserAndTracks()
        }

        override fun onParticipantUpdated(participant: co.daily.model.Participant) {
            updateParticipant(participant)
            updateBotUserAndTracks()

            if (!clientReady && participant.id.toRtvi() == botUser?.id) {
                if (participant.media?.microphone?.state == MediaState.playable) {

                    Log.i(TAG, "Sending client-ready")

                    clientReady = true
                    sendMessage(
                        MsgClientToServer.ClientReady(
                            rtviVersion = transportContext.protocolVersion,
                            library = "Pipecat Android Client",
                            libraryVersion = DAILY_TRANSPORT_VERSION
                        )
                    )
                }
            }
        }

        private fun updateBotUserAndTracks() {
            botUser = participants.values.firstOrNull { !it.local }
            val newTracks = tracks()

            if (newTracks != tracks) {
                tracks = newTracks
                transportContext.callbacks.onTracksUpdated(newTracks)
            }
        }

        private fun updateParticipant(participant: co.daily.model.Participant) {
            participants[participant.id.toRtvi()] = participant.toRtvi()
        }

        override fun onCallStateUpdated(state: CallState) {
            when (state) {
                CallState.initialized -> {}
                CallState.joining -> {}
                CallState.joined -> {
                    call?.participants()?.all?.values?.forEach(::updateParticipant)
                    updateBotUserAndTracks()
                }

                CallState.leaving -> {}
                CallState.left -> {
                    botUser = null
                    clientReady = false
                    participants.clear()
                    setState(TransportState.Disconnected)
                    transportContext.callbacks.onDisconnected()
                }
            }
        }

        override fun onAppMessage(message: String, from: co.daily.model.ParticipantId) {

            Log.i(TAG, "App message: $message")

            try {
                val msgJson: JsonObject = JSON_INSTANCE.decodeFromString(message)

                val label = msgJson.tryGetString("label")
                val type = msgJson.tryGetString("type")

                if (label == "rtvi-ai") {

                    val msg = JSON_INSTANCE.decodeFromJsonElement<MsgServerToClient>(msgJson)
                    transportContext.onMessage(msg)

                } else if (type == "pipecat-metrics") {

                    val metrics =
                        msgJson.tryGetObject("metrics") ?: throw Exception("Missing metrics field")

                    transportContext.callbacks.onMetrics(
                        JSON_INSTANCE.decodeFromJsonElement(metrics)
                    )

                } else {
                    Log.i(TAG, "Unhandled app message: '$message', label=$label, type=$type")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Got exception processing app message", e)
            }
        }

        override fun onInputsUpdated(inputSettings: InputSettings) {
            transportContext.callbacks.onInputsUpdated(
                camera = inputSettings.camera.isEnabled,
                mic = inputSettings.microphone.isEnabled
            )
        }
    }

    object Cameras {
        val Front = MediaDeviceId("Front")
        val Back = MediaDeviceId("Back")

        internal object Info {
            val Front = MediaDeviceInfo(id = Cameras.Front, name = Cameras.Front.id)
            val Back = MediaDeviceInfo(id = Cameras.Back, name = Cameras.Back.id)
        }
    }

    /**
     * Returns the raw Daily CallClient instance for custom operations.
     */
    val callClient: CallClient?
        get() = call

    override fun initialize(ctx: TransportContext) {
        transportContext = ctx
        thread = ctx.thread
    }

    override fun initDevices(): Future<Unit, RTVIError> = withPromise(thread) { promise ->

        thread.runOnThread {

            Log.i(TAG, "initDevices()")

            if (devicesInitialized) {
                promise.resolveOk(Unit)
                return@runOnThread
            }

            try {
                call = CallClient(appContext, handler = transportContext.thread.handler).apply {
                    addListener(callListener)
                    startLocalAudioLevelObserver(intervalMs = 100) {
                        if (it.isError) {
                            Log.e(TAG, "Failed to start local audio level observer: ${it.error}")
                        }
                    }
                    startRemoteParticipantsAudioLevelObserver(intervalMs = 100) {
                        if (it.isError) {
                            Log.e(TAG, "Failed to start remote audio level observer: ${it.error}")
                        }
                    }
                }

                if (state == TransportState.Disconnected) {
                    setState(TransportState.Initialized)
                }

                devicesInitialized = true
                promise.resolveOk(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Exception in initDevices", e)
                promise.resolveErr(RTVIError.ExceptionThrown(e))
            }
        }
    }

    override fun connect(transportParams: Value): Future<Unit, RTVIError> =
        thread.runOnThreadReturningFuture {

            Log.i(TAG, "connect($transportParams)")

            val dailyBundle = try {
                JSON_INSTANCE.decodeFromJsonElement<DailyTransportAuthBundle>(
                    JSON_INSTANCE.encodeToJsonElement(transportParams)
                )
            } catch (e: Exception) {
                return@runOnThreadReturningFuture resolvedPromiseErr(
                    thread,
                    RTVIError.ExceptionThrown(e)
                )
            }

            if (dailyBundle.dailyRoom == null) {
                return@runOnThreadReturningFuture resolvedPromiseErr(
                    thread,
                    RTVIError.OtherError("dailyRoom not set in transportParams")
                )
            }

            setState(TransportState.Connecting)

            return@runOnThreadReturningFuture initDevices()
                .withErrorCallback { setState(TransportState.Error) }
                .chain { _ ->
                    withCall { callClient ->
                        withPromise(thread) { promise ->

                            enableMic(transportContext.options.enableMic).withErrorCallback(promise::resolveErr)
                            enableCam(transportContext.options.enableCam).withErrorCallback(promise::resolveErr)

                            callClient.join(
                                url = dailyBundle.actualRoom!!,
                                meetingToken = dailyBundle.actualToken?.let { MeetingToken(it) },
                            ) {
                                if (it.isError) {
                                    setState(TransportState.Error)
                                    promise.resolveErr(it.error.toRTVIError())
                                    return@join
                                }

                                expiry = it.success?.callConfig?.let { config ->
                                    listOfNotNull(
                                        config.roomExpiration,
                                        config.tokenExpiration
                                    ).minOrNull()
                                }

                                setState(TransportState.Connected)
                                transportContext.callbacks.onConnected()
                                promise.resolveOk(Unit)
                            }
                        }
                    }
                }
        }

    override fun disconnect(): Future<Unit, RTVIError> = thread.runOnThreadReturningFuture {
        withCall { callClient ->
            withPromise(thread) { promise ->
                callClient.leave(promise::resolveWithDailyResult)
            }
        }
    }

    override fun sendMessage(
        message: MsgClientToServer,
    ): Future<Unit, RTVIError> = thread.runOnThreadReturningFuture {
        withCall { callClient ->
            withPromise(thread) { promise ->
                callClient.sendAppMessage(
                    JSON_INSTANCE.encodeToString(MsgClientToServer.serializer(), message),
                    Recipient.All,
                    promise::resolveWithDailyResult
                )
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
        resolvedPromiseOk(thread, getAllCamsInternal())

    override fun getAllMics(): Future<List<MediaDeviceInfo>, RTVIError> =
        resolvedPromiseOk(thread, getAllMicsInternal())

    // TODO test this
    private fun getAllCamsInternal() = listOf(Cameras.Info.Back, Cameras.Info.Front)

    private fun getAllMicsInternal() =
        call?.availableDevices()?.audio?.map { it.toRtvi() } ?: emptyList()

    override fun updateMic(micId: MediaDeviceId): Future<Unit, RTVIError> =
        thread.runOnThreadReturningFuture {
            withCall { callClient ->
                withPromise(thread) { promise ->
                    callClient.setAudioDevice(micId.id, promise::resolveWithDailyResult)
                }
            }
        }

    override fun updateCam(camId: MediaDeviceId): Future<Unit, RTVIError> =
        thread.runOnThreadReturningFuture {
            withCall { callClient ->
                withPromise(thread) { promise ->
                    callClient.updateInputs(
                        InputSettingsUpdate(
                            camera = CameraInputSettingsUpdate(
                                settings = VideoMediaTrackSettingsUpdate(
                                    facingMode = when (camId) {
                                        Cameras.Front -> FacingModeUpdate.user
                                        else -> FacingModeUpdate.environment
                                    }
                                )
                            )
                        ), promise::resolveWithDailyResult
                    )
                }
            }
        }

    override fun selectedMic() =
        call?.inputs()?.microphone?.settings?.deviceId?.let { id -> getAllMicsInternal().firstOrNull { it.id.id == id } }

    override fun selectedCam() =
        call?.inputs()?.camera?.settings?.deviceId?.let { id -> getAllCamsInternal().firstOrNull { it.id.id == id } }

    override fun isCamEnabled() = call?.inputs()?.camera?.isEnabled ?: false

    override fun isMicEnabled() = call?.inputs()?.microphone?.isEnabled ?: false

    override fun enableMic(enable: Boolean): Future<Unit, RTVIError> =
        thread.runOnThreadReturningFuture {
            withCall { callClient ->
                withPromise(thread) { promise ->
                    callClient.updateInputs(
                        InputSettingsUpdate(
                            microphone = MicrophoneInputSettingsUpdate(
                                isEnabled = StateBoolean.from(enable)
                            )
                        ), promise::resolveWithDailyResult
                    )
                }
            }
        }

    override fun enableCam(enable: Boolean): Future<Unit, RTVIError> =
        thread.runOnThreadReturningFuture {
            withCall { callClient ->
                withPromise(thread) { promise ->
                    callClient.updateInputs(
                        InputSettingsUpdate(
                            camera = CameraInputSettingsUpdate(
                                isEnabled = StateBoolean.from(enable)
                            )
                        ), promise::resolveWithDailyResult
                    )
                }
            }
        }

    override fun tracks(): Tracks {

        val participants = call?.participants() ?: return Tracks(
            local = ParticipantTracks(null, null),
            bot = ParticipantTracks(null, null),
        )

        val local = participants.local
        val bot = participants.all.values.firstOrNull { !it.info.isLocal }

        return Tracks(
            local = ParticipantTracks(
                audio = local.media?.microphone?.track?.toRtvi(),
                video = local.media?.camera?.track?.toRtvi(),
            ),
            bot = bot?.let {
                ParticipantTracks(
                    audio = bot.media?.microphone?.track?.toRtvi(),
                    video = bot.media?.camera?.track?.toRtvi(),
                )
            }
        )
    }

    override fun release() {
        thread.assertCurrent()
        call?.release()
        call = null
    }

    private fun <V> withCall(action: (CallClient) -> Future<V, RTVIError>): Future<V, RTVIError> {

        thread.assertCurrent()

        val currentClient = call

        return if (currentClient == null) {
            resolvedPromiseErr(thread, RTVIError.TransportNotInitialized)
        } else {
            return action(currentClient)
        }
    }
}

