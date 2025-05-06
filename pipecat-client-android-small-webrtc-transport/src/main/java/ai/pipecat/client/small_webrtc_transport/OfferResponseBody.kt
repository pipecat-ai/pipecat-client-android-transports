package ai.pipecat.client.small_webrtc_transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OfferResponseBody(
    val sdp: String,
    val type: String,
    @SerialName("pc_id")
    val pcId: String,
)