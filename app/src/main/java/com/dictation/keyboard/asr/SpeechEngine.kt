package com.dictation.keyboard.asr

import com.dictation.keyboard.model.ModelInfo
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

/**
 * Wraps one loaded sherpa-onnx recogniser. Not thread-safe: only call [transcribe] from the
 * single ASR thread (see [EngineHolder]).
 */
class SpeechEngine private constructor(
    val model: ModelInfo,
    private val recognizer: OfflineRecognizer,
) {
    fun transcribe(samples: FloatArray): String {
        // A little silence either side stops the model clipping the first/last word, since
        // the VAD trims phrases tightly.
        val padded = FloatArray(HEAD_PAD + samples.size + TAIL_PAD)
        System.arraycopy(samples, 0, padded, HEAD_PAD, samples.size)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(padded, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val HEAD_PAD = SAMPLE_RATE / 10 // 0.1 s
        private const val TAIL_PAD = SAMPLE_RATE * 3 / 10 // 0.3 s

        fun load(model: ModelInfo, numThreads: Int): SpeechEngine {
            val modelConfig = when (model.type) {
                "nemo_transducer" -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = model.path("encoder"),
                        decoder = model.path("decoder"),
                        joiner = model.path("joiner"),
                    ),
                    tokens = model.path("tokens"),
                    modelType = "nemo_transducer",
                    numThreads = numThreads,
                )
                "moonshine" -> OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        preprocessor = model.path("preprocessor"),
                        encoder = model.path("encoder"),
                        uncachedDecoder = model.path("uncachedDecoder"),
                        cachedDecoder = model.path("cachedDecoder"),
                    ),
                    tokens = model.path("tokens"),
                    numThreads = numThreads,
                )
                else -> error("Unsupported model type ${model.type}")
            }
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            )
            return SpeechEngine(model, OfflineRecognizer(assetManager = null, config = config))
        }
    }
}
