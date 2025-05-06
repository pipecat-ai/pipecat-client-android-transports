package ai.pipecat.client.small_webrtc_transport

import android.content.Context
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CameraVideoCapturer.CameraEventsHandler
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

internal class CameraManager(context: Context, val mode: CameraMode, source: VideoSource) {

    private val surfaceTextureHelper =
        SurfaceTextureHelper.create("VideoCapturerThread", EglUtils.eglBase.eglBaseContext)

    private val enumerator = if (Camera2Enumerator.isSupported(context)) {
        Camera2Enumerator(context)
    } else {
        Camera1Enumerator()
    }

    private val capturer: CameraVideoCapturer?

    init {

        val deviceName = enumerator.deviceNames.firstOrNull {
            when (mode) {
                CameraMode.Front -> enumerator.isFrontFacing(it)
                CameraMode.Rear -> enumerator.isBackFacing(it)
            }
        }

        if (deviceName == null) {
            capturer = null
        } else {
            capturer = enumerator.createCapturer(deviceName, object : CameraEventsHandler {
                override fun onCameraError(p0: String?) {
                }

                override fun onCameraDisconnected() {
                }

                override fun onCameraFreezed(p0: String?) {
                }

                override fun onCameraOpening(p0: String?) {
                }

                override fun onFirstFrameAvailable() {
                }

                override fun onCameraClosed() {
                }
            })

            capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)

            capturer.startCapture(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    fun dispose() {
        capturer?.stopCapture()
        capturer?.dispose()
        surfaceTextureHelper.dispose()
    }
}