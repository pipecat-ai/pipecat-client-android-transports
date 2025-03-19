package ai.pipecat.client.openai_realtime_webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_INSTANCE = Json { ignoreUnknownKeys = true }

internal class WebRTCClient(private val onIncomingEvent: (OpenAIEvent) -> Unit, context: Context) {
    private val peerConnectionFactory: PeerConnectionFactory
    private val peerConnection: PeerConnection
    private val audioSource: AudioSource
    private val localAudioTrack: AudioTrack
    private val dataChannel: DataChannel
    private val negotiateJob = AtomicReference<Job?>(null)

    companion object {
        private const val TAG = "WebRTCClient"
    }

    init {
        Log.d(TAG, "Initializing PeerConnectionFactory")

        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .createAudioDeviceModule()

        val options = PeerConnectionFactory.Options()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory initialized")

        Log.i(TAG, "Creating PeerConnection")

        val iceServers = ArrayList<PeerConnection.IceServer>()
        iceServers.add(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection =
            peerConnectionFactory.createPeerConnection(rtcConfig, LoggingPeerConnectionObserver)
                ?: throw IllegalStateException("Failed to create PeerConnection")

        Log.d(TAG, "PeerConnection created")

        Log.d(TAG, "Creating and adding audio track")

        // Create audio source from microphone
        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("mic", audioSource)
        localAudioTrack.setEnabled(true)

        val sender = peerConnection.addTrack(localAudioTrack)
        if (sender != null) {
            Log.d(TAG, "Added audio track to PeerConnection")
        } else {
            Log.e(TAG, "Failed to add audio track")
        }

        dataChannel = peerConnection.createDataChannel("oai-events", DataChannel.Init())

        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {}

            override fun onMessage(buf: DataChannel.Buffer) {

                try {

                    val bytes = ByteArray(buf.data.remaining())
                    buf.data.get(bytes)

                    val msgString = String(bytes, Charsets.UTF_8)

                    Log.i(TAG, "Got event from bot: $msgString")

                    val msg = JSON_INSTANCE.decodeFromString<OpenAIEvent>(msgString)

                    onIncomingEvent(msg)

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse incoming event", e)
                }
            }

        })
    }

    private fun createOffer(sdpObserver: SdpObserver) {
        Log.d(TAG, "Creating offer")

        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))

        peerConnection.createOffer(sdpObserver, constraints)
    }

    fun setAudioTrackEnabled(enabled: Boolean) {
        localAudioTrack.setEnabled(enabled)
    }

    fun isAudioTrackEnabled() = localAudioTrack.enabled()

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

    suspend fun dispose() {
        try {
            negotiateJob.get()?.cancelAndJoin()
            localAudioTrack.dispose()
            audioSource.dispose()
            dataChannel.dispose()
            peerConnection.close()
            peerConnectionFactory.dispose()
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
        baseUrl: String,
        apiKey: String,
        model: String
    ) {
        if (apiKey.isEmpty()) {
            throw Exception("No API key provided")
        }

        val job = coroutineScope {
            launch {
                try {
                    val offer = createOffer()
                    Log.d(TAG, "Created offer SDP: ${offer.description}")

                    setLocalDescription(offer)
                    Log.d(TAG, "Local description set")

                    val answerSdp = withContext(Dispatchers.IO) {

                        ensureActive()

                        val url = URL("$baseUrl?model=$model")
                        val connection = url.openConnection() as HttpsURLConnection
                        connection.requestMethod = "POST"
                        connection.setRequestProperty("Authorization", "Bearer $apiKey")
                        connection.setRequestProperty("Content-Type", "application/sdp")
                        connection.doOutput = true

                        // Write SDP offer to request body
                        connection.outputStream.use { os ->
                            os.write(offer.description.toByteArray())
                        }

                        // Read response
                        val responseCode = connection.responseCode
                        if (responseCode < 200 || responseCode > 299) {
                            // Read error response body
                            val errorBody =
                                connection.errorStream?.bufferedReader()?.use { it.readText() }
                                    ?: "No error body"
                            Log.e(TAG, "Server responded with code $responseCode: $errorBody")
                            throw Exception("HTTP error: $responseCode, body: $errorBody")
                        }

                        connection.inputStream.bufferedReader().use { it.readText() }
                    }

                    ensureActive()

                    Log.d(TAG, "Received SDP answer: $answerSdp")

                    // Set remote description (the answer)
                    val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
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

    private object LoggingPeerConnectionObserver : PeerConnection.Observer {
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
            Log.i(TAG, "onAddStream")
        }

        override fun onRemoveStream(mediaStream: MediaStream) {
            Log.i(TAG, "onRemoveStream")
        }

        override fun onDataChannel(dataChannel: DataChannel) {
            Log.i(TAG, "onDataChannel")
        }

        override fun onRenegotiationNeeded() {
            Log.i(TAG, "onRenegotiationNeeded")
        }

        override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
            Log.i(TAG, "onAddTrack")
        }
    }
}