package com.edgestream.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TvFrameTest {

    @Test
    fun fullHd_fivePercent() {
        val f = tvFrame(960f, 540f, 5)
        assertEquals(1f, f.scale, 0.0001f)
        // Dock and Home start 16 dp in, the bar 8 dp down: 48 - 16, 27 - 8.
        assertEquals(32f, f.overXDp, 0.01f)
        assertEquals(19f, f.overYDp, 0.01f)
    }

    @Test
    fun uhd_fivePercent_landsAtFivePercentOnScreen() {
        val f = tvFrame(1920f, 1080f, 5)
        assertEquals(1.25f, f.scale, 0.0001f)
        // 5% of 1920 = 96 px-dp on screen, reached by (margin + 16) * 1.25.
        assertEquals(96f, (f.overXDp + 16f) * f.scale, 0.01f)
        assertEquals(54f, (f.overYDp + 8f) * f.scale, 0.01f)
    }

    @Test
    fun zeroPercent_neverNegative() {
        val f = tvFrame(1920f, 1080f, 0)
        assertEquals(0f, f.overXDp, 0f)
        assertEquals(0f, f.overYDp, 0f)
    }

    @Test
    fun scaleClamped() {
        assertEquals(1f, tvFrame(800f, 450f, 5).scale, 0f)
        assertEquals(1.25f, tvFrame(3000f, 1700f, 5).scale, 0f)
    }
}
