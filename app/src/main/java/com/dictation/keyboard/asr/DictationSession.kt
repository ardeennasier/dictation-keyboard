package com.dictation.keyboard.asr

import com.dictation.keyboard.cleanup.Cleaner
import com.dictation.keyboard.cleanup.CleanupStrength
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
 *   audio ──► VAD (splits at short pauses) ──► phrases ──► quick per-phrase transcript (live text)
 *                                                │
 *                                                └─► grouped into blocks of up to ~25 s, split only
 *                                                    at longer pauses ──► block transcript (final text)
 *
 * Transcribing each short phrase on its own loses context: the model guesses words worse and
 * treats every fragment as a sentence ("So it does put a. Good space"). So the inserted text
 * comes from whole blocks, and per-phrase results are only used for the live preview.
 * Blocks are transcribed while you keep talking; when you stop, only the last block is left
 * (and pending previews are skipped), so stop-to-text stays short.
 */
/** What one dictation produced: the exact transcript and the cleaned-up version. */
data class Transcript(
    val raw: String,
    val cleaned: String,
    /** Total clean-up time; only the last block's part of it is felt after you tap stop. */
    val cleanupMs: Long,
    /** How each block was cleaned: "off", "light", "ai", "ai-rejected". */
    val methods: List<String>,
    val notes: List<String>,
) {
    val methodSummary: String
        get() = methods.groupingBy { it }.eachCount().entries.joinToString(" ") { "${it.key}×${it.value}" }
}

class DictationSession(
    private val engine: SpeechEngine,
    paragraphPauseSec: Float,
    private val scope: CoroutineScope,
    private val asrDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    private val cleaner: Cleaner,
    private val strength: CleanupStrength,
) {
    private class Phrase(val samples: FloatArray, val startSec: Double, val endSec: Double) {
        val durationSec: Double get() = endSec - startSec
    }

    private sealed interface AsrJob {
        class Preview(val phrase: Phrase) : AsrJob
        class Block(val samples: FloatArray, val startSec: Double, val endSec: Double) : AsrJob
    }

    private val audio = Channel<FloatArray>(Channel.UNLIMITED)
    private val jobs = Channel<AsrJob>(Channel.UNLIMITED)
    private val assembler = TranscriptAssembler(paragraphPauseSec)

    // Clean-up runs on its own coroutine, block by block, while you keep talking.
    private class RawBlock(val text: String, val startSec: Double, val endSec: Double)
    private val toClean = Channel<RawBlock>(Channel.UNLIMITED)
    private val cleanAssembler = TranscriptAssembler(paragraphPauseSec)
    private var cleanJob: Job? = null
    @Volatile private var cleanupMs = 0L
    private val methods = ArrayList<String>()
    private val notes = ArrayList<String>()

    // Owned by the VAD coroutine.
    private val openBlock = ArrayList<Phrase>()

    // Owned by the ASR coroutine.
    private val previewTexts = ArrayList<String>()

    @Volatile private var finishing = false

    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText

    /** Mic level 0..1 for the waveform. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    @Volatile var samplesFed = 0L
        private set
    @Volatile var asrBusyMs = 0L
        private set
    /** Number of blocks transcribed for the final text. */
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
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
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
                closeBlock()
            } finally {
                vad.release()
                jobs.close()
            }
        }
        cleanJob = scope.launch(Dispatchers.Default) {
            for (b in toClean) {
                val r = cleaner.clean(b.text, strength)
                cleanupMs += r.ms
                methods.add(r.method)
                r.note?.let { notes.add(it) }
                cleanAssembler.add(r.text, b.startSec, b.endSec)
            }
        }
        asrJob = scope.launch(asrDispatcher) {
          try {
            for (job in jobs) {
                when (job) {
                    is AsrJob.Preview -> {
                        if (finishing) continue // nobody will see it
                        val text = timed { engine.transcribe(job.phrase.samples) }
                        if (text.isNotBlank()) previewTexts.add(text)
                    }
                    is AsrJob.Block -> {
                        val text = timed { engine.transcribe(job.samples) }
                        phraseCount++
                        assembler.add(text, job.startSec, job.endSec)
                        toClean.trySend(RawBlock(text, job.startSec, job.endSec))
                        previewTexts.clear() // this block's previews are superseded
                    }
                }
                _liveText.value = listOf(assembler.text, previewTexts.joinToString(" "))
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
            }
          } finally {
            toClean.close()
          }
        }
    }

    private inline fun timed(block: () -> String): String {
        val t0 = System.nanoTime()
        return block().also { asrBusyMs += (System.nanoTime() - t0) / 1_000_000 }
    }

    private fun drain(vad: Vad) {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            val start = seg.start.toDouble() / SpeechEngine.SAMPLE_RATE
            val end = start + seg.samples.size.toDouble() / SpeechEngine.SAMPLE_RATE
            onPhrase(Phrase(seg.samples, start, end))
        }
    }

    private fun onPhrase(p: Phrase) {
        if (openBlock.isNotEmpty()) {
            val gap = p.startSec - openBlock.last().endSec
            val blockLen = p.endSec - openBlock.first().startSec
            if (gap >= BLOCK_SPLIT_PAUSE_SEC || blockLen > MAX_BLOCK_SEC) closeBlock()
        }
        openBlock.add(p)
        jobs.trySend(AsrJob.Preview(p))
    }

    /** Joins the open block's phrases (with a short silence between) and queues it. */
    private fun closeBlock() {
        if (openBlock.isEmpty()) return
        val gapSamples = (SpeechEngine.SAMPLE_RATE * JOIN_SILENCE_SEC).toInt()
        val total = openBlock.sumOf { it.samples.size } + gapSamples * (openBlock.size - 1)
        val joined = FloatArray(total)
        var pos = 0
        openBlock.forEachIndexed { i, ph ->
            if (i > 0) pos += gapSamples
            System.arraycopy(ph.samples, 0, joined, pos, ph.samples.size)
            pos += ph.samples.size
        }
        jobs.trySend(AsrJob.Block(joined, openBlock.first().startSec, openBlock.last().endSec))
        openBlock.clear()
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
    suspend fun finish(): Transcript {
        finishing = true
        _level.value = 0f
        audio.close()
        vadJob?.join()
        asrJob?.join()
        cleanJob?.join()
        return Transcript(
            raw = assembler.finish(),
            cleaned = cleanAssembler.finish(),
            cleanupMs = cleanupMs,
            methods = methods.toList(),
            notes = notes.toList(),
        )
    }

    fun cancel() {
        audio.close()
        vadJob?.cancel()
        asrJob?.cancel()
        cleanJob?.cancel()
    }

    private companion object {
        /** Only pauses at least this long split the final text into separately transcribed blocks. */
        const val BLOCK_SPLIT_PAUSE_SEC = 1.5
        /** Longest block; Parakeet handles this comfortably and it bounds stop-to-text time. */
        const val MAX_BLOCK_SEC = 25.0
        /** Silence put between joined phrases so the model still "hears" the pause. */
        const val JOIN_SILENCE_SEC = 0.3
    }

    val modelId: String get() = engine.model.id

    val audioSeconds: Double get() = samplesFed.toDouble() / SpeechEngine.SAMPLE_RATE
}
