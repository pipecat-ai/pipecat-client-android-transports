package ai.pipecat.client.small_webrtc_transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Outbound signalling messages sent over the WebRTC data channel.
 */
@Serializable
internal data class OutboundSignallingEvent(
    val type: String,
    val message: TrackStatusMessage,
) {
    companion object {
        fun create(message: TrackStatusMessage) = OutboundSignallingEvent(
            type = "signalling",
            message = message
        )
    }
}

@Serializable
internal data class TrackStatusMessage(
    val type: String,
    @SerialName("receiver_index")
    val receiverIndex: Int,
    val enabled: Boolean,
) {
    companion object {
        fun create(receiverIndex: Int, enabled: Boolean) = TrackStatusMessage(
            type = "trackStatus",
            receiverIndex = receiverIndex,
            enabled = enabled
        )
    }
}

internal object SmallWebRTCTransceiverIndex {
    const val AUDIO: Int = 0
    const val VIDEO: Int = 1
}


