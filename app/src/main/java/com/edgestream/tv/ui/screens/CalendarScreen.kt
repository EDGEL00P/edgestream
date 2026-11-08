package com.edgestream.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgestream.tv.data.CalendarEvent
import com.edgestream.tv.data.EntryKind
import com.edgestream.tv.data.Repeat
import com.edgestream.tv.data.eventsOn
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

private val Dim get() = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA)

/** Alarm choices offered in the editor: minutes before the start (null = no alarm). */
private val ALARM_CHOICES: List<Pair<Int?, String>> = listOf(
    null to "Off", 0 to "At start", 5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour", 1440 to "1 day",
)

fun formatTime(t: LocalTime, use24h: Boolean): String =
    t.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()))

/**
 * The viewer's own calendar: a month you walk with the D-pad (focus picks the day), that day's
 * entries beside it, and an editor for title, date, time, repeat and alarm.
 */
@Composable
fun CalendarScreen(vm: AppViewModel) {
    val events by vm.calendarEvents.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    val todayCell = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { todayCell.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Column(Modifier.weight(1.25f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year,
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = Edge.Text, modifier = Modifier.weight(1f),
                )
                EdgeButton("", { month = month.minusMonths(1) }, icon = Icons.Rounded.ChevronLeft)
                Spacer(Modifier.width(8.dp))
                EdgeButton("Today", { month = YearMonth.from(today); selected = today })
                Spacer(Modifier.width(8.dp))
                EdgeButton("", { month = month.plusMonths(1) }, icon = Icons.Rounded.ChevronRight)
            }
            MonthGrid(month, today, selected, events, todayCell, onSelect = { selected = it }, onOpen = { selected = it; editing = newEvent(it) })
        }
        Spacer(Modifier.width(40.dp))
        DayAgenda(
            selected, eventsOn(events, selected), settings.use24h,
            onAdd = { editing = newEvent(selected) },
            onOpen = { e ->
                when (e.kind) {
                    EntryKind.USER -> editing = e
                    EntryKind.GAME -> vm.toggleGameAlarm(e.id)
                    EntryKind.HOLIDAY -> vm.showNotice("${e.title} — public holiday")
                }
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }

    editing?.let { e ->
        EventEditor(
            e, isNew = events.none { it.id == e.id }, use24h = settings.use24h,
            onSave = { vm.saveEvent(it); editing = null; vm.showNotice("Saved: ${it.title}") },
            onDelete = { vm.deleteEvent(e.id); editing = null; vm.showNotice("Deleted: ${e.title}") },
            onClose = { editing = null },
        )
    }
}

private fun newEvent(day: LocalDate) =
    CalendarEvent(UUID.randomUUID().toString(), "", day, LocalTime.of(9, 0), Repeat.NONE, alarmMinutesBefore = 0)

@Composable
private fun MonthGrid(
    month: YearMonth, today: LocalDate, selected: LocalDate, events: List<CalendarEvent>, todayCell: FocusRequester,
    onSelect: (LocalDate) -> Unit, onOpen: (LocalDate) -> Unit,
) {
    val firstDow = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val first = month.atDay(1)
    val start = first.minusDays(((first.dayOfWeek.value - firstDow.value + 7) % 7).toLong())
    Column(Modifier.padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { i ->
                Text(
                    firstDow.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium, color = Dim, modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
        }
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(7) { d ->
                    val day = start.plusDays((week * 7 + d).toLong())
                    val inMonth = day.month == month.month
                    val busy = events.any { it.occursOn(day) }
                    FocusSurface(
                        onClick = { onOpen(day) },
                        modifier = Modifier.weight(1f).height(58.dp)
                            .then(if (day == today) Modifier.focusRequester(todayCell) else Modifier)
                            .onFocusChanged { if (it.isFocused) onSelect(day) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (day == selected) Edge.Text.copy(alpha = 0.14f) else Edge.Text.copy(alpha = 0.04f),
                        focusedColor = Edge.Accent, focusScale = 1.06f, glow = false,
                    ) { focused ->
                        val ink = when { focused -> Edge.OnAccent; inMonth -> Edge.Text; else -> Edge.Text.copy(alpha = 0.35f) }
                        Text(
                            day.dayOfMonth.toString(), color = ink, fontSize = 18.sp,
                            fontWeight = if (day == today) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp)
                                .then(if (day == today && !focused) Modifier.border(2.dp, Edge.Accent, CircleShape).padding(horizontal = 6.dp) else Modifier),
                        )
                        if (busy) Box(
                            Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 9.dp).size(6.dp)
                                .background(if (focused) Edge.OnAccent else Edge.Accent, CircleShape),
                        )
                    }
                }
            }
        }
        Text(
            "OK on a day adds an entry  ·  holidays and your teams' games fill in by themselves",
            style = MaterialTheme.typography.labelMedium, color = Dim, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun DayAgenda(
    day: LocalDate, entries: List<CalendarEvent>, use24h: Boolean,
    onAdd: () -> Unit, onOpen: (CalendarEvent) -> Unit, modifier: Modifier,
) {
    Column(modifier) {
        Text(
            day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        )
        EdgeButton("Add Event", onAdd, Modifier.padding(top = 14.dp), primary = true, icon = Icons.Rounded.Add)
        if (entries.isEmpty()) {
            Text("Nothing on this day.", style = MaterialTheme.typography.bodyLarge, color = Dim, modifier = Modifier.padding(top = 18.dp))
        } else {
            LazyColumn(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { e ->
                    FocusSurface(
                        onClick = { onOpen(e) }, modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(12.dp), color = Edge.Text.copy(alpha = 0.06f), focusedColor = Edge.Text,
                        focusScale = 1.03f, glow = false,
                    ) { focused ->
                        val ink = if (focused) Edge.Bg else Edge.Text
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                e.time?.let { formatTime(it, use24h) } ?: "All day",
                                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = ink,
                                modifier = Modifier.width(96.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val second = e.note ?: e.repeat.takeIf { it != Repeat.NONE }?.label
                                if (second != null) Text(second, style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = SECONDARY_TEXT_ALPHA), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            when (e.kind) {
                                EntryKind.GAME -> Icon(Icons.Rounded.SportsSoccer, "Followed team", Modifier.padding(end = 8.dp).size(20.dp), tint = ink)
                                EntryKind.HOLIDAY -> Icon(Icons.Rounded.Flag, "Public holiday", Modifier.padding(end = 8.dp).size(20.dp), tint = ink)
                                EntryKind.USER -> Unit
                            }
                            if (e.alarmMinutesBefore != null) Icon(Icons.Rounded.Alarm, "Alarm", Modifier.size(20.dp), tint = ink)
                        }
                    }
                }
            }
        }
    }
}

/** One choice in a row of options; the chosen one is filled with the accent. */
@Composable
private fun Choice(label: String, chosen: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(50),
        color = if (chosen) Edge.Accent else Edge.Text.copy(alpha = 0.08f), focusedColor = Edge.Text,
        focusScale = 1.06f, glow = false,
    ) { focused ->
        Text(
            label, style = MaterialTheme.typography.labelLarge,
            color = when { focused -> Edge.Bg; chosen -> Edge.OnAccent; else -> Edge.Text },
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
        )
    }
}

@Composable
private fun EditorRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Dim, modifier = Modifier.width(90.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun EventEditor(
    initial: CalendarEvent, isNew: Boolean, use24h: Boolean,
    onSave: (CalendarEvent) -> Unit, onDelete: () -> Unit, onClose: () -> Unit,
) {
    var e by remember(initial.id) { mutableStateOf(initial) }
    val titleFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onClose)
        LaunchedEffect(Unit) { delay(80); runCatching { titleFocus.requestFocus() } }
        Column(
            Modifier.width(940.dp).background(Edge.Solid, EdgeShapes.Panel)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(28.dp),
        ) {
            Text(if (isNew) "New entry" else "Edit entry", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Edge.Text)
            Field("Title", e.title, "What's on", Modifier.focusRequester(titleFocus).padding(top = 8.dp)) { e = e.copy(title = it) }
            EditorRow("Date") {
                EdgeButton("", { e = e.copy(date = e.date.minusDays(1)) }, icon = Icons.Rounded.ChevronLeft)
                Text(
                    e.date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(170.dp), maxLines = 1,
                )
                EdgeButton("", { e = e.copy(date = e.date.plusDays(1)) }, icon = Icons.Rounded.ChevronRight)
            }
            EditorRow("Time") {
                Choice("All day", e.time == null) { e = e.copy(time = if (e.time == null) LocalTime.of(9, 0) else null) }
                e.time?.let { t ->
                    EdgeButton("−1 h", { e = e.copy(time = t.minusHours(1)) })
                    Text(formatTime(t, use24h), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(96.dp), maxLines = 1)
                    EdgeButton("+1 h", { e = e.copy(time = t.plusHours(1)) })
                    EdgeButton("−5 m", { e = e.copy(time = t.minusMinutes(5)) })
                    EdgeButton("+5 m", { e = e.copy(time = t.plusMinutes(5)) })
                }
            }
            EditorRow("Repeat") { Repeat.entries.forEach { r -> Choice(r.label, e.repeat == r) { e = e.copy(repeat = r) } } }
            EditorRow("Alarm") { ALARM_CHOICES.forEach { (m, label) -> Choice(label, e.alarmMinutesBefore == m) { e = e.copy(alarmMinutesBefore = m) } } }
            Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EdgeButton("Save", { onSave(e.copy(title = e.title.trim().ifEmpty { "Untitled" })) }, primary = true)
                if (!isNew) EdgeButton("Delete", onDelete)
                EdgeButton("Cancel", onClose)
            }
        }
    }
}
