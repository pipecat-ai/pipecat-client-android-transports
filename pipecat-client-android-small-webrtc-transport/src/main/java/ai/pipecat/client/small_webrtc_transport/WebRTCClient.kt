package ai.pipecat.client.small_webrtc_transport

import ai.pipecat.client.result.Future
import ai.pipecat.client.result.RTVIError
import ai.pipecat.client.result.resolvedPromiseErr
import ai.pipecat.client.result.resolvedPromiseOk
import ai.pipecat.client.transport.MsgClientToServer
import ai.pipecat.client.types.MediaDeviceId
import ai.pipecat.client.types.MediaDeviceInfo
import ai.pipecat.client.types.MediaTrackId
import ai.pipecat.client.types.ParticipantTracks
import ai.pipecat.client.types.Tracks
import ai.pipecat.client.utils.ThreadRef
import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val JSON_INSTANCE = Json { ignoreUnknownKeys = true }

internal fun MediaStreamTrack.pipecatId() = MediaTrackId(id())

internal val EMPTY_TRACKS = Tracks(
    local = ParticipantTracks(null, null),
    bot = null
)

internal enum class CameraMode(val info: MediaDeviceInfo) {
    Front(SmallWebRTCTransport.Cameras.Front),
    Rear(SmallWebRTCTransport.Cameras.Rear);

    companion object {
        fun from(info: MediaDeviceInfo) = when (info) {
            Front.info -> Front
            Rear.info -> Rear
            else -> null
        }

        fun from(info: MediaDeviceId) = when (info) {
            Front.info.id -> Front
            Rear.info.id -> Rear
            else -> null
        }
    }
}

internal class WebRTCClient(
    private val onIncomingEvent: (JsonElement) -> Unit,
    private val onTracksUpdated: (Tracks) -> Unit,
    private val onInputsUpdated: (cam: Boolean, mic: Boolean) -> Unit,
    private val context: Context,
    private val thread: ThreadRef,
    initialCamMode: CameraMode?,
    initialMicEnabled: Boolean,
) {
    private val peerConnectionFactory: PeerConnectionFactory
    private val peerConnection: PeerConnection

    private val audioTransceiver: RtpTransceiver
    private val videoTransceiver: RtpTransceiver
    
    private var audioSource: AudioSource? = null

    private var videoSource: VideoSource? = null
    private var cameraManager: CameraManager? = null

    private val dataChannel: DataChannel
    private val negotiateJob = AtomicReference<Job?>(null)
    private val pcId = AtomicReference<String?>(null)

    private var tracks: Tracks? = null

    companion object {
        private const val TAG = "WebRTCClient"
    }

    val camMode: CameraMode?
        get() = enableCam.get()

    val micEnabled: Boolean
        get() = enableMic.get()

    private val enableCam = AtomicReference(initialCamMode)
    private val enableMic = AtomicBoolean(initialMicEnabled)

    init {
        Log.d(TAG, "Initializing PeerConnectionFactory")

        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .createAudioDeviceModule()

        val options = PeerConnectionFactory.Options()

        val eglContext = EglUtils.eglBase.eglBaseContext

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, false, false))
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory initialized")

        Log.i(TAG, "Creating PeerConnection")

        val iceServers = ArrayList<PeerConnection.IceServer>()
        iceServers.add(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val observer = PeerConnectionObserver(onTrackCallback = { transceiver ->
            transceiver?.receiver?.track()?.let(::handleRemoteTrack)
        })

        peerConnection =
            peerConnectionFactory.createPeerConnection(rtcConfig, observer)
                ?: throw IllegalStateException("Failed to create PeerConnection")

        Log.d(TAG, "PeerConnection created")

        val transceiverParams = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_RECV
        )

        audioTransceiver = peerConnection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            transceiverParams
        )

        videoTransceiver = peerConnection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            transceiverParams
        )

        updateLocalTracks()

        val dataChannelReady = AtomicBoolean(false)

        dataChannel = peerConnection.createDataChannel("chat", DataChannel.Init())

        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                if (!dataChannelReady.get() && dataChannel.state() == DataChannel.State.OPEN) {
                    dataChannelReady.set(true)
                    sendDataMessage(
                        MsgClientToServer.serializer(),
                        MsgClientToServer(
                            type = "client-ready",
                            data = JsonObject(emptyMap())
                        )
                    )
                }
            }

            override fun onMessage(buf: DataChannel.Buffer) {

                try {
                    val bytes = ByteArray(buf.data.remaining())
                    buf.data.get(bytes)

                    val msgString = String(bytes, Charsets.UTF_8)

                    Log.i(TAG, "Got event from bot: $msgString")

                    val msgJson = JSON_INSTANCE.parseToJsonElement(msgString)

                    onIncomingEvent(msgJson)

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse incoming event", e)
                }
            }

        })
    }

    fun getTracks() = tracks ?: EMPTY_TRACKS

    private fun updateLocalTracks(): Future<Unit, RTVIError> = thread.runOnThreadReturningFuture {

        try {
            val shouldEnableMic = enableMic.get()
            val shouldEnableCam = enableCam.get()

            if (shouldEnableMic && audioSource == null) {
                audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
                peerConnectionFactory.createAudioTrack("mic", audioSource).apply {
                    TrackRegistry.add(this)
                    audioTransceiver.sender.setTrack(this, true)
                }
            } else if (!shouldEnableMic && audioSource != null) {
                audioTransceiver.sender.track()?.let(TrackRegistry::remove)
                audioTransceiver.sender.setTrack(null, false)
                audioSource?.dispose()
                audioSource = null
            }

            if (shouldEnableCam != null && shouldEnableCam != cameraManager?.mode) {

                val source = videoSource ?: run {
                    peerConnectionFactory.createVideoSource(false).apply {
                        videoSource = this
                    }
                }

                peerConnectionFactory.createVideoTrack("cam", source).apply {
                    TrackRegistry.add(this)
                    videoTransceiver.sender.setTrack(this, true)
                }

                cameraManager?.dispose()
                cameraManager = CameraManager(context, shouldEnableCam, source)

            } else if (shouldEnableCam == null && cameraManager != null) {

                videoTransceiver.sender.track()?.let(TrackRegistry::remove)
                videoTransceiver.sender.setTrack(null, false)

                cameraManager?.dispose()
                cameraManager = null

                videoSource?.dispose()
                videoSource = null
            }

            val newTracks = getTracks().copy(
                local = ParticipantTracks(
                    audio = if (shouldEnableMic) { audioTransceiver.sender.track()?.pipecatId() } else null,
                    video = if (shouldEnableCam != null) { videoTransceiver.sender.track()?.pipecatId() } else null,
                )
            )

            if (tracks != newTracks) {
                tracks = newTracks
                onTracksUpdated(newTracks)
                onInputsUpdated(
                    newTracks.local.video != null,
                    newTracks.local.audio != null
                )
            }

            resolvedPromiseOk(thread, Unit)

        } catch (e: Exception) {
            resolvedPromiseErr(thread, RTVIError.ExceptionThrown(e))
        }
    }

    private fun handleRemoteTrack(track: MediaStreamTrack) = thread.runOnThread {

        val oldRemote = tracks?.bot ?: ParticipantTracks(audio = null, video = null)
        val id = TrackRegistry.add(track)

        val oldTracks = getTracks()

        val newTracks = when (track) {
            is VideoTrack -> oldTracks.copy(bot = oldRemote.copy(video = id))
            is AudioTrack -> oldTracks.copy(bot = oldRemote.copy(audio = id))
            else -> oldTracks
        }

        if (oldTracks != newTracks) {
            tracks = newTracks
            onTracksUpdated(newTracks)
        }
    }

    private fun createOffer(sdpObserver: SdpObserver) {
        Log.d(TAG, "Creating offer")

        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection.createOffer(sdpObserver, constraints)
    }

    fun <T> sendDataMessage(serializer: KSerializer<T>, msg: T) {

        val msgString = JSON_INSTANCE.encodeToString(serializer, msg)

        Log.i(TAG, "Sending message to bot: $msgString")

        dataChannel.send(
            DataChannel.Buffer(
                ByteBuffer.wrap(
                    msgString.toByteArray(charset = Charsets.UTF_8)
                ),
                false
            )
        )
    }

    fun setCamMode(enabled: CameraMode?): Future<Unit, RTVIError> {
        enableCam.set(enabled)
        return updateLocalTracks()
    }

    fun setMicEnabled(enabled: Boolean): Future<Unit, RTVIError> {
        enableMic.set(enabled)
        return updateLocalTracks()
    }

    suspend fun dispose() {
        try {
            negotiateJob.get()?.cancelAndJoin()
            thread.runOnThreadReturningFuture {
                try {
                    setCamMode(null)
                    setMicEnabled(false)
                    audioTransceiver.dispose()
                    videoTransceiver.dispose()
                    audioSource?.dispose()
                    videoSource?.dispose()
                    dataChannel.dispose()
                    peerConnection.dispose()
                    peerConnectionFactory.dispose()
                    resolvedPromiseOk(thread, Unit)
                } catch (e: Exception) {
                    resolvedPromiseErr(thread, e)
                }
            }.awaitNoThrow().errorOrNull?.let { throw it }

        } catch (e: Exception) {
            Log.e(TAG, "Error disposing resources", e)
        }
    }

    private suspend fun createOffer(): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            createOffer(object : SdpObserver {
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    Log.d(TAG, "Offer created successfully")
                    continuation.resume(sessionDescription)
                }

                override fun onCreateFailure(s: String) {
                    Log.e(TAG, "Failed to create offer: $s")
                    continuation.resumeWithException(Exception("Create offer failed: $s"))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(s: String) {}
            })
        }

    private suspend fun setLocalDescription(
        sessionDescription: SessionDescription
    ): Unit = suspendCancellableCoroutine { continuation ->
        peerConnection.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {}
            override fun onCreateFailure(s: String) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Local description set successfully")
                continuation.resume(Unit)
            }

            override fun onSetFailure(s: String) {
                Log.e(TAG, "Failed to set local description: $s")
                continuation.resumeWithException(Exception("Set local description failed: $s"))
            }
        }, sessionDescription)
    }

    private suspend fun setRemoteDescription(
        sessionDescription: SessionDescription
    ): Unit = suspendCancellableCoroutine { continuation ->
        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {}
            override fun onCreateFailure(s: String) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully")
                continuation.resume(Unit)
            }

            override fun onSetFailure(s: String) {
                Log.e(TAG, "Failed to set remote description: $s")
                continuation.resumeWithException(Exception("Set remote description failed: $s"))
            }
        }, sessionDescription)
    }

    suspend fun negotiateConnection(
        url: String,
        restartPc: Boolean
    ) {
        val job = coroutineScope {
            launch {
                try {
                    val offer = createOffer()
                    Log.d(TAG, "Created offer SDP: ${offer.description}")

                    setLocalDescription(offer)
                    Log.d(TAG, "Local description set")

                    val responseJson = HttpClient(Android) {
                        install(ContentNegotiation) {
                            json(JSON_INSTANCE)
                        }
                    }.use { client ->
                        try {
                            val response = client.post(url) {
                                contentType(ContentType.Application.Json)
                                setBody(
                                    OfferRequestBody(
                                        sdp = offer.description,
                                        type = offer.type.canonicalForm(),
                                        pcId = pcId.get(),
                                        restartPc = restartPc
                                    )
                                )
                            }

                            response.bodyAsText()

                        } catch (e: ResponseException) {
                            // Handle all HTTP error responses (both 4xx and 5xx)
                            val errorBody = e.response.bodyAsText()
                            val status = e.response.status.value
                            Log.e(TAG, "Server responded with code $status: $errorBody")
                            throw Exception("HTTP error: $status, body: $errorBody", e)
                        }
                    }

                    ensureActive()

                    Log.d(TAG, "Received response: $responseJson")

                    val response = JSON_INSTANCE.decodeFromString<OfferResponseBody>(responseJson)

                    pcId.set(response.pcId)

                    // Set remote description (the answer)
                    val answer = SessionDescription(SessionDescription.Type.ANSWER, response.sdp)
                    setRemoteDescription(answer)
                    Log.d(TAG, "Remote description set")

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to LLM", e)
                    throw Exception("Failed to connect to LLM", e)
                } finally {
                    negotiateJob.set(null)
                }
            }
        }

        if (!negotiateJob.compareAndSet(null, job)) {
            throw Exception("negotiateConnection already in progress")
        }
    }

    private class PeerConnectionObserver(
        private val onTrackCallback: (transceiver: RtpTransceiver?) -> Unit
    ) : PeerConnection.Observer {
        override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
            Log.i(TAG, "onSignalingChange: $signalingState")
        }

        override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
            Log.i(TAG, "onIceConnectionChange: $iceConnectionState")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.i(TAG, "onIceConnectionReceivingChange: $receiving")
        }

        override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
            Log.i(TAG, "onIceGatheringChange: $iceGatheringState")
        }

        override fun onIceCandidate(iceCandidate: IceCandidate) {
            Log.i(TAG, "onIceCandidate: $iceCandidate")
        }

        override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
            Log.i(TAG, "onIceCandidatesRemoved")
        }

        override fun onAddStream(mediaStream: MediaStream) {
            Log.i(TAG, "onAddStream: $mediaStream")
        }

        override fun onRemoveStream(mediaStream: MediaStream) {
            Log.i(TAG, "onRemoveStream: $mediaStream")
        }

        override fun onDataChannel(dataChannel: DataChannel) {
            Log.i(TAG, "onDataChannel")
        }

        override fun onRenegotiationNeeded() {
            Log.i(TAG, "onRenegotiationNeeded")
        }

        override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
            Log.i(TAG, "onAddTrack($rtpReceiver, $mediaStreams)")
        }

        override fun onRemoveTrack(receiver: RtpReceiver?) {
            Log.i(TAG, "onRemoveTrack($receiver)")
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            onTrackCallback(transceiver)
        }
    }
}