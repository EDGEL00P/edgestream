package com.edgestream.tv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.edgestream.tv.data.PublicApis
import com.edgestream.tv.data.Sports
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.TeamLogo
import com.edgestream.tv.ui.components.WeatherGlyph
import com.edgestream.tv.ui.glass.ClockFont
import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val TileShape = RoundedCornerShape(24.dp)
private val Dim get() = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA)

/**
 * The top deck (Fusion layout): a time/weather/calendar tile, a forecast tile and the Spotlight —
 * a wide glass card describing whatever the remote is on. Each fact has one home: the time lives
 * only in the time tile, the forecast only in the forecast tile; the Spotlight adds detail to the
 * focused card rather than repeating the band.
 */
@Composable
fun AmbientTop(vm: AppViewModel, spotlight: BandItem?, tile: Tile, modifier: Modifier = Modifier) {
    val clock by vm.clock.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val weather by vm.weatherDetail.collectAsStateWithLifecycle()
    val minute = clock / 60_000
    val cal = remember(minute) { Calendar.getInstance().apply { timeInMillis = clock } }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val events by vm.calendarEvents.collectAsStateWithLifecycle()
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val upNext = remember(events, day) { com.edgestream.tv.data.upcoming(events, java.time.LocalDate.now(), days = 14, limit = 3) }
        TimeTile(clock, settings.use24h, weather, settings.fahrenheit, upNext, Modifier.width(196.dp).fillMaxHeight())
        Spotlight(spotlight, tile, settings.use24h, Modifier.weight(1f).fillMaxHeight())
    }
}

private fun temp(c: Double, f: Boolean) = if (c.isNaN()) "–" else if (f) "${(c * 9 / 5 + 32).toInt()}°" else "${c.toInt()}°"

@Composable
private fun TimeTile(clock: Long, use24h: Boolean, w: PublicApis.WeatherDetail?, f: Boolean, upNext: List<Pair<java.time.LocalDate, com.edgestream.tv.data.CalendarEvent>>, modifier: Modifier) {
    val minute = clock / 60_000
    Column(modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(remember(minute, use24h) { Format.time(clock, use24h) }, color = Edge.Text, fontSize = 44.sp, lineHeight = 44.sp, fontFamily = ClockFont)
        Text(
            remember(minute) { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(clock)) },
            color = Edge.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
        // Now — the only place the current temperature appears (the forecast lives in Weather).
        if (w != null) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(w.code, glyphSize = 16.dp)
            Text("${temp(w.tempC, f)}  ${w.city}", color = Edge.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 5.dp))
        }
        UpNext(upNext, use24h)
    }
}

/** The next entries from the viewer's calendar (the month itself lives on the Calendar page). */
@Composable
private fun UpNext(entries: List<Pair<java.time.LocalDate, com.edgestream.tv.data.CalendarEvent>>, use24h: Boolean) {
    val today = java.time.LocalDate.now()
    Text("Up Next", color = Edge.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    if (entries.isEmpty()) Text("Nothing coming up", color = Dim, fontSize = 13.sp, maxLines = 1)
    entries.forEach { (day, e) ->
        val whenText = when (day) {
            today -> e.time?.let { com.edgestream.tv.ui.screens.formatTime(it, use24h) } ?: "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))
        }
        Row(Modifier.padding(top = 3.dp)) {
            Text(whenText, color = Dim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(70.dp))
            Text(e.title, color = Edge.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What the Spotlight says about one band item (or the tile itself when the band is empty). */
private data class SpotText(val overline: String, val title: String, val subtitle: String?, val meta: String?, val chips: List<String>)

private fun spotFor(item: BandItem?, tile: Tile, use24h: Boolean): SpotText = when (item) {
    is BandItem.Game -> item.game.let { g ->
        SpotText(
            overline = g.league.name,
            title = "${g.away.name} vs ${g.home.name}",
            subtitle = when (g.state) {
                Sports.State.LIVE -> "${g.away.abbr} ${g.away.score ?: "0"} – ${g.home.score ?: "0"} ${g.home.abbr}  ·  ${g.detail}"
                Sports.State.UPCOMING -> "Starts ${SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(g.startMs))} ${Format.time(g.startMs, use24h)}"
                Sports.State.FINAL -> "Final  ${g.away.score ?: ""} – ${g.home.score ?: ""}"
            },
            meta = g.venue,
            chips = listOfNotNull(if (g.state == Sports.State.LIVE) "LIVE" else null) + g.broadcasts.take(3),
        )
    }
    is BandItem.Channel -> SpotText("Live TV", item.channel.name, item.now?.let { "Now: $it" }, item.channel.groupName, listOf("Ch ${item.channel.number}"))
    is BandItem.Resume -> item.p.let { p ->
        val left = ((p.durationMs - p.positionMs) / 60_000).coerceAtLeast(0)
        SpotText("Continue watching", p.title, "$left min left", null, emptyList())
    }
    is BandItem.Watch -> item.item.let { w ->
        SpotText("My List", w.title, if (w.vodKey != null) "On your provider" else "Plays through your add-ons", w.year, emptyList())
    }
    is BandItem.Vod -> item.item.let { v ->
        SpotText(if (v.kind == com.edgestream.tv.data.db.VodKind.SERIES) "New show" else "New movie", v.name, v.plot, listOfNotNull(v.year, v.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null }, emptyList())
    }
    is BandItem.Alert -> SpotText("Weather warning", item.warning.title, item.warning.detail, item.warning.source, listOf(item.warning.severity))
    is BandItem.Day -> item.day.let { d ->
        val name = runCatching { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.date)!!) }.getOrDefault(d.date)
        SpotText("Forecast", name, PublicApis.Weather("", 0.0, d.code).description, "High ${d.highC.toInt()}° · Low ${d.lowC.toInt()}°", emptyList())
    }
    null -> SpotText("EdgeStream", tile.label, "Press OK to open", null, emptyList())
}

@Composable
private fun Spotlight(item: BandItem?, tile: Tile, use24h: Boolean, modifier: Modifier) {
    val t = spotFor(item, tile, use24h)
    Row(modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.overline.uppercase(), color = Edge.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, maxLines = 1)
            Text(t.title.uppercase(), color = Edge.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 28.sp)
            t.subtitle?.let { Text(it, color = Edge.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            t.meta?.let { Text(it, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (t.chips.isNotEmpty()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                t.chips.forEach { c ->
                    Text(c, color = if (c == "LIVE") Edge.OnAccent else Edge.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (c == "LIVE") Edge.Live else Edge.Text.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }
        }
        SpotArt(item, Modifier.padding(start = 14.dp).fillMaxHeight())
    }
}

@Composable
private fun SpotArt(item: BandItem?, modifier: Modifier) {
    val ring = Modifier.size(84.dp).clip(CircleShape).background(Edge.Text.copy(alpha = 0.08f))
    when (item) {
        is BandItem.Game -> Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(ring, contentAlignment = Alignment.Center) { TeamLogo(item.game.away.logo, item.game.away.name, size = 60.dp) }
            Box(ring, contentAlignment = Alignment.Center) { TeamLogo(item.game.home.logo, item.game.home.name, size = 60.dp) }
        }
        is BandItem.Channel -> Box(modifier.width(150.dp), contentAlignment = Alignment.Center) {
            AsyncImage(item.channel.logo, item.channel.name, Modifier.fillMaxWidth().height(80.dp), contentScale = ContentScale.Fit)
        }
        is BandItem.Resume -> AsyncImage(item.p.poster, item.p.title, modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
        is BandItem.Watch -> AsyncImage(item.item.poster, item.item.title, modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
        is BandItem.Vod -> AsyncImage(item.item.poster, item.item.name, modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
        is BandItem.Day -> Box(modifier.width(110.dp), contentAlignment = Alignment.Center) { WeatherGlyph(item.day.code, glyphSize = 64.dp) }
        else -> Box(modifier.width(0.dp))
    }
}
