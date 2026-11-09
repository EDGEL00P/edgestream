package com.edgestream.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgestream.tv.data.StreamAddons
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.OneLine
import com.edgestream.tv.ui.components.Pill
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Every version of a film or episode: the provider's own stream first, then what the user's
 * stream addons found on TorBox (torrent and Usenet), best first. A right-hand sheet over the
 * dimmed details screen.
 */
@Composable
fun VersionsSheet(vm: AppViewModel, sheet: AppViewModel.VersionSheet) {
    val streamBadges by androidx.compose.runtime.produceState(com.edgestream.tv.data.StreamBadges.NONE) {
        value = com.edgestream.tv.data.StreamBadges.load(com.edgestream.tv.EdgeStreamApp.instance.cacheDir)
    }
    BackHandler { vm.versionSheet.value = null }
    val first = remember { FocusRequester() }
    val t = sheet.target
    LaunchedEffect(t.key, sheet.streams != null) { repeat(10) { delay(60); runCatching { first.requestFocus() } } }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))) {
        Column(
            Modifier.align(Alignment.CenterEnd).width(520.dp).fillMaxHeight()
                .background(Edge.Solid).padding(start = 28.dp, end = 28.dp, top = 32.dp),
        ) {
            Text("Choose a Source", style = MaterialTheme.typography.headlineSmall)
            OneLine(t.title, Modifier.padding(top = 4.dp), MaterialTheme.typography.bodyMedium, Edge.Dim)
            // Catalogue titles have no details page of their own, so the watch list lives here.
            if (com.edgestream.tv.data.Repository.isCatalogue(t.key)) {
                val saved = vm.watchList.collectAsStateWithLifecycle().value.any { it.id == t.key }
                com.edgestream.tv.ui.components.EdgeButton(
                    if (saved) "In My List" else "Add to My List",
                    { vm.toggleMyList(t.key, t.title, t.poster, movie = t.type == "movie", year = t.year, imdbId = t.imdbId) },
                    Modifier.padding(top = 12.dp),
                )
            }

            LazyColumn(
                Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp, start = 4.dp, end = 4.dp),
            ) {
                if (t.serverUrl.isNotBlank()) item(key = "server") {
                    VersionRow(
                        icon = { Icon(Icons.Rounded.Dns, null, Modifier.size(22.dp), tint = Edge.Dim) },
                        title = "Your media server", details = "Your own copy", badges = emptyList(),
                        modifier = if (sheet.streams.isNullOrEmpty()) Modifier.focusRequester(first) else Modifier,
                    ) { vm.playVersion(t, null, sheet.fromStart) }
                }
                when {
                    sheet.streams == null -> item(key = "loading") {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Edge.Accent, strokeWidth = 2.dp)
                            Text("Searching your add-ons…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    sheet.message != null -> item(key = "message") {
                        Text(
                            sheet.message, style = MaterialTheme.typography.bodyMedium, maxLines = 4,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                itemsIndexed(sheet.streams.orEmpty(), key = { i, s -> "$i|${s.url}" }) { i, s ->
                    VersionRow(
                        icon = {
                            Icon(
                                if (s.cached == false) Icons.Rounded.CloudDownload else Icons.Rounded.Bolt, null, Modifier.size(22.dp),
                                tint = if (s.cached == true) Edge.Accent else Edge.Dim,
                            )
                        },
                        title = s.name.lineSequence().joinToString(" ").trim(),
                        details = s.details.lineSequence().filter { it.isNotBlank() }.take(3).joinToString(" · "),
                        images = remember(s, streamBadges) { streamBadges.match(s.name + " " + s.details) },
                        badges = badges(s, best = i == 0, pictured = streamBadges !== com.edgestream.tv.data.StreamBadges.NONE),
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    ) { vm.playVersion(t, s, sheet.fromStart) }
                }
            }
        }
    }
}

/** Text pills; resolution and HDR only when the picture badges aren't loaded (they show those). */
private fun badges(s: StreamAddons.Stream, best: Boolean, pictured: Boolean): List<String> = buildList {
    if (best && s.cached != false) add("Best match")
    if (!pictured) {
        when (s.resolution) { 2160 -> add("4K"); 1440 -> add("1440p"); 1080 -> add("1080p"); 720 -> add("720p"); 480 -> add("SD") }
        if (s.hdr) add("HDR")
    }
    add(if (s.usenet) "Usenet" else "Torrent")
    when (s.cached) { true -> add("Ready"); false -> add("Not cached"); null -> Unit }
    s.sizeBytes?.let { add(if (it >= 1L shl 30) "%.1f GB".format(it / (1L shl 30).toDouble()) else "${it shr 20} MB") }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VersionRow(
    icon: @Composable () -> Unit, title: String, details: String, badges: List<String>,
    modifier: Modifier = Modifier, images: List<com.edgestream.tv.data.StreamBadges.Badge> = emptyList(), onClick: () -> Unit,
) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = EdgeShapes.Card, focusScale = 1.02f, glow = false) { focused ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                if (badges.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    badges.take(5).forEach { Pill(it, color = if (it == "Best match" || it == "Ready") Edge.Accent else Edge.PanelHi.copy(alpha = 1f)) }
                }
                if (images.isNotEmpty()) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    images.forEach { b -> coil3.compose.AsyncImage(b.image, b.name, Modifier.height(24.dp), contentScale = androidx.compose.ui.layout.ContentScale.FillHeight) }
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = Edge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
