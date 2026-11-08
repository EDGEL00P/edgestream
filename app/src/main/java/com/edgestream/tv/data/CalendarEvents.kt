package com.edgestream.tv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Where an entry came from: the viewer, the public-holiday feed, or a followed team's schedule. */
enum class EntryKind { USER, HOLIDAY, GAME }

enum class Repeat(val label: String) { NONE("Once"), DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Yearly") }

/**
 * One of the viewer's own calendar entries. [time] null means all day. [alarmMinutesBefore] null
 * means no alarm; 0 rings at the start (all-day entries ring at [ALL_DAY_ALARM]).
 */
data class CalendarEvent(
    val id: String,
    val title: String,
    val date: LocalDate,
    val time: LocalTime?,
    val repeat: Repeat = Repeat.NONE,
    val alarmMinutesBefore: Int? = null,
    val kind: EntryKind = EntryKind.USER,
    /** Second line for automatic entries ("NFL · NBC", "Public holiday"). */
    val note: String? = null,
) {
    fun occursOn(day: LocalDate): Boolean = when (repeat) {
        Repeat.NONE -> day == date
        Repeat.DAILY -> !day.isBefore(date)
        Repeat.WEEKLY -> !day.isBefore(date) && day.dayOfWeek == date.dayOfWeek
        // A month without that day (31 Feb) is skipped, like every calendar app does.
        Repeat.MONTHLY -> !day.isBefore(date) && day.dayOfMonth == date.dayOfMonth
        Repeat.YEARLY -> !day.isBefore(date) && day.monthValue == date.monthValue && day.dayOfMonth == date.dayOfMonth
    }
}

/** An alarm that rings at [alarmAt] for the occurrence of [event] on [day]. */
data class DueAlarm(val event: CalendarEvent, val day: LocalDate, val alarmAt: Long)

/** When an all-day entry's alarm counts from. */
val ALL_DAY_ALARM: LocalTime = LocalTime.of(9, 0)

/** How far ahead [nextAlarm] looks (covers a yearly repeat). */
private const val LOOKAHEAD_DAYS = 400L

fun eventsOn(events: List<CalendarEvent>, day: LocalDate): List<CalendarEvent> =
    events.filter { it.occursOn(day) }.sortedWith(compareBy({ it.time != null }, { it.time }, { it.title }))

private fun alarmFor(e: CalendarEvent, day: LocalDate, zone: ZoneId): DueAlarm? {
    val before = e.alarmMinutesBefore ?: return null
    val start = day.atTime(e.time ?: ALL_DAY_ALARM).atZone(zone)
    return DueAlarm(e, day, start.minusMinutes(before.toLong()).toInstant().toEpochMilli())
}

/** Alarms ringing in (from, to], oldest first. An alarm can ring up to a day before its event. */
fun dueAlarms(events: List<CalendarEvent>, from: Long, to: Long, zone: ZoneId): List<DueAlarm> {
    if (to <= from) return emptyList()
    val first = java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
    val last = java.time.Instant.ofEpochMilli(to).atZone(zone).toLocalDate().plusDays(1)
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
        .flatMap { day -> events.filter { it.occursOn(day) }.mapNotNull { alarmFor(it, day, zone) } }
        .filter { it.alarmAt > from && it.alarmAt <= to }
        .sortedBy { it.alarmAt }.toList()
}

/** The first alarm strictly after [now], or null when nothing rings in the next ~13 months. */
fun nextAlarm(events: List<CalendarEvent>, now: Long, zone: ZoneId): DueAlarm? {
    if (events.none { it.alarmMinutesBefore != null }) return null
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return generateSequence(today) { it.plusDays(1) }.take(LOOKAHEAD_DAYS.toInt())
        .flatMap { day -> events.filter { it.occursOn(day) }.mapNotNull { alarmFor(it, day, zone) } }
        .filter { it.alarmAt > now }
        .minByOrNull { it.alarmAt }
}

/** The next [limit] entries from [from] (inclusive) within [days], in date then time order. */
fun upcoming(events: List<CalendarEvent>, from: LocalDate, days: Int, limit: Int): List<Pair<LocalDate, CalendarEvent>> =
    generateSequence(from) { it.plusDays(1) }.take(days)
        .flatMap { day -> eventsOn(events, day).asSequence().map { day to it } }
        .take(limit).toList()

object CalendarJson {
    /** Only the viewer's own entries are saved; automatic ones are fetched fresh. */
    fun encode(events: List<CalendarEvent>): String = JSONArray().apply {
        events.filter { it.kind == EntryKind.USER }.forEach { e ->
            put(JSONObject().put("id", e.id).put("t", e.title).put("d", e.date.toString())
                .put("h", e.time?.toString() ?: JSONObject.NULL).put("r", e.repeat.name)
                .put("a", e.alarmMinutesBefore ?: JSONObject.NULL))
        }
    }.toString()

    fun decode(json: String): List<CalendarEvent> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            CalendarEvent(
                id = o.getString("id"), title = o.getString("t"), date = LocalDate.parse(o.getString("d")),
                time = if (o.isNull("h")) null else LocalTime.parse(o.getString("h")),
                repeat = Repeat.valueOf(o.getString("r")),
                alarmMinutesBefore = if (o.isNull("a")) null else o.getInt("a"),
            )
        }
    } catch (_: JSONException) { emptyList() } catch (_: java.time.format.DateTimeParseException) { emptyList() } catch (_: IllegalArgumentException) { emptyList() }
}

/** The viewer's calendar, kept in one small JSON file. */
class CalendarStore(private val file: File) {
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<CalendarEvent>> = _all

    /** Adds [e], or replaces the entry with the same id. */
    fun put(e: CalendarEvent) = save(_all.value.filterNot { it.id == e.id } + e)

    fun delete(id: String) = save(_all.value.filterNot { it.id == id })

    private fun save(list: List<CalendarEvent>) {
        _all.value = list
        try { file.writeText(CalendarJson.encode(list)) }
        catch (e: IOException) { android.util.Log.e("EdgeCalendar", "could not save the calendar", e) }
    }

    private fun load(): List<CalendarEvent> = if (file.exists()) CalendarJson.decode(file.readText()) else emptyList()
}
