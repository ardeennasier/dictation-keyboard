package com.dictation.keyboard.text

/**
 * The deletion-only core of the clean-up pass. Pure Kotlin, unit-tested.
 *
 * The transcript is split into tokens (words with their punctuation attached, plus paragraph
 * breaks). Clean-up only ever chooses tokens to DELETE; the result is rebuilt from the original
 * tokens, so no word can be added, reworded or reordered. Rebuilding only tidies the seams:
 * a full stop on a deleted word moves to the previous kept word, the next sentence start is
 * capitalised, and stray commas are dropped.
 */
object CleanupText {

    class Token(val text: String, val isParagraph: Boolean = false) {
        /** Lower-case word without punctuation, for matching ("Um," → "um", "It's" → "it's"). */
        val core: String = if (isParagraph) "" else coreOf(text)
        val hasPunctuation: Boolean get() = !isParagraph && text.any { !it.isLetterOrDigit() && it != '\'' && it != '’' }
        override fun toString() = if (isParagraph) "¶" else text
    }

    private const val PARAGRAPH = "\n\n"

    fun coreOf(word: String): String =
        word.lowercase().replace('’', '\'').filter { it.isLetterOrDigit() || it == '\'' }.trim('\'')

    fun tokenize(text: String): List<Token> {
        val out = ArrayList<Token>()
        text.trim().split(Regex("\\n\\s*\\n")).forEachIndexed { p, para ->
            if (p > 0) out.add(Token(PARAGRAPH, isParagraph = true))
            para.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { out.add(Token(it)) }
        }
        return out
    }

    // ---- Light: deterministic rules that are safe without an AI. ----

    /** Always fillers, whatever the context. ("like", "you know" etc. need context: Strong only.) */
    val FILLERS = setOf("um", "umm", "ummm", "uh", "uhh", "uhm", "erm", "er", "err", "hmm", "hm", "mm", "mmm", "ehm")

    /** Words that are often repeated on purpose ("had had", "very very", "no no"). */
    private val INTENTIONAL_REPEATS = setOf(
        "had", "that", "very", "really", "so", "no", "yes", "yeah", "bye", "ha", "haha", "oh", "now",
        "well", "many", "much", "do", "is", "go", "more", "far", "long", "again", "please", "too", "okay", "ok",
    )

    fun lightDeletions(tokens: List<Token>): Set<Int> {
        val del = HashSet<Int>()
        for (i in tokens.indices) {
            val t = tokens[i]
            if (!t.isParagraph && t.core in FILLERS) del.add(i)
        }
        // Immediate repeats: "the the" → "the", "I I" → "I". Only when the first copy has no
        // punctuation, so "No. No." or "Yes, yes" are left alone.
        for (i in 0 until tokens.size - 1) {
            val a = tokens[i]
            val b = nextWord(tokens, i, del) ?: continue
            if (i in del || a.isParagraph || a.hasPunctuation || a.core.isEmpty()) continue
            if (a.core == tokens[b].core && a.core !in INTENTIONAL_REPEATS) del.add(i)
        }
        // Repeated pairs: "of the of the" → "of the", "I was I was" → "I was".
        for (i in 0 until tokens.size - 3) {
            if ((i..i + 3).any { it in del || tokens[it].isParagraph }) continue
            val (a, b, c, d) = listOf(tokens[i], tokens[i + 1], tokens[i + 2], tokens[i + 3])
            if (a.hasPunctuation || b.hasPunctuation) continue
            if (a.core.isNotEmpty() && a.core == c.core && b.core == d.core) {
                del.add(i); del.add(i + 1)
            }
        }
        return del
    }

    private fun nextWord(tokens: List<Token>, i: Int, del: Set<Int>): Int? {
        var j = i + 1
        while (j < tokens.size && j in del) j++
        return if (j < tokens.size && !tokens[j].isParagraph) j else null
    }

    // ---- Strong: map an AI's cleaned text back onto deletions, refusing anything else. ----

    /**
     * Works out which tokens the AI removed. Returns null (reject) if the AI's output contains
     * any word that isn't in the original, in order, i.e. if it added or reworded anything.
     */
    fun deletionsFromOutput(tokens: List<Token>, aiOutput: String): Set<Int>? {
        val outCores = aiOutput.split(Regex("\\s+")).map { coreOf(it) }.filter { it.isNotEmpty() }
        // Match from the END, so when a phrase is repeated ("we should, we should meet") the
        // LAST copy is kept and the earlier, abandoned attempt is the one deleted.
        val kept = HashSet<Int>()
        var j = tokens.size - 1
        for (w in outCores.asReversed()) {
            while (j >= 0 && (tokens[j].isParagraph || tokens[j].core != w)) j--
            if (j < 0) return null // a word that isn't in the original (in order)
            kept.add(j)
            j--
        }
        return tokens.indices.filter { !tokens[it].isParagraph && tokens[it].core.isNotEmpty() && it !in kept }.toSet()
    }

    /** Max share of words the AI may remove before we suspect it's summarising. */
    const val MAX_DELETED_FRACTION = 0.4

    /** Max run of consecutive non-filler words removed in one place (a long false start is ~6–8). */
    const val MAX_DELETED_RUN = 10

    /** Guards against the AI dropping meaningful content. */
    fun passesGuards(tokens: List<Token>, del: Set<Int>): Boolean {
        val words = tokens.indices.filter { !tokens[it].isParagraph && tokens[it].core.isNotEmpty() }
        if (words.isEmpty()) return true
        val removed = words.count { it in del && tokens[it].core !in FILLERS }
        val nonFiller = words.count { tokens[it].core !in FILLERS }
        if (nonFiller > 0 && removed.toDouble() / nonFiller > MAX_DELETED_FRACTION && removed > 3) return false
        var run = 0
        for (i in words) {
            if (i in del && tokens[i].core !in FILLERS) {
                run++
                if (run > MAX_DELETED_RUN) return false
            } else if (i !in del) {
                run = 0
            }
        }
        return true
    }

    // ---- Rebuild ----

    private val SENTENCE_END = setOf('.', '!', '?', '…')
    private val CLOSERS = setOf('"', '\'', '”', '’', ')')

    private fun terminalOf(word: String): Char? {
        var i = word.length - 1
        while (i >= 0 && word[i] in CLOSERS) i--
        return if (i >= 0 && word[i] in SENTENCE_END) word[i] else null
    }

    fun apply(tokens: List<Token>, del: Set<Int>): String {
        val out = StringBuilder()
        var capitaliseNext = true
        var pendingTerminal: Char? = null // full stop etc. carried by deleted word(s)
        var lastKeptEndsSentence = true
        var anyKeptInParagraph = false

        fun settlePending() {
            val term = pendingTerminal ?: return
            pendingTerminal = null
            if (anyKeptInParagraph && !lastKeptEndsSentence) {
                while (out.isNotEmpty() && out.last() in ",;:") out.setLength(out.length - 1)
                out.append(term)
                lastKeptEndsSentence = true
            }
            capitaliseNext = true
        }

        for (i in tokens.indices) {
            val t = tokens[i]
            if (t.isParagraph) {
                settlePending()
                if (anyKeptInParagraph) {
                    if (!lastKeptEndsSentence) {
                        while (out.isNotEmpty() && out.last() in ",;:") out.setLength(out.length - 1)
                        out.append('.')
                    }
                    out.append(PARAGRAPH)
                }
                capitaliseNext = true
                lastKeptEndsSentence = true
                anyKeptInParagraph = false
                continue
            }
            if (i in del) {
                // Only the last deleted word's ending matters: in "Tuesday. No," the break was
                // inside the abandoned part, so nothing carries over.
                pendingTerminal = terminalOf(t.text)
                continue
            }
            settlePending()
            var word = t.text
            if (capitaliseNext) word = TranscriptAssembler.capitaliseFirst(word)
            if (out.isNotEmpty() && !out.endsWith(PARAGRAPH)) out.append(' ')
            out.append(word)
            anyKeptInParagraph = true
            lastKeptEndsSentence = terminalOf(word) != null
            capitaliseNext = lastKeptEndsSentence
        }
        settlePending()
        // A trailing comma left by removing the last word ("…and, um.") becomes a full stop.
        while (out.isNotEmpty() && out.last() in ",;:") {
            out.setLength(out.length - 1)
            if (out.isNotEmpty() && terminalOf(out.toString()) == null) out.append('.')
        }
        return out.toString().trimEnd()
    }

    fun light(text: String): String {
        val tokens = tokenize(text)
        return apply(tokens, lightDeletions(tokens))
    }
}
