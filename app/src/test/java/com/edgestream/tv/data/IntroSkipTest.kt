package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroSkipTest {
    private val hour = 60 * 60_000L

    @Test fun introSegmentSkipsToItsEnd() {
        val s = IntroSegments(intro = listOf(IntroSegment(314_528, 331_101)))
        assertEquals(listOf("Skip intro" to 331_101L), IntroSkip.pills(320_000, hour, s, null, false))
        assertTrue(IntroSkip.pills(331_101, hour, s, null, false).isEmpty())
    }

    @Test fun ownMarkIsOfferedOnlyInTheRunUp() {
        val mark = 10 * 60_000L
        // A long cold open: the old rule showed "Skip intro" from the first frame.
        assertTrue(IntroSkip.pills(0, hour, null, mark, false).isEmpty())
        assertEquals(listOf("Skip intro" to mark), IntroSkip.pills(mark - 60_000, hour, null, mark, false))
        assertTrue(IntroSkip.pills(mark, hour, null, mark, false).isEmpty())
    }

    @Test fun upNextStandsInForSkipCredits() {
        val s = IntroSegments(credits = listOf(IntroSegment(hour - 5 * 60_000, null)))
        val at = hour - 4 * 60_000
        assertEquals(listOf("Skip credits" to hour - 400), IntroSkip.pills(at, hour, s, null, false))
        assertTrue(IntroSkip.pills(at, hour, s, null, true).isEmpty())
        // No credits data: the last-two-minutes guess also yields to Up Next.
        assertTrue(IntroSkip.pills(hour - 60_000, hour, null, null, true).isEmpty())
    }
}
