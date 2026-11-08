package com.edgestream.tv.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgestream.tv.data.NflModel
import com.edgestream.tv.data.NflPicks
import com.edgestream.tv.data.NflProps
import com.edgestream.tv.data.Sports
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.FocusDimGroup
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.components.TeamLogo
import java.util.Locale
import kotlin.math.roundToInt

/**
 * NFL Picks as a guide: the slate on the left, and the focused game's whole story on the right
 * (pick, lines, value, both teams' props, track record). Moving focus is the only navigation;
 * OK opens the game sheet to watch it.
 */
@Composable
fun NflHub(board: NflPicks.Board?, use24h: Boolean, onOpen: (Sports.Game) -> Unit, modifier: Modifier = Modifier) {
    if (board == null) {
        Text("Loading NFL model…", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, modifier = modifier.padding(top = 18.dp))
        return
    }
    if (board.picks.isEmpty()) {
        Text("No upcoming NFL games with a line yet.", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, modifier = modifier.padding(top = 18.dp))
        return
    }
    var selectedId by remember { mutableStateOf(board.picks.first().espnId) }
    val selected = board.picks.firstOrNull { it.espnId == selectedId } ?: board.picks.first()
    Row(modifier.fillMaxSize()) {
        // The slate is the one long list here, so it alone gets the solid strip.
        Box(Modifier.width(330.dp).fillMaxHeight().background(Edge.Solid, EdgeShapes.Card)) { FocusDimGroup {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(board.picks, key = { it.espnId }) { pick ->
                    SlateRow(
                        pick, use24h, selected = pick.espnId == selected.espnId,
                        props = board.props.count { l ->
                            l.espnGame == pick.espnId && (NflProps.playsFor(l.player, pick.game.home.abbr) || NflProps.playsFor(l.player, pick.game.away.abbr))
                        },
                        onFocus = { selectedId = pick.espnId },
                        onClick = { onOpen(pick.game) },
                    )
                }
            }
        } }
        Spacer(Modifier.width(28.dp))
        MatchupPane(selected, board, use24h, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun SlateRow(pick: NflPicks.Pick, use24h: Boolean, selected: Boolean, props: Int, onFocus: () -> Unit, onClick: () -> Unit) {
    val g = pick.game
    FocusSurface(
        onClick = onClick, selected = selected, focusScale = 1.02f, glow = false,
        memoryKey = "betting:nfl|${pick.espnId}",
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(g.away.logo, g.away.name, size = 26.dp)
            Text(" @ ", style = MaterialTheme.typography.labelSmall, color = Edge.Faint)
            TeamLogo(g.home.logo, g.home.name, size = 26.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("${g.away.abbr} @ ${g.home.abbr}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    Format.time(g.startMs, use24h) + when (props) { 0 -> ""; 1 -> "  ·  1 prop"; else -> "  ·  $props props" },
                    style = MaterialTheme.typography.labelSmall, color = if (focused || selected) Edge.Dim else Edge.Faint, maxLines = 1,
                )
            }
            when {
                pick.values.isNotEmpty() -> Pill("VALUE", color = Edge.Gold)
                pick.tier != null -> Pill(pick.tier.label.uppercase(), color = Edge.Accent)
                else -> Text(pct(pick.winProb), style = MaterialTheme.typography.labelMedium, color = Edge.Dim)
            }
        }
    }
}

@Composable
private fun MatchupPane(pick: NflPicks.Pick, board: NflPicks.Board, use24h: Boolean, modifier: Modifier) {
    val g = pick.game
    val leans = board.props.filter { it.espnGame == pick.espnId }
    Column(modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Matchup hero.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(g.away.logo, g.away.name, size = 52.dp)
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text("${g.away.name}  at  ${g.home.name}", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(Format.time(g.startMs, use24h), g.venue, g.broadcasts.firstOrNull()).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium, color = Edge.Dim, maxLines = 1,
                )
            }
            TeamLogo(g.home.logo, g.home.name, size = 52.dp)
        }

        // The pick, what built it, and how it compares with the market: one instrument row.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            Column {
                Text("PICK", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = Edge.Faint)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(pick.winner, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(pct(pick.winProb), style = MaterialTheme.typography.headlineSmall, color = Edge.Accent, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pick.tier?.let { Pill(it.label.uppercase(), color = Edge.Accent) }
                    Text(
                        "Total ${pick.totalLine?.let(NflPicks::trim) ?: "—"}",
                        style = MaterialTheme.typography.labelMedium, color = Edge.Dim,
                    )
                }
            }
            ModelVsMarket(pick, Modifier.weight(1f))
        }
        WhyBars(pick, Modifier.fillMaxWidth())

        if (pick.values.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pick.values.forEach { v ->
                    val record = board.rules.firstOrNull { it.rule == v.rule }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill("VALUE", color = Edge.Gold)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            v.selection + (v.windMph?.let { "  ·  wind $it mph" } ?: "") + if (v.modelAgrees) "  ·  model agrees" else "",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                        record?.holdout?.hitRate?.let {
                            Text(
                                "   ${v.rule.label} ${pct(it)} since ${NflModel.HOLDOUT_FROM} (${record.holdout.graded})",
                                style = MaterialTheme.typography.labelMedium, color = Edge.Dim,
                            )
                        }
                    }
                }
            }
        }

        // Both sides' props, side by side.
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            for (team in listOf(g.away, g.home)) {
                TeamProps(team, leans.filter { NflProps.playsFor(it.player, team.abbr) }.take(3), Modifier.weight(1f))
            }
        }

        TrackRecordLine(board)
    }
}

/** What built the model's line: four diverging bars in a 2×2 grid (home side to the right). */
@Composable
private fun WhyBars(pick: NflPicks.Pick, modifier: Modifier) {
    val g = pick.game
    val f = pick.factors
    val cells = listOf("Team strength" to f.strength, "Home field" to f.homeField, "Rest" to f.rest, "Quarterback" to f.quarterback)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("WHY  ·  POINTS TOWARD ${g.away.abbr} ←  → ${g.home.abbr}", style = MaterialTheme.typography.labelSmall, color = Edge.Faint)
        cells.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                pair.forEach { (label, v) -> WhyCell(label, v, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun WhyCell(label: String, v: Double, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Edge.Dim, modifier = Modifier.weight(1f), maxLines = 1)
            Text(if (kotlin.math.abs(v) < 0.05) "0" else String.format(Locale.US, "%+.1f", v), style = MaterialTheme.typography.labelMedium, color = Edge.Text)
        }
        // ±10 points fills one half; bigger values pin to the edge.
        val share = (kotlin.math.abs(v) / 10.0).coerceAtMost(1.0).toFloat()
        Row(Modifier.fillMaxWidth().height(6.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Edge.Line.copy(alpha = 0.35f)), contentAlignment = Alignment.CenterEnd) {
                if (v < 0) Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(Edge.Dim))
            }
            Box(Modifier.width(2.dp).fillMaxHeight().background(Edge.Text.copy(alpha = 0.5f)))
            Box(Modifier.weight(1f).fillMaxHeight().background(Edge.Line.copy(alpha = 0.35f)), contentAlignment = Alignment.CenterStart) {
                if (v > 0) Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(Edge.Accent))
            }
        }
    }
}

/** Where the model sits against the market line, and what that gap is worth. */
@Composable
private fun ModelVsMarket(pick: NflPicks.Pick, modifier: Modifier) {
    val g = pick.game
    val market = pick.marketHomeMargin
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("MODEL VS MARKET", style = MaterialTheme.typography.labelSmall, color = Edge.Faint)
        if (market == null) {
            Text("No market line yet.", style = MaterialTheme.typography.labelMedium, color = Edge.Faint)
            return@Column
        }
        val gap = kotlin.math.abs(pick.modelHomeMargin - market)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("MODEL", NflPicks.lineText(pick.modelHomeMargin, g.home.abbr, g.away.abbr))
            Stat("MARKET", NflPicks.lineText(market, g.home.abbr, g.away.abbr))
            pick.marketHomeProb?.let { home -> Stat("MKT WIN", "${pick.winner} ${pct(if (pick.winner == g.home.abbr) home else 1 - home)}") }
        }
        Text(
            if (gap >= 2.0) String.format(Locale.US, "%.1f pts apart. Gaps like this lost on unseen seasons, so the pick follows the market.", gap)
            else "Close to the market, the sharper number. The pick follows it.",
            style = MaterialTheme.typography.labelMedium, color = Edge.Dim, maxLines = 3,
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = Edge.Faint)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun TeamProps(team: Sports.Team, leans: List<NflProps.Lean>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(team.logo, team.name, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Text("${team.name.uppercase()} PROPS", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp), color = Edge.Dim)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Edge.Line))
        if (leans.isEmpty()) {
            Text("No prop leans", style = MaterialTheme.typography.labelMedium, color = Edge.Faint)
        }
        leans.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.player.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${if (l.over) "Over" else "Under"} ${NflPicks.trim(l.line)} ${l.market.label}",
                        style = MaterialTheme.typography.labelMedium, color = Edge.Text, maxLines = 1,
                    )
                    Text(
                        "proj ${String.format(Locale.US, "%.1f", l.projection.mean)}" +
                            (l.open?.takeIf { it != l.line }?.let { " · opened ${NflPicks.trim(it)}" } ?: "") +
                            " · ${l.projection.games} games",
                        style = MaterialTheme.typography.labelSmall, color = Edge.Faint, maxLines = 1,
                    )
                }
                Text(pct(l.confidence), style = MaterialTheme.typography.titleSmall, color = Edge.Accent, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The honest small print: how the model, tiers and rules have actually done. */
@Composable
private fun TrackRecordLine(board: NflPicks.Board) {
    val parts = buildList {
        board.backtest?.let { add("model ${pct(it.winnerHitRate)} of winners") }
        board.tiers.forEach { t -> t.holdout.hitRate?.let { add("${t.tier.label} ${pct(it)} (${t.holdout.graded})") } }
        board.rules.forEach { r -> r.holdout.hitRate?.let { add("${r.rule.label} ${pct(it)} (${r.holdout.graded})") } }
        board.log.record.let { if (it.graded > 0) add("Logged ${it.wins}-${it.losses}-${it.pushes}") }
        board.propLog?.record?.let { if (it.graded > 0) add("Props ${it.wins}-${it.losses}-${it.pushes}") }
    }
    Text(
        "Unseen seasons since ${NflModel.HOLDOUT_FROM}: " + parts.joinToString("  ·  ") + ".  Props graded live; informational only.",
        style = MaterialTheme.typography.labelSmall, color = Edge.Dim, maxLines = 2,
    )
}

private fun pct(p: Double): String = "${(p * 100).roundToInt()}%"
