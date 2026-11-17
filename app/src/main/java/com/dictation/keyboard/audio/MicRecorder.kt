package com.dictation.keyboard.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process

/**
 * Records 16 kHz mono audio on its own high-priority thread and hands it over in ~32 ms chunks.
 * The caller must already hold RECORD_AUDIO.
 */
class MicRecorder(private val onChunk: (FloatArray) -> Unit) {

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        check(thread == null) { "Already recording" }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE * 2), // ~1 s of headroom
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone unavailable")
        }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IllegalStateException("Microphone is in use by another app")
        }
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(CHUNK)
            try {
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    onChunk(FloatArray(n) { buf[it] / 32768f })
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "mic").also { it.start() }
    }

    /** Stops recording. Every chunk read before this returns has been delivered. */
    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 512
    }
}
