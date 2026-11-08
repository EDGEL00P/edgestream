package com.edgestream.tv.ui.screens

import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgestream.tv.data.ChannelNumbers
import com.edgestream.tv.data.Sports
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.VideoSurface
import com.edgestream.tv.ui.components.EdgeButton
import kotlinx.coroutines.delay

/**
 * Two live pictures side by side. Left/Right picks a side (its sound plays, it gets the
 * accent frame); OK on a side swaps its channel from what's live now; Back keeps the
 * highlighted side full screen.
 */
@Composable
fun MultiviewScreen(vm: AppViewModel, mv: AppViewModel.Multiview) {
    val leftFocus = remember { FocusRequester() }
    var picking by remember { mutableStateOf<Boolean?>(null) } // null = closed, true = right side
    LaunchedEffect(Unit) { delay(80); runCatching { leftFocus.requestFocus() } }
    LifecycleStartEffect(Unit) {
        vm.resumeMultiview()
        onStopOrDispose { vm.pauseMultiview() }
    }
    BackHandler { vm.endMultiview(keepRight = mv.audioRight) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Pane(mv.left, !mv.audioRight, Modifier.weight(1f).focusRequester(leftFocus), onFocus = { vm.setMultiviewAudio(false) }, onOk = { picking = false }) {
                VideoSurface(vm.player, Modifier.fillMaxSize())
            }
            Pane(mv.right, mv.audioRight, Modifier.weight(1f), onFocus = { vm.setMultiviewAudio(true) }, onOk = { picking = true }) {
                val second = vm.second
                if (second != null) key(second) {
                    AndroidView(factory = { c -> TextureView(c).also { second.exo.setVideoTextureView(it) } }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Text(
            "←/→ choose sound  ·  OK change game  ·  Back keep this side",
            style = MaterialTheme.typography.labelLarge, color = Edge.Dim,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
        )
    }

    picking?.let { right ->
        LiveNowPicker(vm, onPick = { vm.changeMultiviewPane(right, it); picking = null }, onClose = { picking = null })
    }
}

@Composable
private fun Pane(
    channel: ChannelEntity, active: Boolean, modifier: Modifier,
    onFocus: () -> Unit, onOk: () -> Unit, video: @Composable () -> Unit,
) {
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .border(if (active) 4.dp else 1.dp, if (active) Edge.Accent else Edge.Text.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(4.dp)
                .onFocusChanged { if (it.isFocused) onFocus() }
                .onKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (ok && e.type == KeyEventType.KeyUp) { onOk(); true } else ok
                }
                .focusable(),
        ) { video() }
        Text(
            "${ChannelNumbers.lcn(channel)}  ${channel.name}" + if (active) "  ·  sound" else "",
            style = MaterialTheme.typography.titleMedium, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) Edge.Text else Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** What's live right now with a channel in the lineup; favourites when no game is on. */
@Composable
fun LiveNowPicker(vm: AppViewModel, onPick: (ChannelEntity) -> Unit, onClose: () -> Unit) {
    val games by vm.sportsGames.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val favourites by vm.favouriteChannels.collectAsStateWithLifecycle()
    val lookup by vm.sportsLookup.collectAsStateWithLifecycle()
    val live = games.filter { it.state == Sports.State.LIVE }.mapNotNull { g -> lookup.find(g)?.let { g to it } }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        LaunchedEffect(live.isNotEmpty(), favourites.isNotEmpty()) { delay(80); runCatching { first.requestFocus() } }
        Column(Modifier.width(620.dp).heightIn(max = 560.dp).background(Edge.Solid, EdgeShapes.Panel).padding(24.dp)) {
            Text(if (live.isNotEmpty()) "Live now" else "Favourites", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Edge.Text)
            if (live.isEmpty() && favourites.isEmpty()) {
                Text("No live games right now, and no favourite channels yet.", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, modifier = Modifier.padding(top = 12.dp))
                EdgeButton("Close", onClose, Modifier.padding(top = 16.dp).focusRequester(first))
            } else LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val rows: List<Pair<String, ChannelEntity>> =
                    if (live.isNotEmpty()) live.map { (g, ch) -> "${g.league.name}  ·  ${g.away.abbr} @ ${g.home.abbr}  ·  ${ch.name}" to ch }
                    else favourites.map { "${ChannelNumbers.lcn(it)}  ${it.name}" to it }
                items(rows.size) { i ->
                    val (label, ch) = rows[i]
                    EdgeButton(label, { onPick(ch) }, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier), fill = true)
                }
            }
        }
    }
}
