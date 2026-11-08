package com.edgestream.tv.ui.screens

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgestream.tv.data.Sports
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.components.SectionTitle
import com.edgestream.tv.ui.components.TeamLogo
import kotlinx.coroutines.delay

/** Which surfaces the match view draws per sport. */
private val BASKETBALL_IDS = setOf("nba", "wnba", "ncaab", "nbl")
private val HOCKEY_IDS = setOf("nhl")
private val FOOTBALL_IDS = setOf("nfl", "ncaaf", "cfl")
private val BASEBALL_IDS = setOf("mlb")
private val CRICKET_IDS = setOf("bbl", "county", "t20", "worldcup", "sheffield")
private val TENNIS_IDS = setOf("atp", "wta")
private val RUGBY_IDS = setOf("nrl", "afl", "superrugby", "rugbychamp", "urc")

/**
 * Shared Gamecast data: one refresh loop for both surfaces (sheet + full-screen panel).
 * Live games every 15 s, everything else every 30 s so corrections and late stat
 * updates always flow through. Returns (insight, loaded) for SportsGamecast.
 */
@Composable
internal fun rememberGamecastInsight(game: Sports.Game): Pair<Sports.Insight?, Boolean> {
    var insight by remember(game.id) { mutableStateOf<Sports.Insight?>(null) }
    var loaded by remember(game.id) { mutableStateOf(false) }
    LaunchedEffect(game.id) {
        while (true) {
            insight = runCatching { Sports.insight(game, force = loaded) }.getOrNull() ?: insight
            loaded = true
            delay(if (game.state == Sports.State.LIVE) 15_000 else 30_000)
        }
    }
    return insight to loaded
}

/**
 * The analyst view shared by the Sports detail sheet and the full-screen Gamecast panel:
 * a live 3D match view, model and market win probability, game props, the advanced box score,
 * team leaders, scoring summary and play-by-play. Everything comes from ESPN's summary payload;
 * sections ESPN doesn't publish are simply skipped.
 */
@Composable
internal fun SportsGamecast(
    game: Sports.Game,
    insight: Sports.Insight?,
    loaded: Boolean,
    maxPlays: Int = 8,
    /** Spoiler switch: the box score, win probability and plays would give the result away. */
    hideScores: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (hideScores && game.state != Sports.State.UPCOMING) {
        Text("Scores hidden. Turn off Hide scores in Settings to see the analysis.", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = modifier)
        return
    }
    if (!loaded) {
        Text("Loading analysis…", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = modifier)
        return
    }
    if (insight == null || insight.isEmpty) {
        Text("No analyst data for this game.", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = modifier)
        return
    }

    Column(modifier) {
        GamecastTeams(game)
        val liveHome = insight.liveHomeWinChance.takeIf { game.state == Sports.State.LIVE }
        val homeChance = liveHome ?: insight.homeWinChance

        // The match at a glance, always alive: possession, ball, clock and win probability.
        if (game.state != Sports.State.UPCOMING) {
            SectionTitle("Match View")
            FieldView(game, insight)
        }

        if (homeChance != null || insight.marketHomeWinChance != null) {
            SectionTitle("Predictor")
            if (homeChance != null) {
                val awayChance = if (liveHome != null) 100 - liveHome else insight.awayWinChance ?: (100 - homeChance)
                WinChance(
                    game, awayChance, homeChance, live = liveHome != null,
                    source = if (liveHome != null) "Live win probability" else "ESPN model",
                )
            }
            insight.marketHomeWinChance?.let { market ->
                Spacer(Modifier.height(12.dp))
                WinChance(game, 100 - market, market, live = false, source = "Market implied · no vig", dim = true)
            }
        }

        insight.line?.let { line ->
            Spacer(Modifier.height(18.dp))
            SectionTitle("Game props · ${line.provider}")
            PropRow("Point spread", line.details.ifBlank { "—" })
            line.overUnder?.let { PropRow("Total", "O/U ${it.toString().removeSuffix(".0")}") }
            line.awayMoneyLine?.let { PropRow("${label(game.away)} moneyline", odds(it)) }
            line.homeMoneyLine?.let { PropRow("${label(game.home)} moneyline", odds(it)) }
        }

        if (insight.stats.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("Team Stats")
            StatHeader(game)
            insight.stats.forEach { stat ->
                StatRow(stat)
            }
        }

        if (insight.leaders.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle(
                if (game.league.id in setOf("nfl", "ncaaf")) "Player props · Next Gen Stats"
                else "Player props",
            )
            Text(
                if (game.state == Sports.State.LIVE) "Live game leaders · the stats the books set prop lines from"
                else "Projected leaders · the stats the books set prop lines from",
                style = MaterialTheme.typography.labelSmall, color = Edge.Dim,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            insight.leaders.take(8).forEach { leader ->
                val teamColor = when (leader.team) {
                    game.home.abbr -> teamColorHex(game.home) ?: Edge.Accent
                    game.away.abbr -> teamColorHex(game.away) ?: Edge.Accent2
                    else -> Edge.Dim
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(4.dp, 30.dp)
                            .clip(RoundedCornerShape(50))
                            .background(teamColor),
                    )
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(
                            leader.player, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            leader.category, style = MaterialTheme.typography.labelSmall,
                            color = Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // The value, styled as a prop line with tabular numerals.
                    Text(
                        leader.value,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold,
                        color = teamColor,
                        textAlign = TextAlign.End,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(110.dp),
                    )
                    // Over/under lean: live games show the actual, upcoming show the projection.
                    if (game.state == Sports.State.LIVE) {
                        Text(
                            "  ●",
                            style = MaterialTheme.typography.labelSmall,
                            color = Edge.Live,
                        )
                    }
                }
            }
        }

        if (insight.scoringPlays.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("Scoring Plays")
            insight.scoringPlays.take(8).forEach { play ->
                PlayRow(play, game, strong = true)
            }
        }

        if (insight.plays.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("Play-by-Play")
            Text(
                if (game.state == Sports.State.LIVE) "Live · updates every 15 seconds"
                else "Updating · scores and stats refresh continuously",
                style = MaterialTheme.typography.labelSmall, color = if (game.state == Sports.State.LIVE) Edge.Live else Edge.Dim,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            insight.plays.take(maxPlays).forEachIndexed { index, play ->
                // The newest play always leads and is always highlighted, so the game reads
                // top-down as it happens — live or replay.
                PlayRow(play, game, strong = play.scoring || index == 0)
            }
        }

        if (insight.awayForm.isNotEmpty() || insight.homeForm.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("Recent Form")
            if (insight.awayForm.isNotEmpty()) FormLine(game.away, insight.awayForm)
            if (insight.homeForm.isNotEmpty()) FormLine(game.home, insight.homeForm)
        }

        if (insight.injuries.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("Injuries")
            insight.injuries.groupBy { it.team }.forEach { (team, list) ->
                Text(
                    team, style = MaterialTheme.typography.labelSmall, color = Edge.Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                )
                list.forEach { injury ->
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            injury.player, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        val state = listOfNotNull(injury.status.takeIf { it.isNotBlank() }, injury.detail).joinToString(" · ")
                        Text(state, style = MaterialTheme.typography.labelMedium, color = Edge.Dim, maxLines = 1)
                    }
                }
            }
        }

        if (insight.headlines.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("Analysis")
            insight.headlines.forEach { headline ->
                Text(
                    "• $headline", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** One game-prop line: label left, value right, tabular numerals. */
@Composable
private fun PropRow(labelText: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            labelText, style = MaterialTheme.typography.bodyMedium, color = Edge.Dim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text(
            value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = Edge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WinChance(game: Sports.Game, awayChance: Int, homeChance: Int, live: Boolean, source: String, dim: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${label(game.away)} $awayChance%",
            style = MaterialTheme.typography.labelLarge, color = if (dim) Edge.Faint else Edge.Dim,
        )
        Spacer(Modifier.weight(1f))
        Text("${label(game.home)} $homeChance%", style = MaterialTheme.typography.labelLarge, color = if (dim) Edge.Dim else Edge.Text)
    }
    Spacer(Modifier.height(6.dp))
    val fallback = if (dim) Edge.Accent2 else Edge.Accent
    val awayBar = teamColorHex(game.away) ?: fallback
    val homeBar = teamColorHex(game.home) ?: fallback
    Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))) {
        Box(Modifier.weight(awayChance.coerceAtLeast(1).toFloat()).fillMaxHeight().background(awayBar.copy(alpha = 0.45f)))
        Box(Modifier.weight(homeChance.coerceAtLeast(1).toFloat()).fillMaxHeight().background(homeBar))
    }
    Text(
        source, style = MaterialTheme.typography.labelSmall, color = Edge.Faint,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** The match at a glance: a rendered, perspective pitch in the teams' real uniform colours. */
@Composable
private fun FieldView(game: Sports.Game, insight: Sports.Insight?) {
    val awayC = teamColorHex(game.away) ?: Edge.Accent2
    val homeC = teamColorHex(game.home) ?: Edge.Accent
    val newest = insight?.plays?.firstOrNull()
    // Real-time motion from ONE animator: the ball drifts slowly while its glow and the
    // possession side pulse with a sine derived from the same progress, keeping the per-frame
    // work minimal on older GPUs.
    val drift by androidx.compose.animation.core.rememberInfiniteTransition(label = "ball")
        .animateFloat(
            0f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(4_200),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "drift",
        )
    val pulse = 0.5f + 0.5f * kotlin.math.sin(drift * kotlin.math.PI.toFloat() * 6f)

    val possessionHome = newest?.team?.let { t ->
        val abbr = t.lowercase()
        game.home.abbr.lowercase() == abbr
    } ?: false
    val liveHome = insight?.liveHomeWinChance
    val homeWin = liveHome ?: insight?.homeWinChance
    val awayWin = if (homeWin != null) 100 - homeWin else null
    val startersHome = insight?.starters.orEmpty().filter { it.team.equals(game.home.abbr, true) }.take(6)
    val startersAway = insight?.starters.orEmpty().filter { it.team.equals(game.away.abbr, true) }.take(6)

    Box(
        Modifier.fillMaxWidth().aspectRatio(2.1f).clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF0C1E12), Color(0xFF060F09)))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val topY = h * 0.24f
            val cx = w / 2f
            val cy = (h + topY) / 2f
            val rx = w * 0.16f
            val ry = (h - topY) * 0.18f

            drawSurface(game.league.id, w, h, topY, cx, cy, rx, ry)
            // glowing end zones in the teams' colours
            val awayGlow = if (!possessionHome) 0.30f + 0.22f * pulse else 0.18f
            val homeGlow = if (possessionHome) 0.30f + 0.22f * pulse else 0.18f
            drawRect(
                Brush.verticalGradient(listOf(awayC.copy(alpha = awayGlow), awayC.copy(alpha = 0.02f)), startY = topY, endY = h * 0.45f),
                topLeft = Offset(0f, topY), size = Size(w, h * 0.45f - topY),
            )
            drawRect(
                Brush.verticalGradient(listOf(homeC.copy(alpha = 0.02f), homeC.copy(alpha = homeGlow)), startY = h * 0.55f, endY = h),
                topLeft = Offset(0f, h * 0.55f), size = Size(w, h - h * 0.55f),
            )
            // centre glow, layered team colours
            drawCircle(awayC.copy(alpha = 0.14f), radius = rx * 0.40f, center = Offset(cx, cy))
            drawCircle(homeC.copy(alpha = 0.14f), radius = rx * 0.26f, center = Offset(cx, cy))
            // the starters, with real jersey numbers
            drawPlayers(startersAway, h * 0.32f, awayC, !possessionHome, w, h, rx)
            drawPlayers(startersHome, h * 0.62f, homeC, possessionHome, w, h, rx)
            // the ball: drifts along the possession side with a pulsing glow
            val ballX = cx + (drift - 0.5f) * rx * 1.6f
            val ballY = if (possessionHome) h * 0.78f else h * 0.22f
            val ballC = if (possessionHome) homeC else awayC
            drawCircle(ballC.copy(alpha = 0.22f * pulse + 0.10f), radius = rx * 0.14f, center = Offset(ballX, ballY))
            drawCircle(Color.White.copy(alpha = 0.9f), radius = rx * 0.05f, center = Offset(ballX, ballY))
        }
        Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(awayC))
            Text(
                "  ${game.away.abbr} ${game.away.score ?: ""}".trim(),
                style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1,
            )
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(homeC))
            Text(
                "  ${game.home.abbr} ${game.home.score ?: ""}".trim(),
                style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1,
            )
        }
        // Win probability floats over the centre circle, updated with every refresh.
        if (awayWin != null && homeWin != null) {
            Row(
                Modifier.align(Alignment.Center).padding(top = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$awayWin%", style = MaterialTheme.typography.labelSmall, color = awayC, maxLines = 1)
                Text("  ·  ", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                Text("$homeWin%", style = MaterialTheme.typography.labelSmall, color = homeC, maxLines = 1)
            }
        }
        newest?.takeIf { it.text.isNotBlank() }?.let { play ->
            Text(
                (if (play.period > 0) "Q${play.period} ${play.clock} · " else "") + play.text,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp),
            )
        }
    }
}

/** The playing surface itself, drawn for the sport the game belongs to. */
private fun DrawScope.drawSurface(
    leagueId: String,
    w: Float,
    h: Float,
    topY: Float,
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
) {
    when (leagueId) {
        // Hardwood: painted lanes, arcs, hoops.
        in BASKETBALL_IDS -> {
            drawRect(
                Brush.horizontalGradient(listOf(Color(0xFF9E6C33), Color(0xFFC28742), Color(0xFF9E6C33))),
                topLeft = Offset(0f, topY), size = Size(w, h - topY),
            )
            val line = Color.White.copy(alpha = 0.55f)
            val lw = 1.5.dp.toPx()
            drawRect(line, topLeft = Offset(cx - rx * 2.1f, topY), size = Size(rx * 4.2f, h - topY), style = Stroke(lw))
            drawOval(line, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2), style = Stroke(lw))
            drawLine(line, Offset(cx, topY), Offset(cx, h), lw)
            for (isTop in listOf(true, false)) {
                val y = if (isTop) topY else h
                val laneH = (h - topY) * 0.22f
                val laneY = if (isTop) y else y - laneH
                drawRect(line, topLeft = Offset(cx - rx * 0.9f, laneY), size = Size(rx * 1.8f, laneH), style = Stroke(lw))
                drawArc(
                    line, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(cx - rx * 1.7f, if (isTop) y + laneH else y - laneH - ry * 1.1f),
                    size = Size(rx * 3.4f, ry * 2.2f),
                    style = Stroke(lw),
                )
                val hoopY = if (isTop) y + laneH * 0.32f else y - laneH * 0.32f
                drawLine(line, Offset(cx - rx * 0.55f, hoopY), Offset(cx + rx * 0.55f, hoopY), 3.dp.toPx())
                drawCircle(line, radius = 4.dp.toPx(), center = Offset(cx, hoopY))
            }
        }
        // Ice: blue lines, faceoff circles, creases.
        in HOCKEY_IDS -> {
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFFE3EDF5), Color(0xFFB7CDDE))),
                topLeft = Offset(0f, topY), size = Size(w, h - topY),
            )
            val line = Color(0xFFB23A3A).copy(alpha = 0.8f)
            val lw = 2.dp.toPx()
            val blue = Color(0xFF2E5FA3).copy(alpha = 0.9f)
            drawLine(line, Offset(cx, topY), Offset(cx, h), lw)
            for (f in listOf(0.30f, 0.70f)) {
                val y = topY + (h - topY) * f
                drawLine(blue, Offset(0f, y), Offset(w, y), lw)
            }
            for (fx in listOf(0.20f, 0.80f)) {
                for (fy in listOf(0.38f, 0.62f)) {
                    drawCircle(line, radius = rx * 0.42f, center = Offset(w * fx, topY + (h - topY) * fy), style = Stroke(lw))
                    drawCircle(line, radius = 3.dp.toPx(), center = Offset(w * fx, topY + (h - topY) * fy))
                }
            }
            for (isTop in listOf(true, false)) {
                val y = if (isTop) topY else h
                drawArc(
                    blue, startAngle = if (isTop) 0f else 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(cx - rx * 0.7f, y - ry * 0.55f),
                    size = Size(rx * 1.4f, ry * 1.1f),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
        // Gridiron: yard lines and hashes between the glowing end zones.
        in FOOTBALL_IDS -> {
            val line = Color.White.copy(alpha = 0.5f)
            val lw = 1.2.dp.toPx()
            for (i in 1..11) {
                val y = topY + (h - topY) * i / 12f
                drawLine(line, Offset(0f, y), Offset(w, y), lw)
            }
            for (fx in listOf(0.16f, 0.84f)) {
                for (i in 1..11) {
                    val y = topY + (h - topY) * i / 12f
                    drawLine(line, Offset(w * fx - rx * 0.3f, y), Offset(w * fx + rx * 0.3f, y), lw)
                }
            }
        }
        // Diamond: dirt infield, bases, mound and outfield grass.
        in BASEBALL_IDS -> {
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFF1D5A2E), Color(0xFF123B1F))),
                topLeft = Offset(0f, topY), size = Size(w, h - topY),
            )
            val dirt = Color(0xFFB0722F)
            val line = Color.White.copy(alpha = 0.7f)
            val lw = 1.5.dp.toPx()
            val iy = (h + topY) / 2f
            val dx = rx * 1.2f
            val dy = (h - topY) * 0.22f
            drawPath(
                Path().apply {
                    moveTo(cx, iy - dy * 2); lineTo(cx + dx * 2, iy); lineTo(cx, iy + dy * 2); lineTo(cx - dx * 2, iy); close()
                },
                dirt.copy(alpha = 0.8f),
            )
            drawLine(line, Offset(cx - dx * 2.4f, iy + dy * 1.8f), Offset(cx + dx * 0.6f, iy - dy * 1.2f), lw)
            drawLine(line, Offset(cx + dx * 2.4f, iy + dy * 1.8f), Offset(cx - dx * 0.6f, iy - dy * 1.2f), lw)
            for (b in listOf(
                Offset(cx, iy - dy * 1.8f), Offset(cx + dx * 1.8f, iy),
                Offset(cx, iy + dy * 1.8f), Offset(cx - dx * 1.8f, iy),
            )) {
                drawRect(
                    Color.White.copy(alpha = 0.9f),
                    topLeft = Offset(b.x - 4.dp.toPx(), b.y - 4.dp.toPx()),
                    size = Size(8.dp.toPx(), 8.dp.toPx()),
                )
            }
            drawCircle(dirt.copy(alpha = 0.9f), radius = rx * 0.12f, center = Offset(cx, iy))
        }
        // Cricket: a lighter pitch strip with creases at each end.
        in CRICKET_IDS -> {
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFF1B6232), Color(0xFF0F3E1F))),
                topLeft = Offset(0f, topY), size = Size(w, h - topY),
            )
            val line = Color.White.copy(alpha = 0.55f)
            val lw = 1.5.dp.toPx()
            val pitchW = rx * 0.5f
            drawRect(
                Color(0xFFC89858).copy(alpha = 0.75f),
                topLeft = Offset(cx - pitchW / 2, topY + (h - topY) * 0.12f),
                size = Size(pitchW, (h - topY) * 0.76f),
            )
            for (f in listOf(0.14f, 0.86f)) {
                val y = topY + (h - topY) * f
                drawLine(line, Offset(cx - pitchW * 0.9f, y), Offset(cx + pitchW * 0.9f, y), lw)
                drawLine(line, Offset(cx, y - 6.dp.toPx()), Offset(cx, y + 6.dp.toPx()), 2.dp.toPx())
            }
            drawOval(
                line.copy(alpha = 0.3f),
                topLeft = Offset(w * 0.05f, topY + (h - topY) * 0.05f),
                size = Size(w * 0.9f, (h - topY) * 0.9f),
                style = Stroke(lw),
            )
        }
        // Tennis: a blue court with the net and service boxes.
        in TENNIS_IDS -> {
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFF1A5C8C), Color(0xFF0F3D5E))),
                topLeft = Offset(0f, topY), size = Size(w, h - topY),
            )
            val line = Color.White.copy(alpha = 0.75f)
            val lw = 1.5.dp.toPx()
            drawRect(
                line, topLeft = Offset(cx - rx * 2.0f, topY + (h - topY) * 0.08f),
                size = Size(rx * 4.0f, (h - topY) * 0.84f), style = Stroke(lw),
            )
            drawLine(line, Offset(cx - rx * 2.0f, cy), Offset(cx + rx * 2.0f, cy), 3.dp.toPx())
            for (isBottom in listOf(false, true)) {
                val y = if (isBottom) h else topY
                val alleyY = if (isBottom) y - (h - topY) * 0.21f else y + (h - topY) * 0.21f
                drawLine(line, Offset(cx - rx * 0.75f, alleyY), Offset(cx + rx * 0.75f, alleyY), lw)
                val endY = if (isBottom) y - (h - topY) * 0.01f else y + (h - topY) * 0.01f
                drawLine(line, Offset(cx - rx * 0.75f, alleyY), Offset(cx - rx * 0.75f, endY), lw)
                drawLine(line, Offset(cx + rx * 0.75f, alleyY), Offset(cx + rx * 0.75f, endY), lw)
            }
        }
        // Rugby league/union: grass, halfway, goal posts at each end.
        in RUGBY_IDS -> {
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFF18603A), Color(0xFF0E3E22))),
                topLeft = Offset(0f, topY), size = Size(w, h - topY),
            )
            val line = Color.White.copy(alpha = 0.6f)
            val lw = 2.dp.toPx()
            drawLine(line, Offset(0f, cy), Offset(w, cy), lw)
            for (f in listOf(0.22f, 0.78f)) {
                val y = topY + (h - topY) * f
                drawLine(line.copy(alpha = 0.4f), Offset(0f, y), Offset(w, y), lw / 2)
            }
            for (isTop in listOf(true, false)) {
                val y = if (isTop) topY + (h - topY) * 0.08f else h - (h - topY) * 0.08f
                val pw = rx * 0.35f
                val crossY = if (isTop) y + 14.dp.toPx() else y - 14.dp.toPx()
                drawLine(line, Offset(cx - pw, y), Offset(cx - pw, crossY), 3.dp.toPx())
                drawLine(line, Offset(cx + pw, y), Offset(cx + pw, crossY), 3.dp.toPx())
                drawLine(line, Offset(cx - pw, crossY), Offset(cx + pw, crossY), 3.dp.toPx())
            }
        }
        // The round-ball pitch with boxes, arcs and the centre spot.
        else -> {
            val line = Color.White.copy(alpha = 0.32f)
            val lw = 1.5.dp.toPx()
            drawLine(line, Offset(0f, h), Offset(0f, topY), lw)
            drawLine(line, Offset(w, h), Offset(w, topY), lw)
            drawLine(line, Offset(0f, topY), Offset(w, topY), lw)
            drawOval(line, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2), style = Stroke(lw))
            drawLine(line, Offset(cx, topY), Offset(cx, h), lw)
            drawCircle(line, radius = 2.5.dp.toPx(), center = Offset(cx, cy))
            for (isTop in listOf(true, false)) {
                val y = if (isTop) topY else h
                val boxH = (h - topY) * 0.16f
                val boxY = if (isTop) y else y - boxH
                drawRect(line, topLeft = Offset(cx - rx * 1.9f, boxY), size = Size(rx * 3.8f, boxH), style = Stroke(lw))
                val goalY = if (isTop) y + boxH * 0.45f else y - boxH * 0.45f
                drawRect(line, topLeft = Offset(cx - rx * 0.7f, goalY - boxH * 0.22f), size = Size(rx * 1.4f, boxH * 0.44f), style = Stroke(lw))
                drawArc(
                    line, startAngle = if (isTop) 0f else 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(cx - rx * 1.05f, if (isTop) y + boxH else y - boxH - ry * 0.9f),
                    size = Size(rx * 2.1f, ry * 1.8f),
                    style = Stroke(lw),
                )
            }
        }
    }
}

/** Numbered starter chips in their real formation halves. */
private fun DrawScope.drawPlayers(
    list: List<Sports.Starter>,
    half: Float,
    color: Color,
    active: Boolean,
    w: Float,
    h: Float,
    rx: Float,
) {
    val n = list.size.coerceAtLeast(1)
    list.forEachIndexed { i, p ->
        val fx = -0.5f + (i + 1f) / (n + 1f)
        val x = w * (0.5f + fx * 0.80f)
        val y = h * half + (i % 2) * h * 0.03f
        val r = rx * 0.11f
        drawCircle(color.copy(alpha = if (active) 0.95f else 0.5f), radius = r + 2.5.dp.toPx(), center = Offset(x, y))
        drawCircle(Color(0xFF10151A), radius = r, center = Offset(x, y))
        if (p.jersey.isNotBlank()) {
            drawContext.canvas.nativeCanvas.drawText(
                p.jersey.take(2), x, y + 4.dp.toPx(),
                android.graphics.Paint().apply {
                    this.color = android.graphics.Color.WHITE
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 8.dp.toPx()
                    isFakeBoldText = true
                },
            )
        }
    }
}

@Composable
private fun GamecastTeams(game: Sports.Game) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamBadge(game.away, Modifier.weight(1f))
        Text("at", style = MaterialTheme.typography.labelSmall, color = Edge.Dim, modifier = Modifier.padding(horizontal = 12.dp))
        TeamBadge(game.home, Modifier.weight(1f), alignEnd = true)
    }
}

@Composable
private fun TeamBadge(team: Sports.Team, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        if (alignEnd) {
            TeamName(team, alignEnd = true)
            Spacer(Modifier.width(6.dp))
            TeamLogo(team.logo, team.name, size = 24.dp)
        } else {
            TeamLogo(team.logo, team.name, size = 24.dp)
            Spacer(Modifier.width(6.dp))
            TeamName(team, alignEnd = false)
        }
    }
}

@Composable
private fun TeamName(team: Sports.Team, alignEnd: Boolean) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(
            label(team), style = MaterialTheme.typography.titleMedium, color = Edge.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        team.record?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Edge.Dim, maxLines = 1)
        }
    }
}

@Composable
private fun StatHeader(game: Sports.Game) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label(game.away), style = MaterialTheme.typography.labelSmall, color = Edge.Dim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(64.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            label(game.home), style = MaterialTheme.typography.labelSmall, color = Edge.Dim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.width(64.dp),
        )
    }
}

@Composable
private fun StatRow(stat: Sports.TeamStat) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stat.away, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(64.dp),
        )
        Text(
            stat.label, style = MaterialTheme.typography.labelMedium, color = Edge.Dim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Text(
            stat.home, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}

@Composable
private fun PlayRow(play: Sports.Play, game: Sports.Game, strong: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(62.dp)) {
            Text(
                if (play.period > 0) "Q${play.period}" else "", style = MaterialTheme.typography.labelSmall,
                color = if (strong) Edge.Accent else Edge.Dim, maxLines = 1,
            )
            Text(play.clock, style = MaterialTheme.typography.labelSmall, color = Edge.Dim, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Text(
                play.text, style = MaterialTheme.typography.bodyMedium,
                color = if (strong) Edge.Text else Edge.Dim,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (play.awayScore != null && play.homeScore != null) {
                Text(
                    "${label(game.away)} ${play.awayScore} – ${play.homeScore} ${label(game.home)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (strong) Edge.Accent else Edge.Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        play.team?.let { team ->
            Text(
                team, style = MaterialTheme.typography.labelSmall, color = Edge.Dim,
                maxLines = 1, modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** One team's last five results as W/L chips plus the most recent score line. */
@Composable
internal fun FormLine(team: Sports.Team, games: List<Sports.FormGame>) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label(team), style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(52.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            games.forEach { form ->
                val win = form.result.equals("W", true)
                Text(
                    form.result,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (win) Edge.OnAccent else Edge.Dim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (win) Edge.Accent else Edge.PanelHi)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        val latest = games.first()
        Text(
            "${latest.score} ${latest.atVs} ${latest.opponent}".trim(),
            style = MaterialTheme.typography.labelSmall, color = Edge.Dim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp),
        )
    }
}

internal fun label(team: Sports.Team): String = team.abbr.ifBlank { team.name }

private fun odds(value: Int): String = if (value > 0) "+$value" else "$value"

/** A team's primary uniform colour from the league feed's hex, or null when unpublished. */
internal fun teamColorHex(team: Sports.Team): Color? = team.color?.let { hex ->
    runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrNull()
}
