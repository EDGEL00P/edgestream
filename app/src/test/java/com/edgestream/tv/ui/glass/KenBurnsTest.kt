package com.edgestream.tv.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KenBurnsTest {

    @Test fun zoomsInSlowlyOverTheShot() {
        assertEquals(1.04f, kenBurns(0f, 0).scale, 0.0001f)
        assertEquals(1.12f, kenBurns(1f, 0).scale, 0.0001f)
    }

    @Test fun panDirectionAlternatesPerPhoto() {
        val a = kenBurns(1f, 0).dx
        val b = kenBurns(1f, 1).dx
        assertTrue("$a vs $b", a * b < 0)
    }

    @Test fun panStaysInsideTheZoomMargin() {
        for (i in 0..3) for (p in listOf(0f, 0.5f, 1f)) {
            val k = kenBurns(p, i)
            // A shift of dx (fraction of width) is hidden only if the zoom adds at least 2*|dx|.
            assertTrue("photo edge would show: $k", (k.scale - 1f) >= 2 * kotlin.math.abs(k.dx) && (k.scale - 1f) >= 2 * kotlin.math.abs(k.dy))
        }
    }

    @Test fun stillWhenMotionOff() = assertEquals(KenBurns.STILL, kenBurns(0.7f, 2, motion = false))
}
