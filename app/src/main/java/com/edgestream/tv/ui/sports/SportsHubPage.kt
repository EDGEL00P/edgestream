package com.edgestream.tv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgestream.tv.data.Racing
import com.edgestream.tv.data.Sports
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.LocalScrollToTop
import com.edgestream.tv.ui.components.TeamLogo
import com.edgestream.tv.ui.components.requestFocusWithRetry
import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA
import com.edgestream.tv.ui.input.hoverFocus
import com.edgestream.tv.ui.input.remoteClickable
import com.edgestream.tv.ui.screens.RaceDialog
import com.edgestream.tv.ui.screens.raceChannel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Dim get() = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA)

/**
 * Sports (the user's Sports Hub concept, real data only): a featured scoreboard for the focused
 * game, sport chips, then a grid of game cards. OK plays the channel carrying the game; Racing
 * lists next-to-go races and opens the race card (horses → Sky Racing 1, dogs → 2, harness → 3).
 */
@Composable
fun SportsHubPage(vm: AppViewModel) {
    val games by vm.sportsGames.collectAsStateWithLifecycle()
    val races by vm.sportsFeed.races.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val chips = remember(games, races) { sportChips(games, races.isNotEmpty()) }
    var chip by rememberSaveable { mutableStateOf(ALL_SPORTS) }
    if (chip !in chips) chip = ALL_SPORTS
    val shown = remember(games, chip) { if (chip == RACING) emptyList() else gamesFor(games, chip) }
    var focusedGame by remember { mutableStateOf<Sports.Game?>(null) }
    val featured = focusedGame?.takeIf { f -> shown.any { it.id == f.id } } ?: featuredGame(shown)
    var openRace by remember { mutableStateOf<Racing.Race?>(null) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }

    val scrollToTop = LocalScrollToTop.current
    val toTop: suspend () -> Unit = remember { { gridState.scrollToItem(0); firstFocus.requestFocusWithRetry() } }
    DisposableEffect(scrollToTop, toTop) {
        scrollToTop.register(atTop = { gridState.firstVisibleItemIndex == 0 }, scrollToTop = toTop)
        onDispose { scrollToTop.unregister(toTop) }
    }
    LaunchedEffect(shown.isNotEmpty() || races.isNotEmpty()) { firstFocus.requestFocusWithRetry() }

    val lookup by vm.sportsLookup.collectAsStateWithLifecycle()

    fun watch(g: Sports.Game) {
        val ch = lookup.find(g)
        if (ch != null) vm.playChannel(ch, vm.zapListFor(ch)) else vm.showNotice("No channel in your lineup is carrying ${g.away.abbr} v ${g.home.abbr}")
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Scoreboard(featured, featured?.let { lookup.find(it)?.name }, settings.use24h, Modifier.fillMaxWidth().height(150.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips, key = { it }) { c ->
                Chip(c, selected = c == chip) { chip = c; focusedGame = null; scope.launch { gridState.scrollToItem(0) } }
            }
        }
        if (chip == RACING) {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), state = gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                items(races, key = { it.id }) { r ->
                    RaceTile(r, settings.use24h, if (r.id == races.first().id) firstFocus else null) { openRace = r }
                }
            }
        } else if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No games right now", color = Dim, fontSize = 17.sp) }
        } else {
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), state = gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                items(shown, key = { it.id }) { g ->
                    GameTile(g, settings.use24h, if (g.id == shown.first().id) firstFocus else null, onFocus = { focusedGame = g }) { watch(g) }
                }
            }
        }
    }
    openRace?.let { r ->
        val ch = raceChannel(r, channels, nowNext)
        RaceDialog(r, ch, settings.use24h, onWatch = ch?.let { c -> { openRace = null; vm.playChannel(c, vm.zapListFor(c)) } }, onClose = { openRace = null })
    }
}

private fun status(g: Sports.Game, use24h: Boolean): String = when (g.state) {
    Sports.State.LIVE -> "LIVE · ${g.detail}"
    Sports.State.FINAL -> g.detail.ifEmpty { "Final" }.uppercase()
    Sports.State.UPCOMING -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(g.startMs)).uppercase() + " · " + Format.time(g.startMs, use24h)
}

@Composable
private fun Scoreboard(g: Sports.Game?, channel: String?, use24h: Boolean, modifier: Modifier) {
    Box(modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
        if (g == null) { Text("No games right now", color = Dim, fontSize = 17.sp, modifier = Modifier.align(Alignment.Center)); return@Box }
        Row(Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
            Text(status(g, use24h), color = if (g.state == Sports.State.LIVE) androidx.compose.ui.graphics.Color.White else Edge.Text,
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (g.state == Sports.State.LIVE) Edge.Live else Edge.Text.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 4.dp))
            Text(g.league.name.uppercase(), color = Edge.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(start = 12.dp))
        }
        Text(
            if (channel != null) "OK  Watch on $channel" else "Not on your channels",
            color = if (channel != null) Edge.OnAccent else Dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(50))
                .background(if (channel != null) Edge.Accent else Edge.Text.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 5.dp),
        )
        Row(Modifier.align(Alignment.Center).padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            BigTeam(g.away, Modifier.weight(1f), alignEnd = true)
            Text(
                if (g.state == Sports.State.UPCOMING) "vs" else "${g.away.score ?: "0"}  –  ${g.home.score ?: "0"}",
                color = Edge.Text, fontSize = if (g.state == Sports.State.UPCOMING) 24.sp else 40.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 26.dp),
            )
            BigTeam(g.home, Modifier.weight(1f), alignEnd = false)
        }
        val foot = listOfNotNull(g.venue, g.broadcasts.take(3).joinToString(" · ").ifBlank { null }, g.line?.details?.let { "Line $it" },
            g.line?.overUnder?.let { "O/U $it" }).joinToString("   ·   ")
        Text(foot, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BigTeam(t: Sports.Team, modifier: Modifier, alignEnd: Boolean) {
    Row(modifier, horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        if (!alignEnd) Box(Modifier.size(56.dp).clip(CircleShape).background(Edge.Text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) { TeamLogo(t.logo, t.name, size = 40.dp) }
        Column(Modifier.padding(horizontal = 12.dp), horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
            Text(t.name.uppercase(), color = Edge.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            t.record?.let { Text(it, color = Dim, fontSize = 13.sp) }
        }
        if (alignEnd) Box(Modifier.size(56.dp).clip(CircleShape).background(Edge.Text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) { TeamLogo(t.logo, t.name, size = 40.dp) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Text(
        label, color = if (selected || f) Edge.OnAccent else Edge.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.background(if (selected || f) Edge.Accent else Edge.Text.copy(alpha = 0.08f), shape)
            .then(if (f) Modifier.border(2.dp, Edge.Text, shape) else Modifier)
            .onFocusChanged { f = it.isFocused }.remoteClickable(onClick).padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

@Composable
private fun Tile(requester: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit, content: @Composable () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val req = requester ?: remember { FocusRequester() }
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.fillMaxWidth().height(118.dp)
            .graphicsLayer { val s = if (f) 1.04f else 1f; scaleX = s; scaleY = s }
            .background(if (f) Edge.Text.copy(alpha = 0.18f) else Edge.Text.copy(alpha = 0.08f), shape)
            .then(if (f) Modifier.border(2.dp, Edge.Text, shape) else Modifier)
            .focusRequester(req).hoverFocus(req)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .remoteClickable(onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) { content() }
}

@Composable
private fun GameTile(g: Sports.Game, use24h: Boolean, requester: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit) {
    Tile(requester, onFocus, onClick) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status gets the room; the league name gives way (it is on the chip and scoreboard too).
                Text(status(g, use24h), color = if (g.state == Sports.State.LIVE) Edge.Live else Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(g.league.name, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            TeamLine(g.away, g, other = g.home)
            TeamLine(g.home, g, other = g.away)
            Text(listOfNotNull(g.line?.details?.let { "Line $it" }, g.venue).joinToString(" · ").ifBlank { " " }, color = Dim, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TeamLine(t: Sports.Team, g: Sports.Game, other: Sports.Team) {
    val a = t.score?.toIntOrNull(); val b = other.score?.toIntOrNull()
    val beaten = g.state == Sports.State.FINAL && a != null && b != null && a < b
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(t.logo, t.name, size = 22.dp)
        Text(t.name, color = if (beaten) Dim else Edge.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f))
        if (g.state != Sports.State.UPCOMING) Text(t.score ?: "0", color = if (beaten) Dim else Edge.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RaceTile(r: Racing.Race, use24h: Boolean, requester: FocusRequester?, onClick: () -> Unit) {
    Tile(requester, onFocus = {}, onClick = onClick) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(r.category.uppercase(), color = Edge.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("R${r.number}  ${r.venue}", color = Edge.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(Format.time(r.startMs, use24h), color = Edge.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text("${r.runners.size} runners · ${r.country}", color = Dim, fontSize = 13.sp)
            }
        }
    }
}
