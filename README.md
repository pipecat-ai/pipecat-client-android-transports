# Pipecat Android Client SDK: Transports

[RTVI](https://github.com/rtvi-ai/) is an open standard for Real-Time Voice (and Video) Inference.

## Daily Websocket Transport

Add the following dependency to your `build.gradle` file:

```
implementation "ai.pipecat:daily-transport:0.3.0"
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

val client = PipecatClient(transport, callbacks, options)

client.start().withCallback {
    // ...
}
```

`client.start()` (and other APIs) return a `Future`, which can give callbacks, or be awaited
using Kotlin Coroutines (`client.start().await()`).
