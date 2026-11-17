package com.dictation.keyboard.asr

import android.net.Uri
import android.os.SystemClock
import com.dictation.keyboard.DictationApp
import com.dictation.keyboard.audio.AudioFileDecoder
import com.dictation.keyboard.metrics.SessionMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs a recording through exactly the same VAD + recogniser pipeline as the keyboard, so the
 * acceptance tests (e.g. the 2-minute ramble) are repeatable. In real-time mode audio is fed at
 * speaking speed, so "stop to text" matches what you'd see live.
 */
object FileTest {

    class Result(val text: String, val metrics: SessionMetrics)

    suspend fun run(app: DictationApp, uri: Uri, realTime: Boolean, onProgress: (String) -> Unit): Result {
        onProgress("Decoding audio…")
        val samples = withContext(Dispatchers.IO) { AudioFileDecoder.decodeToMono16k(app, uri) }
        if (samples.isEmpty()) error("That file has no audio")
        onProgress("Loading speech model…")
        val engine = app.engine.get() ?: error("No speech model installed")

        val probe = app.metrics.startProbe(app.appScope)
        val session = DictationSession(engine, app.settings.paragraphPauseSec.value, app.appScope, app.engine.dispatcher)
        session.start()
        val chunk = 512
        val totalSec = samples.size / 16_000.0
        withContext(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            var i = 0
            var lastReport = 0L
            while (i < samples.size) {
                val end = minOf(i + chunk, samples.size)
                session.feed(samples.copyOfRange(i, end))
                i = end
                if (realTime) {
                    val due = t0 + (i * 1000L / 16_000)
                    val wait = due - SystemClock.elapsedRealtime()
                    if (wait > 0) delay(wait)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport > 500) {
                    lastReport = now
                    onProgress("Playing %.0f / %.0f s…".format(i / 16_000.0, totalSec))
                }
            }
        }
        onProgress("Finishing…")
        val stopAt = SystemClock.elapsedRealtime()
        val text = session.finish()
        val stopToText = SystemClock.elapsedRealtime() - stopAt
        val (pss, uah) = probe.stop()
        val m = SessionMetrics(
            timestamp = System.currentTimeMillis(),
            source = if (realTime) "file" else "file-fast",
            modelId = session.modelId,
            audioSec = session.audioSeconds,
            phrases = session.phraseCount,
            stopToTextMs = stopToText,
            asrBusyMs = session.asrBusyMs,
            peakPssMb = pss,
            chargeUsedUah = uah,
        )
        app.metrics.add(m)
        return Result(text, m)
    }
}
