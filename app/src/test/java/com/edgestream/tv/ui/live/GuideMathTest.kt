package com.edgestream.tv.ui.live

import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideMathTest {
    private val m = 60_000L
    private fun p(start: Long, stop: Long, title: String) =
        ProgrammeEntity(sourceId = 1, epgId = "e", start = start * m, stop = stop * m, title = title, description = null, category = null)

    @Test fun blocksClipToWindowAndFillGaps() {
        val b = guideBlocks(listOf(p(0, 40, "A"), p(60, 200, "B")), from = 30 * m, to = 150 * m)
        assertEquals(listOf(30 * m to 40 * m, 40 * m to 60 * m, 60 * m to 150 * m), b.map { it.start to it.end })
        assertEquals(listOf("A", null, "B"), b.map { it.programme?.title })
    }

    @Test fun emptyGuideIsOneNoListingBlock() {
        val b = guideBlocks(emptyList(), from = 0, to = 120 * m)
        assertEquals(1, b.size)
        assertNull(b.single().programme)
        assertEquals(1f, b.single().fraction(0, 120 * m), 0.0001f)
    }

    @Test fun overlappingProgrammesNeverOverlapBlocks() {
        val b = guideBlocks(listOf(p(0, 60, "A"), p(30, 90, "B")), from = 0, to = 120 * m)
        b.zipWithNext().forEach { (x, y) -> assertTrue("$x then $y", x.end <= y.start) }
    }

    @Test fun timeLeft() {
        assertEquals("45 min left", timeLeftLabel(now = 15 * m, stop = 60 * m))
        assertEquals("1 h 5 min left", timeLeftLabel(now = 0, stop = 65 * m))
        assertEquals("Ending", timeLeftLabel(now = 60 * m, stop = 60 * m))
    }

    @Test fun progressClamped() {
        assertEquals(0.25f, progressOf(now = 15 * m, start = 0, stop = 60 * m), 0.0001f)
        assertEquals(1f, progressOf(now = 90 * m, start = 0, stop = 60 * m), 0.0001f)
        assertEquals(0f, progressOf(now = 0, start = 0, stop = 0), 0.0001f)
    }

    @Test fun chipsHideAllChannelsAndEmptyGroups() {
        val chips = guideChips(listOf(AppViewModel.FAVOURITES to 3, AppViewModel.RECENT to 0, AppViewModel.ALL to 900, "Sports" to 40, "Kids" to 0))
        assertEquals(listOf(AppViewModel.FAVOURITES, "Sports"), chips.map { it.first })
    }
}
