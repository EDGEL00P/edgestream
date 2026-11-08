package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AutoCalendarTest {
    private val holidays = """[
        {"date":"2026-01-26","localName":"Australia Day","name":"Australia Day","global":true,"counties":null},
        {"date":"2026-03-02","localName":"Labour Day","name":"Labour Day","global":false,"counties":["AU-WA"]},
        {"date":"2026-05-04","localName":"Labour Day","name":"Labour Day","global":false,"counties":["AU-QLD","AU-NT"]}
    ]"""

    @Test fun nationalPlusOwnStateOnly() {
        val qld = AutoCalendar.parseHolidays(holidays, "AU", "QLD")
        assertEquals(listOf(LocalDate.of(2026, 1, 26), LocalDate.of(2026, 5, 4)), qld.map { it.date })
        assertTrue(qld.all { it.kind == EntryKind.HOLIDAY && it.time == null })
    }

    @Test fun unknownStateGetsNationalOnly() =
        assertEquals(listOf("Australia Day"), AutoCalendar.parseHolidays(holidays, "AU", null).map { it.title })

    @Test fun badHolidayJsonIsEmpty() = assertTrue(AutoCalendar.parseHolidays("<html>", "AU", "QLD").isEmpty())

    private val nfl = Sports.LEAGUES.first { it.id == "nfl" }
    private val schedule = """{"events":[
        {"id":"1","date":"2026-11-01T17:00Z","name":"Old game","shortName":"PIT @ NYJ","competitions":[{}]},
        {"id":"2","date":"2026-11-16T01:20Z","name":"Pittsburgh Steelers at Cincinnati Bengals","shortName":"PIT @ CIN",
         "competitions":[{"broadcasts":[{"media":{"shortName":"NBC"}},{"media":{"shortName":"NBC"}}]}]}
    ]}"""

    @Test fun fixturesFromNowInLocalTimeWithBroadcaster() {
        val from = Sports.parseDate("2026-11-08T00:00Z")
        val games = AutoCalendar.parseSchedule(schedule, nfl, from, ZoneId.of("Australia/Brisbane"))
        assertEquals(1, games.size)
        val g = games.single()
        assertEquals("game:2", g.id)
        assertEquals("PIT @ CIN", g.title)
        assertEquals(LocalDate.of(2026, 11, 16), g.date)
        assertEquals(LocalTime.of(11, 20), g.time)
        assertEquals("NFL  ·  NBC", g.note)
        assertEquals(EntryKind.GAME, g.kind)
    }

    @Test fun teamIdsByAbbreviation() = assertEquals(
        mapOf("ARS" to "359", "LIV" to "364"),
        AutoCalendar.parseTeamIds("""{"sports":[{"leagues":[{"teams":[{"team":{"id":"359","abbreviation":"ARS"}},{"team":{"id":"364","abbreviation":"liv"}}]}]}]}"""),
    )

    @Test fun stateNamesToCodes() {
        assertEquals("QLD", AutoCalendar.subdivisionCode("au", "Queensland"))
        assertEquals("ON", AutoCalendar.subdivisionCode("CA", "Ontario"))
        assertNull(AutoCalendar.subdivisionCode("NZ", "Auckland"))
    }

    @Test fun automaticEntriesAreNeverSaved() {
        val mine = CalendarEvent("u", "Mine", LocalDate.of(2026, 11, 8), null)
        val auto = CalendarEvent("holiday:x", "Hol", LocalDate.of(2026, 11, 9), null, kind = EntryKind.HOLIDAY)
        assertEquals(listOf(mine), CalendarJson.decode(CalendarJson.encode(listOf(mine, auto))))
    }
}
