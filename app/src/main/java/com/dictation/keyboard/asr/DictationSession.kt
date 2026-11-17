package com.dictation.keyboard.asr

import com.dictation.keyboard.text.TranscriptAssembler
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * One dictation, from first audio chunk to final text.
 *
 *   audio chunks ──► VAD (splits at pauses) ──► phrase queue ──► recogniser (ASR thread)
 *                                                                    │
 *                                                       TranscriptAssembler ──► liveText
 *
 * Each phrase is transcribed while you keep talking, so when you stop, only the last phrase is
 * left to process. Audio can come from the mic or from a file (the test tool); both use this.
 */
class DictationSession(
    private val engine: SpeechEngine,
    paragraphPauseSec: Float,
    private val scope: CoroutineScope,
    private val asrDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {
    private class Phrase(val samples: FloatArray, val startSec: Double, val endSec: Double)

    private val audio = Channel<FloatArray>(Channel.UNLIMITED)
    private val phrases = Channel<Phrase>(Channel.UNLIMITED)
    private val assembler = TranscriptAssembler(paragraphPauseSec)

    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText

    /** Mic level 0..1 for the waveform. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    @Volatile var samplesFed = 0L
        private set
    @Volatile var asrBusyMs = 0L
        private set
    @Volatile var phraseCount = 0
        private set

    private var vadJob: Job? = null
    private var asrJob: Job? = null

    fun start() {
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = engine.model.vadPath,
                    threshold = 0.5f,
                    // A phrase ends after this much silence. Shorter = snappier live text but less
                    // context per phrase for punctuation; 0.5 s is a reasonable middle.
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    // Force a cut if you talk this long without pausing.
                    maxSpeechDuration = 20f,
                ),
                sampleRate = SpeechEngine.SAMPLE_RATE,
                numThreads = 1,
            ),
        )
        vadJob = scope.launch(Dispatchers.Default) {
            try {
                for (chunk in audio) {
                    vad.acceptWaveform(chunk)
                    drain(vad)
                }
                vad.flush()
                drain(vad)
            } finally {
                vad.release()
                phrases.close()
            }
        }
        asrJob = scope.launch(asrDispatcher) {
            for (p in phrases) {
                val t0 = System.nanoTime()
                val text = engine.transcribe(p.samples)
                asrBusyMs += (System.nanoTime() - t0) / 1_000_000
                phraseCount++
                assembler.add(text, p.startSec, p.endSec)
                _liveText.value = assembler.text
            }
        }
    }

    private fun drain(vad: Vad) {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            val start = seg.start.toDouble() / SpeechEngine.SAMPLE_RATE
            val end = start + seg.samples.size.toDouble() / SpeechEngine.SAMPLE_RATE
            phrases.trySend(Phrase(seg.samples, start, end))
        }
    }

    /** Thread-safe; called from the mic thread. */
    fun feed(chunk: FloatArray) {
        samplesFed += chunk.size
        var sum = 0.0
        for (s in chunk) sum += s * s
        val rms = sqrt(sum / chunk.size.coerceAtLeast(1))
        val db = 20 * log10(rms.coerceAtLeast(1e-6))
        _level.value = ((db + 55) / 45).toFloat().coerceIn(0f, 1f)
        audio.trySend(chunk)
    }

    /** No more audio: finish the last phrase and return the full text. */
    suspend fun finish(): String {
        _level.value = 0f
        audio.close()
        vadJob?.join()
        asrJob?.join()
        return assembler.finish()
    }

    fun cancel() {
        audio.close()
        vadJob?.cancel()
        asrJob?.cancel()
    }

    val modelId: String get() = engine.model.id

    val audioSeconds: Double get() = samplesFed.toDouble() / SpeechEngine.SAMPLE_RATE
}
