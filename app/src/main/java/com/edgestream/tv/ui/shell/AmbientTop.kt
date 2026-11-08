package com.edgestream.tv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
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
    val games by vm.sportsGames.collectAsStateWithLifecycle()
    val nfl by vm.nflBoard.collectAsStateWithLifecycle()
    val briefing = remember(games, nfl, minute) {
        com.edgestream.tv.data.Briefing.compose(
            games, nfl?.picks.orEmpty(),
            timeOf = { Format.time(it, settings.use24h) }, now = clock,
        )
    }
    Column(modifier) {
        // Screenshot top row: clock left, briefing centre, ASK right.
        val events by vm.calendarEvents.collectAsStateWithLifecycle()
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val upNext = remember(events, day) { com.edgestream.tv.data.upcoming(events, java.time.LocalDate.now(), days = 14, limit = 3) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            TimeTile(clock, settings.use24h, weather, settings.fahrenheit, upNext, Modifier.width(240.dp))
            if (briefing.isNotEmpty()) BriefingLine(briefing, Modifier.weight(1f).padding(start = 18.dp, top = 8.dp))
            AskButton(vm, Modifier.padding(start = 12.dp, top = 6.dp))
        }
        val preview by vm.preview.collectAsStateWithLifecycle()
        if (spotlight is BandItem.Game) GameSpot(spotlight.game, settings.use24h, preview?.name, Modifier.weight(1f).fillMaxWidth())
        else Spotlight(spotlight, tile, settings.use24h, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun AskButton(vm: AppViewModel, modifier: Modifier) {
    com.edgestream.tv.ui.components.FocusSurface(
        onClick = { vm.openSearch("") }, modifier = modifier, focusScale = 1.04f, glow = false,
    ) {
        Text("/  Ask", color = Edge.Text.copy(alpha = 0.55f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** The briefing: one live sentence, key words in the theme accent, behind an accent edge. */
@Composable
private fun BriefingLine(parts: List<com.edgestream.tv.data.Briefing.Part>, modifier: Modifier) {
    val accent = Edge.Accent
    val text = androidx.compose.ui.text.buildAnnotatedString {
        parts.forEach { p ->
            if (p.key) withStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(p.text) }
            else append(p.text)
        }
    }
    Row(modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Text("BRIEFING", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Text(text, color = Edge.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun temp(c: Double, f: Boolean) = if (c.isNaN()) "–" else if (f) "${(c * 9 / 5 + 32).toInt()}°" else "${c.toInt()}°"

@Composable
private fun TimeTile(clock: Long, use24h: Boolean, w: PublicApis.WeatherDetail?, f: Boolean, upNext: List<Pair<java.time.LocalDate, com.edgestream.tv.data.CalendarEvent>>, modifier: Modifier) {
    val minute = clock / 60_000
    Column(modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
        Text(remember(minute, use24h) { Format.time(clock, use24h) }, color = Edge.Text, fontSize = 54.sp, lineHeight = 54.sp, fontFamily = ClockFont, fontWeight = FontWeight.Black)
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                remember(minute) { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(clock)) },
                color = Edge.Text.copy(alpha = 0.75f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            // Now — the only place the current temperature appears (the forecast lives in Weather).
            if (w != null) {
                Text("   ${temp(w.tempC, f)}  ${w.city}", color = Edge.Text.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                WeatherGlyph(w.code, glyphSize = 14.dp, modifier = Modifier.padding(start = 6.dp))
            }
        }
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

/**
 * Home becomes the focused game: its name in scoreboard type, the score large, and ESPN's own
 * win probability as a ribbon in the two teams' colours (live model in play, matchup predictor
 * before kick-off, the market's no-vig price when ESPN publishes neither).
 */
@Composable
private fun GameSpot(g: Sports.Game, use24h: Boolean, previewing: String?, modifier: Modifier) {
    var insight by remember(g.id) { mutableStateOf<Sports.Insight?>(null) }
    LaunchedEffect(g.id) { insight = runCatching { Sports.insight(g) }.getOrNull() }
    val homeChance = insight?.let { it.liveHomeWinChance ?: it.homeWinChance ?: it.marketHomeWinChance }
    val latestPlay = insight?.plays?.lastOrNull()
    Column(modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Eyebrow: red dot · LEAGUE · LIVE · VENUE
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Edge.Live))
            Spacer(Modifier.width(10.dp))
            Text(
                listOfNotNull(g.league.name, if (g.state == Sports.State.LIVE) "LIVE" else null, previewing?.let { "ON $it" } ?: g.venue)
                    .joinToString("  ·  ").uppercase(),
                color = Edge.Live, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 2.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${g.away.name.uppercase()} VS ${g.home.name.uppercase()}",
            color = Edge.Text, fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        // Score line: AWAY score · ABBRs + clock · HOME score (screenshot: 24 CIN·ATL Q3·6:18 17)
        if (g.state == Sports.State.UPCOMING) {
            Text("${SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(g.startMs))}  ${Format.time(g.startMs, use24h)}",
                color = Edge.Text, fontSize = 44.sp, fontWeight = FontWeight.Black)
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(g.away.score ?: "0", color = Edge.Text, fontSize = 48.sp, fontWeight = FontWeight.Black)
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text("${g.away.abbr} · ${g.home.abbr}", color = Edge.Text.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(if (g.state == Sports.State.FINAL) "FINAL" else g.detail.ifBlank { "LIVE" }, color = Edge.Text.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(g.home.score ?: "0", color = Edge.Text.copy(alpha = 0.45f), fontSize = 48.sp, fontWeight = FontWeight.Black)
            }
        }
        if (homeChance != null) {
            val away = teamColor(g.away)
            val home = teamColor(g.home)
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))) {
                Box(Modifier.weight((100 - homeChance).coerceAtLeast(1).toFloat()).fillMaxHeight().background(away))
                Box(Modifier.weight(homeChance.coerceAtLeast(1).toFloat()).fillMaxHeight().background(home.copy(alpha = 0.75f)))
            }
            Row(Modifier.fillMaxWidth()) {
                Text("${g.away.abbr} ${100 - homeChance}% to win", color = Edge.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${g.home.abbr} $homeChance%", color = Edge.Text.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            // Drive/field chart (NFL-style, from latest play text) — only when ESPN published plays.
            latestPlay?.let { DriveChart(g, it, Modifier.fillMaxWidth()) }
        } else if (latestPlay != null) {
            DriveChart(g, latestPlay, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DriveChart(g: Sports.Game, play: Sports.Play, modifier: Modifier) {
    // Parse "3rd & 4 at ATL 32" style situation from latest play text; fall back to recent text.
    val situation = play.text.substringBefore(" - ").substringBefore(". ").trim().take(60)
    val yard = Regex("at\\s+(?:the\\s+)?(\\d{1,2})\\s*$").find(situation)?.groupValues?.get(1)?.toIntOrNull() ?: 30
    val ownSide = !situation.contains(g.home.abbr, true)
    val frac = if (ownSide) (100 - yard) / 100f else yard / 100f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(situation.uppercase(), color = Edge.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp)) {
            // Yard lines
            Row(Modifier.fillMaxWidth().height(52.dp)) {
                repeat(10) { i ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(Edge.Text.copy(alpha = 0.22f)))
                        if (i > 0 && i < 10) {
                            val label = when (i) { 1 -> "10"; 2 -> "20"; 3 -> "30"; 4 -> "40"; 5 -> "50"; 6 -> "40"; 7 -> "30"; 8 -> "20"; else -> "10" }
                            Text(label, color = Edge.Text.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
                        }
                    }
                }
            }
            // End zones
            Box(Modifier.align(Alignment.CenterStart).width(18.dp).height(52.dp).background(Color(0xFFE6512A).copy(alpha = 0.75f)))
            Box(Modifier.align(Alignment.CenterEnd).width(18.dp).height(52.dp).background(Color(0xFF7A1030).copy(alpha = 0.85f)))
            // Ball marker + line
            val cx = (frac.coerceIn(0.02f, 0.98f))
            Box(Modifier.fillMaxWidth(cx).height(52.dp), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(Edge.Live))
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF8A5A2B)).padding(2.dp)) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.White))
                }
            }
        }
    }
}

/** A team's own colour, or the theme's text colour when ESPN has none. */
private fun teamColor(t: Sports.Team): Color =
    t.color?.takeIf { it.length == 6 }?.let { runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull() } ?: Edge.Text.copy(alpha = 0.5f)

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
