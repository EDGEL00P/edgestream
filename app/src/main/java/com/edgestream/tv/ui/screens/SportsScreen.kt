package com.edgestream.tv.ui.screens

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.edgestream.tv.data.Sports
import com.edgestream.tv.data.SportsFollows
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeLayout
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.Badge
import com.edgestream.tv.ui.components.CardSize
import com.edgestream.tv.ui.components.EmptyState
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.FilterChip
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Logo
import com.edgestream.tv.ui.components.OneLine
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.components.TeamLogo
import com.edgestream.tv.ui.components.SectionTitle
import com.edgestream.tv.ui.components.Shelf
import com.edgestream.tv.ui.components.loadingPulse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SPORTS_ALERT_CHANNEL = "edgestream.sports_alerts"
private const val SPORTS_ALERT_ID = 7007

/**
 * The Sports hub: what's live now, what's on today and next, and final scores, with real team
 * and league logos, records, scores, game state, and the networks showing each game, so the hub
 * can tune straight to the channel. The last known board paints instantly from the snapshot
 * cache; fresh scores arrive behind it. Scores refresh every 30 s; the league chips narrow the
 * board; selecting a game opens its Gamecast, and followed games alert on start and final.
 *
 * Chrome matches the TV Guide so every tab reads as one app: the same page padding, the
 * same header height, the same filter chips and the same rails. The header follows the
 * focused game, exactly like the guide's focused programme.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SportsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val channels by vm.channels.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val clock by vm.clock.collectAsStateWithLifecycle()
    // Device market decides the first tab; the five provider markets are always one click away.
    var country by rememberSaveable { mutableStateOf(defaultCountry()) }
    var games by remember { mutableStateOf(Sports.cachedGames().orEmpty().filter { country in it.league.countries }) }
    var loaded by remember { mutableStateOf(games.isNotEmpty()) }
    var refreshing by remember { mutableStateOf(games.isEmpty()) }
    var selectedLeague by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Sports.Game?>(null) }
    var focusedGame by remember { mutableStateOf<Sports.Game?>(null) }
    val first = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val list = games
    var cardFocused by remember { mutableStateOf(false) }

    LaunchedEffect(country) {
        // A country tab paints from its own cached boards instantly; only stale leagues refetch.
        val cached = Sports.cachedGames().orEmpty().filter { country in it.league.countries }
        games = cached
        loaded = cached.isNotEmpty()
        selectedLeague = null
        refreshing = true
        var previous = cached
        while (true) {
            val fresh = runCatching { Sports.games(country) }.getOrDefault(previous)
            Sports.newlyLiveOrFinal(previous, fresh, Sports.followedIds()).forEach { SportsAlerts.post(context, it) }
            previous = fresh
            games = fresh
            loaded = true
            refreshing = false
            delay(30_000)
        }
    }
    LaunchedEffect(loaded, list.isNotEmpty()) {
        if (!loaded || list.isEmpty()) return@LaunchedEffect
        // Keep asking until the first card really holds focus: a request that lands elsewhere
        // (for example on the tab bar) would otherwise leave OK pressing the wrong thing.
        repeat(14) {
            delay(120)
            if (cardFocused) return@LaunchedEffect
            runCatching { first.requestFocus() }
        }
    }

    val channelLookup by vm.sportsLookup.collectAsStateWithLifecycle()
    // Every channel that could carry each game — matched off the UI thread, capped, so a 10k
    // channel playlist can never stall a frame or exhaust the heap on the deck.
    val channelsForGames by produceState(emptyMap<String, List<ChannelEntity>>(), list, channelLookup) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            list.associate { g -> g.id to channelLookup.candidates(g).take(6) }
        }
    }
    // Channels that are actually showing a game float to the front; the rest stay one scroll away.
    val sportsChannels = remember(channels, channelsForGames) {
        val showing = channelsForGames.values.flatten().map { it.key }.toSet()
        channels.asSequence()
            .filter(Sports::isSportsChannel)
            .sortedWith(
                compareByDescending<ChannelEntity> { Sports.sportsChannelRank(it, it.key in showing) }
                    .thenBy { com.edgestream.tv.data.ChannelNumbers.lcn(it) },
            )
            .take(60)
            .toList()
    }

    fun watch(g: Sports.Game) {
        val ch = channelsForGames[g.id]?.firstOrNull()
        if (ch != null) {
            vm.playChannel(ch, vm.zapListFor(ch))
        } else {
            val alternatives = channelLookup.candidates(g).take(3)
            vm.showNotice(
                when {
                    g.broadcasts.isEmpty() -> "No channel listed for this game"
                    alternatives.isNotEmpty() -> "Available in your lineup: ${alternatives.joinToString { it.name }}"
                    else -> "Listed on ${g.broadcasts.joinToString()} — check your provider"
                },
            )
        }
    }

    val live = list.filter { it.state == Sports.State.LIVE && (selectedLeague == null || it.league.id == selectedLeague) }.sortedBy { it.startMs }
    val upcoming = list.filter { it.state == Sports.State.UPCOMING && (selectedLeague == null || it.league.id == selectedLeague) }.sortedBy { it.startMs }.take(40)
    val finals = list.filter { it.state == Sports.State.FINAL && (selectedLeague == null || it.league.id == selectedLeague) }.sortedByDescending { it.startMs }.take(30)

    val rows = listOf("Live Now" to live, "Coming Up" to upcoming, "Results" to finals).filter { it.second.isNotEmpty() }
    val countryLeagues = remember(country) { Sports.leaguesFor(country) }
    val counts = remember(list) { list.groupingBy { it.league.id }.eachCount() }

    Box(Modifier.fillMaxSize()) {
        // Guide layout: fixed header + filter chips, scrolling rails below. Outer padding
        // matches every other tab (EdgeLayout tokens) so left/top edges align on switch.
        Column(
            Modifier.fillMaxSize().padding(
                start = EdgeLayout.ContentInset,
                top = EdgeLayout.PageTop,
                end = EdgeLayout.PageEdge,
                bottom = EdgeLayout.PageBottom,
            ),
        ) {
            SportsHeader(
                focusedGame, refreshing = refreshing && list.isNotEmpty(), liveCount = live.size,
                upcomingCount = upcoming.size, use24h = settings.use24h, clock = clock,
            )
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Sports.Country.entries.toList(), key = { it.name }) { c ->
                    CountryChip(c, c == country) { country = c }
                }
            }
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all") {
                    FilterChip(selectedLeague == null, { selectedLeague = null }) {
                        Text("All", style = MaterialTheme.typography.labelLarge, maxLines = 1, color = Edge.Text)
                    }
                }
                items(countryLeagues, key = { it.id }) { league ->
                    val count = counts[league.id]
                    LeagueChip(
                        if (count == null || count == 0) league.name else "${league.name} · $count",
                        league.logo, selectedLeague == league.id,
                        onClick = { selectedLeague = league.id },
                    )
                }
            }
            SportsTimeRuler(list, clock, settings.use24h)
            LazyColumn(
                Modifier.fillMaxSize().focusRestorer(gridFocus),
                contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(EdgeLayout.SectionGap),
            ) {
                if (list.isEmpty() && !loaded) {
                    item(key = "loading") {
                        EmptyState(
                            "Loading scores\u2026",
                            "Scores paint instantly from the last board, then refresh live.",
                            Modifier.fillMaxWidth().padding(top = 24.dp),
                            icon = Icons.Rounded.SportsSoccer,
                        )
                    }
                }
                rows.forEachIndexed { r, row ->
                    val title = row.first
                    val rowGames = row.second
                    item(key = title) {
                        Shelf(title) {
                            items(rowGames, key = { it.id }) { g ->
                                GameCard(
                                    g, settings.use24h, channels = channelsForGames[g.id].orEmpty(),
                                    modifier = if (r == 0 && g == rowGames.first()) Modifier
                                        .focusRequester(first)
                                        .onFocusChanged { cardFocused = it.isFocused; focusedGame = g }
                                    else Modifier.onFocusChanged { if (it.isFocused) focusedGame = g },
                                    onClick = { detail = g },
                                    onLongClick = { watch(g) },
                                )
                            }
                        }
                    }
                }
                if (loaded && rows.isEmpty()) item(key = "empty") {
                    val sel = selectedLeague
                    EmptyState(
                        if (sel != null) "${leagueName(sel)} \u00b7 no fixtures right now" else "No games right now",
                        "Scores and fixtures appear here as soon as the league publishes them.",
                        Modifier.fillMaxWidth().padding(top = 24.dp),
                        icon = Icons.Rounded.SportsSoccer,
                    )
                }
                if (sportsChannels.isNotEmpty()) item(key = "channels") {
                    Shelf("Sports on Your TV") {
                        items(sportsChannels, key = { it.key }) { ch ->
                            SportsChannelCard(vm, ch, sportsChannels) { focusedGame = null }
                        }
                    }
                }
            }
        }
        val open = detail
        if (open != null) SportsGameSheet(vm, open, channelsForGames[open.id].orEmpty(), channels) { detail = null }
    }
}

/**
 * Guide-style header: the focused game's broadcast details, or the section title when
 * nothing is focused. Same height and type as the guide header so the top of both tabs
 * reads identically.
 */
@Composable
private fun SportsHeader(
    focused: Sports.Game?, refreshing: Boolean, liveCount: Int, upcomingCount: Int,
    use24h: Boolean, clock: Long,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 112.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (focused == null) {
                Text("Sports", style = MaterialTheme.typography.displaySmall, maxLines = 1)
                val sub = when {
                    refreshing -> "Updating scores\u2026"
                    liveCount > 0 -> "$liveCount live now \u00b7 $upcomingCount coming up"
                    upcomingCount > 0 -> "$upcomingCount coming up"
                    else -> "Scores for your market"
                }
                Text(sub, style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            } else {
                val g = focused
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (g.state) {
                        Sports.State.LIVE -> Pill("\u25cf Live", color = Edge.Live)
                        Sports.State.FINAL -> Pill("Final", color = Edge.Faint)
                        Sports.State.UPCOMING -> Pill(kickoff(g.startMs, use24h), color = Edge.Gold)
                    }
                    Text(
                        "  ${g.league.name.uppercase()}" +
                            (if (g.state == Sports.State.UPCOMING) " \u00b7 ${Format.day(g.startMs)} ${Format.time(g.startMs, use24h)}" else " \u00b7 ${Sports.statusText(g)}") +
                            (if (g.venue != null) " \u00b7 ${g.venue}" else ""),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    "${g.away.name} vs ${g.home.name}" + if (g.state != Sports.State.UPCOMING) " \u00b7 ${g.away.score ?: ""} \u2013 ${g.home.score ?: ""}".trim() else "",
                    style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    when {
                        g.state == Sports.State.LIVE -> g.detail.ifBlank { "Live now" }
                        g.broadcasts.isNotEmpty() -> "On ${g.broadcasts.joinToString(" \u00b7 ")}"
                        g.venue != null -> g.venue
                        else -> "OK for full analysis \u00b7 hold OK to watch"
                    },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A day/time strip modelled on the guide's ruler: today, kickoff markers, now tick. */
@Composable
private fun SportsTimeRuler(games: List<Sports.Game>, clock: Long, use24h: Boolean) {
    val dayStart = clock / 86_400_000L * 86_400_000L
    val dayLabel = Format.day(clock)
    val kicks = remember(games) {
        games.filter { it.state != Sports.State.FINAL && it.startMs >= dayStart }
            .sortedBy { it.startMs }.take(24)
    }
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(end = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            dayLabel, style = MaterialTheme.typography.labelLarge, color = Edge.Accent,
            maxLines = 1,
        )
        Row(
            Modifier.weight(1f).padding(start = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (h in listOf(0, 6, 12, 18, 23)) {
                val t = dayStart + h * 3_600_000L
                Text(
                    Format.time(t, use24h), style = MaterialTheme.typography.labelMedium,
                    color = if (h % 2 == 0) Edge.Dim else Edge.Faint,
                )
            }
        }
        if (kicks.isNotEmpty()) {
            val next = kicks.first()
            Text(
                "  Next ${Format.time(next.startMs, use24h)}",
                style = MaterialTheme.typography.labelMedium, color = Edge.Gold, maxLines = 1,
            )
        }
    }
}

/** A market tab in the hub: the real flag plus the country's full name. */
@Composable
private fun CountryChip(country: Sports.Country, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(selected, onClick, modifier, leading = {
        AsyncImage(
            country.flag, country.label,
            Modifier.size(width = 26.dp, height = 18.dp).clip(RoundedCornerShape(3.dp)),
            contentScale = ContentScale.Crop,
        )
    }) {
        Text(
            country.label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = Edge.Text,
        )
    }
}

/** The user's own market gets the first tab; the other four are always one click away. */
private fun defaultCountry(): Sports.Country = when (java.util.Locale.getDefault().country.uppercase()) {
    "US" -> Sports.Country.US
    "GB", "UK" -> Sports.Country.UK
    "CA" -> Sports.Country.CA
    "AU" -> Sports.Country.AU
    "NZ" -> Sports.Country.NZ
    else -> Sports.Country.US
}

/** A league filter chip in the hub's row of leagues, carrying the league's real ESPN mark. */
@Composable
private fun LeagueChip(name: String, logo: String?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(selected, onClick, modifier, leading = if (logo != null) {
        { AsyncImage(logo, name, Modifier.size(22.dp), contentScale = ContentScale.Fit) }
    } else null) {
        Text(
            name, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = Edge.Text,
        )
    }
}

/** A game: league, both teams with real logos and records, score or kick-off, and where it's on. */
@Composable
private fun GameCard(g: Sports.Game, use24h: Boolean, channels: List<ChannelEntity>, modifier: Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val cardColor = when (g.state) {
        Sports.State.LIVE -> Edge.PanelHi
        Sports.State.FINAL -> Edge.Panel.copy(alpha = Edge.Panel.alpha * 0.6f)
        Sports.State.UPCOMING -> Edge.Panel
    }
    FocusSurface(
        onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.width(CardSize.Wide).aspectRatio(16f / 9f),
        shape = RoundedCornerShape(8.dp), color = cardColor, focusScale = 1f, glow = false,
    ) { f ->
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (g.league.logo != null) {
                    AsyncImage(g.league.logo, g.league.name, Modifier.size(16.dp), contentScale = ContentScale.Fit)
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    g.league.name.uppercase(), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                when (g.state) {
                    Sports.State.LIVE -> Pill("\u25cf Live", color = Edge.Live)
                    Sports.State.FINAL -> Pill("Final", color = Edge.Faint)
                    Sports.State.UPCOMING -> Text(kickoff(g.startMs, use24h), style = MaterialTheme.typography.labelMedium, color = Edge.Accent)
                }
            }
            Spacer(Modifier.weight(1f))
            TeamLine(g.away, g.state != Sports.State.UPCOMING)
            Spacer(Modifier.size(6.dp))
            TeamLine(g.home, g.state != Sports.State.UPCOMING)
            if (g.state == Sports.State.FINAL) {
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Final", style = MaterialTheme.typography.labelMedium, color = Edge.Dim, modifier = Modifier.weight(1f))
                    Text(
                        "${g.away.score ?: ""} \u2013 ${g.home.score ?: ""}".trim(),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = Edge.Text,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            val where = when {
                g.state == Sports.State.LIVE -> g.detail
                g.broadcasts.isNotEmpty() -> "On ${g.broadcasts.take(2).joinToString(" \u00b7 ")}"
                else -> g.venue.orEmpty()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(where, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (g.state != Sports.State.FINAL) {
                    Text(
                        if (channels.isNotEmpty()) "\u25b6 Watch" + if (channels.size > 1) " \u00b7 +${channels.size - 1}" else "" else "Not in lineup",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (channels.isNotEmpty()) Edge.Accent else Edge.Dim,
                    )
                }
            }
        }
        // Guide-style left accent strip: live green, finals dimmed.
        val strip = when (g.state) {
            Sports.State.LIVE -> Edge.Accent2
            Sports.State.FINAL -> Edge.Faint
            Sports.State.UPCOMING -> Edge.PanelHi
        }
        Box(
            Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight(0.6f)
                .clip(RoundedCornerShape(50)).background(strip.copy(alpha = if (g.state == Sports.State.FINAL) 0.4f else 0.9f)),
        )
    }
}

@Composable
private fun TeamLine(t: Sports.Team, showScore: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(t.logo, t.name, size = 30.dp)
        Text(
            t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (showScore && !t.winner) Edge.Dim else Edge.Text,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        if (!t.record.isNullOrBlank()) Text(
            t.record, style = MaterialTheme.typography.labelSmall, color = Edge.Dim,
            maxLines = 1, modifier = Modifier.padding(start = 8.dp),
        )
        if (showScore && t.score != null) Text(
            t.score, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = teamColorHex(t) ?: (if (t.winner) Edge.Text else Edge.Dim),
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** Guide-style channel cell: number, logo, name, what's on now. */
@Composable
private fun SportsChannelCard(vm: AppViewModel, ch: ChannelEntity, list: List<ChannelEntity>, onFocus: () -> Unit = {}) {
    val now = vm.epgFor(ch)?.first
    val settings by vm.settings.collectAsStateWithLifecycle()
    FocusSurface(
        onClick = { vm.playChannel(ch, list) },
        modifier = Modifier.width(CardSize.Wide).aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocus() },
        shape = RoundedCornerShape(8.dp), focusScale = 1f, glow = false,
    ) {
        Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${com.edgestream.tv.data.ChannelNumbers.lcn(ch)}", style = MaterialTheme.typography.labelMedium, color = Edge.Faint, maxLines = 1, softWrap = false, modifier = Modifier.width(48.dp))
            Logo(ch.logo, ch.name, Modifier.size(width = 48.dp, height = 34.dp))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                OneLine(ch.name, style = MaterialTheme.typography.labelLarge)
                OneLine(now?.title ?: ch.name, style = MaterialTheme.typography.bodyMedium)
                if (now != null) OneLine(
                    Format.range(now.start, now.stop, settings.use24h),
                    style = MaterialTheme.typography.labelMedium, color = Edge.Faint,
                )
            }
        }
    }
}

private fun leagueName(id: String): String = Sports.LEAGUES.firstOrNull { it.id == id }?.name ?: id

/** "Today 19:30", "Sat 06:00". */
private fun kickoff(ms: Long, use24h: Boolean): String {
    if (ms <= 0) return ""
    val zone = java.time.ZoneId.systemDefault()
    val d = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val day = when (d.toLocalDate()) {
        today -> "Today"; today.plusDays(1) -> "Tomorrow"
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
    }
    return "$day ${Format.time(ms, use24h)}"
}

/** The sheet that answers "how are we doing?": full game, records, every channel and the Gamecast. */
@Composable
fun SportsGameSheet(
    vm: AppViewModel,
    game: Sports.Game,
    available: List<ChannelEntity>,
    channels: List<ChannelEntity>,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val first = remember { FocusRequester() }
    LaunchedEffect(game.id) { repeat(10) { delay(60); runCatching { first.requestFocus() } } }
    var following by remember { mutableStateOf(Sports.isFollowing(game.id)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val actions = remember { FocusRequester() }
    var sheetFocused by remember { mutableStateOf(false) }
    val primary = available.firstOrNull()
    val missing = remember(game.id, channels) { Sports.missingBroadcasts(game, channels) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))) {
        Column(
            Modifier.align(Alignment.CenterEnd).width(520.dp).fillMaxHeight()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                .background(Edge.Solid)
                // The sheet keeps the remote while it is open: Up/Down scroll the analysis and only
                // hand off to the buttons at the very end, so nothing behind the sheet can be pressed.
                .focusRequester(first)
                .onFocusChanged { sheetFocused = it.isFocused }
                .focusable()
                .onKeyEvent { e ->
                    if (!sheetFocused || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = with(density) { 120.dp.toPx() }
                    when (e.key) {
                        Key.DirectionDown -> when {
                            scroll.canScrollForward -> { scope.launch { scroll.scrollBy(step) }; true }
                            else -> { runCatching { actions.requestFocus() }; true }
                        }
                        Key.DirectionUp -> if (scroll.canScrollBackward) { scope.launch { scroll.scrollBy(-step) }; true } else false
                        else -> false
                    }
                }
                .verticalScroll(scroll)
                .padding(start = 28.dp, end = 28.dp, top = 32.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (game.league.logo != null) {
                    AsyncImage(game.league.logo, game.league.name, Modifier.size(24.dp), contentScale = ContentScale.Fit)
                    Spacer(Modifier.size(10.dp))
                }
                Text("${game.league.name.uppercase()} \u00b7 ${Sports.statusText(game)}", style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(10.dp))
                FocusSurface(
                    onClick = onDismiss,
                    modifier = Modifier.height(30.dp),
                    shape = EdgeShapes.Chip,
                    color = Edge.Panel,
                    focusedColor = Edge.PanelHi,
                    focusScale = 1.03f,
                    glow = false,
                ) {
                    Text(
                        "CLOSE", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                        color = Edge.Dim, modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                    )
                }
            }
            Spacer(Modifier.size(18.dp))
            TeamLine(game.home, game.state != Sports.State.UPCOMING)
            if (game.state != Sports.State.UPCOMING) Spacer(Modifier.size(6.dp))
            TeamLine(game.away, game.state != Sports.State.UPCOMING)
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FollowTeamChip(game.league, game.home)
                FollowTeamChip(game.league, game.away)
            }
            Spacer(Modifier.size(6.dp))
            if (game.state == Sports.State.FINAL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Final", style = MaterialTheme.typography.labelMedium, color = Edge.Dim, modifier = Modifier.weight(1f))
                    Text(
                        "${game.away.score ?: ""} \u2013 ${game.home.score ?: ""}".trim(),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = Edge.Text,
                    )
                }
            }
            if (game.venue != null) Text(game.venue, style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (game.broadcasts.isNotEmpty()) Text("Listed on ${game.broadcasts.joinToString(" \u00b7 ")}", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (missing.isNotEmpty()) Text(
                "Not carried: ${missing.joinToString()}",
                style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (game.state == Sports.State.LIVE) Text(game.detail, style = MaterialTheme.typography.bodyMedium, color = Edge.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (available.isNotEmpty()) {
                Text("Available in your lineup", style = MaterialTheme.typography.labelMedium, color = Edge.Dim, modifier = Modifier.padding(top = 14.dp))
                val pins by com.edgestream.tv.data.ChannelPins.state.collectAsStateWithLifecycle()
                val pinnedKey = com.edgestream.tv.data.ChannelPins.pinFor(game, pins)
                // The channel the pin buttons act on: whichever one the remote last rested on.
                var target by remember(game.id) { mutableStateOf(available.first()) }
                available.forEachIndexed { i, alt ->
                    val label = when {
                        alt.key == pinnedKey -> "  \u00b7  Your choice"
                        i == 0 -> "  \u00b7  Best available"
                        else -> ""
                    }
                    EdgeButton(
                        "${com.edgestream.tv.data.ChannelNumbers.lcn(alt)} \u00b7 ${alt.name}$label",
                        { vm.playChannel(alt, vm.zapListFor(alt)); onDismiss() },
                        Modifier.fillMaxWidth().padding(top = if (i == 0) 8.dp else 0.dp)
                            .then(if (i == 0) Modifier.focusRequester(actions) else Modifier)
                            .onFocusChanged { if (it.isFocused) target = alt },
                        fill = true,
                    )
                }
                val playingLive = vm.nowPlaying.collectAsStateWithLifecycle().value is com.edgestream.tv.ui.NowPlaying.Live
                if (playingLive) EdgeButton(
                    "Watch alongside what's on now",
                    { vm.startMultiview(target); onDismiss() },
                    Modifier.fillMaxWidth().padding(top = 8.dp), fill = true,
                )
                Text(
                    "Always watch on ${target.name}",
                    style = MaterialTheme.typography.labelMedium, color = Edge.Dim, modifier = Modifier.padding(top = 14.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun pin(key: String, what: String) {
                        com.edgestream.tv.data.ChannelPins.set(key, target.key)
                        vm.showNotice("$what will play on ${target.name}")
                    }
                    EdgeButton("This Game", { pin(com.edgestream.tv.data.ChannelPins.gameKey(game), "This Game") })
                    EdgeButton("${game.home.abbr} games", { pin(com.edgestream.tv.data.ChannelPins.teamKey(game.league, game.home), "${game.home.name} games") })
                    EdgeButton("${game.away.abbr} games", { pin(com.edgestream.tv.data.ChannelPins.teamKey(game.league, game.away), "${game.away.name} games") })
                }
                if (pinnedKey != null) EdgeButton(
                    "Forget my choice",
                    {
                        listOf(
                            com.edgestream.tv.data.ChannelPins.gameKey(game),
                            com.edgestream.tv.data.ChannelPins.teamKey(game.league, game.home),
                            com.edgestream.tv.data.ChannelPins.teamKey(game.league, game.away),
                        ).forEach { com.edgestream.tv.data.ChannelPins.set(it, null) }
                        vm.showNotice("Back to the best available channel")
                    },
                    Modifier.padding(top = 8.dp),
                )
            }
            if (primary != null) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(primary.logo, primary.name, Modifier.size(width = 84.dp, height = 48.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(primary.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Channel ${com.edgestream.tv.data.ChannelNumbers.lcn(primary)}", style = MaterialTheme.typography.labelMedium, color = Edge.Dim)
                    }
                }
            }

            ProInsights(game, vm.settings.collectAsStateWithLifecycle().value.hideScores)

            Spacer(Modifier.size(24.dp))
            EdgeButton(
                if (following) "Following" else "\u2606 Follow alerts",
                {
                    Sports.toggleFollow(game.id)
                    following = Sports.isFollowing(game.id)
                    if (Sports.isFollowing(game.id) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                Modifier.fillMaxWidth().then(if (available.isEmpty()) Modifier.focusRequester(actions) else Modifier),
                primary = true,
                fill = true,
            )
        }
    }
}

/** A compact team follow toggle: My Teams is populated from these. */
@Composable
private fun FollowTeamChip(league: Sports.League, team: Sports.Team) {
    val follows by SportsFollows.state.collectAsStateWithLifecycle()
    val followed = SportsFollows.isFollowed(league, team)
    FocusSurface(
        onClick = { SportsFollows.toggle(league, team) },
        shape = EdgeShapes.Chip,
        color = if (followed) Edge.Gold.copy(alpha = 0.16f) else Edge.Panel,
        focusedColor = if (followed) Edge.Gold.copy(alpha = 0.28f) else Edge.PanelHi,
        selected = followed,
        focusScale = 1.03f,
        glow = false,
    ) { focused ->
        Text(
            (if (followed) "Following  " else "Follow  ") + team.abbr,
            style = MaterialTheme.typography.labelMedium,
            color = if (followed) Edge.Gold else if (focused) Edge.Text else Edge.Dim,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** The analyst layer; the same Gamecast body used by the full-screen player panel. */
@Composable
private fun ProInsights(game: Sports.Game, hideScores: Boolean) {
    val (insight, loaded) = rememberGamecastInsight(game)
    Spacer(Modifier.size(24.dp))
    SportsGamecast(game, insight, loaded, maxPlays = 10, hideScores = hideScores)
}

/** A local notification when a followed game starts or finishes. */
object SportsAlerts {
    fun post(context: Context, game: Sports.Game) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PermissionChecker.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(SPORTS_ALERT_CHANNEL)
            if (existing == null) manager.createNotificationChannel(
                NotificationChannel(SPORTS_ALERT_CHANNEL, "Sports alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Game start and final" },
            )
        }
        val title = if (game.state == Sports.State.LIVE) "Live now" else "Final"
        val text = if (game.state == Sports.State.LIVE) "${game.away.name} at ${game.home.name}" else "${game.away.score ?: ""} \u2013 ${game.home.score ?: ""}".trim()
        val notification = NotificationCompat.Builder(context, SPORTS_ALERT_CHANNEL)
            .setSmallIcon(com.edgestream.tv.R.drawable.ic_launcher_foreground)
            .setContentTitle("${game.league.name}: $title")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(SPORTS_ALERT_ID, notification)
    }
}
