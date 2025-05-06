package ai.pipecat.client.small_webrtc_transport

import ai.pipecat.client.types.MediaTrackId
import org.webrtc.MediaStreamTrack

internal object TrackRegistry {

    private val tracks = mutableMapOf<MediaTrackId, MediaStreamTrack>()
    private val trackIds = mutableMapOf<MediaStreamTrack, MediaTrackId>()

    fun add(track: MediaStreamTrack): MediaTrackId {
        synchronized(tracks) {
            val id = track.pipecatId()
            tracks[id] = track
            trackIds[track] = id
            return id
        }
    }

    fun remove(track: MediaStreamTrack) {
        track.state()
        synchronized(tracks) {
            trackIds.remove(track)?.let { id ->
                tracks.remove(id)
            }
        }
    }

    fun get(id: MediaTrackId) = synchronized(tracks) {
        tracks[id]
    }
}