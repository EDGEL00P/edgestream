package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarEventsTest {
    private val zone = ZoneId.of("Australia/Brisbane")
    private fun ev(
        date: LocalDate, time: LocalTime? = LocalTime.of(9, 0), repeat: Repeat = Repeat.NONE,
        alarm: Int? = null, title: String = "x", id: String = title,
    ) = CalendarEvent(id, title, date, time, repeat, alarm)
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun oneOffOccursOnlyOnItsDay() {
        val e = ev(LocalDate.of(2026, 11, 8))
        assertTrue(e.occursOn(LocalDate.of(2026, 11, 8)))
        assertFalse(e.occursOn(LocalDate.of(2026, 11, 9)))
    }

    @Test fun repeatsNeverStartBeforeTheFirstDate() {
        val e = ev(LocalDate.of(2026, 11, 8), repeat = Repeat.DAILY)
        assertFalse(e.occursOn(LocalDate.of(2026, 11, 7)))
        assertTrue(e.occursOn(LocalDate.of(2027, 3, 1)))
    }

    @Test fun weeklyKeepsTheWeekday() {
        val sunday = ev(LocalDate.of(2026, 11, 8), repeat = Repeat.WEEKLY)
        assertTrue(sunday.occursOn(LocalDate.of(2026, 11, 15)))
        assertFalse(sunday.occursOn(LocalDate.of(2026, 11, 16)))
    }

    @Test fun monthlySkipsMonthsWithoutThatDay() {
        val e = ev(LocalDate.of(2026, 1, 31), repeat = Repeat.MONTHLY)
        assertTrue(e.occursOn(LocalDate.of(2026, 3, 31)))
        assertFalse("no 31 February", e.occursOn(LocalDate.of(2026, 2, 28)))
    }

    @Test fun yearlyLeapDayOnlyInLeapYears() {
        val e = ev(LocalDate.of(2024, 2, 29), repeat = Repeat.YEARLY)
        assertFalse(e.occursOn(LocalDate.of(2025, 2, 28)))
        assertTrue(e.occursOn(LocalDate.of(2028, 2, 29)))
    }

    @Test fun dayListsAllDayFirstThenByTime() {
        val d = LocalDate.of(2026, 11, 8)
        val list = listOf(ev(d, LocalTime.of(18, 0), title = "dinner"), ev(d, null, title = "holiday"), ev(d, LocalTime.of(7, 30), title = "gym"))
        assertEquals(listOf("holiday", "gym", "dinner"), eventsOn(list, d).map { it.title })
    }

    @Test fun alarmFiresInsideTheWindowOnly() {
        val e = ev(LocalDate.of(2026, 11, 8), LocalTime.of(15, 0), alarm = 10)
        val due = dueAlarms(listOf(e), from = at(2026, 11, 8, 14, 45), to = at(2026, 11, 8, 14, 50), zone = zone)
        assertEquals(listOf(at(2026, 11, 8, 14, 50)), due.map { it.alarmAt })
        assertTrue(dueAlarms(listOf(e), from = at(2026, 11, 8, 14, 50), to = at(2026, 11, 8, 14, 55), zone = zone).isEmpty())
    }

    @Test fun noAlarmWhenOff() =
        assertTrue(dueAlarms(listOf(ev(LocalDate.of(2026, 11, 8))), at(2026, 11, 8, 0, 0), at(2026, 11, 9, 0, 0), zone).isEmpty())

    @Test fun allDayAlarmCountsFromNineAm() {
        val e = ev(LocalDate.of(2026, 11, 8), time = null, alarm = 0)
        assertEquals(at(2026, 11, 8, 9, 0), nextAlarm(listOf(e), at(2026, 11, 8, 0, 0), zone)?.alarmAt)
    }

    @Test fun nextAlarmFindsTheNextRepeat() {
        val e = ev(LocalDate.of(2026, 11, 2), LocalTime.of(6, 30), Repeat.WEEKLY, alarm = 0) // Mondays
        assertEquals(at(2026, 11, 9, 6, 30), nextAlarm(listOf(e), at(2026, 11, 8, 12, 0), zone)?.alarmAt)
    }

    @Test fun nextAlarmNullWhenAllPast() =
        assertNull(nextAlarm(listOf(ev(LocalDate.of(2020, 1, 1), alarm = 0)), at(2026, 11, 8, 0, 0), zone))

    @Test fun storeRoundTripsJson() {
        val list = listOf(
            ev(LocalDate.of(2026, 11, 8), LocalTime.of(7, 5), Repeat.WEEKLY, 15, "Gym, \"early\""),
            ev(LocalDate.of(2026, 12, 25), null, Repeat.YEARLY, null, "Christmas"),
        )
        assertEquals(list, CalendarJson.decode(CalendarJson.encode(list)))
    }

    @Test fun corruptJsonIsEmpty() = assertEquals(emptyList<CalendarEvent>(), CalendarJson.decode("{oops"))

    @Test fun upcomingWalksDaysInOrderAndStopsAtLimit() {
        val d = LocalDate.of(2026, 11, 8)
        val list = listOf(ev(d.plusDays(2), title = "later"), ev(d, LocalTime.of(20, 0), title = "tonight"), ev(d.plusDays(1), repeat = Repeat.DAILY, title = "daily"))
        assertEquals(listOf("tonight", "daily", "daily"), upcoming(list, d, days = 14, limit = 3).map { it.second.title })
    }
}
