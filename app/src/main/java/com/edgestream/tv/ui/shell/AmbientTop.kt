package com.edgestream.tv.ui.shell

import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
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
fun AmbientTop(vm: AppViewModel, spotlight: BandItem?, tile: Tile, deck: List<BandItem> = emptyList(), modifier: Modifier = Modifier) {
    val clock by vm.clock.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val weather by vm.weatherDetail.collectAsStateWithLifecycle()
    val minute = clock / 60_000
    val cal = remember(minute) { Calendar.getInstance().apply { timeInMillis = clock } }
    Column(modifier) {
        // Top right, tvOS style: the clock with voice beside it. The game hero owns the left.
        val events by vm.calendarEvents.collectAsStateWithLifecycle()
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val upNext = remember(events, day) { com.edgestream.tv.data.upcoming(events, java.time.LocalDate.now(), days = 14, limit = 3) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Spacer(Modifier.weight(1f))
            TimeTile(clock, settings.use24h, weather, settings.fahrenheit, upNext, Modifier)
            VoiceButton(vm, Modifier.padding(start = 14.dp, top = 8.dp))
        }
        val preview by vm.preview.collectAsStateWithLifecycle()
        // At rest the hero turns through the best of Home (top games, the forecast, a warning,
        // what you were watching); browsing a row, it follows focus instead.
        val forecast = weather?.takeIf { settings.showWeather && it.daily.size >= 2 }
        val cards: List<Any> = if (deck.isEmpty()) listOfNotNull(spotlight) else deck + listOfNotNull(forecast)
        var index by remember(cards.size, deck.isEmpty()) { androidx.compose.runtime.mutableIntStateOf(0) }
        LaunchedEffect(cards.size, deck.isEmpty()) {
            if (cards.size > 1) while (true) { kotlinx.coroutines.delay(HERO_TURN_MS); index = (index + 1) % cards.size }
        }
        val card = cards.getOrNull(index) ?: spotlight
        Box(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.animation.Crossfade(card, label = "hero") { c ->
                when (c) {
                    is BandItem.Game -> GameSpot(c.game, settings.use24h, settings.fahrenheit, preview?.name, Modifier.fillMaxSize())
                    is PublicApis.WeatherDetail -> ForecastSpot(c, settings.fahrenheit, Modifier.fillMaxSize())
                    is BandItem.Resume -> ResumeSpot(c.p, Modifier.fillMaxSize())
                    // Nothing loaded yet: an empty hero, never a placeholder card.
                    null -> Unit
                    else -> Spotlight(c as BandItem, tile, settings.use24h, Modifier.fillMaxSize())
                }
            }
        }
        if (cards.size > 1) Row(Modifier.padding(start = 8.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cards.indices.forEach { i ->
                Box(Modifier.size(width = if (i == index) 18.dp else 6.dp, height = 6.dp).clip(CircleShape)
                    .background(Edge.Text.copy(alpha = if (i == index) 0.9f else 0.35f)))
            }
        }
    }
}

/**
 * The remote's voice key on screen: OK listens, then runs what was said the way the phone remote
 * does (rules first, Gemini only for a guessed search). Questions open Search, where Ask answers them.
 */
@Composable
private fun VoiceButton(vm: AppViewModel, modifier: Modifier) {
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
    com.edgestream.tv.ui.components.FocusSurface(
        onClick = {
            runCatching {
                listen.launch(
                    android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                )
            }.onFailure { vm.openSearch("") } // no speech service: typed search instead
        },
        modifier = modifier.onFocusChanged { focused = it.hasFocus }, shape = CircleShape, focusScale = 1.08f, glow = false,
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Rounded.Mic, "Voice",
            tint = Edge.Text.copy(alpha = if (focused) 1f else 0.7f), modifier = Modifier.padding(10.dp).size(24.dp),
        )
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
 * Home becomes the game: each side washed in that team's colour, both logos beside their scores,
 * the venue and its weather under the name, and ESPN's win probability (live model in play,
 * matchup predictor before kick-off, the market's no-vig price otherwise) beside the score.
 */
@Composable
private fun GameSpot(g: Sports.Game, use24h: Boolean, fahrenheit: Boolean, previewing: String?, modifier: Modifier) {
    var insight by remember(g.id) { mutableStateOf<Sports.Insight?>(null) }
    LaunchedEffect(g.id) { insight = runCatching { Sports.insight(g) }.getOrNull() }
    var venueWeather by remember(g.id) { mutableStateOf(VENUE_WEATHER[g.id]) }
    LaunchedEffect(g.id) { if (venueWeather == null) venueWeather = venueWeatherOf(g) }
    val homeChance = insight?.let { it.liveHomeWinChance ?: it.homeWinChance ?: it.marketHomeWinChance }
    val latestPlay = insight?.plays?.lastOrNull()
    val away = teamColor(g.away)
    val home = teamColor(g.home)
    // The hero gets whatever height the band leaves; type scales to it and the drive chart only
    // appears when there is room for it.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val big = (maxHeight.value / 170f).coerceIn(0.7f, 1f)
        val tall = maxHeight >= 240.dp
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(
            0f to away.copy(alpha = 0.42f), 0.4f to Color.Transparent, 0.6f to Color.Transparent, 1f to home.copy(alpha = 0.42f),
        )))
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (g.state == Sports.State.LIVE) { LivePill(); Spacer(Modifier.width(14.dp)) }
                Text(
                    "${g.away.name.uppercase()} VS ${g.home.name.uppercase()}",
                    color = Edge.Text, fontSize = 50.sp * big, lineHeight = 54.sp * big, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Where it's played and what the weather is doing there; the channel while it previews.
            val place = listOfNotNull(
                previewing?.let { "On $it" },
                g.venue,
                venueWeather?.let { w -> if (w.indoor) "Indoors" else "${temp(w.tempC, fahrenheit)} ${w.description}" },
            ).joinToString("  ·  ")
            if (place.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(place, color = Edge.Text.copy(alpha = 0.8f), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                venueWeather?.takeIf { !it.indoor }?.let { WeatherGlyph(it.code, glyphSize = 18.dp, modifier = Modifier.padding(start = 8.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(g.away.logo, g.away.name, size = 50.dp * big)
                if (g.state == Sports.State.UPCOMING) {
                    Text("${SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(g.startMs))}  ${Format.time(g.startMs, use24h)}",
                        color = Edge.Text, fontSize = 34.sp * big, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp))
                } else {
                    Text(g.away.score ?: "0", color = Edge.Text, fontSize = 46.sp * big, lineHeight = 48.sp * big, fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(start = 14.dp))
                    Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${g.away.abbr} · ${g.home.abbr}", color = Edge.Text.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(if (g.state == Sports.State.FINAL) "FINAL" else g.detail.ifBlank { "LIVE" }, color = Edge.Text.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(g.home.score ?: "0", color = Edge.Text, fontSize = 46.sp * big, lineHeight = 48.sp * big, fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(end = 14.dp))
                }
                TeamLogo(g.home.logo, g.home.name, size = 50.dp * big)
                if (homeChance != null) Column(Modifier.padding(start = 36.dp).weight(1f)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("${g.away.abbr} ${100 - homeChance}%", color = Edge.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("  to win", color = Edge.Text.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        Text("${g.home.abbr} $homeChance%", color = Edge.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Row(Modifier.padding(top = 5.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))) {
                        Box(Modifier.weight((100 - homeChance).coerceAtLeast(1).toFloat()).fillMaxHeight().background(away))
                        Box(Modifier.weight(homeChance.coerceAtLeast(1).toFloat()).fillMaxHeight().background(home))
                    }
                }
            }
            if (tall && latestPlay != null) DriveChart(g, latestPlay, Modifier.fillMaxWidth())
        }
    }
}

/** The LIVE badge: theme accent, its dot breathing like a recording light. */
@Composable
private fun LivePill() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "live")
    val dot by t.animateFloat(1f, 0.2f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "dot")
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Edge.Live).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).alpha(dot).clip(CircleShape).background(Edge.OnAccent))
        Text("LIVE", color = Edge.OnAccent, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 7.dp))
    }
}

/** Continue watching: the poster as a card, its progress under it, the title and time left beside it. */
@Composable
private fun ResumeSpot(p: com.edgestream.tv.data.db.ProgressEntity, modifier: Modifier) {
    val left = ((p.durationMs - p.positionMs) / 60_000).coerceAtLeast(0)
    val done = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
    Row(modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.fillMaxHeight().aspectRatio(2f / 3f)) {
            AsyncImage(p.poster, p.title, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Edge.Text.copy(alpha = 0.08f)),
                contentScale = ContentScale.Crop)
            Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Edge.Text.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(Edge.Accent))
            }
        }
        Column(Modifier.padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("CONTINUE WATCHING", color = Edge.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(p.title.uppercase(), color = Edge.Text, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$left min left", color = Edge.Text.copy(alpha = 0.8f), fontSize = 17.sp)
        }
    }
}

private const val HERO_TURN_MS = 8_000L

private data class VenueWeather(val indoor: Boolean, val tempC: Double, val code: Int) {
    val description: String get() = PublicApis.Weather("", tempC, code).description
}

/** Game id -> weather at the ground, so turning back to a card doesn't fetch it again. */
private val VENUE_WEATHER = java.util.concurrent.ConcurrentHashMap<String, VenueWeather>()

private suspend fun venueWeatherOf(g: Sports.Game): VenueWeather? {
    if (g.indoor == true) return VenueWeather(true, Double.NaN, 0).also { VENUE_WEATHER[g.id] = it }
    val city = g.venueCity ?: return null
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val loc = PublicApis.geocode(listOfNotNull(city, g.venueCountry).joinToString(", ")) ?: PublicApis.geocode(city)
        loc?.let { PublicApis.weather(it) }?.let { VenueWeather(false, it.tempC, it.code) }
    }?.also { VENUE_WEATHER[g.id] = it }
}

/** The forecast card: today on the left, the next days beside it. */
@Composable
private fun ForecastSpot(w: PublicApis.WeatherDetail, fahrenheit: Boolean, modifier: Modifier) {
    Row(modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherGlyph(w.code, glyphSize = 40.dp)
                Text(w.city.uppercase(), color = Edge.Text, fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Black, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
            }
            Text("${temp(w.tempC, fahrenheit)} ${w.description}  ·  High ${temp(w.highC, fahrenheit)}  Low ${temp(w.lowC, fahrenheit)}" +
                (if (w.rainChance > 0) "  ·  ${w.rainChance}% rain" else ""),
                color = Edge.Text.copy(alpha = 0.8f), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            w.daily.drop(1).take(4).forEach { d ->
                val day = runCatching { java.time.LocalDate.parse(d.date).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) }.getOrDefault(d.date)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(day.uppercase(), color = Edge.Text.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    WeatherGlyph(d.code, glyphSize = 28.dp)
                    Text("${temp(d.highC, fahrenheit)}  ${temp(d.lowC, fahrenheit)}", color = Edge.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
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
            Box(Modifier.align(Alignment.CenterStart).width(18.dp).height(52.dp).background(teamColor(g.away).copy(alpha = 0.8f)))
            Box(Modifier.align(Alignment.CenterEnd).width(18.dp).height(52.dp).background(teamColor(g.home).copy(alpha = 0.8f)))
            // Ball marker + line
            val cx = (frac.coerceIn(0.02f, 0.98f))
            Box(Modifier.fillMaxWidth(cx).height(52.dp), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(Edge.Live))
                Box(Modifier.size(14.dp).clip(CircleShape).background(Edge.Live).padding(2.dp)) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(Edge.Text))
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
