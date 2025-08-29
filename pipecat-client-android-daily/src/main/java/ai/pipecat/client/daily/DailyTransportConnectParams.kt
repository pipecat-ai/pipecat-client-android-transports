package ai.pipecat.client.daily

import co.daily.model.MeetingToken

data class DailyTransportConnectParams(
    val dailyRoom: String,
    val dailyToken: MeetingToken?
)