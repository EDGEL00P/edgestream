package com.edgestream.tv.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import coil3.compose.AsyncImage
import com.edgestream.tv.data.Sports
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.components.TeamLogo
import com.edgestream.tv.ui.components.WeatherGlyph
import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA
import com.edgestream.tv.ui.input.hoverFocus
import com.edgestream.tv.ui.input.remoteClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val CARD_HEIGHT = 118.dp
private val CardShape = RoundedCornerShape(14.dp)

/**
 * Every band card: focus lifts it (scale in the draw phase, so held keys never relayout) and draws
 * a bright border — focus is shown by size and outline, never colour alone.
 */
@Composable
fun BandCard(
    width: Dp,
    requester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val active = LocalBandActive.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, spring(stiffness = 700f), label = "card")
    Box(
        modifier
            .width(width).height(CARD_HEIGHT)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                shape = CardShape; clip = true
            }
            .background(Edge.palette.panelHi)
            .then(if (focused) Modifier.border(3.dp, Edge.Text, CardShape) else Modifier)
            .hoverFocus(requester)
            .onFocusChanged { focused = it.isFocused }
            .focusProperties { canFocus = active }
            .remoteClickable(onClick),
        content = content,
    )
}

private val Scrim = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f))

private fun teamColor(hex: String?): Color? =
    hex?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

@Composable
fun GameCard(g: Sports.Game, requester: FocusRequester, onClick: () -> Unit) {
    val away = teamColor(g.away.color) ?: Edge.palette.panelHi
    val home = teamColor(g.home.color) ?: Edge.palette.panel
    BandCard(228.dp, requester, onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(away, home))))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
        g.league.logo?.let { TeamLogo(it, g.league.name, Modifier.align(Alignment.TopCenter).padding(top = 8.dp), size = 18.dp) }
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(g.away.logo, g.away.name, size = 52.dp)
            val live = g.state == Sports.State.LIVE
            Text(
                if (live) "${g.away.score ?: "0"} – ${g.home.score ?: "0"}" else "vs",
                color = Color.White, fontSize = if (live) 20.sp else 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            TeamLogo(g.home.logo, g.home.name, size = 52.dp)
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (g.state == Sports.State.LIVE) {
                Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Edge.Live).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            Text(
                if (g.state == Sports.State.LIVE) g.detail
                else remember(g.startMs) { SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(g.startMs)) },
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
fun ChannelCard(item: BandItem.Channel, requester: FocusRequester, onClick: () -> Unit) {
    BandCard(228.dp, requester, onClick) {
        AsyncImage(item.channel.logo, item.channel.name, Modifier.align(Alignment.Center).padding(bottom = 26.dp).size(width = 120.dp, height = 60.dp),
            contentScale = ContentScale.Fit)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Scrim).padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(item.channel.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.now?.let {
                Text(it, color = Color.White.copy(alpha = SECONDARY_TEXT_ALPHA), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ResumeCard(item: BandItem.Resume, requester: FocusRequester, onClick: () -> Unit) {
    val p = item.p
    // A poster like every other title card; only a thin progress line, no text.
    BandCard(86.dp, requester, onClick) {
        AsyncImage(p.poster, p.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val frac = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Edge.Accent))
        }
    }
}

@Composable
fun VodCard(item: BandItem.Vod, requester: FocusRequester, onClick: () -> Unit) {
    BandCard(86.dp, requester, onClick) {
        AsyncImage(item.item.poster, item.item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun WatchCard(item: BandItem.Watch, requester: FocusRequester, onClick: () -> Unit) {
    BandCard(86.dp, requester, onClick) {
        AsyncImage(item.item.poster, item.item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun AlertCard(item: BandItem.Alert, requester: FocusRequester, onClick: () -> Unit) {
    val w = item.warning
    BandCard(300.dp, requester, onClick) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(Edge.Live))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(w.severity.uppercase(), color = Edge.Live, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(w.title, color = Edge.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(w.source, color = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA), fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun DayCard(item: BandItem.Day, fahrenheit: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    val d = item.day
    fun t(c: Double) = if (c.isNaN()) "–" else if (fahrenheit) "${(c * 9 / 5 + 32).toInt()}°" else "${c.toInt()}°"
    val name = remember(d.date) {
        runCatching { SimpleDateFormat("EEEE", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.date)!!) }.getOrDefault(d.date)
    }
    BandCard(150.dp, requester, onClick) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Text(name, color = Edge.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            WeatherGlyph(d.code, glyphSize = 40.dp)
            Row {
                Text(t(d.highC), color = Edge.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("  ${t(d.lowC)}", color = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA), fontSize = 17.sp)
            }
        }
    }
}
