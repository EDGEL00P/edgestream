package com.edgestream.tv.ui.shell

import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val CARD_HEIGHT = 118.dp
private val CardShape = RoundedCornerShape(14.dp)

/**
 * Every band card: focus lifts it (scale in the draw phase, so held keys never relayout) and draws
 * a bright border — focus is shown by size and outline, never colour alone. Screenshot style:
 * dark translucent card, accent bottom edge, focused gets a red border.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BandCard(
    width: Dp,
    requester: FocusRequester,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val active = LocalBandActive.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, spring(stiffness = 700f), label = "card")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .width(width).height(CARD_HEIGHT)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                shape = CardShape; clip = true
            }
            .background(Edge.Text.copy(alpha = 0.08f))
            .then(if (focused) Modifier.border(2.dp, Edge.Live, CardShape) else Modifier)
            .hoverFocus(requester)
            .onFocusChanged { focused = it.isFocused }
            .focusProperties { canFocus = active }
            .focusable(interactionSource = interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        // Accent bottom edge (orange/gradient like screenshot), always present.
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
            .background(Brush.horizontalGradient(listOf(Edge.Accent, Edge.Accent2))))
        content(focused)
    }
}

private val Scrim = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f))

private fun teamColor(hex: String?): Color? =
    hex?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

@Composable
fun GameCard(g: Sports.Game, requester: FocusRequester, onClick: () -> Unit) {
    val live = g.state == Sports.State.LIVE
    val minute = remember(g.startMs, g.state) { if (live) g.detail.substringAfter("·").trim().substringBefore(" ").ifBlank { g.detail } else "" }
    BandCard(260.dp, requester, onClick) { focused ->
        // Focused card: a "Watch on …" pill overlapping the top edge.
        if (focused) {
            val label = "Watch on ${g.broadcasts.firstOrNull() ?: g.league.name}"
            Text(
                label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).offset(y = (-10).dp)
                    .clip(RoundedCornerShape(8.dp)).background(Edge.Live).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (live) "${g.away.abbr} ${g.away.score ?: "0"}-${g.home.score ?: "0"} ${g.home.abbr}" else "${g.away.abbr} vs ${g.home.abbr}").uppercase(),
                    color = Edge.Text, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (minute.isNotBlank()) Text(minute, color = Edge.Live, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(
                if (live) "${g.league.name} · ${g.broadcasts.firstOrNull() ?: g.detail}"
                else remember(g.startMs) { "${g.league.name} · ${SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(g.startMs))}" },
                color = Edge.Text.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun ChannelCard(item: BandItem.Channel, requester: FocusRequester, editing: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    BandCard(228.dp, requester, onClick, onLongClick = onRemove) {
        AsyncImage(item.channel.logo, item.channel.name, Modifier.align(Alignment.Center).padding(bottom = 26.dp).size(width = 120.dp, height = 60.dp),
            contentScale = ContentScale.Fit)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Scrim).padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(item.channel.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.now?.let {
                Text(it, color = Color.White.copy(alpha = SECONDARY_TEXT_ALPHA), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (editing) RemoveBadge()
    }
}

/** Shown on every card of a row in edit mode: OK removes this one. */
@Composable
private fun BoxScope.RemoveBadge() {
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Text(
            "Remove", color = Edge.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Edge.Live).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** The end of a removable row: OK switches the row into edit mode (Edit) and back out (Done). */
@Composable
fun EditCard(editing: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    BandCard(120.dp, requester, onClick) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (editing) Icons.Rounded.Check else Icons.Rounded.Edit, null,
                tint = Edge.Text, modifier = Modifier.size(28.dp),
            )
            Text(if (editing) "Done" else "Edit", color = Edge.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun ResumeCard(item: BandItem.Resume, requester: FocusRequester, editing: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val p = item.p
    // A poster like every other title card; only a thin progress line. The row's Edit card (or a
    // long-press) removes it.
    BandCard(86.dp, requester, onClick, onLongClick = onRemove) {
        AsyncImage(p.poster, p.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val frac = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Edge.Accent))
        }
        if (editing) RemoveBadge()
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
