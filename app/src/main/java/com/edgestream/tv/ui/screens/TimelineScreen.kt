package com.edgestream.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.edgestream.tv.data.Racing
import com.edgestream.tv.data.Sports
import com.edgestream.tv.data.SportsFollows
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.FocusMemory
import com.edgestream.tv.ui.components.FocusDimGroup
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.NowPlaying
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.FilterChip
import com.edgestream.tv.ui.components.FilterChipText
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.components.PreviewFrame
import com.edgestream.tv.ui.glass
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * The first slice of the sports OS: a single live timeline instead of a channel grid.
 * Games and races are ordered by what is happening now, with one action to tune and one
 * to open a data-backed match centre. Everything is in-process; no companion service.
 */
@Composable
fun TimelineScreen(vm: AppViewModel) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val clock by vm.clock.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val lookup by vm.sportsLookup.collectAsStateWithLifecycle()
    var leagueFilter by remember { mutableStateOf<String?>(null) }
    var dayOffset by remember { mutableStateOf(0) }
    var liveOnly by remember { mutableStateOf(false) }
    var standings by remember { mutableStateOf(false) }

    // One shared live feed (the view model's), not a loop per screen.
    val games by vm.sportsGames.collectAsStateWithLifecycle()
    val races by vm.sportsFeed.races.collectAsStateWithLifecycle()
    val loaded by vm.sportsFeed.loaded.collectAsStateWithLifecycle()
    val loading = !loaded
    var selectedGame by remember { mutableStateOf<Sports.Game?>(null) }
    var raceSheet by remember { mutableStateOf<Racing.Race?>(null) }


    val dayStartMs = remember(clock / 60_000L, dayOffset) { startOfDay(clock, dayOffset) }
    val dayGames = remember(games, dayStartMs) {
        games.filter { it.startMs > 0 && it.startMs >= dayStartMs && it.startMs < dayStartMs + 86_400_000L }
    }
    val visibleGames = if (liveOnly) dayGames.filter { it.state == Sports.State.LIVE } else dayGames
    val followedCount = visibleGames.count { SportsFollows.gameFollowed(it) }
    val leaguesWithGames = remember(visibleGames) {
        Sports.LEAGUES.filter { league -> visibleGames.any { it.league.id == league.id } }
    }
    val shownLeague = when (leagueFilter) {
        null -> visibleGames
        "teams" -> visibleGames.filter { SportsFollows.gameFollowed(it) }
        else -> visibleGames.filter { it.league.id == leagueFilter }
    }

    Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 18.dp, end = 42.dp, bottom = 26.dp)) {
        Text(
            buildString {
                append(dayWord(clock, dayOffset))
                append("  ·  ${visibleGames.size} ${if (visibleGames.size == 1) "game" else "games"}")
                val liveNow = visibleGames.count { it.state == Sports.State.LIVE }
                if (liveNow > 0) append("  ·  $liveNow live")
                append("  ·  ${Format.time(clock, settings.use24h)}")
            },
            style = MaterialTheme.typography.labelMedium, color = Edge.Dim,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        FocusDimGroup {
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 8.dp)
                    .drawWithContent {
                        drawContent()
                        val fade = 56.dp.toPx()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Transparent, Edge.Bg),
                                startX = size.width - fade,
                                endX = size.width,
                            ),
                            topLeft = androidx.compose.ui.geometry.Offset(size.width - fade, 0f),
                            size = androidx.compose.ui.geometry.Size(fade, size.height),
                        )
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp, end = 64.dp),
            ) {
                item(key = "__all") {
                    LeagueFilterChip("All", null, leagueFilter == null, visibleGames.size, memoryKey = "timeline:league|all") { leagueFilter = null }
                }
                item(key = "__teams") {
                    LeagueFilterChip("My Teams", null, leagueFilter == "teams", followedCount, star = true, memoryKey = "timeline:league|teams") {
                        leagueFilter = if (leagueFilter == "teams") null else "teams"
                    }
                }
                items(leaguesWithGames, key = { it.id }) { league ->
                    LeagueFilterChip(
                        league.name, league.logo, leagueFilter == league.id,
                        visibleGames.count { it.league.id == league.id },
                        memoryKey = "timeline:league|${league.id}",
                    ) { leagueFilter = if (leagueFilter == league.id) null else league.id }
                }
            }
        }

        LazyRow(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 4.dp, end = 64.dp),
        ) {
            item(key = "__standings") {
                FilterChipText("Standings", standings, onClick = { standings = !standings })
            }
            item(key = "__live") {
                FilterChipText("● Live now", liveOnly, onClick = { liveOnly = !liveOnly; standings = false; if (liveOnly) dayOffset = 0 })
            }
            item(key = "__yesterday") {
                FilterChipText("Yesterday", dayOffset == -1, onClick = { dayOffset = -1 })
            }
            item(key = "__today") {
                FilterChipText("Today", dayOffset == 0, onClick = { dayOffset = 0 })
            }
            item(key = "__tomorrow") {
                FilterChipText("Tomorrow", dayOffset == 1, onClick = { dayOffset = 1 })
            }
            items((2..6).toList(), key = { "__day$it" }) { offset ->
                FilterChipText(dayLabel(clock, offset), dayOffset == offset, onClick = { dayOffset = offset })
            }
        }

        val tableLeague = Sports.LEAGUES.firstOrNull { it.id == leagueFilter }
        if (standings) {
            if (tableLeague != null) StandingsView(vm, tableLeague, Modifier.weight(1f).padding(top = 8.dp))
            else Text(
                "Pick a league above to see its table.",
                style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, modifier = Modifier.padding(top = 24.dp),
            )
        } else SportsGuide(
            games = shownLeague,
            races = if (leagueFilter == null) races else emptyList(),
            lookup = lookup,
            channels = channels,
            nowNext = nowNext,
            clock = clock,
            use24h = settings.use24h,
            loading = loading,
            onGame = { selectedGame = it },
            onRace = { raceSheet = it },
            modifier = Modifier.weight(1f).padding(top = 8.dp),
        )
    }

    selectedGame?.let { game ->
        SportsGameSheet(
            vm = vm,
            game = game,
            available = lookup.candidates(game).take(6),
            channels = channels,
            onDismiss = { selectedGame = null },
        )
    }
    raceSheet?.let { race ->
        val channel = raceChannel(race, channels, nowNext)
        RaceDialog(
            race = race,
            channel = channel,
            use24h = settings.use24h,
            onWatch = channel?.let { { vm.playChannel(it, vm.zapListFor(it)); raceSheet = null } },
            onClose = { raceSheet = null },
        )
    }
}

/**
 * One league's table(s). OK on a team follows it (its games then lead the lists and fill the
 * calendar). Focus returns to the last row when coming back up from below.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun StandingsView(vm: AppViewModel, league: Sports.League, modifier: Modifier) {
    val tables by androidx.compose.runtime.produceState<List<com.edgestream.tv.data.Standings.Table>?>(null, league.id) {
        value = com.edgestream.tv.data.Standings.load(league)
    }
    val follows by SportsFollows.state.collectAsStateWithLifecycle()
    val t = tables
    when {
        t == null -> Text("Loading the table…", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, modifier = modifier.padding(top = 24.dp))
        t.isEmpty() -> Text("No table for ${league.name} right now.", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, modifier = modifier.padding(top = 24.dp))
        else -> LazyColumn(
            modifier.fillMaxWidth().focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            t.forEach { table ->
                item(key = "h:${table.group}") {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp, start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(table.group, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        table.columns.forEach { c -> Text(c, style = MaterialTheme.typography.labelMedium, color = Edge.Dim, modifier = Modifier.width(STAT_W), textAlign = androidx.compose.ui.text.style.TextAlign.End) }
                    }
                }
                items(table.rows, key = { "${table.group}:${it.abbr}:${it.rank}" }) { r ->
                    val team = Sports.Team(r.abbr, r.name, r.logo, null, false)
                    val followed = SportsFollows.key(league, team) in follows
                    FocusSurface(
                        onClick = {
                            SportsFollows.toggle(league, team)
                            vm.showNotice(if (followed) "Stopped following ${r.name}" else "Following ${r.name}: games now in your calendar")
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        color = Edge.Text.copy(alpha = if (followed) 0.12f else 0.04f), focusedColor = Edge.Text,
                        focusScale = 1.01f, glow = false,
                    ) { focused ->
                        val ink = if (focused) Edge.Bg else Edge.Text
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.rank}", style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.7f), modifier = Modifier.width(32.dp))
                            AsyncImage(r.logo, null, Modifier.size(26.dp), contentScale = ContentScale.Fit)
                            Text(
                                (if (followed) "★ " else "") + r.name,
                                style = MaterialTheme.typography.bodyLarge, fontWeight = if (followed) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (followed && !focused) Edge.Accent else ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 12.dp),
                            )
                            r.values.forEach { v -> Text(v, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.width(STAT_W), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

private val STAT_W = 58.dp

@Composable
private fun LeagueFilterChip(
    label: String,
    logo: String?,
    selected: Boolean,
    count: Int,
    star: Boolean = false,
    memoryKey: String? = null,
    onClick: () -> Unit,
) {
    FilterChip(selected, onClick, memoryKey = memoryKey) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (star) {
                androidx.compose.material3.Icon(Icons.Rounded.Star, null, Modifier.size(16.dp), tint = Edge.Gold)
            } else if (logo != null) {
                AsyncImage(logo, null, Modifier.size(18.dp), contentScale = ContentScale.Fit)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text("$count", style = MaterialTheme.typography.labelSmall, color = Edge.Faint, maxLines = 1)
        }
    }
}

private fun stateRank(state: Sports.State): Int = when (state) {
    Sports.State.LIVE -> 0
    Sports.State.UPCOMING -> 1
    Sports.State.FINAL -> 2
}

private fun startOfDay(now: Long, offset: Int): Long {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = now
    c.set(java.util.Calendar.HOUR_OF_DAY, 0)
    c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    c.add(java.util.Calendar.DAY_OF_YEAR, offset)
    return c.timeInMillis
}

private fun dayWord(now: Long, offset: Int): String = when (offset) {
    -1 -> "Yesterday"
    0 -> "Today"
    1 -> "Tomorrow"
    else -> dayLabel(now, offset)
}

private fun dayLabel(now: Long, offset: Int): String {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = now
    c.add(java.util.Calendar.DAY_OF_YEAR, offset)
    return java.text.SimpleDateFormat("EEE d", java.util.Locale.getDefault()).format(c.time)
}

/**
 * Sports as a broadcast guide: each sport gets a section, and every game underneath it names
 * the channel carrying it. Live games first, then the next start times, then results.
 */
@Composable
private fun SportsGuide(
    games: List<Sports.Game>,
    races: List<Racing.Race>,
    lookup: Sports.ChannelLookup,
    channels: List<ChannelEntity>,
    nowNext: Map<String, Pair<ProgrammeEntity?, ProgrammeEntity?>>,
    clock: Long,
    use24h: Boolean,
    loading: Boolean,
    onGame: (Sports.Game) -> Unit,
    onRace: (Racing.Race) -> Unit,
    modifier: Modifier = Modifier,
) {
    val leagueRank = remember { Sports.LEAGUES.mapIndexed { i, l -> l.id to i }.toMap() }
    val uniqueGames = remember(games) {
        games.distinctBy { "${it.league.id}:${it.home.abbr}:${it.away.abbr}:${it.startMs}" }
    }
    val featured = remember(uniqueGames) {
        uniqueGames.firstOrNull { it.state == Sports.State.LIVE }
            ?: uniqueGames.firstOrNull { it.state == Sports.State.UPCOMING }
            ?: uniqueGames.firstOrNull()
    }
    val groups = remember(uniqueGames, featured?.id) {
        uniqueGames.filterNot { it.id == featured?.id }.groupBy { it.league.id }.values
            .map { list ->
                list.first().league to list.sortedWith(compareBy({ stateRank(it.state) }, { it.startMs }))
            }
            .sortedWith(compareBy({ leagueRank[it.first.id] ?: 99 }, { it.first.name }))
    }
    val orderedRaces = remember(races) { races.sortedBy { it.startMs } }

    if (featured == null && orderedRaces.isEmpty()) {
        Text(
            if (loading) "Loading the sports guide…" else "No games or races listed right now.",
            style = MaterialTheme.typography.bodyLarge, color = Edge.Dim,
            modifier = modifier.padding(top = 24.dp),
        )
        return
    }

    FocusDimGroup {
        LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (featured != null) {
                item(key = "__hero") {
                    SportHero(
                        game = featured,
                        channel = lookup.find(featured),
                        direct = lookup.isDirectMatch(featured),
                        clock = clock,
                        use24h = use24h,
                        onClick = { onGame(featured) },
                    )
                }
            }
            groups.forEach { (league, list) ->
                item(key = "league:${league.id}") {
                    SportSectionHeader(league, list)
                }
                items(list, key = { "game:${it.id}" }) { game ->
                    SportGameRow(
                        game = game,
                        channel = lookup.find(game),
                        direct = lookup.isDirectMatch(game),
                        use24h = use24h,
                        clock = clock,
                        onClick = { onGame(game) },
                    )
                }
            }
            if (orderedRaces.isNotEmpty()) {
                item(key = "__racing") {
                    SportSectionHeader(
                        league = null,
                        games = emptyList(),
                        title = "Racing",
                        subtitle = "${orderedRaces.size} ${if (orderedRaces.size == 1) "race" else "races"} · next to go",
                    )
                }
                items(orderedRaces, key = { "race:${it.id}" }) { race ->
                    SportRaceRow(
                        race = race,
                        channel = raceChannel(race, channels, nowNext),
                        use24h = use24h,
                        onClick = { onRace(race) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SportHero(
    game: Sports.Game,
    channel: ChannelEntity?,
    direct: Boolean,
    clock: Long,
    use24h: Boolean,
    onClick: () -> Unit,
) {
    val live = game.state == Sports.State.LIVE
    val final = game.state == Sports.State.FINAL
    val status = when {
        live -> game.detail.uppercase().ifBlank { "LIVE" }
        final -> "FULL TIME"
        else -> Format.time(game.startMs, use24h)
    }
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 148.dp),
        shape = EdgeShapes.Panel,
        color = Edge.Panel,
        focusedColor = Edge.PanelHi,
        focusScale = 1.008f,
        glow = false,
    ) { focused ->
        Box(Modifier.fillMaxSize()) {
            game.league.logo?.let {
                AsyncImage(
                    it, null,
                    Modifier.align(Alignment.Center).size(140.dp)
                        .graphicsLayer { alpha = 0.045f },
                    contentScale = ContentScale.Fit,
                )
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live) com.edgestream.tv.ui.components.LiveBadge(label = "LIVE NOW", compact = true)
                    else Pill(if (final) "RESULT" else "UP NEXT", color = if (final) Edge.Faint else Edge.Accent)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${game.league.name}  ·  ${game.venue ?: "Venue TBC"}",
                        style = MaterialTheme.typography.labelMedium, color = Edge.Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeroTeam(game.away, homeSide = false, modifier = Modifier.weight(1f))
                    Column(
                        Modifier.padding(horizontal = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            when {
                                live || final -> "${game.away.score ?: "–"}  –  ${game.home.score ?: "–"}"
                                else -> Format.time(game.startMs, use24h)
                            },
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                            maxLines = 1,
                        )
                        Text(
                            when {
                                live -> status
                                final -> "Final"
                                else -> startsIn(clock, game.startMs)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                live -> Edge.Live
                                final -> Edge.Faint
                                else -> Edge.Accent
                            },
                            maxLines = 1,
                        )
                    }
                    HeroTeam(game.home, homeSide = true, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (channel != null) {
                            if (direct) "Watch on ${channel.name}  ›" else "Suggested: ${channel.name}  ›"
                        } else {
                            "Pick a channel in match centre  ›"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (channel != null) Edge.Accent2 else Edge.Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroTeam(team: Sports.Team, homeSide: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (homeSide) Arrangement.End else Arrangement.Start,
    ) {
        if (!homeSide) TeamBadge(team, 46.dp)
        Column(
            Modifier.padding(horizontal = 10.dp),
            horizontalAlignment = if (homeSide) Alignment.End else Alignment.Start,
        ) {
            Text(
                team.name,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!team.record.isNullOrBlank()) {
                Text(team.record, style = MaterialTheme.typography.labelSmall, color = Edge.Faint, maxLines = 1)
            }
        }
        if (homeSide) TeamBadge(team, 46.dp)
    }
}

@Composable
private fun TeamBadge(team: Sports.Team, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (team.logo != null) {
            AsyncImage(team.logo, team.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Box(
                Modifier.size(size * 0.82f).clip(RoundedCornerShape(50)).background(Edge.PanelHi),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    team.abbr, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Edge.Dim, maxLines = 1,
                )
            }
        }
    }
}

private fun startsIn(now: Long, start: Long): String {
    val mins = ((start - now) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 60 -> "Starts in ${mins}m"
        mins < 24 * 60 -> "Starts in ${mins / 60}h ${mins % 60}m"
        else -> "Starts ${Format.day(start)}"
    }
}


@Composable
private fun SportSectionHeader(
    league: Sports.League?,
    games: List<Sports.Game>,
    title: String = league?.name.orEmpty(),
    subtitle: String? = null,
) {
    val live = games.count { it.state == Sports.State.LIVE }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        league?.logo?.let {
            AsyncImage(it, league.name, Modifier.size(22.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(9.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Text(
            subtitle ?: buildString {
                append("${games.size} ${if (games.size == 1) "game" else "games"}")
                if (live > 0) append("  ·  $live live")
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (live > 0) Edge.Live else Edge.Faint,
            maxLines = 1,
        )
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Edge.Line))
    }
}

@Composable
private fun SportGameRow(
    game: Sports.Game,
    channel: ChannelEntity?,
    direct: Boolean,
    use24h: Boolean,
    clock: Long,
    onClick: () -> Unit,
) {
    val live = game.state == Sports.State.LIVE
    val final = game.state == Sports.State.FINAL
    val followed = SportsFollows.gameFollowed(game)
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = EdgeShapes.Card,
        focusScale = 1.005f,
        glow = false,
        memoryKey = "timeline:game|${game.id}",
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(96.dp)) {
                if (live) com.edgestream.tv.ui.components.LiveBadge(compact = true)
                else Text(
                    if (final) "FINAL" else Format.time(game.startMs, use24h),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (final) Edge.Faint else Edge.Text,
                    maxLines = 1,
                )
                Text(
                    when {
                        live -> game.detail.uppercase().ifBlank { "In play" }
                        final -> "Full time"
                        else -> Format.day(game.startMs)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live) Edge.Live.copy(alpha = 0.85f) else Edge.Faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamBadge(game.away, 24.dp)
                    Text(
                        buildString {
                            append("  ${game.away.abbr}")
                            if (game.state != Sports.State.UPCOMING) append("  ${game.away.score ?: "–"}")
                            else game.away.record?.let { append("  $it") }
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    Text("   @   ", style = MaterialTheme.typography.labelMedium, color = Edge.Faint, maxLines = 1)
                    TeamBadge(game.home, 24.dp)
                    Text(
                        buildString {
                            append("  ${game.home.abbr}")
                            if (game.state != Sports.State.UPCOMING) append("  ${game.home.score ?: "–"}")
                            else game.home.record?.let { append("  $it") }
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    if (followed) {
                        Spacer(Modifier.width(7.dp))
                        Text("★", style = MaterialTheme.typography.labelMedium, color = Edge.Gold)
                    }
                }
                Text(
                    game.venue?.takeIf { it.isNotBlank() } ?: game.broadcasts.take(2).joinToString("  ·  ").ifBlank { game.league.name },
                    style = MaterialTheme.typography.labelSmall, color = if (focused) Edge.Text else Edge.Faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(Modifier.width(210.dp), horizontalAlignment = Alignment.End) {
                Text(
                    channel?.name ?: "Check provider",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (channel != null) Edge.Accent2 else Edge.Faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                Text(
                    when {
                        channel == null -> "Check provider"
                        direct -> "In your lineup"
                        else -> "Best available"
                    },
                    style = MaterialTheme.typography.labelSmall, color = Edge.Faint,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun SportRaceRow(
    race: Racing.Race,
    channel: ChannelEntity?,
    use24h: Boolean,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = EdgeShapes.Card,
        focusScale = 1.005f,
        glow = false,
        memoryKey = "timeline:race|${race.id}",
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(96.dp)) {
                Text(
                    Format.time(race.startMs, use24h),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Edge.Gold, maxLines = 1,
                )
                Text(
                    "Race ${race.number}", style = MaterialTheme.typography.labelSmall, color = Edge.Faint, maxLines = 1,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    race.venue, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    race.category.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall, color = if (focused) Edge.Text else Edge.Faint,
                    maxLines = 1,
                )
            }
            Column(Modifier.width(210.dp), horizontalAlignment = Alignment.End) {
                Text(
                    channel?.name ?: "Sky Racing",
                    style = MaterialTheme.typography.labelLarge, color = if (channel != null) Edge.Accent2 else Edge.Faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                Text(
                    "Race centre", style = MaterialTheme.typography.labelSmall, color = Edge.Faint, maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun RaceDialog(
    race: Racing.Race,
    channel: ChannelEntity?,
    use24h: Boolean,
    onWatch: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val sorted = remember(race.id) { race.runners.sortedBy { it.number ?: 999 } }
    LaunchedEffect(Unit) { delay(180); runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth(0.92f).widthIn(max = 780.dp).glass(EdgeShapes.Panel, Edge.Solid.copy(alpha = 0.96f)).padding(26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(race.category.uppercase(), color = Edge.Accent)
                Spacer(Modifier.width(10.dp))
                Text("${race.venue} R${race.number}", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(10.dp))
                Text(Format.time(race.startMs, use24h), style = MaterialTheme.typography.labelMedium, color = Edge.Dim)
            }
            Text(
                "${race.booksQuoting} books · ${race.runners.size} runners · D-pad to scroll",
                style = MaterialTheme.typography.labelMedium,
                color = Edge.Faint,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (channel != null && onWatch != null) {
                Spacer(Modifier.height(14.dp))
                EdgeButton(
                    "Watch ${channel.name}",
                    onWatch,
                    Modifier.fillMaxWidth().focusRequester(focus),
                    primary = true,
                    icon = Icons.Rounded.PlayArrow,
                )
            } else {
                Text(
                    "No Sky Racing channel in your lineup",
                    style = MaterialTheme.typography.labelMedium,
                    color = Edge.Faint,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(top = 14.dp)) {
                itemsIndexed(sorted, key = { _, runner -> "${runner.number}-${runner.name}" }) { index, runner ->
                    RunnerRow(runner, if (index == 0 && channel == null) focus else null)
                }
            }
            EdgeButton(
                "Close",
                onClose,
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RunnerRow(runner: Racing.Runner, firstFocus: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) Edge.PanelHi else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "#${runner.number ?: "-"}",
            style = MaterialTheme.typography.labelSmall,
            color = Edge.Faint,
            modifier = Modifier.width(38.dp),
        )
        Text(runner.name, style = MaterialTheme.typography.bodyLarge, color = Edge.Text, modifier = Modifier.weight(1f))
        Text(
            runner.best?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Edge.Text,
        )
    }
}

private fun margin(game: Sports.Game): Int {
    val away = game.away.score?.toIntOrNull() ?: 0
    val home = game.home.score?.toIntOrNull() ?: 0
    return abs(home - away)
}

/** Only replay catch-up when the channel's EPG actually names one of the teams, or the channel
 * itself is a team-specific event feed. A network carrying something else must not be replayed. */
internal fun raceChannel(
    race: Racing.Race,
    channels: List<ChannelEntity>,
    nowNext: Map<String, Pair<ProgrammeEntity?, ProgrammeEntity?>>,
): ChannelEntity? {
    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    val candidates = channels.filter {
        val n = norm(it.name)
        n.contains("skyracing") || n.contains("skysportsracing")
    }
    val pool = candidates.filter {
        it.name.startsWith("AU", true) || it.groupName.contains("AU", true) ||
            it.groupName.contains("Australia", true)
    }
    if (pool.isEmpty()) return null
    val venue = race.venue.lowercase()
    pool.firstOrNull { channel ->
        channel.epgId?.let { nowNext[it]?.first?.title?.lowercase()?.contains(venue) } == true
    }?.let { return it }
    fun pick(n: Int) = pool.firstOrNull { norm(it.name).contains("skyracing$n") }
    return when (race.category.lowercase()) {
        "horse" -> pick(1) ?: pick(2) ?: pick(3)
        "greyhound" -> pick(2) ?: pick(1) ?: pick(3)
        "harness" -> pick(3) ?: pick(2) ?: pick(1)
        else -> pick(1) ?: pick(2) ?: pick(3)
    }
}
