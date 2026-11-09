package com.edgestream.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OnThisDayTest {

    private fun feed(vararg entries: String) = JSONObject("{" + entries.joinToString(",") + "}")

    private fun ev(year: Int, text: String) = """{"year":$year,"text":"$text"}"""

    @Test fun endpointIsZeroPaddedForEveryMonthAndDay() {
        assertEquals("https://api.wikimedia.org/feed/v1/wikipedia/en/onthisday/all/01/09", OnThisDay.endpoint(1, 9))
        assertEquals("https://api.wikimedia.org/feed/v1/wikipedia/en/onthisday/all/11/10", OnThisDay.endpoint(11, 10))
        assertEquals("https://api.wikimedia.org/feed/v1/wikipedia/en/onthisday/all/12/31", OnThisDay.endpoint(12, 31))
    }

    @Test fun parseSpreadsTheCategoriesInsteadOfBeingBuriedByBirths() {
        val json = feed(
            """"births":[${ev(1900, "A person is born")},${ev(1901, "Another person is born")},${ev(1902, "Third person is born")},${ev(1903, "Fourth person is born")}]""",
            """"events":[${ev(1066, "A famous battle is fought")},${ev(1989, "A wall comes down")}]""",
            """"deaths":[${ev(1800, "A famous person dies")}]""",
            """"holidays":[{"text":"A national day of remembrance"}]""",
        )
        val facts = OnThisDay.parse(json)
        assertEquals(OnThisDay.Kind.EVENT, facts.first().kind)
        assertEquals(1066, facts.first().year)
        assertEquals(OnThisDay.Kind.OBSERVANCE, facts.last().kind)
        assertNull(facts.last().year)
        assertEquals(3, facts.count { it.kind == OnThisDay.Kind.BIRTH })
        assertTrue(facts.any { it.kind == OnThisDay.Kind.DEATH })
        assertTrue(facts.size <= 12)
    }

    @Test fun parseOrdersAsATimelineAndDeduplicatesSelectedAgainstEvents() {
        val shared = "The same historic event is recorded twice"
        val json = feed(
            """"selected":[${ev(2020, shared)}]""",
            """"events":[${ev(2020, shared)},${ev(44, "An older event takes place")}]""",
        )
        val facts = OnThisDay.parse(json)
        assertEquals(1, facts.count { it.text == shared })
        assertEquals(listOf(44, 2020), facts.map { it.year })
    }

    @Test fun parseDropsMalformedAndMissingYears() {
        val json = feed(
            """"events":[${ev(1969, "A moon landing happens")},{"text":"No year here"},{"year":1999},"just a string",${ev(1500, "x")}]""",
        )
        val facts = OnThisDay.parse(json)
        assertEquals(1, facts.size)
        assertEquals(1969, facts.single().year)
    }

    @Test fun parseRespectsTheLimitAndToleratesMissingCategories() {
        val events = (1..20).joinToString(",") { ev(1000 + it, "Historic event number $it takes place") }
        assertEquals(5, OnThisDay.parse(feed(""""events":[$events]"""), limit = 5).size)
        assertTrue(OnThisDay.parse(JSONObject("{}")).isEmpty())
    }

    @Test fun bceYearsFormatAndGroupCorrectly() {
        assertEquals("44 BC", OnThisDay.formatYear(-44))
        assertEquals("2020", OnThisDay.formatYear(2020))
        assertEquals("ANNUAL", OnThisDay.formatYear(null))
        assertEquals("Annual observances", OnThisDay.era(null))
        assertEquals("Before the common era", OnThisDay.era(-44))
        assertEquals("11th century", OnThisDay.era(1066))
        assertEquals("12th century", OnThisDay.era(1111))
        assertEquals("21st century", OnThisDay.era(2020))
        assertEquals("1st century", OnThisDay.era(44))
        assertEquals("13th century", OnThisDay.era(1213))
    }

    @Test fun yearsAgoCountsAgainstTheViewersYearAndSkipsAnnualObservances() {
        assertEquals(106, OnThisDay.yearsAgo(1920, LocalDate.of(2026, 11, 10)))
        assertEquals(0, OnThisDay.yearsAgo(2026, LocalDate.of(2026, 1, 1)))
        assertNull(OnThisDay.yearsAgo(null, LocalDate.of(2026, 1, 1)))
    }

    @Test fun cachedDayIsFreshForAMonthThenRefreshes() {
        val now = 1_000_000_000L
        assertFalse(OnThisDay.shouldRefresh(now - 1_000L, now))
        assertFalse(OnThisDay.shouldRefresh(now - 29L * 24 * 3600 * 1000, now))
        assertTrue(OnThisDay.shouldRefresh(now - 31L * 24 * 3600 * 1000, now))
        assertTrue(OnThisDay.shouldRefresh(now + 5_000L, now)) // clock went backwards: refresh
    }
}
