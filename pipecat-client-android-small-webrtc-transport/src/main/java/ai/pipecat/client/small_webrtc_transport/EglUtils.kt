package ai.pipecat.client.small_webrtc_transport

import org.webrtc.EglBase

internal object EglUtils {
    val eglBase = EglBase.create(null, EglBase.CONFIG_PLAIN)
}