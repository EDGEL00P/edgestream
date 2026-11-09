package com.edgestream.tv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.edgestream.tv.data.PublicApis
import com.edgestream.tv.data.Sports
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.LiveBadge
import com.edgestream.tv.ui.components.RealBadge
import com.edgestream.tv.ui.components.Sheen
import com.edgestream.tv.ui.components.TeamLogo
import com.edgestream.tv.ui.components.WeatherGlyph
import com.edgestream.tv.ui.glass.ClockFont
import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA
import com.edgestream.tv.ui.input.remoteClickable
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Dim get() = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA)

/**
 * The top deck: the clock and a full-bleed hero. Every card the hero turns through — a game, a
 * film, a show, the forecast, what you were watching — uses the same [HeroFrame] lower third, so
 * the deck reads as one broadcast product rather than a set of unrelated widgets.
 */
@Composable
fun AmbientTop(
    vm: AppViewModel, spotlight: BandItem?, tile: Tile, deck: List<BandItem> = emptyList(), modifier: Modifier = Modifier,
    /** OK on the hero: what to do with the card it shows. */
    onOpen: (Any) -> Unit = {},
) {
    val clock by vm.clock.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val weather by vm.weatherDetail.collectAsStateWithLifecycle()
    val cal = remember(clock / 60_000) { Calendar.getInstance().apply { timeInMillis = clock } }
    Column(modifier) {
        val events by vm.calendarEvents.collectAsStateWithLifecycle()
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val upNext = remember(events, day) { com.edgestream.tv.data.upcoming(events, java.time.LocalDate.now(), days = 14, limit = 3) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Spacer(Modifier.weight(1f))
            TimeTile(clock, settings.use24h, weather, settings.fahrenheit, upNext, Modifier)
        }
        val preview by vm.preview.collectAsStateWithLifecycle()
        val forecast = weather?.takeIf { settings.showWeather && it.daily.size >= 2 }
        val cards: List<Any> = if (deck.isEmpty()) listOfNotNull(spotlight) else deck + listOfNotNull(forecast)
        var index by remember(cards.size, deck.isEmpty()) { androidx.compose.runtime.mutableIntStateOf(0) }
        var heroFocused by remember { mutableStateOf(false) }
        LaunchedEffect(cards.size, deck.isEmpty(), heroFocused) {
            if (cards.size > 1 && !heroFocused) while (true) { kotlinx.coroutines.delay(HERO_TURN_MS); index = (index + 1) % cards.size }
        }
        val card = cards.getOrNull(index) ?: spotlight
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onFocusChanged { heroFocused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || cards.size < 2) return@onKeyEvent false
                    when (e.key) {
                        androidx.compose.ui.input.key.Key.DirectionLeft -> { index = (index - 1 + cards.size) % cards.size; true }
                        androidx.compose.ui.input.key.Key.DirectionRight -> { index = (index + 1) % cards.size; true }
                        else -> false
                    }
                }
                .remoteClickable { card?.let(onOpen) },
        ) {
            androidx.compose.animation.Crossfade(card, label = "hero") { c ->
                when (c) {
                    is BandItem.Game -> GameSpot(c.game, settings.use24h, settings.fahrenheit, preview?.name, Modifier.fillMaxSize())
                    is PublicApis.WeatherDetail -> ForecastSpot(c, settings.fahrenheit, Modifier.fillMaxSize())
                    is BandItem.Resume -> ResumeSpot(c.p, Modifier.fillMaxSize())
                    is BandItem.Vod -> NewTitleSpot(c.item, Modifier.fillMaxSize())
                    null -> Unit
                    else -> Spotlight(c as BandItem, tile, settings.use24h, Modifier.fillMaxSize())
                }
            }
            // Focus is an end-to-end accent rule, never a box around the hero.
            if (heroFocused) Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                    .background(Brush.horizontalGradient(listOf(Edge.Accent, Edge.Accent2))),
            )
            Sheen(heroFocused)
        }
        if (cards.size > 1) Row(
            Modifier.fillMaxWidth().padding(end = 40.dp, top = 8.dp),
            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
        ) {
            cards.indices.forEach { i ->
                Box(Modifier.size(width = if (i == index) 20.dp else 6.dp, height = 5.dp).clip(CircleShape)
                    .background(Edge.Text.copy(alpha = if (i == index) 0.9f else 0.35f)))
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/**
 * The one hero treatment over the real full-screen artwork. The shell's [Shade] already lays a
 * continuous gradient across the whole screen, so this frame deliberately adds NO box-bounded
 * shade of its own: a gradient that ends at the hero's edge would cut a hard line through the
 * photograph. Type is bottom-anchored in the deck and scales to the height actually available.
 * Every card type — game, film, show, weather, resume — shares this exact frame.
 */
@Composable
private fun HeroFrame(
    modifier: Modifier,
    eyebrow: @Composable RowScope.() -> Unit,
    title: String,
    subtitle: String?,
    meta: String?,
    chips: List<String> = emptyList(),
    art: @Composable BoxScope.() -> Unit = {},
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        // Scale type to the deck, but never grow it: a long matchup must wrap, not get cut.
        val k = (maxHeight.value / 210f).coerceIn(0.72f, 1f)
        // One detail line rather than two: the title gets the height it needs to wrap to 2 lines.
        val detail = listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, meta?.takeIf { it.isNotBlank() })
            .joinToString("  ·  ").ifBlank { null }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(start = 40.dp, end = 40.dp, bottom = (10 * k).dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((4 * k).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { eyebrow() }
                // One line, shrunk by length rather than clipped: a matchup name must never be cut.
                val shrink = when {
                    title.length > 34 -> 0.70f
                    title.length > 26 -> 0.82f
                    else -> 1f
                }
                Text(
                    title.uppercase(), color = Color.White, fontSize = (30 * k * shrink).sp,
                    lineHeight = (33 * k * shrink).sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
                )
                if (detail != null) Text(
                    detail, color = Color.White.copy(alpha = 0.82f), fontSize = (13 * k).sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (chips.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    chips.take(3).forEach { c ->
                        if (c == "LIVE") LiveBadge(compact = true)
                        // Cap the tag so a long network name ellipsizes instead of being cut by the
                        // score bug's column.
                        else RealBadge(c, modifier = Modifier.widthIn(max = (210 * k).dp), compact = true)
                    }
                }
            }
            Box(Modifier.padding(start = 20.dp), contentAlignment = Alignment.BottomEnd) { art() }
        }
    }
}

/** The accent bar every eyebrow starts with, so all card types share the same lead-in. */
@Composable
private fun EyebrowLead() = Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(50)).background(Edge.Accent))

/** The real league/competition mark: ESPN's own artwork identifies the sport at a glance. */
@Composable
private fun LeagueMark(logo: String?, name: String) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (logo.isNullOrBlank()) Text(
            name.trim().take(2).uppercase().ifBlank { "TV" }, color = Color.White,
            fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1,
        ) else AsyncImage(logo, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun EyebrowText(text: String, color: Color = Edge.Accent) {
    Text(text.uppercase(), color = color, fontSize = 13.sp, fontWeight = FontWeight.Black,
        letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Real poster artwork in a lit frame; optional watch progress on the base edge. */
@Composable
private fun Poster(url: String?, name: String, progress: Float? = null) {
    Box(
        Modifier.height(186.dp).aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        AsyncImage(url, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (progress != null) Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.6f)),
        ) { Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Edge.Accent)) }
    }
}

/** A team's real logo in a ring lit by its own colour — colour as an accent, never a slab. */
@Composable
private fun LogoRing(t: Sports.Team, colour: Color, ringSize: Dp = 52.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(ringSize).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                .border(1.5.dp, colour.copy(alpha = 0.9f), CircleShape).padding((ringSize.value * 0.13f).dp),
            contentAlignment = Alignment.Center,
        ) { TeamLogo(t.logo, t.name, size = ringSize * 0.72f) }
        Box(Modifier.width(26.dp).height(2.dp).clip(RoundedCornerShape(50)).background(colour))
    }
}

/**
 * A compact broadcast score bug: real team logos, scores and the clock in one framed instrument.
 * Two rows only, so it always fits the short deck. Team colour is a hairline, never a slab.
 */
@Composable
private fun ScoreBug(g: Sports.Game, use24h: Boolean, homeChance: Int?, modifier: Modifier) {
    val upcoming = g.state == Sports.State.UPCOMING
    val final = g.state == Sports.State.FINAL
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), g.id, upcoming) {
        while (upcoming) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) }
    }
    val (clock, label) = when {
        upcoming -> kickoffCountdown(g.startMs - now) to startWord(g)
        final -> "FINAL" to ""
        else -> clockParts(g.detail)
    }
    val awayColor = teamColor(g.away)
    val homeColor = teamColor(g.home)
    // An upcoming game has no score yet — ESPN sends "0", which must never read as a result.
    val awayScore = if (upcoming) "–" else g.away.score ?: "–"
    val homeScore = if (upcoming) "–" else g.home.score ?: "–"
    Column(
        modifier.width(318.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6101317))
            .border(1.dp, Color(0xFFD6DAE1).copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogoRing(g.away, awayColor)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(g.away.abbr, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1)
                Text(awayScore, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
            Text(":", color = Color.White.copy(alpha = 0.4f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(g.home.abbr, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1)
                Text(homeScore, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
            LogoRing(g.home, homeColor)
        }
        // The model's win split: a hairline, not a chart. Real odds, no extra height.
        if (homeChance != null) Row(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50))) {
            Box(Modifier.weight((100 - homeChance).coerceAtLeast(1).toFloat()).fillMaxHeight().background(awayColor))
            Box(Modifier.weight(homeChance.coerceAtLeast(1).toFloat()).fillMaxHeight().background(homeColor))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), color = Edge.Accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(clock, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(Format.time(g.startMs, use24h), color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** The game hero: league mark for the sport, team logos in the score bug, venue and weather. */
@Composable
private fun GameSpot(g: Sports.Game, use24h: Boolean, fahrenheit: Boolean, previewing: String?, modifier: Modifier) {
    var insight by remember(g.id) { mutableStateOf<Sports.Insight?>(null) }
    LaunchedEffect(g.id) { insight = runCatching { Sports.insight(g) }.getOrNull() }
    var venueWeather by remember(g.id) { mutableStateOf(VENUE_WEATHER[g.id]) }
    LaunchedEffect(g.id) { if (venueWeather == null) venueWeather = venueWeatherOf(g) }
    val homeChance = insight?.let { it.liveHomeWinChance ?: it.homeWinChance ?: it.marketHomeWinChance }
    val stateLabel = when (g.state) {
        Sports.State.LIVE -> "LIVE NOW"
        Sports.State.FINAL -> "FINAL"
        Sports.State.UPCOMING -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(g.startMs)).uppercase()
    }
    val subtitle = when (g.state) {
        Sports.State.LIVE -> "${g.away.abbr} ${g.away.score ?: "0"} – ${g.home.score ?: "0"} ${g.home.abbr}  ·  ${g.detail}"
        Sports.State.FINAL -> "Final  ${g.away.score ?: ""} – ${g.home.score ?: ""}"
        Sports.State.UPCOMING -> "${startWord(g)} ${SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(g.startMs))} at ${Format.time(g.startMs, use24h)}"
    }
    val place = listOfNotNull(
        previewing?.let { "On $it" },
        g.venue,
        venueWeather?.let { w -> if (w.indoor) "Indoors" else "${temp(w.tempC, fahrenheit)} ${w.description}" },
    ).joinToString("  ·  ")
    HeroFrame(
        modifier,
        eyebrow = {
            EyebrowLead()
            LeagueMark(g.league.logo, g.league.name)
            EyebrowText(g.league.name)
            EyebrowText("·  $stateLabel", Color.White.copy(alpha = 0.7f))
        },
        title = "${g.away.name} vs ${g.home.name}",
        subtitle = subtitle,
        meta = place.ifBlank { null },
        chips = listOfNotNull(if (g.state == Sports.State.LIVE) "LIVE" else null) + g.broadcasts.distinct().take(3),
        art = { ScoreBug(g, use24h, homeChance, Modifier) },
    )
}

/** A title new on the catalogue: the same frame, with its real poster. */
@Composable
private fun NewTitleSpot(v: com.edgestream.tv.data.db.VodEntity, modifier: Modifier) {
    HeroFrame(
        modifier,
        eyebrow = {
            EyebrowLead()
            EyebrowText(if (v.kind == com.edgestream.tv.data.db.VodKind.SERIES) "NEW SHOW" else "NEW MOVIE")
        },
        title = v.name,
        subtitle = v.plot,
        meta = listOfNotNull(v.year, v.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null },
        art = { Poster(v.poster, v.name) },
    )
}

/** Continue watching: the same frame, its poster carrying the watch progress. */
@Composable
private fun ResumeSpot(p: com.edgestream.tv.data.db.ProgressEntity, modifier: Modifier) {
    val left = ((p.durationMs - p.positionMs) / 60_000).coerceAtLeast(0)
    val done = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
    HeroFrame(
        modifier,
        eyebrow = { EyebrowLead(); EyebrowText("CONTINUE WATCHING") },
        title = p.title,
        subtitle = "$left min left  ·  ${(done * 100).toInt()}% watched",
        meta = null,
        art = { Poster(p.poster, p.title, done) },
    )
}

/** The forecast card: the same frame, with a real weather glyph and the coming days. */
@Composable
private fun ForecastSpot(w: PublicApis.WeatherDetail, fahrenheit: Boolean, modifier: Modifier) {
    HeroFrame(
        modifier,
        eyebrow = { EyebrowLead(); EyebrowText("WEATHER  ·  ${w.city}") },
        title = "${temp(w.tempC, fahrenheit)}  ${w.description}",
        subtitle = "High ${temp(w.highC, fahrenheit)}   ·   Low ${temp(w.lowC, fahrenheit)}" +
            (if (w.rainChance > 0) "   ·   ${w.rainChance}% rain" else ""),
        meta = null,
        art = {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                WeatherGlyph(w.code, glyphSize = 82.dp, night = !w.isDay)
                w.daily.drop(1).take(3).forEach { d ->
                    val day = runCatching { java.time.LocalDate.parse(d.date).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) }.getOrDefault(d.date)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(day.uppercase(), color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        WeatherGlyph(d.code, glyphSize = 30.dp)
                        Text("${temp(d.highC, fahrenheit)}  ${temp(d.lowC, fahrenheit)}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        },
    )
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
    is BandItem.Channel -> SpotText("Live TV", item.channel.name, item.now?.let { "Now: $it" }, item.channel.groupName, listOf("Ch ${com.edgestream.tv.data.ChannelNumbers.lcn(item.channel)}"))
    is BandItem.Resume -> item.p.let { p ->
        val left = ((p.durationMs - p.positionMs) / 60_000).coerceAtLeast(0)
        SpotText("Continue watching", p.title, "$left min left", null, emptyList())
    }
    is BandItem.Watch -> item.item.let { w -> SpotText("My List", w.title, "Plays through your add-ons", w.year, emptyList()) }
    is BandItem.Vod -> item.item.let { v ->
        SpotText(if (v.kind == com.edgestream.tv.data.db.VodKind.SERIES) "New show" else "New movie", v.name, v.plot,
            listOfNotNull(v.year, v.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null }, emptyList())
    }
    is BandItem.Alert -> SpotText("Weather warning", item.warning.title, item.warning.detail, item.warning.source, listOf(item.warning.severity))
    is BandItem.Day -> item.day.let { d ->
        val name = runCatching { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.date)!!) }.getOrDefault(d.date)
        SpotText("Forecast", name, PublicApis.Weather("", 0.0, d.code).description, "High ${d.highC.toInt()}° · Low ${d.lowC.toInt()}°", emptyList())
    }
    is BandItem.Event -> item.event.let { e ->
        val day = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(java.sql.Date.valueOf(item.date.toString()))
        SpotText("Calendar", e.title, listOfNotNull(day, e.time?.let { Format.time(java.time.LocalDateTime.of(item.date, it).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), use24h) }).joinToString("  ·  "), null, emptyList())
    }
    is BandItem.App -> SpotText("App", item.app.label, "Press OK to open", null, emptyList())
    null -> SpotText("EdgeStream", tile.label, "Press OK to open", null, emptyList())
}

/** Generic band items use the same frame, with their own real artwork on the right. */
@Composable
private fun Spotlight(item: BandItem?, tile: Tile, use24h: Boolean, modifier: Modifier) {
    val t = spotFor(item, tile, use24h)
    val league = (item as? BandItem.Game)?.game?.league
    HeroFrame(
        modifier,
        eyebrow = {
            EyebrowLead()
            if (league != null) LeagueMark(league.logo, league.name)
            EyebrowText(t.overline)
        },
        title = t.title,
        subtitle = t.subtitle,
        meta = t.meta,
        chips = t.chips,
        art = { SpotArt(item, Modifier) },
    )
}

@Composable
private fun SpotArt(item: BandItem?, modifier: Modifier) {
    when (item) {
        is BandItem.Game -> Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LogoRing(item.game.away, teamColor(item.game.away))
            Text("VS", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            LogoRing(item.game.home, teamColor(item.game.home))
        }
        is BandItem.Channel -> Box(modifier.width(160.dp).height(96.dp), contentAlignment = Alignment.Center) {
            AsyncImage(item.channel.logo, item.channel.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        is BandItem.Resume -> Poster(item.p.poster, item.p.title)
        is BandItem.Watch -> Poster(item.item.poster, item.item.title)
        is BandItem.Vod -> Poster(item.item.poster, item.item.name)
        is BandItem.Day -> WeatherGlyph(item.day.code, glyphSize = 82.dp)
        else -> Unit
    }
}

/**
 * The remote's voice key on screen: OK listens, then runs what was said the way the phone remote
 * does (rules first, Gemini only for a guessed search). Questions open Search, where Ask answers them.
 */
@Composable
internal fun VoiceButton(vm: AppViewModel, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listen = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?: return@rememberLauncherForActivityResult
        val parsed = com.edgestream.tv.remote.VoiceCommand.parse(text) ?: return@rememberLauncherForActivityResult
        scope.launch {
            val key = vm.settings.value.geminiApiKey
            val cmd = if (parsed is com.edgestream.tv.remote.VoiceCommand.Search && parsed.guessed)
                com.edgestream.tv.data.AiAssist.interpret(text, key) ?: parsed else parsed
            if (cmd.isQuestion) vm.openSearch(text)
            else com.edgestream.tv.remote.PhoneLink.post(com.edgestream.tv.remote.PhoneLink.Command.Voice(cmd))
        }
    }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.padding(horizontal = 3.dp).size(44.dp).clip(CircleShape)
            .background(if (focused) Edge.Text else Edge.Text.copy(alpha = 0.08f))
            .onFocusChanged { focused = it.isFocused }
            .remoteClickable {
                runCatching {
                    listen.launch(
                        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                    )
                }.onFailure { vm.openSearch("") }
            },
        contentAlignment = Alignment.Center,
    ) {
        com.edgestream.tv.ui.components.DockIcon("voice", if (focused) Edge.Bg else Edge.Text, focused)
    }
}

private fun temp(c: Double, f: Boolean) = if (c.isNaN()) "–" else if (f) "${(c * 9 / 5 + 32).toInt()}°" else "${c.toInt()}°"

@Composable
private fun TimeTile(clock: Long, use24h: Boolean, w: PublicApis.WeatherDetail?, f: Boolean, upNext: List<Pair<java.time.LocalDate, com.edgestream.tv.data.CalendarEvent>>, modifier: Modifier) {
    val minute = clock / 60_000
    Column(modifier.padding(horizontal = 2.dp, vertical = 2.dp), horizontalAlignment = Alignment.End) {
        Text(remember(minute, use24h) { Format.time(clock, use24h) }, color = Edge.Text, fontSize = 54.sp, lineHeight = 54.sp, fontFamily = ClockFont, fontWeight = FontWeight.Black)
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                remember(minute) { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(clock)) },
                color = Edge.Text.copy(alpha = 0.75f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            if (w != null) {
                Text("   ${temp(w.tempC, f)}  ${w.city}", color = Edge.Text.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                WeatherGlyph(w.code, glyphSize = 14.dp, modifier = Modifier.padding(start = 6.dp), night = !w.isDay)
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

private const val HERO_TURN_MS = 8_000L

private data class VenueWeather(val indoor: Boolean, val tempC: Double, val code: Int, val isDay: Boolean = true) {
    val description: String get() = PublicApis.Weather("", tempC, code).description
}

/** Game id -> weather at the ground, so turning back to a card doesn't fetch it again. */
private val VENUE_WEATHER = java.util.concurrent.ConcurrentHashMap<String, VenueWeather>()

private suspend fun venueWeatherOf(g: Sports.Game): VenueWeather? {
    if (g.indoor == true) return VenueWeather(true, Double.NaN, 0).also { VENUE_WEATHER[g.id] = it }
    val city = g.venueCity ?: return null
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val loc = PublicApis.geocode(listOfNotNull(city, g.venueCountry).joinToString(", ")) ?: PublicApis.geocode(city)
        loc?.let { PublicApis.weather(it) }?.let { VenueWeather(false, it.tempC, it.code, it.isDay) }
    }?.also { VENUE_WEATHER[g.id] = it }
}

/** The word under a countdown, by sport. */
private fun startWord(g: Sports.Game): String = when {
    "basketball" in g.league.path -> "Tip-off"
    "hockey" in g.league.path -> "Puck drop"
    "baseball" in g.league.path -> "First pitch"
    else -> "Kickoff"
}

/** "11:13 - 4th" -> ("11:13", "4TH"); a detail without a clock ("Halftime") stays whole. */
internal fun clockParts(detail: String): Pair<String, String> {
    val parts = detail.split(" - ", limit = 2)
    return if (parts.size == 2) parts[0].trim() to parts[1].trim().uppercase().replace(" QUARTER", "")
    else detail.uppercase().ifBlank { "LIVE" } to ""
}

/** Time to the start: "02:14:05" under a day, "2d 04:10" beyond, "SOON" once it's due. */
internal fun kickoffCountdown(ms: Long): String {
    if (ms <= 0) return "SOON"
    val s = ms / 1000
    val d = s / 86_400
    val h = s % 86_400 / 3_600
    val m = s % 3_600 / 60
    val sec = s % 60
    return if (d > 0) "%dd %02d:%02d".format(d, h, m) else "%02d:%02d:%02d".format(h, m, sec)
}

/** A team's own colour, or the theme's text colour when ESPN has none. */
private fun teamColor(t: Sports.Team): Color =
    t.color?.takeIf { it.length == 6 }?.let { runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull() } ?: Edge.Text.copy(alpha = 0.5f)
