package ai.pipecat.client.small_webrtc_transport.views

import ai.pipecat.client.small_webrtc_transport.EglUtils
import ai.pipecat.client.small_webrtc_transport.TrackRegistry
import ai.pipecat.client.types.MediaTrackId
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.widget.RelativeLayout
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Renders a video track from a local or remote participant.
 *
 * Internally, this uses a `SurfaceView` to provide the best performance. For UI layouts
 * which require overlapping or transparent video views, consider `VideoTextureView` instead.
 */
open class VideoView(
    private val viewContext: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defStyleRes: Int
) : RelativeLayout(viewContext, attrs, defStyleAttr, defStyleRes) {

    companion object {
        const val TAG = "VideoView"
    }

    constructor(viewContext: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            this(viewContext, attrs, defStyleAttr, 0)

    constructor(viewContext: Context, attrs: AttributeSet?) :
            this(viewContext, attrs, 0)

    constructor(viewContext: Context) :
            this(viewContext, null)

    enum class VideoScaleMode(var rendererScaleType: RendererCommon.ScalingType) {
        FILL(RendererCommon.ScalingType.SCALE_ASPECT_FILL),
        FIT(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
    }

    private var renderer: SurfaceViewRenderer? = null

    private var internalTrack: VideoTrack? = null

    var track: MediaTrackId? = null
        get() = field
        set(value) {

            if (value == field && value != null && TrackRegistry.get(value) == internalTrack) {
                return
            }

            field = value

            if (renderer != null) {
                internalTrack?.removeSink(renderer)
            }

            if (value != null) {
                internalTrack = TrackRegistry.get(value) as? VideoTrack
                if (renderer != null) {
                    internalTrack?.addSink(renderer)
                }
            } else {
                internalTrack = null
            }
        }

    var videoScaleMode: VideoScaleMode = VideoScaleMode.FILL
        set(value) {
            field = value
            Log.i(TAG, "Setting scale mode to $value")
            MainScope().launch {
                renderer?.setScalingType(value.rendererScaleType)
            }
        }

    /**
     * When true, flips rendering in the X axis.
     */
    var mirrorHorizontally = false
        set(value) {
            field = value
            Log.i(TAG, "Setting mirror horizontally to $value")
            MainScope().launch {
                renderer?.setMirror(value)
            }
        }

    /**
     * If true, show this [VideoView] on top of any other [VideoView] instances.
     *
     * This value must be set in the case where two [VideoView] instances overlap. If the Z-order
     * is not explicitly set using this property, either [VideoView] may end up on top,
     * regardless of the view hierarchy or creation order.
     *
     * Having three or more overlapping [VideoView] instances is unsupported.
     *
     * Note: This value must not be changed after the view is attached to the window.
     *
     * @throws IllegalStateException if the [VideoView] is already attached to the window.
     */
    var bringVideoToFront: Boolean = false
        set(value) {
            if (isAttachedToWindow) {
                throw IllegalStateException("The VideoView is already attached to the window")
            }
            field = value
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.i(TAG, "VideoView attached to window")
        createRenderer()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.i(TAG, "VideoView detached from window")
        destroyRenderer()
    }

    private fun createRenderer() {

        Log.i(TAG, "Creating renderer")

        val renderer = SurfaceViewRenderer(viewContext)

        this.renderer = renderer

        renderer.init(
            EglUtils.eglBase.eglBaseContext,
            null /* rendererEvents */,
            EglBase.CONFIG_PLAIN,
            GlRectDrawer()
        )

        if (bringVideoToFront) {
            Log.i(TAG, "Bringing to front")
            renderer.setZOrderMediaOverlay(true)
        }

        renderer.setScalingType(videoScaleMode.rendererScaleType)
        renderer.setMirror(mirrorHorizontally)

        internalTrack?.addSink(renderer)

        addView(renderer)
    }

    private fun destroyRenderer() {

        Log.i(TAG, "Destroying renderer")

        if (renderer != null) {
            removeView(renderer)
            renderer?.release()
            renderer = null
        }
    }

    protected fun finalize() {
        if (renderer != null) {
            Log.e(
                TAG,
                "Warning! Renderer was leaked (never received onDetachedFromWindow?)"
            )
            destroyRenderer()
        }
    }
}