package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TheIntroDbTest {
    @Test
    fun parsesAllSegmentTypes() {
        val parsed = parseIntroSegments(
            """
            {
              "intro": [{"start_ms": 30000, "end_ms": 90000}],
              "recap": [{"start_ms": 0, "end_ms": 45000}],
              "credits": [{"start_ms": 3300000, "end_ms": null}],
              "preview": [{"start_ms": 3360000, "end_ms": 3390000}]
            }
            """.trimIndent(),
        )
        assertEquals(1, parsed.intro.size)
        assertEquals(30_000L, parsed.intro[0].startMs)
        assertEquals(90_000L, parsed.intro[0].endMs)
        assertEquals(0L, parsed.recap[0].startMs)
        assertEquals(3_300_000L, parsed.credits[0].startMs)
        assertNull(parsed.credits[0].endMs)
        assertEquals(3_390_000L, parsed.preview[0].endMs)
    }

    @Test
    fun nullStartBecomesZeroAndNullEndStaysOpen() {
        val parsed = parseIntroSegments("""{"intro":[{"start_ms":null,"end_ms":null}]}""")
        assertEquals(0L, parsed.intro.single().startMs)
        assertNull(parsed.intro.single().endMs)
    }

    @Test
    fun keepsMultipleSegments() {
        val parsed = parseIntroSegments(
            """{"recap":[{"start_ms":0,"end_ms":1000},{"start_ms":2000,"end_ms":3000}]}""",
        )
        assertEquals(2, parsed.recap.size)
        assertEquals(2_000L, parsed.recap[1].startMs)
    }

    @Test
    fun omittedArraysAreEmpty() {
        val parsed = parseIntroSegments("""{"intro":[{"start_ms":1000,"end_ms":2000}]}""")
        assertTrue(parsed.recap.isEmpty())
        assertTrue(parsed.credits.isEmpty())
        assertTrue(parsed.preview.isEmpty())
        assertTrue(!parsed.isEmpty)
    }

    @Test
    fun invalidJsonIsEmpty() {
        assertTrue(parseIntroSegments("not json").isEmpty)
        assertTrue(parseIntroSegments("{}").isEmpty)
    }

    @Test
    fun cacheKeyDistinguishesMovieAndEpisode() {
        assertEquals("imdb:tt0111161", TheIntroDb.cacheKey("tt0111161", null, null, null))
        assertEquals("tmdb:1396:s1:e2", TheIntroDb.cacheKey(null, 1396, 1, 2))
    }
}
