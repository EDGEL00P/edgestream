package com.edgestream.tv.ui.shell

import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgestream.tv.data.Headline
import com.edgestream.tv.data.Sports
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.TeamLogo
import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA

/** One page of the ticker: a few scores, or one headline. (Ported from EdgeLauncher.) */
sealed interface TickerPage {
    val badge: String
    val live: Boolean
    data class Scores(val games: List<Sports.Game>, override val badge: String, override val live: Boolean) : TickerPage
    data class News(val headline: Headline) : TickerPage {
        override val badge get() = "Headlines"
        override val live get() = false
    }
}

/**
 * Sports only — no news, no "LIVE" badge, no pages that end: one endless casino-style stream of
 * every game (live first, then coming up, then results), looping end to end.
 */
fun tickerPages(games: List<Sports.Game>, headlines: List<Headline>, perPage: Int): List<TickerPage> {
    val order = mapOf(Sports.State.LIVE to 0, Sports.State.UPCOMING to 1, Sports.State.FINAL to 2)
    if (games.isEmpty()) return emptyList()
    val sorted = games.sortedWith(compareBy({ order[it.state] }, { it.startMs }))
    return listOf(TickerPage.Scores(sorted, "Scoreboard", live = sorted.any { it.state == Sports.State.LIVE }))
}

/**
 * The slim always-on strip under the dock: sports scores scrolling continuously right-to-left,
 * end to end (medium-fast). The scroll is a draw-phase translation of its own layer, so it
 * never recomposes or relays out the screen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Ticker(pages: List<TickerPage>, use24h: Boolean, modifier: Modifier = Modifier) {
    if (pages.isEmpty()) return
    // End-to-end casino ticker: full-width, no badge, no news — scores scroll forever.
    Row(
        modifier.padding(horizontal = 12.dp).clipToBounds()
            .basicMarquee(
                iterations = Int.MAX_VALUE, initialDelayMillis = 0, repeatDelayMillis = 0,
                spacing = androidx.compose.foundation.MarqueeSpacing(64.dp), velocity = 80.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      // Recorded once into its own layer: each scroll frame only slides this finished layer
      // (re-recording ~50 items per frame cost ~25 ms on the SHIELD).
      Row(Modifier.graphicsLayer(), verticalAlignment = Alignment.CenterVertically) {
        pages.forEach { p ->
            when (p) {
                is TickerPage.Scores -> p.games.forEach { ScoreCell(it, use24h, Modifier.padding(end = 32.dp)) }
                is TickerPage.News -> Unit // ticker is sports-only now
            }
        }
      }
    }
}

/** One compact score: logo AWY 1 – 2 HOM logo · clock. Beaten sides dim at full time. */
@Composable
private fun ScoreCell(g: Sports.Game, use24h: Boolean, modifier: Modifier) {
    val a = g.away.score?.toIntOrNull()
    val h = g.home.score?.toIntOrNull()
    val dim = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA)
    val final = g.state == Sports.State.FINAL && a != null && h != null
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(g.away.logo, g.away.name, size = 18.dp)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = if (final && a!! < h!!) dim else Edge.Text)) { append(" ${g.away.abbr}") }
                if (g.state == Sports.State.UPCOMING) withStyle(SpanStyle(color = dim)) { append(" v ") }
                else append(" ${g.away.score ?: "0"}–${g.home.score ?: "0"} ")
                withStyle(SpanStyle(color = if (final && h!! < a!!) dim else Edge.Text)) { append("${g.home.abbr} ") }
            },
            fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
        )
        TeamLogo(g.home.logo, g.home.name, size = 18.dp)
        Text(
            "  " + when (g.state) {
                Sports.State.LIVE -> g.detail
                Sports.State.FINAL -> g.detail.ifEmpty { "Final" }
                Sports.State.UPCOMING -> Format.time(g.startMs, use24h)
            },
            color = if (g.state == Sports.State.LIVE) Edge.Live else dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
