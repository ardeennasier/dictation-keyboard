package com.dictation.keyboard.text

/**
 * Adjusts dictated text so it fits where the cursor is: adds a space if needed, and
 * lower-cases the first word if the cursor is mid-sentence.
 */
object InsertionFormatter {

    private val NO_SPACE_AFTER = setOf('(', '[', '{', '"', '“', '‘', '/', '@', '#', '-', '\n')

    fun format(dictated: String, before: CharSequence?, after: CharSequence?): String {
        if (dictated.isEmpty()) return dictated
        var text = dictated
        val b = before?.toString().orEmpty()

        val needsLeadingSpace = b.isNotEmpty() && !b.last().isWhitespace() && b.last() !in NO_SPACE_AFTER
        val trimmed = b.trimEnd()
        val midSentence = trimmed.isNotEmpty() &&
            !TranscriptAssembler.endsSentence(trimmed) &&
            !b.endsWith("\n") &&
            trimmed.last() !in NO_SPACE_AFTER

        if (midSentence) {
            text = TranscriptAssembler.decapitaliseIfCommon(text)
            // Mid-sentence and the cursor has text right after it: don't force a full stop.
            if (!after.isNullOrEmpty() && after.first().isLetterOrDigit() && text.endsWith(".")) {
                text = text.dropLast(1)
            }
        }
        if (needsLeadingSpace) text = " $text"
        if (!after.isNullOrEmpty() && !after.first().isWhitespace() && after.first() !in ".,;:!?)") {
            text = "$text "
        }
        return text
    }
}
