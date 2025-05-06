package ai.pipecat.client.small_webrtc_transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OfferRequestBody(
    val sdp: String,
    val type: String,
    @SerialName("pc_id")
    val pcId: String?,
    @SerialName("restart_pc")
    val restartPc: Boolean
)