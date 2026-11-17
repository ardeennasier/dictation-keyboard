package com.dictation.keyboard.text

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertionFormatterTest {

    @Test
    fun emptyFieldUnchanged() {
        assertEquals("Hello there.", InsertionFormatter.format("Hello there.", "", ""))
    }

    @Test
    fun addsSpaceAfterSentence() {
        assertEquals(" Next one.", InsertionFormatter.format("Next one.", "Done.", ""))
    }

    @Test
    fun noDoubleSpace() {
        assertEquals("Next one.", InsertionFormatter.format("Next one.", "Done. ", ""))
    }

    @Test
    fun midSentenceLowercasesCommonWord() {
        assertEquals(" the shop.", InsertionFormatter.format("The shop.", "I went to", ""))
    }

    @Test
    fun midSentenceBeforeTextDropsFullStopAndAddsSpace() {
        assertEquals(" the big ", InsertionFormatter.format("The big.", "I saw", "dog"))
    }

    @Test
    fun afterNewlineKeepsCapital() {
        assertEquals("Dear Sam,", InsertionFormatter.format("Dear Sam,", "Hi\n", ""))
    }
}
