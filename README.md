<h1><div align="center">
 <img alt="pipecat" width="500px" height="auto" src="assets/pipecat-android.png">
</div></h1>

# Pipecat Android Client SDK: Transports

[RTVI](https://github.com/rtvi-ai/) is an open standard for Real-Time Voice (and Video) Inference.

The following RTVI transports are available in this repository:

## Daily WebRTC Transport

Add the following dependency to your `build.gradle` file:

```
implementation "ai.pipecat:daily-transport:0.3.7"
```

Instantiate from your code:

```kotlin
val callbacks = object : RTVIEventCallbacks() {

    override fun onBackendError(message: String) {
        Log.e(TAG, "Error from backend: $message")
    }

    // ...
}

val transport = DailyTransport.Factory(context)

val client = RTVIClient(transport, callbacks, options)

client.start().withCallback {
    // ...
}
```

`client.start()` (and other APIs) return a `Future`, which can give callbacks, or be awaited
using Kotlin Coroutines (`client.start().await()`).


## Gemini Live Websocket Transport

Add the following dependency to your `build.gradle` file:

```
implementation "ai.pipecat:gemini-live-websocket-transport:0.3.7"
```

Instantiate from your code:

```kotlin
val transport = GeminiLiveWebsocketTransport.Factory(context)

val options = RTVIClientOptions(
    params = RTVIClientParams(
        baseUrl = null,
        config = GeminiLiveWebsocketTransport.buildConfig(
            apiKey = "<your Gemini api key>",
            generationConfig = Value.Object(
                "speech_config" to Value.Object(
                    "voice_config" to Value.Object(
                        "prebuilt_voice_config" to Value.Object(
                            "voice_name" to Value.Str("Puck")
                        )
                    )
                )
            ),
            initialUserMessage = "How tall is the Eiffel Tower?"
        )
    )
)

val client = RTVIClient(transport, callbacks, options)

client.connect().withCallback {
    // ...
}
```


## OpenAI Realtime WebRTC Transport

Add the following dependency to your `build.gradle` file:

```
implementation "ai.pipecat:openai-realtime-webrtc-transport:0.3.7"
```

Instantiate from your code:

```kotlin
val transport = OpenAIRealtimeWebRTCTransport.Factory(context)

val options = RTVIClientOptions(
    params = RTVIClientParams(
        baseUrl = null,
        config = OpenAIRealtimeWebRTCTransport.buildConfig(
            apiKey = apiKey,
            initialMessages = listOf(
                LLMContextMessage(role = "user", content = "How tall is the Eiffel Tower?")
            ),
            initialConfig = OpenAIRealtimeSessionConfig(
                voice = "ballad",
                turnDetection = Value.Object("type" to Value.Str("semantic_vad")),
                inputAudioNoiseReduction = Value.Object("type" to Value.Str("near_field")),
                inputAudioTranscription = Value.Object("model" to Value.Str("gpt-4o-transcribe"))
            )
        )
    )
)

val client = RTVIClient(transport, callbacks, options)

client.connect().withCallback {
    // ...
}
```


## Small WebRTC Transport

Add the following dependency to your `build.gradle` file:

```
implementation "ai.pipecat:small-webrtc-transport:0.3.7"
```

Instantiate from your code:

```kotlin
val options = RTVIClientOptions(
    params = RTVIClientParams(baseUrl = null),
    enableMic = true,
    enableCam = true
)

val connectionUrl = "http://localhost:7860/api/offer"

val client = RTVIClient(SmallWebRTCTransport.Factory(context, connectionUrl), callbacks, options)

client.connect().withCallback {
    // ...
}
```