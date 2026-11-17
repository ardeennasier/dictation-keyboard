package com.dictation.keyboard.cleanup

import android.os.SystemClock
import com.dictation.keyboard.text.CleanupText
import kotlinx.coroutines.withTimeoutOrNull

enum class CleanupStrength { OFF, LIGHT, STRONG }

data class CleanupResult(
    val text: String,
    /** "off", "light", "ai" (AI result accepted) or "ai-rejected" (AI broke the rules; Light used). */
    val method: String,
    val ms: Long,
    val note: String? = null,
)

/**
 * Runs the clean-up for one block of transcript.
 *
 * Light: fixed rules (fillers like um/uh, stutters like "the the").
 * Strong: Light + an on-device AI for false starts, self-corrections and context-dependent
 * fillers ("like", "you know"). The AI's answer is only used to decide which words to DELETE;
 * if it added, changed or reordered anything, or removed too much, it's thrown away.
 */
class Cleaner(private val nano: GeminiNanoBackend) {

    @Volatile private var cachedStatus: AiStatus? = null
    @Volatile private var statusAt = 0L

    suspend fun aiStatus(refresh: Boolean = false): AiStatus {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedStatus
        if (!refresh && cached != null && now - statusAt < 60_000) return cached
        return nano.status().also { cachedStatus = it; statusAt = now }
    }

    /** Asks AICore to fetch Gemini Nano, if the phone supports it. */
    fun downloadAi() = nano.download().also { cachedStatus = null }

    suspend fun clean(raw: String, strength: CleanupStrength): CleanupResult {
        val t0 = SystemClock.elapsedRealtime()
        fun done(text: String, method: String, note: String? = null) =
            CleanupResult(text, method, SystemClock.elapsedRealtime() - t0, note)

        if (strength == CleanupStrength.OFF || raw.isBlank()) return done(raw, "off")
        val tokens = CleanupText.tokenize(raw)
        val light = CleanupText.lightDeletions(tokens)
        val lightText = CleanupText.apply(tokens, light)
        if (strength == CleanupStrength.LIGHT) return done(lightText, "light")

        val status = aiStatus()
        if (status != AiStatus.Available) return done(lightText, "light", "AI not available: $status")

        val words = tokens.count { !it.isParagraph }
        val output: String? = try {
            withTimeoutOrNull(AI_TIMEOUT_MS) { nano.complete(prompt(raw), maxOutputTokens = words * 2 + 32) }
        } catch (t: Throwable) {
            cachedStatus = null // re-check next time
            return done(lightText, "light", "AI error: ${t.message ?: t.javaClass.simpleName}")
        }
        if (output == null) return done(lightText, "light", "AI timed out")

        val aiDel = CleanupText.deletionsFromOutput(tokens, stripWrapper(output))
            ?: return done(lightText, "ai-rejected", "AI changed wording: \"${output.take(120)}\"")
        val all = aiDel + light
        if (!CleanupText.passesGuards(tokens, all)) {
            return done(lightText, "ai-rejected", "AI removed too much: \"${output.take(120)}\"")
        }
        return done(CleanupText.apply(tokens, all), "ai")
    }

    private fun stripWrapper(s: String): String =
        s.trim().removePrefix("Output:").trim().trim('"', '“', '”').trim()

    companion object {
        const val AI_TIMEOUT_MS = 15_000L

        private val INSTRUCTIONS = """
            You tidy up dictated speech by DELETING words. Delete only:
            - filler words used as fillers: um, uh, er, like, you know, sort of, kind of, I mean, basically
            - false starts and repeated words
            - the abandoned part of a self-correction, keeping the corrected version
            Never add, change, reorder or rephrase words. Never fix grammar. Never drop a sentence that carries meaning. Keep the speaker's punctuation.
            Reply with the remaining text only.

            Input: So um I think we should, we should go on Tuesday, no, Wednesday.
            Output: So I think we should go on Wednesday.

            Input: It was like, you know, really good. I like the blue one.
            Output: It was really good. I like the blue one.

            Input: Let's see how the how this model works.
            Output: Let's see how this model works.

            Input: Can you send it to John, sorry, to Mary by Friday?
            Output: Can you send it to Mary by Friday?
        """.trimIndent()

        fun prompt(text: String): String = "$INSTRUCTIONS\n\nInput: $text\nOutput:"
    }
}
