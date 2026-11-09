package com.edgestream.tv.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreBugTest {
    @Test fun clockSplitsIntoTimeAndPeriod() {
        assertEquals("11:13" to "4TH", clockParts("11:13 - 4th"))
        assertEquals("2:00" to "2ND", clockParts("2:00 - 2nd Quarter"))
        assertEquals("HALFTIME" to "", clockParts("Halftime"))
    }

    @Test fun countdownToKickoff() {
        assertEquals("02:14:05", kickoffCountdown((2 * 3600 + 14 * 60 + 5) * 1000L))
        assertEquals("2d 04:10", kickoffCountdown(((2 * 24 + 4) * 3600 + 10 * 60) * 1000L))
        assertEquals("SOON", kickoffCountdown(0))
    }
}
