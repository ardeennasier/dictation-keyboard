package com.dictation.keyboard.cleanup

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow

/** What the phone can offer for AI clean-up. */
sealed interface AiStatus {
    data object Available : AiStatus
    data object Downloadable : AiStatus
    data object Downloading : AiStatus
    data object Unavailable : AiStatus
    data class Error(val message: String) : AiStatus
}

/**
 * Gemini Nano through ML Kit's Prompt API. The model runs inside Android's AICore system
 * service, on the phone; nothing goes over the network. Its memory isn't counted against the
 * keyboard either. Only on phones that ship it.
 */
class GeminiNanoBackend {

    private val model: GenerativeModel by lazy { Generation.getClient() }

    suspend fun status(): AiStatus = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> AiStatus.Available
            FeatureStatus.DOWNLOADABLE -> AiStatus.Downloadable
            FeatureStatus.DOWNLOADING -> AiStatus.Downloading
            else -> AiStatus.Unavailable
        }
    } catch (t: Throwable) {
        AiStatus.Error(t.message ?: t.javaClass.simpleName)
    }

    /** Asks AICore to fetch Gemini Nano (AICore does the download, not this app). */
    fun download(): Flow<DownloadStatus> = model.download()

    suspend fun complete(prompt: String, maxOutputTokens: Int): String {
        val response = model.generateContent(
            generateContentRequest(TextPart(prompt)) {
                temperature = 0f
                topK = 1
                candidateCount = 1
                this.maxOutputTokens = maxOutputTokens
            },
        )
        return response.candidates.firstOrNull()?.text.orEmpty()
    }
}
