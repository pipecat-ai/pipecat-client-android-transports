# Changelog

## daily-transport

### 1.2.2 (2026-10-08)

No changes.

### 1.2.1 (2026-08-03)

- Updated Daily client to 0.39.1

### 1.2.0 (2026-07-17)

- Updated Pipecat client dependency from 1.1.0 to 1.2.0
- Updated Daily Android client from 0.35.0 to 0.38.1
- Added `INTERNET` and `ACCESS_NETWORK_STATE` permissions to the library manifest, plus a
  runtime check on connect that fails gracefully with an `RTVIError` if
  `ACCESS_NETWORK_STATE` is missing, instead of WebRTC aborting the process with a native
  SIGABRT
- Toolchain/build updates: Kotlin 2.0.20 → 2.3.10, kotlinx-serialization 1.7.1 → 1.10.0,
  core-ktx 1.13.1 → 1.17.0, Dokka 2.1.0, migration to the new `kotlin { compilerOptions }`
  DSL, and explicit `singleVariant("release")` publishing config

## small-webrtc-transport

### 1.2.2 (2026-10-08)

- Added libwebrtc license details.

### 1.2.1 (2026-08-03)

No changes.

### 1.2.0 (2026-07-17)

- Added trickle ICE support: local ICE candidates are gathered and sent to the server in
  ~200 ms batches via HTTP PATCH
- Added configurable ICE servers via new `IceConfig`/`IceServer` types — settable on
  `SmallWebRTCTransportConnectParams` or returned by the server in the start-bot response,
  falling back to Google's STUN server
- Transport now signals mic/cam track enable/disable status to the server via new outbound
  signalling messages, and syncs track status on connect
- Connection setup now waits for the data channel to open before proceeding
- Added `INTERNET`/`ACCESS_NETWORK_STATE` manifest permissions and a graceful runtime
  `ACCESS_NETWORK_STATE` check
- Updated Pipecat client dependency from 1.1.0 to 1.2.0, plus the same toolchain updates
  as daily-transport

## gemini-live-websocket-transport

### 1.2.2 (2026-10-08)

No changes.

### 1.2.1 (2026-08-03)

No changes.

### 1.2.0 (2026-07-17)

- Migrated to the Pipecat Client 1.x transport API: the transport is now
  `Transport<GeminiServiceOptions>` with a new serializable `GeminiServiceOptions`
  connect-params type replacing `ServiceConfig`/`buildConfig()`, the `Factory` class is
  removed, and a `PipecatClientGeminiLiveWebsocket` typealias was added
- Updated Pipecat client dependency from 0.3.4 to 1.2.0
- Added LLM function-call support: function calls from Gemini are forwarded as RTVI
  `llm-function-call` messages, and `llm-function-call-result` responses are sent back to
  Gemini
- Added user speaking-state detection and real-time mic audio level reporting to transport
  callbacks
- Updated to Google's `v1beta` BidiGenerateContent endpoint (previously `v1alpha`)
- Default model updated from `gemini-2.0-flash-exp` to `gemini-3.1-flash-live-preview`
- Config builder now takes a `voice` parameter (default "Puck") and constructs
  `generation_config` internally with audio response modality and speech config, replacing
  the raw `generationConfig` parameter
- Realtime audio input is sent using the new `audio` field required by newer Live API
  models, instead of the deprecated `mediaChunks`
- Handles new server events: session resumption updates, `goAway` (logs a warning before
  server-side termination), tool-call cancellation, usage metadata, and VAD signals
- Removed an unnecessary dependency on `daily-android-client`
- Fixed the log tag, which incorrectly said "DailyTransport"
- Toolchain: JVM target raised from 1.8 to 11, plus the shared Kotlin/serialization/
  publishing updates

## openai-realtime-webrtc-transport

### 1.2.2 (2026-10-08)

- Added libwebrtc license details.

### 1.2.1 (2026-08-03)

No changes.

### 1.2.0 (2026-07-17)

- Migrated to the Pipecat Client 1.x transport API: now `Transport<OpenAIServiceOptions>`
  with a new serializable `OpenAIServiceOptions` type (`apiKey`, `sessionConfig`, `model`,
  `initialMessages`) replacing the llm service-config options, `Factory` and `expiry()`
  removed, and a `PipecatClientOpenAIRealtimeWebRTC` typealias added; `model` is now
  optional, defaulting to `gpt-realtime`
- Updated Pipecat client dependency from 0.3.4 to 1.2.0
- Updated to the GA Realtime API:
  - Connection negotiation now POSTs to `/v1/realtime/calls`, sending the SDP offer and
    session config together as multipart form data
  - Session config is converted to the GA session shape (audio settings nested under
    `audio.input`/`audio.output`, explicit session `type: "realtime"`) and applied at call
    setup rather than via a `session.update` after connecting
  - `session.update` events sent via `sendConfigUpdate()` get the required
    `type: "realtime"` added automatically
  - Handles the GA `response.output_audio_transcript.delta` event name (legacy beta name
    still supported)
  - Assistant conversation items use the renamed `output_text` content type
  - `temperature` is deprecated and ignored (not supported by the GA API)
- Added `INTERNET`/`ACCESS_NETWORK_STATE` manifest permissions and a graceful runtime
  `ACCESS_NETWORK_STATE` check
- Shared toolchain/build updates as above
