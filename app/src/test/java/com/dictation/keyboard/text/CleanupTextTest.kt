package com.dictation.keyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupTextTest {

    private fun strong(raw: String, ai: String): String? {
        val t = CleanupText.tokenize(raw)
        val del = CleanupText.deletionsFromOutput(t, ai) ?: return null
        val all = del + CleanupText.lightDeletions(t)
        if (!CleanupText.passesGuards(t, all)) return null
        return CleanupText.apply(t, all)
    }

    // ---- Light ----

    @Test fun removesFillers() =
        assertEquals("So I think we should go.", CleanupText.light("So um I think we should go."))

    @Test fun removesLeadingFillerAndCapitalises() =
        assertEquals("So I went to the shop.", CleanupText.light("Um, so I went to the the shop."))

    @Test fun fillerSentenceDisappears() =
        assertEquals("I went home. Then I slept.", CleanupText.light("I went home. Uh. Then I slept."))

    @Test fun fullStopMovesBack() =
        assertEquals("It was.", CleanupText.light("It was, um."))

    @Test fun keepsIntentionalRepeats() {
        assertEquals("I had had enough.", CleanupText.light("I had had enough."))
        assertEquals("No. No. Stop.", CleanupText.light("No. No. Stop."))
        assertEquals("It was very very good.", CleanupText.light("It was very very good."))
    }

    @Test fun removesRepeatedPair() =
        assertEquals("I was going home.", CleanupText.light("I was I was going home."))

    @Test fun keepsParagraphs() =
        assertEquals("First point.\n\nSecond point.", CleanupText.light("First um point.\n\nUh second point."))

    @Test fun leavesLikeAlone() =
        assertEquals("I like it.", CleanupText.light("I like it."))

    @Test fun allFillersGivesEmpty() =
        assertEquals("", CleanupText.light("Um, uh."))

    // ---- Strong (AI output mapped back to deletions) ----

    @Test fun selfCorrection() =
        assertEquals("Meet Wednesday.", strong("meet Tuesday, no, Wednesday.", "meet Wednesday."))

    @Test fun selfCorrectionAcrossFullStop() =
        assertEquals("We meet at six.", strong("We meet at five. No, at six.", "We meet at six."))

    @Test fun falseStartKeepsOtherWords() =
        assertEquals("Let's see how this model works.", strong("let's see how the how this model works.", "let's see how this model works."))

    @Test fun keepsOriginalWordingAndPunctuation() =
        // The AI changed "gonna" to "going to" and dropped a comma: wording must come from the original.
        assertNull(strong("I'm gonna go, um, now.", "I'm going to go now."))

    @Test fun rejectsAddedWord() =
        assertNull(strong("meet Tuesday, no, Wednesday.", "meet on Wednesday."))

    @Test fun rejectsReordering() =
        assertNull(strong("meet Tuesday, no, Wednesday.", "Wednesday meet."))

    @Test fun rejectsSummarising() {
        val raw = "I went to the shop this morning and bought milk, eggs, bread and some cheese for the party tonight."
        assertNull(strong(raw, "I went to the shop."))
    }

    @Test fun guardsAllowFillerHeavyText() {
        val t = CleanupText.tokenize("Um, uh, um, so, um, yes.")
        assertTrue(CleanupText.passesGuards(t, CleanupText.lightDeletions(t)))
    }

    @Test fun guardsRejectLongDeletedRun() {
        val t = CleanupText.tokenize("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twentyone twentytwo twentythree twentyfour twentyfive twentysix twentyseven twentyeight end")
        assertFalse(CleanupText.passesGuards(t, (1..11).toSet()))
    }
}
