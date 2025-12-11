package ai.pipecat.client.small_webrtc_transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class IceCandidatesRequestBody(
    @SerialName("pc_id")
    val pcId: String,
    val candidates: List<IceCandidateItem>,
)

@Serializable
internal data class IceCandidateItem(
    val candidate: String,
    @SerialName("sdp_mid")
    val sdpMid: String?,
    @SerialName("sdp_mline_index")
    val sdpMLineIndex: Int,
)


