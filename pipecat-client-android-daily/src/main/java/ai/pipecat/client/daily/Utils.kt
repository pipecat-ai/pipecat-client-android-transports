package ai.pipecat.client.daily

import ai.pipecat.client.result.Promise
import ai.pipecat.client.result.RTVIError
import ai.pipecat.client.types.MediaDeviceId
import ai.pipecat.client.types.MediaDeviceInfo
import ai.pipecat.client.types.MediaTrackId
import ai.pipecat.client.types.Participant
import ai.pipecat.client.types.ParticipantId
import co.daily.model.RequestError
import co.daily.model.RequestResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal val JSON_INSTANCE = Json { ignoreUnknownKeys = true }

internal fun RequestError?.toRTVIError() =
    RTVIError.OtherError("Error from Daily client: ${this?.msg}")

internal fun Promise<Unit, RTVIError>.resolveWithDailyResult(result: RequestResult) {
    if (result.isError) {
        resolveErr(result.error.toRTVIError())
    } else {
        resolveOk(Unit)
    }
}

internal fun co.daily.model.MediaStreamTrack.toRtvi() = MediaTrackId(id)

internal fun JsonElement.tryGetObject(name: String): JsonObject? {
    return (this as? JsonObject)?.get(name) as? JsonObject
}

internal fun JsonElement.tryGetString(name: String): String? {
    return ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull
}

internal fun co.daily.model.Participant.toRtvi() = Participant(
    id = ParticipantId(id.uuid.toString()),
    name = info.userName,
    local = info.isLocal
)

internal fun co.daily.model.MediaDeviceInfo.toRtvi() = MediaDeviceInfo(
    id = MediaDeviceId(deviceId),
    name = label
)

internal fun co.daily.model.ParticipantId.toRtvi() = ParticipantId(uuid.toString())