package ai.pipecat.client.daily

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DailyTransportAuthBundle(
    // Legacy (backwards compat)
    @SerialName("room_url")
    val roomUrl: String? = null,
    val token: String? = null,

    // New
    val dailyRoom: String? = null,
    val dailyToken: String? = null
) {
    fun actualRoom() = dailyRoom ?: roomUrl
    fun actualToken() = dailyToken ?: token
}