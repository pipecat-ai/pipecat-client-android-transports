package ai.pipecat.client.gemini_live_websocket

import ai.pipecat.client.gemini_live_websocket.GeminiClient.Companion.calculateAudioLevel
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "AudioIn"

private const val BUFFER_MS = 100

internal class AudioIn @RequiresPermission(android.Manifest.permission.RECORD_AUDIO) constructor(
    sampleRateHz: Int,
    onAudioCaptured: (ByteArray) -> Unit,
    onAudioLevelUpdate: (Float) -> Unit,
    onError: (Throwable) -> Unit,
    onStopped: () -> Unit,
    initialMicEnabled: Boolean
) {

    private val thread: Thread
    private val mute = AtomicBoolean(!initialMicEnabled)
    private val stop = AtomicBoolean(false)

    init {

        thread = thread(name = "AudioIn") {

            try {
                while (!stop.get()) {

                    while (mute.get() && !stop.get()) {
                        synchronized(mute) {
                            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                            (mute as Object).wait()
                        }
                    }

                    if (stop.get()) {
                        break
                    }

                    val frameSizeBytes = 2 // 16-bit, mono

                    val bufferBytes = (frameSizeBytes * sampleRateHz * BUFFER_MS) / 1000

                    val record = AudioRecord(
                        AudioSource.VOICE_COMMUNICATION,
                        sampleRateHz,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes
                    )

                    try {
                        record.startRecording()

                        val buf = ByteArray(bufferBytes)

                        while (!stop.get() && !mute.get()) {

                            val readResult =
                                record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)

                            if (readResult <= 0) {
                                throw RuntimeException("record.read returned $readResult")
                            }

                            onAudioLevelUpdate(calculateAudioLevel(buf))
                            onAudioCaptured(buf.copyOf(readResult))
                        }
                    } finally {
                        record.release()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception when capturing audio", e)
                onError(e)

            } finally {
                onStopped()
            }
        }
    }

    fun stop() {
        stop.set(true)
    }

    var muted: Boolean
        get() = mute.get()
        set(value) {
            synchronized(mute) {
                mute.set(value)
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (mute as Object).notifyAll()
            }
        }
}