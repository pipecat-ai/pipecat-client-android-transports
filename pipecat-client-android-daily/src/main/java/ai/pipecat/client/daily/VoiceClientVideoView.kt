package ai.pipecat.client.daily

import ai.pipecat.client.types.MediaTrackId
import android.content.Context
import android.util.AttributeSet
import co.daily.model.MediaStreamTrack
import co.daily.view.VideoView

/**
 * Overrides the Daily [VideoView] to allow [MediaTrackId] tracks from the VoiceClient to be
 * rendered.
 */
class VoiceClientVideoView @JvmOverloads constructor(
    viewContext: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : VideoView(viewContext, attrs, defStyleAttr, defStyleRes) {

    /**
     * Displays the specified [MediaTrackId] in this view.
     */
    var voiceClientTrack: MediaTrackId?
        get() = track?.id?.let(::MediaTrackId)
        set(value) {
            track = value?.id?.let(::MediaStreamTrack)
        }
}