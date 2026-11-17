package com.dictation.keyboard.text

/**
 * Joins the per-phrase transcripts that come out of VAD + ASR into one block of text.
 *
 * The speech model already punctuates and capitalises each phrase. This class only fixes the
 * seams between phrases:
 *  - a pause of [paragraphPauseSec] or more starts a new paragraph;
 *  - a phrase that was cut mid-sentence by a short pause is joined back on, lower-casing the
 *    next word if it is an ordinary word the model capitalised only because it came first;
 *  - a phrase that did not end a sentence, followed by a longer pause, gets a full stop.
 *
 * Pure Kotlin so it can be unit-tested off-device.
 */
class TranscriptAssembler(private val paragraphPauseSec: Float = 2.0f) {

    private val sb = StringBuilder()
    private var lastEndSec: Double? = null

    val text: String get() = sb.toString()

    fun add(raw: String, startSec: Double, endSec: Double) {
        val phrase = normaliseSpaces(raw)
        if (phrase.isEmpty()) return // noise / cough: don't move the "last speech" marker
        val gap = lastEndSec?.let { startSec - it }
        lastEndSec = endSec

        if (sb.isEmpty()) {
            sb.append(capitaliseFirst(phrase))
            return
        }
        when {
            gap != null && gap >= paragraphPauseSec -> {
                ensureSentenceEnd()
                sb.append("\n\n").append(capitaliseFirst(phrase))
            }
            endsSentence(sb) -> sb.append(' ').append(capitaliseFirst(phrase))
            gap == null || gap < CONTINUATION_MAX_GAP_SEC ->
                sb.append(' ').append(decapitaliseIfCommon(phrase))
            else -> {
                ensureSentenceEnd()
                sb.append(' ').append(capitaliseFirst(phrase))
            }
        }
    }

    /** Final text, with a closing full stop if the last phrase didn't have one. */
    fun finish(): String {
        if (sb.isNotEmpty()) ensureSentenceEnd()
        return sb.toString()
    }

    private fun ensureSentenceEnd() {
        if (endsSentence(sb)) return
        // Swap a trailing comma/semicolon/colon for a full stop rather than doubling up.
        while (sb.isNotEmpty() && sb.last() in ",;:") sb.setLength(sb.length - 1)
        sb.append('.')
    }

    companion object {
        /** A pause shorter than this after an unfinished phrase is treated as mid-sentence. */
        const val CONTINUATION_MAX_GAP_SEC = 1.0

        private val SENTENCE_END = setOf('.', '!', '?', '…')
        private val CLOSERS = setOf('"', '\'', '”', '’', ')')

        fun endsSentence(s: CharSequence): Boolean {
            var i = s.length - 1
            while (i >= 0 && s[i].isWhitespace()) i--
            while (i >= 0 && s[i] in CLOSERS) i--
            return i >= 0 && s[i] in SENTENCE_END
        }

        fun normaliseSpaces(s: String): String = s.trim().replace(Regex("[ \\t]+"), " ")

        fun capitaliseFirst(s: String): String {
            val i = s.indexOfFirst { it.isLetter() }
            if (i < 0 || s[i].isUpperCase()) return s
            return s.substring(0, i) + s[i].uppercaseChar() + s.substring(i + 1)
        }

        /**
         * Lower-cases the first word only if it is a common word that is normally lower case
         * mid-sentence. Names, "I", acronyms etc. are left alone.
         */
        fun decapitaliseIfCommon(s: String): String {
            val first = s.takeWhile { !it.isWhitespace() }
            val bare = first.trimEnd { !it.isLetterOrDigit() && it != '\'' && it != '’' }
            if (bare.isEmpty()) return s
            if (bare.length > 1 && bare.all { it.isUpperCase() || !it.isLetter() }) return s // acronym
            if (bare.lowercase() !in COMMON_WORDS) return s
            val i = s.indexOfFirst { it.isLetter() }
            return s.substring(0, i) + s[i].lowercaseChar() + s.substring(i + 1)
        }

        val COMMON_WORDS = setOf(
            "a", "an", "the", "and", "but", "or", "nor", "so", "yet", "to", "of", "in", "on",
            "at", "for", "with", "from", "by", "about", "into", "over", "after", "before",
            "it", "it's", "its", "that", "that's", "this", "these", "those", "then", "than",
            "we", "we're", "we'll", "you", "you're", "they", "they're", "he", "she", "him",
            "her", "them", "us", "me", "my", "our", "your", "their", "his",
            "is", "was", "are", "were", "be", "been", "being", "have", "has", "had",
            "do", "does", "did", "will", "would", "can", "could", "should", "might", "must",
            "if", "when", "while", "because", "which", "who", "whom", "where", "as", "like",
            "not", "just", "also", "there", "there's", "here", "what", "how", "why",
            "some", "any", "all", "more", "most", "very", "really", "maybe", "probably",
            "even", "still", "only", "again", "too", "up", "down", "out", "off", "through",
            "until", "since", "though", "although", "unless", "whether", "both", "each",
            "every", "no", "yes", "well", "now", "get", "got", "go", "going", "make", "need",
        )
    }
}
