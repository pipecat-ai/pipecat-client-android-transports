package ai.pipecat.client.daily

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DailyTransportAuthBundle(
    @SerialName("room_url")
    val roomUrl: String,
    val token: String? = null,
)