package com.edgestream.tv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgestream.tv.data.Sports
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.FocusSurface

private const val HOUR = 3_600_000L
private const val WINDOW_HOURS = 12
private val TRACK_HEIGHT = 46.dp
private val LABEL_WIDTH = 132.dp

/**
 * The day's sport as one timeline: a lane per league, each game a block from kick-off to its
 * typical finish, a now-line across every lane, and live games filling in their teams' colours
 * as they play. Games that overlap stack inside their lane. D-pad moves between blocks.
 */
@Composable
fun TimeRiver(
    games: List<Sports.Game>,
    now: Long,
    use24h: Boolean,
    firstFocus: FocusRequester,
    onFocus: (Sports.Game) -> Unit,
    onWatch: (Sports.Game) -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = (now - 3 * HOUR) / HOUR * HOUR
    val end = start + WINDOW_HOURS * HOUR
    val lanes = remember(games, start) { riverLanes(games, start, end) }
    val first = lanes.firstOrNull()?.tracks?.flatten()?.let { all -> all.firstOrNull { it.state == Sports.State.LIVE } ?: all.firstOrNull() }
    if (lanes.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No games in the next few hours", color = Edge.Dim, fontSize = 17.sp) }
        return
    }
    Column(modifier) {
        // Hour axis, every two hours, aligned with the lanes' tracks.
        Row(Modifier.fillMaxWidth().height(22.dp)) {
            Box(Modifier.width(LABEL_WIDTH))
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                for (h in 0..WINDOW_HOURS step 2) {
                    val x = maxWidth * (h.toFloat() / WINDOW_HOURS)
                    Text(
                        Format.time(start + h * HOUR, use24h), color = Edge.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset(x = (x - 20.dp).coerceAtLeast(0.dp)),
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(lanes, key = { it.league }) { lane ->
                Row(Modifier.fillMaxWidth().height(TRACK_HEIGHT * lane.tracks.size + 10.dp)) {
                    Text(
                        lane.league.uppercase(), color = Edge.Dim, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(LABEL_WIDTH).padding(top = 14.dp, end = 10.dp),
                    )
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val width = maxWidth
                        fun xOf(t: Long): Dp = width * ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Edge.Line.copy(alpha = 0.5f)))
                        // The now-line runs under the blocks through every lane, reading as one line.
                        Box(Modifier.offset(x = xOf(now)).width(2.dp).fillMaxHeight().background(Edge.Accent))
                        lane.tracks.forEachIndexed { row, track ->
                            track.forEach { g ->
                                val x = xOf(g.startMs)
                                val w = (xOf(g.startMs + durationOf(g)) - x).coerceAtLeast(146.dp)
                                RiverBlock(
                                    g, now,
                                    Modifier.offset(x = x.coerceAtMost(width - w), y = TRACK_HEIGHT * row + 5.dp).width(w).height(TRACK_HEIGHT - 6.dp)
                                        .then(if (g.id == first?.id) Modifier.focusRequester(firstFocus) else Modifier)
                                        .onFocusChanged { if (it.isFocused) onFocus(g) },
                                    onClick = { onWatch(g) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RiverBlock(g: Sports.Game, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val live = g.state == Sports.State.LIVE
    val done = g.state == Sports.State.FINAL
    val progress = if (live) ((now - g.startMs).toFloat() / durationOf(g)).coerceIn(0.05f, 1f) else if (done) 1f else 0f
    val a = teamColor(g.away)
    val b = teamColor(g.home)
    FocusSurface(onClick = onClick, modifier = modifier, shape = EdgeShapes.Badge, focusScale = 1.04f, glow = false, color = Edge.Text.copy(alpha = 0.06f)) { focused ->
        if (progress > 0f) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(progress)
                    .background(Brush.horizontalGradient(listOf(a.copy(alpha = if (done) 0.18f else 0.55f), b.copy(alpha = if (done) 0.12f else 0.4f)))),
            )
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                Box(Modifier.width(7.dp).height(7.dp).background(Edge.Live, androidx.compose.foundation.shape.CircleShape))
                Box(Modifier.width(7.dp))
            }
            Text(
                if (g.state == Sports.State.UPCOMING) "${g.away.abbr} @ ${g.home.abbr}"
                else "${g.away.abbr} ${g.away.score ?: "0"}–${g.home.score ?: "0"} ${g.home.abbr}",
                color = if (done && !focused) Edge.Dim else Edge.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

internal data class RiverLane(val league: String, val tracks: List<List<Sports.Game>>)

/**
 * Lanes for the window: games overlapping [start, end) grouped by league, live leagues first,
 * then by earliest kick-off. Within a lane, games that overlap in time go on separate tracks
 * (first track that is free when the game starts).
 */
internal fun riverLanes(games: List<Sports.Game>, start: Long, end: Long): List<RiverLane> =
    games.filter { it.startMs < end && it.startMs + durationOf(it) > start }
        .groupBy { it.league.name }
        .map { (league, gs) ->
            val tracks = mutableListOf<MutableList<Sports.Game>>()
            for (g in gs.sortedBy { it.startMs }) {
                val free = tracks.firstOrNull { t -> t.last().let { it.startMs + durationOf(it) <= g.startMs } }
                if (free != null) free += g else tracks += mutableListOf(g)
            }
            RiverLane(league, tracks)
        }
        .sortedWith(compareBy<RiverLane>({ lane -> if (lane.tracks.flatten().any { it.state == Sports.State.LIVE }) 0 else 1 }, { lane -> lane.tracks.flatten().minOf { it.startMs } }))

/**
 * Typical length of a game in this sport, kick-off to final whistle including breaks. Feeds
 * don't publish an end time, so blocks are this long (an estimate, shown as such on screen).
 */
internal fun durationOf(g: Sports.Game): Long {
    val p = g.league.path
    val minutes = when {
        p.startsWith("football/nfl") || p.startsWith("football/cfl") -> 195
        p.startsWith("football/") -> 210
        p.startsWith("basketball/") -> 140
        p.startsWith("hockey/") -> 155
        p.startsWith("baseball/") -> 180
        p.startsWith("soccer/") -> 115
        p.startsWith("rugby") -> 110
        p.startsWith("australian-football/") -> 165
        p.startsWith("cricket/") -> 210
        else -> 150
    }
    return minutes * 60_000L
}

private fun teamColor(t: Sports.Team): Color =
    t.color?.takeIf { it.length == 6 }?.let { runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull() } ?: Edge.Accent
