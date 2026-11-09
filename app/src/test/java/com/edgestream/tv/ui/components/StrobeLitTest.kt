package com.edgestream.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StrobeLitTest {
    @Test fun oneLetterPerTenth() {
        assertEquals(StrobeLit(0, false), strobeLit(0f, 0, 10))
        assertEquals(StrobeLit(3, true), strobeLit(0.35f, 0, 10))
        assertEquals(StrobeLit(10, false), strobeLit(1f, 0, 10))
    }

    @Test fun unmeasuredStepChasesThroughTheWord() {
        assertEquals(StrobeLit(5, false), strobeLit(null, STROBE_CHASE_MS / 2, 10))
        assertEquals(StrobeLit(0, false), strobeLit(null, STROBE_CHASE_MS, 10))
    }
}
