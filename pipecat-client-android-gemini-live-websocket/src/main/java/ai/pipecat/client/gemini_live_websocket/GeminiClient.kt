package ai.pipecat.client.gemini_live_websocket

import ai.pipecat.client.types.Value
import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val API_URL =
    "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

private const val TAG = "GeminiClient"

private val JSON = Json { ignoreUnknownKeys = true }

private fun <E> AtomicReference<E?>.take(): E? = getAndSet(null)

@Serializable
private data class SetupRequest(
    val setup: Value
)

@Serializable
private data class ClientContentRequest(
    @SerialName("client_content")
    val clientContent: ClientContent
) {
    @Serializable
    data class ClientContent(
        val turns: List<Turn>,
        @SerialName("turn_complete")
        val turnComplete: Boolean
    ) {
        @Serializable
        data class Turn(
            val role: String,
            val parts: List<TurnPart>
        )
    }
}

@Serializable
private data class RealtimeInputRequest(
    val realtimeInput: RealtimeInput
) {
    @Serializable
    data class RealtimeInput(
        val mediaChunks: List<InlineData>
    )
}

@Serializable
private data class IncomingMessage(
    val setupComplete: SetupComplete? = null,
    val serverContent: ServerContent? = null
) {
    @Serializable
    data class SetupComplete(
        val dummyField: Boolean = false
    )

    @Serializable
    data class ServerContent(
        val modelTurn: ModelTurn? = null,
        val turnComplete: Boolean = false,
        val interrupted: Boolean = false
    ) {
        @Serializable
        data class ModelTurn(
            val parts: List<TurnPart>
        )
    }
}

@Serializable
private data class TurnPart(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@Serializable
private data class InlineData(
    val mimeType: String,
    val data: String
) {
    companion object {
        fun ofPcm(sampleRateHz: Int, data: ByteArray) = InlineData(
            mimeType = "audio/pcm;rate=$sampleRateHz",
            data = Base64.encodeToString(data, Base64.NO_WRAP)
        )
    }
}

private data class AppendedMessage(
    val role: String,
    val content: String
)

internal class GeminiClient private constructor(
    private val onSendUserMessage: (AppendedMessage) -> Unit,
    private val onClose: () -> Unit,
    private val setMicMuted: (Boolean) -> Unit,
    private val isMicMuted: () -> Boolean,
) {
    data class Config(
        val apiKey: String,
        val modelConfig: Value,
        val initialMessage: String?,
        val initialMicEnabled: Boolean,
    )

    interface Listener {
        fun onConnected()
        fun onSessionEnded(reason: String, t: Throwable?)
        fun onBotTalking(isTalking: Boolean)
        fun onBotAudioLevel(level: Float)
    }

    private sealed interface ClientThreadEvent {
        class SendAudioData(val buf: ByteArray) : ClientThreadEvent
        class SendUserMessage(val msg: AppendedMessage) : ClientThreadEvent
        data object Stop : ClientThreadEvent
        data object WebsocketClosed : ClientThreadEvent
        class WebsocketFailed(val t: Throwable) : ClientThreadEvent
        class WebsocketMessage(val msg: IncomingMessage, val originalText: String) :
            ClientThreadEvent

        class SetMicMute(val muted: Boolean) : ClientThreadEvent
    }

    companion object {

        @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
        fun connect(config: Config, listener: Listener): GeminiClient {

            val events = LinkedBlockingQueue<ClientThreadEvent>()
            val isMicMutedRef = AtomicReference<(() -> Boolean)?>(null)

            fun postEvent(event: ClientThreadEvent) = events.put(event)

            thread(name = "GeminiClient") {
                val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().build()

                val inputSampleRate = 16000

                val audioInCallback =
                    AtomicReference<((ByteArray) -> Unit)?>(null)

                val audioIn = AudioIn(
                    inputSampleRate,
                    onAudioCaptured = { audioInCallback.get()?.invoke(it) },
                    onError = {},
                    onStopped = {},
                    initialMicEnabled = config.initialMicEnabled
                )
                val audioOut = AudioOut(
                    sampleRateHz = 24000,
                    onAudioLevelUpdate = listener::onBotAudioLevel
                )

                var isBotTalking = false

                isMicMutedRef.set { audioIn.muted }

                val listenerRef = AtomicReference(listener)

                val request: okhttp3.Request = okhttp3.Request.Builder()
                    .url(
                        API_URL.toHttpUrl().newBuilder()
                            .addQueryParameter("key", config.apiKey)
                            .build()
                    )
                    .build()

                val ws = client.newWebSocket(request, object : okhttp3.WebSocketListener() {

                    override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "Websocket onClosed($code, $reason)")
                        postEvent(ClientThreadEvent.WebsocketClosed)
                    }

                    override fun onClosing(
                        webSocket: okhttp3.WebSocket,
                        code: Int,
                        reason: String
                    ) {
                        Log.i(TAG, "Websocket onClosing($code, $reason)")
                    }

                    override fun onFailure(
                        webSocket: okhttp3.WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?
                    ) {
                        Log.e(TAG, "Websocket onFailure", t)
                        postEvent(ClientThreadEvent.WebsocketFailed(t))
                    }

                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                        onIncomingMessage(webSocket, text)
                    }

                    override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                        onIncomingMessage(webSocket, bytes.utf8())
                    }

                    @SuppressLint("MissingPermission")
                    fun onIncomingMessage(webSocket: okhttp3.WebSocket, msg: String) {
                        val data =
                            JSON.decodeFromString<IncomingMessage>(
                                msg
                            )
                        postEvent(
                            ClientThreadEvent.WebsocketMessage(
                                msg = data,
                                originalText = msg
                            )
                        )
                    }

                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        Log.i(TAG, "Websocket onOpen")
                        webSocket.send(
                            JSON.encodeToString(
                                SetupRequest.serializer(),
                                SetupRequest(setup = config.modelConfig)
                            )
                        )
                    }
                })

                fun doSendUserMessage(msg: AppendedMessage) {
                    ws.send(
                        JSON.encodeToString(
                            ClientContentRequest.serializer(),
                            ClientContentRequest(
                                clientContent = ClientContentRequest.ClientContent(
                                    turns = listOf(
                                        ClientContentRequest.ClientContent.Turn(
                                            role = msg.role,
                                            parts = listOf(TurnPart(text = msg.content))
                                        )
                                    ),
                                    turnComplete = true
                                )
                            )
                        )
                    )
                }

                fun stopAudio() {
                    audioIn.stop()
                    audioOut.interrupt()
                    audioOut.stop()
                }

                while (true) {
                    when (val event = events.take()) {
                        is ClientThreadEvent.SendAudioData -> {

                            ws.send(
                                JSON.encodeToString(
                                    RealtimeInputRequest.serializer(),
                                    RealtimeInputRequest(
                                        RealtimeInputRequest.RealtimeInput(
                                            listOf(
                                                InlineData.ofPcm(
                                                    inputSampleRate,
                                                    event.buf
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        }

                        is ClientThreadEvent.SendUserMessage -> {
                            doSendUserMessage(event.msg)
                        }

                        ClientThreadEvent.Stop -> {
                            stopAudio()
                            ws.close(1000, "user requested close")
                        }

                        ClientThreadEvent.WebsocketClosed -> {
                            stopAudio()
                            listenerRef.take()?.onSessionEnded("websocket closed", null)
                            return@thread
                        }

                        is ClientThreadEvent.WebsocketFailed -> {
                            stopAudio()
                            listenerRef.take()?.onSessionEnded("websocket failed", event.t)
                            return@thread
                        }

                        is ClientThreadEvent.WebsocketMessage -> {

                            val data = event.msg

                            if (data.setupComplete != null) {
                                Log.i(TAG, "Setup complete")

                                if (config.initialMessage != null) {
                                    doSendUserMessage(
                                        AppendedMessage(
                                            content = config.initialMessage,
                                            role = "user"
                                        )
                                    )
                                }

                                listenerRef.get()?.onConnected()

                                audioInCallback.set { buf ->
                                    postEvent(ClientThreadEvent.SendAudioData(buf))
                                }

                            } else if (data.serverContent != null) {

                                if (data.serverContent.modelTurn != null) {

                                    if (!isBotTalking) {
                                        isBotTalking = true
                                        listenerRef.get()?.onBotTalking(true)
                                    }

                                    data.serverContent.modelTurn.parts.forEach { part ->

                                        if (part.text != null) {
                                            Log.i(
                                                TAG,
                                                "Model text: " + part.text
                                            )
                                        }

                                        part.inlineData?.let { inlineData ->
                                            audioOut.write(
                                                Base64.decode(
                                                    inlineData.data,
                                                    Base64.DEFAULT
                                                )
                                            )
                                        }
                                    }
                                }

                                if (data.serverContent.interrupted) {
                                    Log.i(TAG, "User interrupted model output")
                                    audioOut.interrupt()
                                }

                                if (data.serverContent.turnComplete) {
                                    Log.i(TAG, "Model turn complete")

                                    if (isBotTalking) {
                                        isBotTalking = false
                                        listenerRef.get()?.onBotTalking(false)
                                    }
                                }

                            } else {
                                Log.e(
                                    TAG,
                                    "Unknown message type: ${event.originalText}"
                                )
                            }
                        }

                        is ClientThreadEvent.SetMicMute -> {
                            audioIn.muted = event.muted
                        }
                    }
                }
            }

            return GeminiClient(
                onSendUserMessage = { text ->
                    postEvent(ClientThreadEvent.SendUserMessage(text))
                },
                onClose = {
                    postEvent(ClientThreadEvent.Stop)
                },
                isMicMuted = {
                    isMicMutedRef.get()?.invoke() ?: false
                },
                setMicMuted = {
                    postEvent(ClientThreadEvent.SetMicMute(it))
                }
            )
        }
    }

    var micMuted: Boolean
        get() = isMicMuted()
        set(muted) {
            setMicMuted(muted)
        }

    fun sendUserMessage(role: String, content: String) =
        onSendUserMessage(AppendedMessage(role = role, content = content))

    fun close() = onClose()
}