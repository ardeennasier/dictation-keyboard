package com.dictation.keyboard.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAssemblerTest {

    @Test
    fun joinsSentences() {
        val a = TranscriptAssembler(2f)
        a.add("Hello there.", 0.0, 1.0)
        a.add("How are you?", 1.5, 2.5)
        assertEquals("Hello there. How are you?", a.finish())
    }

    @Test
    fun longPauseStartsParagraph() {
        val a = TranscriptAssembler(2f)
        a.add("First point", 0.0, 1.0)
        a.add("Second point.", 3.2, 4.0)
        assertEquals("First point.\n\nSecond point.", a.finish())
    }

    @Test
    fun shortPauseMidSentenceRejoinsAndLowercases() {
        val a = TranscriptAssembler(2f)
        a.add("I went to the shop", 0.0, 1.5)
        a.add("And bought some milk.", 2.0, 3.0)
        assertEquals("I went to the shop and bought some milk.", a.finish())
    }

    @Test
    fun keepsNamesCapitalisedWhenRejoining() {
        val a = TranscriptAssembler(2f)
        a.add("I spoke to", 0.0, 1.0)
        a.add("Sarah about it.", 1.4, 2.4)
        assertEquals("I spoke to Sarah about it.", a.finish())
    }

    @Test
    fun keepsIAndAcronyms() {
        assertEquals("I think so", TranscriptAssembler.decapitaliseIfCommon("I think so"))
        assertEquals("NHS staff", TranscriptAssembler.decapitaliseIfCommon("NHS staff"))
        assertEquals("the end", TranscriptAssembler.decapitaliseIfCommon("The end"))
    }

    @Test
    fun mediumPauseAfterUnfinishedPhraseAddsFullStop() {
        val a = TranscriptAssembler(2f)
        a.add("That's all for today", 0.0, 1.0)
        a.add("Thanks.", 2.5, 3.0)
        assertEquals("That's all for today. Thanks.", a.finish())
    }

    @Test
    fun trailingCommaBecomesFullStopAtParagraph() {
        val a = TranscriptAssembler(2f)
        a.add("Milk, eggs, bread,", 0.0, 2.0)
        a.add("Next thing.", 5.0, 6.0)
        assertEquals("Milk, eggs, bread.\n\nNext thing.", a.finish())
    }

    @Test
    fun ignoresEmptyPhrases() {
        val a = TranscriptAssembler(2f)
        a.add("Hello.", 0.0, 1.0)
        a.add("   ", 1.2, 1.4)
        a.add("Bye.", 1.6, 2.0)
        assertEquals("Hello. Bye.", a.finish())
    }

    @Test
    fun emptyTranscriptStaysEmpty() {
        assertEquals("", TranscriptAssembler(2f).finish())
    }
}
