package ai.pipecat.client.gemini_live_websocket

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

private const val BUFFER_MS = 100

private const val TAG = "AudioOut"

internal class AudioOut(sampleRateHz: Int) {

    private val queue = LinkedBlockingQueue<ByteArray?>()

    init {
        thread(name = "AudioOut") {
            val audioFormat = AudioFormat.Builder().setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()

            val frameSizeBytes = 2 // 16-bit, mono

            val bufferBytes = (frameSizeBytes * sampleRateHz * BUFFER_MS) / 1000

            Log.i(
                TAG,
                "Creating AudioOut: sampleRateHz = $sampleRateHz, bufferBytes = $bufferBytes"
            )

            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                audioFormat,
                (frameSizeBytes * sampleRateHz * BUFFER_MS) / 1000,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            try {
                track.play()

                while (true) {
                    val item = queue.take()

                    if (item == null) {
                        Log.i(TAG, "Terminating AudioOut")
                        return@thread
                    }

                    var writePtr = 0

                    while (writePtr < item.size) {
                        val writeRes = track.write(item, 0, item.size)

                        if (writeRes <= 0) {
                            throw RuntimeException("write returned $writeRes")
                        }

                        writePtr += item.size
                    }
                }
            } finally {
                track.release()
            }
        }
    }

    fun write(samples: ByteArray) {
        queue.offer(samples)
    }

    fun stop() {
        queue.offer(null)
    }
}