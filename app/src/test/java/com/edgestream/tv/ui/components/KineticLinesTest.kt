package com.edgestream.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KineticLinesTest {
    @Test fun shortWordsShareALine() = assertEquals(listOf("THE BEAR"), kineticLines("The Bear"))

    @Test fun longTitlesStackIntoAtMostThreeLines() {
        val lines = kineticLines("The Lord of the Rings The Return of the King")
        assertTrue(lines.size <= 3)
        assertEquals("THE LORD OF THE RINGS THE RETURN OF THE KING", lines.joinToString(" "))
    }

    @Test fun blankTitleStillHasALine() = assertEquals(listOf(""), kineticLines("  "))
}
