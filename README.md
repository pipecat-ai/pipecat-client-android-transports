<h1><div align="center">
 <img alt="pipecat" width="500px" height="auto" src="assets/pipecat-android.png">
</div></h1>

# Pipecat Android Client SDK: Transports

[RTVI](https://github.com/rtvi-ai/) is an open standard for Real-Time Voice (and Video) Inference.

The following RTVI transports are available in this repository:

## Daily WebRTC Transport

Add the following dependency to your `build.gradle` file:

```
implementation "ai.pipecat:daily-transport:1.2.0"
```

Instantiate from your code:

```kotlin
val callbacks = object : PipecatEventCallbacks() {

    override fun onBackendError(message: String) {
        Log.e(TAG, "Error from backend: $message")
    }

    // ...
}

val options = PipecatClientOptions(callbacks = callbacks)

val client: PipecatClientDaily = PipecatClient(DailyTransport(context), options)

val apiRequest = APIRequest(
    endpoint = "https://your-server/start",
    requestData = Value.Object(
        "createDailyRoom" to Value.Bool(true)
    )
)

client.startBotAndConnect(apiRequest).withCallback {
    // ...
}
```

Many `PipecatClient` APIs return a `Future`, which can give callbacks, or be awaited
using Kotlin Coroutines (`client.startBotAndConnect().await()`).


## Gemini Live Websocket Transport

Add the following dependency to your `build.gradle` file:

```
implementation "ai.pipecat:gemini-live-websocket-transport:1.2.0"
```

Instantiate from your code:

```kotlin
val callbacks = object : PipecatEventCallbacks() {

    override fun onBackendError(message: String) {
        Log.e(TAG, "Error from backend: $message")
    }

    // ...
}

val options = PipecatClientOptions(callbacks = callbacks)

val client: PipecatClientGeminiLiveWebsocket =
    PipecatClient(GeminiLiveWebsocketTransport(context), options)

client.connect(
    GeminiServiceOptions.withDefaults(
        apiKey = "<your Gemini API key>",
        voice = "Puck",
        initialUserMessage = "How tall is the Eiffel Tower?"
    )
).withCallback {
    // ...
}
```


## OpenAI Realtime WebRTC Transport

Add the following dependency to your `build.gradle` file:

```
implementation "ai.pipecat:openai-realtime-webrtc-transport:1.2.0"
```

Instantiate from your code:

```kotlin
val callbacks = object : PipecatEventCallbacks() {

    override fun onBackendError(message: String) {
        Log.e(TAG, "Error from backend: $message")
    }

    // ...
}

val options = PipecatClientOptions(callbacks = callbacks)

val client: PipecatClientOpenAIRealtimeWebRTC =
    PipecatClient(OpenAIRealtimeWebRTCTransport(context), options)

client.connect(
    OpenAIServiceOptions(
        apiKey = "<your OpenAI API key>",
        sessionConfig = OpenAIRealtimeSessionConfig(
            voice = "marin",
            turnDetection = Value.Object("type" to Value.Str("semantic_vad")),
            inputAudioNoiseReduction = Value.Object("type" to Value.Str("near_field")),
            inputAudioTranscription = Value.Object("model" to Value.Str("whisper-1"))
        ),
        initialMessages = listOf(
            LLMContextMessage(
                role = LLMContextMessage.Role.User,
                content = "How tall is the Eiffel Tower?"
            )
        )
    )
).withCallback {
    // ...
}
```


## Small WebRTC Transport

Add the following dependency to your `build.gradle` file:

```
implementation "ai.pipecat:small-webrtc-transport:1.2.0"
```

Instantiate from your code:

```kotlin
val callbacks = object : PipecatEventCallbacks() {

    override fun onBackendError(message: String) {
        Log.e(TAG, "Error from backend: $message")
    }

    // ...
}

val options = PipecatClientOptions(callbacks = callbacks)

val client: PipecatClientSmallWebRTC = PipecatClient(SmallWebRTCTransport(context), options)

val apiRequest = APIRequest(
    endpoint = "https://your-server/start",
    requestData = Value.Object(
        "transport" to Value.Str("webrtc"),
        "enableDefaultIceServers" to Value.Bool(true)
    )
)

client.startBotAndConnect(apiRequest).withCallback {
    // ...
}
```

Note: the `requestData` values shown above are for bots hosted on
[Pipecat Cloud](https://docs.pipecat.ai/pipecat-cloud/introduction), which uses the
`/start` request body to determine how the bot session is launched. Self-hosted
runners may ignore these fields or accept different ones.
