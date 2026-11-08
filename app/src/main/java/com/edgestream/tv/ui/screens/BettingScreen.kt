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
import androidx.compose.material.icons.rounded.QueryStats
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
import com.edgestream.tv.data.Racing
import com.edgestream.tv.data.Sports
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.FocusMemory
import com.edgestream.tv.ui.components.FocusDimGroup
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.FilterChipText
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.glass
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Betting board: live and upcoming odds from the sports scoreboard, plus the next Australian
 * and New Zealand races with the best price per runner. Data only — no wagers are placed here.
 */
@Composable
fun BettingScreen(vm: AppViewModel) {
    val clock by vm.clock.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val lookup by vm.sportsLookup.collectAsStateWithLifecycle()
    // One shared live feed (the view model's), not a loop per screen.
    val games by vm.sportsGames.collectAsStateWithLifecycle()
    val races by vm.sportsFeed.races.collectAsStateWithLifecycle()
    val loaded by vm.sportsFeed.loaded.collectAsStateWithLifecycle()
    val loading = !loaded
    var marketMode by remember { mutableStateOf("all") }
    var gameSheet by remember { mutableStateOf<Sports.Game?>(null) }
    var raceSheet by remember { mutableStateOf<Racing.Race?>(null) }
    val firstRow = remember { FocusRequester() }


    val live = if (marketMode == "all" || marketMode == "live") games.filter { it.state == Sports.State.LIVE } else emptyList()
    val upcoming = if (marketMode == "all" || marketMode == "next") games.filter { it.state == Sports.State.UPCOMING }.sortedBy { it.startMs }.take(10) else emptyList()
    val sortedRaces = if (marketMode == "all" || marketMode == "racing") races.sortedBy { it.startMs } else emptyList()

    LaunchedEffect(loading) {
        if (!loading && FocusMemory.last("betting:row") == null) {
            delay(120)
            runCatching { firstRow.requestFocus() }
        }
    }

    FocusDimGroup {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 18.dp, end = 42.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BetStat("LIVE", "${live.size}", "in play", Edge.Live, Modifier.weight(1f))
                BetStat("NEXT", "${upcoming.size}", "pre-match", Edge.Accent, Modifier.weight(1f))
                BetStat("RACING", "${sortedRaces.size}", "next to go", Edge.Gold, Modifier.weight(1f))
                BetStat("BOOKS", "${sortedRaces.sumOf { it.booksQuoting }}", "quoting", Edge.Accent2, Modifier.weight(1f))
            }
        }
        item {
            FocusDimGroup {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 64.dp),
                ) {
                    item(key = "__all") { FilterChipText("All markets", marketMode == "all", onClick = { marketMode = "all" }) }
                    item(key = "__live") { FilterChipText("Live", marketMode == "live", onClick = { marketMode = "live" }) }
                    item(key = "__next") { FilterChipText("Pre-match", marketMode == "next", onClick = { marketMode = "next" }) }
                    item(key = "__racing") { FilterChipText("Racing", marketMode == "racing", onClick = { marketMode = "racing" }) }
                }
            }
        }

        if (loading && games.isEmpty() && races.isEmpty()) {
            item { Text("Loading markets…", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, modifier = Modifier.padding(top = 18.dp)) }
        }
        if (live.isNotEmpty()) {
            item { BetSection("LIVE MARKETS", "in-play prices from the scoreboard") }
            itemsIndexed(live, key = { _, g -> "bet-live:${g.id}" }) { i, g ->
                BetGameRow(g, settings.use24h, if (i == 0) firstRow else null, "betting:row|game:${g.id}") { gameSheet = g }
            }
        }
        if (upcoming.isNotEmpty()) {
            item { BetSection("UP NEXT", "pre-match lines") }
            itemsIndexed(upcoming, key = { _, g -> "bet-next:${g.id}" }) { i, g ->
                BetGameRow(g, settings.use24h, if (i == 0 && live.isEmpty()) firstRow else null, "betting:row|game:${g.id}") { gameSheet = g }
            }
        }
        if (sortedRaces.isNotEmpty()) {
            item { BetSection("RACING · BEST PRICES", "PuntersEdge · AU/NZ") }
            itemsIndexed(sortedRaces, key = { _, r -> "bet-race:${r.id}" }) { i, r ->
                BetRaceRow(
                    r, clock, settings.use24h, raceChannel(r, channels, nowNext),
                    if (i == 0 && live.isEmpty() && upcoming.isEmpty()) firstRow else null,
                    "betting:row|race:${r.id}",
                ) { raceSheet = r }
            }
        }
        if (!loading && games.isEmpty() && sortedRaces.isEmpty()) {
            item { Text("No markets right now.", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim) }
        }
        item {
            Text(
                "Odds are informational only. EdgeStream does not accept or place wagers.",
                style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
    }

    gameSheet?.let { g ->
        BetGameSheet(
            game = g,
            channel = lookup.find(g),
            use24h = settings.use24h,
            onWatch = { ch -> vm.playChannel(ch, vm.zapListFor(ch)); gameSheet = null },
            onClose = { gameSheet = null },
        )
    }
    raceSheet?.let { r ->
        BetRaceSheet(
            race = r,
            channel = raceChannel(r, channels, nowNext),
            use24h = settings.use24h,
            onWatch = { ch -> vm.playChannel(ch, vm.zapListFor(ch)); raceSheet = null },
            onClose = { raceSheet = null },
        )
    }
}

@Composable
private fun BetSection(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(14.dp))
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Edge.Faint)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Edge.Line))
    }
}

@Composable
private fun BetStat(label: String, value: String, caption: String, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.height(96.dp).glass(RoundedCornerShape(16.dp), Edge.Panel).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(3.dp, 38.dp).clip(RoundedCornerShape(50)).background(accent))
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = Edge.Faint)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = Edge.Dim)
        }
    }
}

@Composable
private fun BetMini(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp), color = Edge.Faint, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BetGameRow(game: Sports.Game, use24h: Boolean, focusRequester: FocusRequester? = null, memoryKey: String? = null, onClick: () -> Unit) {
    val line = game.line
    FocusSurface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier), shape = EdgeShapes.Card,
        focusScale = 1.01f, glow = false, memoryKey = memoryKey,
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(game.league.name.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (focused) Edge.Text else Edge.Faint)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (game.state == Sports.State.LIVE) game.detail.uppercase().ifBlank { "LIVE" }
                        else if (game.state == Sports.State.FINAL) "FINAL" else Format.time(game.startMs, use24h),
                        style = MaterialTheme.typography.labelSmall, color = if (game.state == Sports.State.LIVE) Edge.Live else Edge.Dim,
                    )
                    line?.provider?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.width(10.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = Edge.Faint)
                    }
                }
                Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${game.away.abbr}  ${game.away.score ?: "–"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("   @   ", style = MaterialTheme.typography.labelMedium, color = Edge.Faint)
                    Text("${game.home.abbr}  ${game.home.score ?: "–"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (line != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        BetMini("SPREAD", line.details.ifBlank { "—" }, Modifier.weight(1.2f))
                        BetMini("TOTAL", line.overUnder?.let { trimNumber(it) } ?: "—", Modifier.weight(0.8f))
                        BetMini("AWAY ML", line.awayMoneyLine?.let { money(it) } ?: "—", Modifier.weight(0.8f))
                        BetMini("HOME ML", line.homeMoneyLine?.let { money(it) } ?: "—", Modifier.weight(0.8f))
                    }
                } else {
                    Text(
                        "No line published yet", style = MaterialTheme.typography.labelMedium, color = Edge.Faint,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Pill(if (game.state == Sports.State.LIVE) "LIVE" else "OPEN", color = if (game.state == Sports.State.LIVE) Edge.Live else Edge.Accent)
        }
    }
}

@Composable
private fun BetRaceRow(
    race: Racing.Race,
    clock: Long,
    use24h: Boolean,
    channel: ChannelEntity?,
    focusRequester: FocusRequester? = null,
    memoryKey: String? = null,
    onClick: () -> Unit,
) {
    val mins = ((race.startMs - clock) / 60_000L).coerceAtLeast(0)
    val top = race.runners.filter { it.best != null }.sortedBy { it.best ?: Double.MAX_VALUE }.take(3)
    FocusSurface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier), shape = EdgeShapes.Card,
        focusScale = 1.01f, glow = false, memoryKey = memoryKey,
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(race.category.uppercase(), color = Edge.Accent)
                    Spacer(Modifier.width(10.dp))
                    Text("${race.venue} R${race.number}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (mins <= 120) "in $mins min" else Format.time(race.startMs, use24h),
                        style = MaterialTheme.typography.labelSmall, color = if (mins <= 10) Edge.Live else Edge.Dim,
                    )
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    top.forEach { r ->
                        Column {
                            Text(
                                "#${r.number ?: "-"}  ${r.name}", style = MaterialTheme.typography.labelMedium,
                                color = if (focused) Edge.Text else Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(210.dp),
                            )
                            Text(
                                r.best?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Text(
                    "${race.booksQuoting} books · ${race.runners.size} runners" + (channel?.let { "  ·  ${it.name}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = if (channel != null) Edge.Accent else Edge.Faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Pill("BEST", color = Edge.Gold)
        }
    }
}

@Composable
private fun BetGameSheet(
    game: Sports.Game,
    channel: ChannelEntity?,
    use24h: Boolean,
    onWatch: (ChannelEntity) -> Unit,
    onClose: () -> Unit,
) {
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
            Text(
                "${game.away.name} at ${game.home.name}",
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "${game.away.abbr} ${game.away.score ?: "–"}   –   ${game.home.abbr} ${game.home.score ?: "–"}",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(16.dp))
            if (line != null) {
                OddsRow("Line", line.details.ifBlank { "—" })
                line.overUnder?.let { OddsRow("Over / under", trimNumber(it)) }
                line.awayMoneyLine?.let { OddsRow("${game.away.abbr} moneyline", money(it)) }
                line.homeMoneyLine?.let { OddsRow("${game.home.abbr} moneyline", money(it)) }
                OddsRow("Book", line.provider.ifBlank { "Market" })
            } else {
                Text("No line published for this game yet.", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim)
            }
            Text(
                channel?.let { "On ${it.name}" } ?: "Check provider lineup",
                style = MaterialTheme.typography.labelMedium, color = if (channel != null) Edge.Accent else Edge.Faint,
                modifier = Modifier.padding(top = 14.dp),
            )
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (channel != null) {
                    EdgeButton("Watch ${channel.name}", { onWatch(channel) }, Modifier.fillMaxWidth().focusRequester(focus), primary = true, icon = Icons.Rounded.PlayArrow)
                }
                EdgeButton("Close", onClose, Modifier.fillMaxWidth().then(if (channel == null) Modifier.focusRequester(focus) else Modifier))
            }
        }
    }
}

@Composable
private fun OddsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BetRaceSheet(
    race: Racing.Race,
    channel: ChannelEntity?,
    use24h: Boolean,
    onWatch: (ChannelEntity) -> Unit,
    onClose: () -> Unit,
) {
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
            Text(
                "${race.booksQuoting} books · ${race.runners.size} runners · best price per runner",
                style = MaterialTheme.typography.labelMedium, color = Edge.Faint, modifier = Modifier.padding(top = 4.dp),
            )
            if (channel != null) {
                EdgeButton(
                    "Watch ${channel.name}", { onWatch(channel) },
                    Modifier.fillMaxWidth().padding(top = 14.dp).focusRequester(focus), primary = true, icon = Icons.Rounded.PlayArrow,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(top = 14.dp)) {
                itemsIndexed(sorted, key = { _, r -> "${r.number}-${r.name}" }) { i, r ->
                    BetRunnerRow(r, if (i == 0 && channel == null) focus else null)
                }
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
        Modifier.fillMaxWidth()
            .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) Edge.PanelHi else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#${runner.number ?: "-"}", style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.width(38.dp))
        Text(runner.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        bestBook?.let {
            Text(
                it.key.replaceFirstChar { c -> c.uppercase() },
                style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.padding(end = 12.dp),
            )
        }
        Text(
            runner.best?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        )
    }
}

private fun trimNumber(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

private fun money(v: Int): String = if (v > 0) "+$v" else "$v"
