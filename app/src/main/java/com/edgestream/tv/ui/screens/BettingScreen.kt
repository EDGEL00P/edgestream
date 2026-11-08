package com.edgestream.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgestream.tv.data.NflPicks
import com.edgestream.tv.data.NflProps
import com.edgestream.tv.data.Racing
import com.edgestream.tv.data.Sports
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.FilterChipText
import com.edgestream.tv.ui.components.FocusDimGroup
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.components.TeamLogo
import com.edgestream.tv.ui.glass
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Odds board: sports markets in the same slate+detail presentation as NFL Picks, plus racing.
 * Data only — no wagers are placed here.
 */
@Composable
fun BettingScreen(vm: AppViewModel) {
    val clock by vm.clock.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val lookup by vm.sportsLookup.collectAsStateWithLifecycle()
    val games by vm.sportsGames.collectAsStateWithLifecycle()
    val races by vm.sportsFeed.races.collectAsStateWithLifecycle()
    val loaded by vm.sportsFeed.loaded.collectAsStateWithLifecycle()
    val nfl by vm.nflBoard.collectAsStateWithLifecycle()
    val nflFocus by vm.nflFocus.collectAsStateWithLifecycle()
    var marketMode by remember { mutableStateOf(if (nflFocus != null) "nfl" else "all") }
    var gameSheet by remember { mutableStateOf<Sports.Game?>(null) }
    var raceSheet by remember { mutableStateOf<Racing.Race?>(null) }

    val live = games.filter { it.state == Sports.State.LIVE }
    val upcoming = games.filter { it.state == Sports.State.UPCOMING }.sortedBy { it.startMs }
    val sportsGames = when (marketMode) {
        "live" -> live
        "next" -> upcoming
        else -> live + upcoming.take(12)
    }
    val sortedRaces = races.sortedBy { it.startMs }
    val nflPicks = nfl?.picks.orEmpty()

    val marketChips: @Composable () -> Unit = {
        FocusDimGroup {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 64.dp)) {
                item(key = "__all") { FilterChipText("All Markets", marketMode == "all", onClick = { marketMode = "all" }) }
                item(key = "__live") { FilterChipText("Live", marketMode == "live", onClick = { marketMode = "live" }) }
                item(key = "__next") { FilterChipText("Pre-Match", marketMode == "next", onClick = { marketMode = "next" }) }
                item(key = "__racing") { FilterChipText("Racing", marketMode == "racing", onClick = { marketMode = "racing" }) }
                item(key = "__nfl") { FilterChipText("NFL", marketMode == "nfl", onClick = { marketMode = "nfl" }) }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 18.dp, end = 42.dp, bottom = 26.dp)) {
        marketChips()
        Spacer(Modifier.height(12.dp))
        when (marketMode) {
            "nfl" -> NflHub(nfl, settings.use24h, onOpen = { gameSheet = it }, modifier = Modifier.weight(1f),
                startAt = nflFocus, onStarted = { vm.nflFocus.value = null })
            "racing" -> RacingHub(sortedRaces, channels, nowNext, clock, settings.use24h, loaded, Modifier.weight(1f)) { raceSheet = it }
            else -> {
                if (!loaded && sportsGames.isEmpty()) {
                    Text("Loading markets", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, modifier = Modifier.padding(top = 18.dp))
                } else if (sportsGames.isEmpty()) {
                    Column(Modifier.weight(1f)) {
                        Text("No markets right now", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, modifier = Modifier.padding(top = 18.dp))
                        if (marketMode == "all" && sortedRaces.isNotEmpty()) RacingHub(sortedRaces, channels, nowNext, clock, settings.use24h, loaded, Modifier.weight(1f).padding(top = 12.dp)) { raceSheet = it }
                    }
                } else {
                    SportsOddsHub(sportsGames, settings.use24h, onOpen = { gameSheet = it }, modifier = Modifier.weight(1f))
                    if (marketMode == "all" && sortedRaces.isNotEmpty()) {
                        RacingStrip(sortedRaces.take(4), clock, settings.use24h, Modifier.padding(top = 10.dp)) { marketMode = "racing" }
                    }
                }
            }
        }
        Text("Odds are informational only. EdgeStream does not accept or place wagers.",
            style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.padding(top = 8.dp))
    }

    gameSheet?.let { g ->
        BetGameSheet(g, lookup.find(g), settings.use24h, nflPicks.firstOrNull { it.game.id == g.id },
            nfl?.props.orEmpty().filter { "nfl:${it.espnGame}" == g.id }.take(6),
            onWatch = { ch -> vm.playChannel(ch, vm.zapListFor(ch)); gameSheet = null }, onClose = { gameSheet = null })
    }
    raceSheet?.let { r ->
        BetRaceSheet(r, raceChannel(r, channels, nowNext), settings.use24h,
            onWatch = { ch -> vm.playChannel(ch, vm.zapListFor(ch)); raceSheet = null }, onClose = { raceSheet = null })
    }
}

/** Sports markets in NFL Picks style: slate left, matchup detail right. */
@Composable
private fun SportsOddsHub(
    games: List<Sports.Game>,
    use24h: Boolean,
    onOpen: (Sports.Game) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedId by remember(games) { mutableStateOf(games.firstOrNull()?.id) }
    val selected = games.firstOrNull { it.id == selectedId } ?: games.firstOrNull() ?: return
    Row(modifier.fillMaxSize()) {
        Box(Modifier.width(330.dp).fillMaxHeight().background(Edge.Solid, EdgeShapes.Card)) {
            FocusDimGroup {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(games, key = { it.id }) { g ->
                        GameSlateRow(g, use24h, selected = g.id == selected.id, onFocus = { selectedId = g.id }, onClick = { onOpen(g) })
                    }
                }
            }
        }
        Spacer(Modifier.width(28.dp))
        GameMatchupPane(selected, use24h, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun GameSlateRow(g: Sports.Game, use24h: Boolean, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    val live = g.state == Sports.State.LIVE
    FocusSurface(onClick = onClick, selected = selected, focusScale = 1.02f, glow = false, memoryKey = "odds:game|${g.id}",
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(g.away.logo, g.away.name, size = 26.dp)
            Text(" @ ", style = MaterialTheme.typography.labelSmall, color = Edge.Faint)
            TeamLogo(g.home.logo, g.home.name, size = 26.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("${g.away.abbr} @ ${g.home.abbr}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    when { live -> g.detail.uppercase().ifBlank { "LIVE" }; g.state == Sports.State.FINAL -> "Final"; else -> Format.time(g.startMs, use24h) } +
                        g.line?.details?.takeIf { it.isNotBlank() }?.let { "  ·  $it" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall, color = if (live) Edge.Live else if (focused || selected) Edge.Dim else Edge.Faint, maxLines = 1,
                )
            }
            when {
                live -> Pill("LIVE", color = Edge.Live)
                g.line != null -> Pill("LINE", color = Edge.Accent)
                else -> Text(g.league.name, style = MaterialTheme.typography.labelSmall, color = Edge.Faint, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GameMatchupPane(g: Sports.Game, use24h: Boolean, modifier: Modifier) {
    val line = g.line
    val live = g.state == Sports.State.LIVE
    LaunchedEffect(g.id) { com.edgestream.tv.ui.components.RoomLight.of(g) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.edgestream.tv.ui.components.RoomLight.clear() } }
    Column(modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(g.away.logo, g.away.name, size = 52.dp)
            Column(Modifier.padding(horizontal = 18.dp).weight(1f)) {
                Text("${g.away.name}  at  ${g.home.name}", style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(g.league.name, if (live) "Live" else null, g.venue, g.broadcasts.firstOrNull()).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium, color = Edge.Dim, maxLines = 1,
                )
            }
            TeamLogo(g.home.logo, g.home.name, size = 52.dp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
            Column {
                Text(if (live) "SCORE" else if (g.state == Sports.State.FINAL) "FINAL" else "START", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = Edge.Faint)
                if (g.away.score != null || g.home.score != null) {
                    Text("${g.away.abbr} ${g.away.score ?: "–"}  ·  ${g.home.abbr} ${g.home.score ?: "–"}", style = MaterialTheme.typography.headlineSmall)
                } else {
                    Text(Format.time(g.startMs, use24h), style = MaterialTheme.typography.headlineSmall)
                }
                Text(if (live) g.detail.ifBlank { "In progress" } else if (g.state == Sports.State.FINAL) g.detail.ifBlank { "Final" } else "Scheduled",
                    style = MaterialTheme.typography.labelMedium, color = if (live) Edge.Live else Edge.Dim)
            }
            Spacer(Modifier.weight(1f))
            line?.provider?.takeIf { it.isNotBlank() }?.let { Pill(it, color = Edge.Accent2) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Edge.Line))
        if (line != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                MarketStat("SPREAD", line.details.ifBlank { "—" }, Modifier.weight(1f))
                MarketStat("TOTAL", line.overUnder?.let { trimNumber(it) } ?: "—", Modifier.weight(1f))
                MarketStat("${g.away.abbr} ML", line.awayMoneyLine?.let { money(it) } ?: "—", Modifier.weight(1f))
                MarketStat("${g.home.abbr} ML", line.homeMoneyLine?.let { money(it) } ?: "—", Modifier.weight(1f))
            }
        } else {
            Text("No line published for this matchup yet.", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim)
        }
        Text("Select for details and viewing options.", style = MaterialTheme.typography.labelSmall, color = Edge.Faint)
    }
}

@Composable private fun MarketStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = Edge.Faint, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RacingHub(races: List<Racing.Race>, channels: List<ChannelEntity>, nowNext: Map<String, Pair<com.edgestream.tv.data.db.ProgrammeEntity?, com.edgestream.tv.data.db.ProgrammeEntity?>>, clock: Long, use24h: Boolean, loaded: Boolean, modifier: Modifier, onOpen: (Racing.Race) -> Unit) {
    if (!loaded && races.isEmpty()) { Text("Loading markets", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, modifier = modifier.padding(top = 18.dp)); return }
    if (races.isEmpty()) { Text("No races right now", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, modifier = modifier.padding(top = 18.dp)); return }
    FocusDimGroup {
        LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(races, key = { _, r -> "race:${r.id}" }) { i, r ->
                BetRaceRow(r, clock, use24h, raceChannel(r, channels, nowNext), if (i == 0) remember { FocusRequester() } else null, "odds:race:${r.id}") { onOpen(r) }
            }
        }
    }
}

@Composable
private fun RacingStrip(races: List<Racing.Race>, clock: Long, use24h: Boolean, modifier: Modifier, onOpenRacing: () -> Unit) {
    FocusSurface(onClick = onOpenRacing, modifier = modifier.fillMaxWidth(), focusScale = 1.01f, glow = false) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("RACING", color = Edge.Gold)
            Spacer(Modifier.width(12.dp))
            Text(races.joinToString("  ·  ") { "${it.venue} R${it.number} ${Format.time(it.startMs, use24h)}" }, style = MaterialTheme.typography.labelLarge, color = if (focused) Edge.Text else Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("Open", style = MaterialTheme.typography.labelMedium, color = Edge.Accent)
        }
    }
}

@Composable
private fun BetRaceRow(race: Racing.Race, clock: Long, use24h: Boolean, channel: ChannelEntity?, focusRequester: FocusRequester? = null, memoryKey: String? = null, onClick: () -> Unit) {
    val mins = ((race.startMs - clock) / 60_000L).coerceAtLeast(0)
    val top = race.runners.filter { it.best != null }.sortedBy { it.best ?: Double.MAX_VALUE }.take(3)
    FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier), shape = EdgeShapes.Card, focusScale = 1.01f, glow = false, memoryKey = memoryKey) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(race.category.uppercase(), color = Edge.Accent)
                    Spacer(Modifier.width(10.dp))
                    Text("${race.venue} R${race.number}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(10.dp))
                    Text(if (mins <= 120) "in $mins min" else Format.time(race.startMs, use24h), style = MaterialTheme.typography.labelSmall, color = if (mins <= 10) Edge.Live else Edge.Dim)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    top.forEach { r -> Column {
                        Text("#${r.number ?: "-"}  ${r.name}", style = MaterialTheme.typography.labelMedium, color = if (focused) Edge.Text else Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(210.dp))
                        Text(r.best?.let { String.format(Locale.US, "%.2f", it) } ?: "—", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    } }
                }
                Text("${race.booksQuoting} books · ${race.runners.size} runners" + (channel?.let { "  ·  ${it.name}" } ?: ""), style = MaterialTheme.typography.labelSmall, color = if (channel != null) Edge.Accent else Edge.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            Pill("BEST", color = Edge.Gold)
        }
    }
}

@Composable
private fun BetGameSheet(game: Sports.Game, channel: ChannelEntity?, use24h: Boolean, pick: NflPicks.Pick?, props: List<NflProps.Lean>, onWatch: (ChannelEntity) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(game.id) { delay(100); runCatching { focus.requestFocus() } }
    val line = game.line
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth(0.92f).widthIn(max = 760.dp).glass(EdgeShapes.Panel, Edge.Solid.copy(alpha = 0.96f)).padding(26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(if (game.state == Sports.State.LIVE) "LIVE" else "MARKET", color = if (game.state == Sports.State.LIVE) Edge.Live else Edge.Accent)
                Spacer(Modifier.width(10.dp))
                Text(game.league.name.uppercase(), style = MaterialTheme.typography.labelSmall, color = Edge.Dim)
                Spacer(Modifier.weight(1f))
                Text(if (game.state == Sports.State.UPCOMING) Format.time(game.startMs, use24h) else game.detail, style = MaterialTheme.typography.labelSmall, color = Edge.Faint)
            }
            Text("${game.away.name} at ${game.home.name}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
            Text("${game.away.abbr} ${game.away.score ?: "–"}   ·   ${game.home.abbr} ${game.home.score ?: "–"}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(16.dp))
            if (line != null) {
                OddsRow("Spread", line.details.ifBlank { "—" })
                line.overUnder?.let { OddsRow("Total", trimNumber(it)) }
                line.awayMoneyLine?.let { OddsRow("${game.away.abbr} moneyline", money(it)) }
                line.homeMoneyLine?.let { OddsRow("${game.home.abbr} moneyline", money(it)) }
                OddsRow("Source", line.provider.ifBlank { "Market" })
            } else { Text("No line published for this matchup yet.", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim) }
            if (pick != null) {
                Spacer(Modifier.height(10.dp))
                OddsRow("Pick", "${pick.winner} · ${pct(pick.winProb)}" + (pick.tier?.let { "  ·  ${it.label}" } ?: ""))
                OddsRow("Model", NflPicks.lineText(pick.modelHomeMargin, game.home.abbr, game.away.abbr))
                pick.marketHomeProb?.let { OddsRow("Market (no vig)", "${game.home.abbr} ${pct(it)}") }
                pick.values.forEach { v -> OddsRow(v.rule.label, v.selection + (v.windMph?.let { "  ·  wind $it mph" } ?: "") + if (v.modelAgrees) "  ·  model agrees" else "") }
            }
            props.forEach { l -> OddsRow("Prop · ${pct(l.confidence)}", l.selection) }
            Text(channel?.let { "Available on ${it.name}" } ?: "Check your lineup for coverage", style = MaterialTheme.typography.labelMedium, color = if (channel != null) Edge.Accent else Edge.Faint, modifier = Modifier.padding(top = 14.dp))
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (channel != null) EdgeButton("Watch ${channel.name}", { onWatch(channel) }, Modifier.fillMaxWidth().focusRequester(focus), primary = true, icon = Icons.Rounded.PlayArrow)
                EdgeButton("Close", onClose, Modifier.fillMaxWidth().then(if (channel == null) Modifier.focusRequester(focus) else Modifier))
            }
        }
    }
}

@Composable private fun OddsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BetRaceSheet(race: Racing.Race, channel: ChannelEntity?, use24h: Boolean, onWatch: (ChannelEntity) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val sorted = remember(race.id) { race.runners.sortedBy { it.number ?: 999 } }
    LaunchedEffect(race.id) { delay(140); runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth(0.92f).widthIn(max = 800.dp).glass(EdgeShapes.Panel, Edge.Solid.copy(alpha = 0.96f)).padding(26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(race.category.uppercase(), color = Edge.Accent)
                Spacer(Modifier.width(10.dp))
                Text("${race.venue} R${race.number}", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(10.dp))
                Text(Format.time(race.startMs, use24h), style = MaterialTheme.typography.labelMedium, color = Edge.Dim)
            }
            Text("${race.booksQuoting} books · ${race.runners.size} runners · best price per runner", style = MaterialTheme.typography.labelMedium, color = Edge.Faint, modifier = Modifier.padding(top = 4.dp))
            if (channel != null) EdgeButton("Watch ${channel.name}", { onWatch(channel) }, Modifier.fillMaxWidth().padding(top = 14.dp).focusRequester(focus), primary = true, icon = Icons.Rounded.PlayArrow)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(top = 14.dp)) {
                itemsIndexed(sorted, key = { _, r -> "${r.number}-${r.name}" }) { i, r -> BetRunnerRow(r, if (i == 0 && channel == null) focus else null) }
            }
            EdgeButton("Close", onClose, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}

@Composable
private fun BetRunnerRow(runner: Racing.Runner, firstFocus: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    val bestBook = runner.books.maxByOrNull { it.price }
    Row(
        Modifier.fillMaxWidth().then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
            .onFocusChanged { focused = it.isFocused }.focusable()
            .background(if (focused) Edge.PanelHi else Color.Transparent).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#${runner.number ?: "-"}", style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.width(38.dp))
        Text(runner.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        bestBook?.let { Text(it.key.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.padding(end = 12.dp)) }
        Text(runner.best?.let { String.format(Locale.US, "%.2f", it) } ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

private fun pct(p: Double): String = "${(p * 100).roundToInt()}%"
private fun trimNumber(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
private fun money(v: Int): String = if (v > 0) "+$v" else "$v"
