package com.edgestream.tv.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgestream.tv.data.Library
import com.edgestream.tv.data.db.ProgressEntity
import com.edgestream.tv.data.db.VodEntity
import com.edgestream.tv.data.db.VodKind
import com.edgestream.tv.data.parse.XtreamApi
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.bleed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import com.edgestream.tv.ui.glass
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.EmptyState
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Logo
import com.edgestream.tv.ui.components.OneLine
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.components.ProgressLine
import com.edgestream.tv.ui.components.SectionTitle
import kotlinx.coroutines.delay

/**
 * On Demand: one tab, with a Movies / TV Shows switch above the browse page. The switch sets
 * vm.screen (MOVIES or SERIES), so deep links and the phone remote land on the right side.
 */
@Composable
fun VodScreen(vm: AppViewModel, kind: VodKind) {
    var art by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // The featured title's art fills the screen behind the page, edge to edge (past the safe
    // area and the section's own 8 dp): one picture, never a box over the room's backdrop.
    androidx.compose.animation.Crossfade(art, Modifier.fillMaxSize().bleed(com.edgestream.tv.ui.LocalSafeArea.current, com.edgestream.tv.ui.shell.SECTION_PAD), animationSpec = androidx.compose.animation.core.tween(400), label = "art") { url ->
        if (url != null) coil3.compose.AsyncImage(
            url, null,
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen; alpha = 0.8f }
                .drawWithContent {
                    drawContent()
                    // Clear under the title and shelves (left, lower half), sharp top right.
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black, 0.75f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    drawRect(Brush.horizontalGradient(0.1f to Color.Transparent, 0.55f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                },
            contentScale = ContentScale.Crop,
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KindSwitch("Movies", kind == VodKind.MOVIE) { vm.screen.value = com.edgestream.tv.ui.Screen.MOVIES }
            KindSwitch("TV Shows", kind == VodKind.SERIES) { vm.screen.value = com.edgestream.tv.ui.Screen.SERIES }
            // Anything by name, not only what the provider lists (Search covers the universal catalogue).
            KindSwitch("Search", false) { vm.screen.value = com.edgestream.tv.ui.Screen.SEARCH }
        }
        Box(Modifier.weight(1f)) { androidx.compose.runtime.key(kind) { BrowseScreen(vm, kind) { art = it } } }
    }
}

@Composable
private fun KindSwitch(label: String, chosen: Boolean, onClick: () -> Unit) {
    com.edgestream.tv.ui.components.FocusSurface(
        onClick = onClick, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(50),
        color = if (chosen) Edge.Accent else Edge.Text.copy(alpha = 0.08f), focusedColor = Edge.Text,
        focusScale = 1.06f, glow = false,
    ) { focused ->
        Text(
            label, style = MaterialTheme.typography.labelLarge,
            color = when { focused -> Edge.Bg; chosen -> Edge.OnAccent; else -> Edge.Text },
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 20.dp),
        )
    }
}

@Composable
fun VodDetails(vm: AppViewModel, item: VodEntity) {
    BackHandler { vm.vodDetail.value = null }
    val progress by produceState<ProgressEntity?>(null, item.key) { value = vm.repo.progress(item.key) }
    val series by produceState<Result<XtreamApi.SeriesInfo?>?>(null, item.key) {
        value = if (item.kind == VodKind.SERIES) runCatching { vm.repo.seriesInfo(item) } else Result.success(null)
    }
    val info by produceState<com.edgestream.tv.data.PublicApis.Meta?>(null, item.key) { value = vm.repo.metadata(item) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(item.key, series != null) { repeat(10) { delay(60); runCatching { playFocus.requestFocus() } } }
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val debrid = appSettings.streamAddons.isNotBlank() || appSettings.torboxApiKey.isNotBlank()
    // Per-episode resume positions so the list can show bars + ticks.
    val episodesList = series?.getOrNull()?.episodes.orEmpty()
    val epKeys = remember(item.key, episodesList) { episodesList.map { "${item.key}:E:${it.id}" } }
    val progressMap by produceState<Map<String, ProgressEntity>>(emptyMap(), item.key, epKeys) {
        value = if (item.kind == VodKind.SERIES && epKeys.isNotEmpty()) vm.repo.progressMap(epKeys) else emptyMap()
    }
    val safe = com.edgestream.tv.ui.LocalSafeArea.current

    Box(Modifier.fillMaxSize()) {
        // Picture and shade run edge to edge, past the overscan inset the text keeps to: no box.
        com.edgestream.tv.ui.AmbientBackground(Modifier.bleed(safe).background(Edge.Bg)) {
            Logo(info?.backdrop ?: item.poster, item.name, Modifier.fillMaxSize().alpha(0.35f), ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Edge.Bg.copy(alpha = 0.96f), Edge.Bg.copy(alpha = 0.86f), Edge.Bg.copy(alpha = 0.72f)))))
        }
        when (item.kind) {
            VodKind.MOVIE -> Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
                Logo(item.poster ?: info?.poster, item.name, Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)), ContentScale.Crop)
                Column(Modifier.weight(1f).padding(start = 36.dp)) {
                    DetailsHeader(item, info, null, plotLines = 4)
                    info?.director?.let { Text("Director  $it", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
                    info?.cast?.let { Text("Cast  $it", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                    MovieActions(vm, item, progress, vm.watchList.collectAsStateWithLifecycle().value.any { it.id == item.key }, lib.watched.contains(item.key), debrid, playFocus)
                }
            }
            VodKind.SERIES -> SeriesDetails(vm, item, info, series, progressMap, lib, playFocus)
        }
    }
}

@Composable
private fun DetailsHeader(item: VodEntity, info: com.edgestream.tv.data.PublicApis.Meta?, seriesPlot: String?, plotLines: Int) {
    Text(com.edgestream.tv.data.NameCleaner.tidyTitle(item.name), style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    val meta = listOfNotNull(
        item.year ?: info?.year, (item.rating ?: info?.rating)?.let { "★ $it" },
        info?.durationMin?.let { Format.duration(it * 60_000L) }, info?.genre ?: item.category.takeUnless { com.edgestream.tv.data.Repository.isCatalogue(item.key) },
    ).joinToString("  ·  ")
    Text(meta, style = MaterialTheme.typography.labelLarge, color = Edge.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    val plot = info?.plot ?: item.plot ?: seriesPlot
    if (!plot.isNullOrBlank()) Text(plot, style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, maxLines = plotLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
    info?.credit?.let { Text("Details from $it", style = MaterialTheme.typography.labelMedium, color = Edge.Faint, maxLines = 1, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun MovieActions(
    vm: AppViewModel, item: VodEntity, progress: ProgressEntity?, inList: Boolean, watched: Boolean,
    debrid: Boolean, focus: FocusRequester,
) {
    Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val p = progress
        val target = vm.movieTarget(item)
        val resumeAt = p?.positionMs?.takeIf { it > 30_000 }
        val verb = if (resumeAt != null) "Resume" else "Play"
        if (debrid) {
            EdgeButton("$verb", { vm.vodDetail.value = null; vm.playPreferred(target) }, Modifier.focusRequester(focus), primary = true, icon = Icons.Rounded.Bolt)
            EdgeButton("$verb from Provider", { vm.vodDetail.value = null; vm.playProvider(target) }, icon = Icons.Rounded.LiveTv)
        } else {
            EdgeButton(if (resumeAt != null) "Resume from ${Format.clock(resumeAt)}" else "Play", { vm.vodDetail.value = null; vm.playProvider(target) }, Modifier.focusRequester(focus), primary = true, icon = Icons.Rounded.PlayArrow)
        }
        if (resumeAt != null) EdgeButton("Start Over", { vm.vodDetail.value = null; if (debrid) vm.playPreferred(target, fromStart = true) else vm.playProvider(target, fromStart = true) }, icon = Icons.Rounded.Replay)
        EdgeButton("Sources", { vm.openVersions(target) }, icon = Icons.Rounded.Layers)
        EdgeButton(if (inList) "In My List" else "My List", { vm.toggleMyList(item) }, icon = if (inList) Icons.Rounded.Check else Icons.Rounded.Add)
    }
    if (progress != null || watched) {
        Column(Modifier.padding(top = 14.dp)) {
            if (progress != null) {
                ProgressLine(
                    progress.positionMs.toFloat() / progress.durationMs.coerceAtLeast(1),
                    Modifier.width(420.dp), 4.dp,
                )
                val left = (progress.durationMs - progress.positionMs).coerceAtLeast(0)
                Text(
                    "${Format.clock(progress.positionMs)} watched · ${Format.clock(left)} left",
                    style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (watched) EdgeButton("Mark as Unwatched", { vm.toggleWatched(item.key) }, icon = Icons.Rounded.VisibilityOff)
                else EdgeButton("Mark as Watched", { vm.toggleWatched(item.key) }, icon = Icons.Rounded.CheckCircle)
                if (progress != null) EdgeButton("Remove from Continue Watching", { vm.removeContinue(item.key) }, icon = Icons.Rounded.Close)
            }
        }
    }
}

/**
 * A series: what it is and what plays next on the left, the season's episodes on the right with
 * the full height of the screen, so each episode's title and synopsis can actually be read.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeriesDetails(
    vm: AppViewModel, item: VodEntity, meta: com.edgestream.tv.data.PublicApis.Meta?, series: Result<XtreamApi.SeriesInfo?>?,
    progressMap: Map<String, ProgressEntity>, lib: com.edgestream.tv.data.LibraryState, focus: FocusRequester,
) {
    val info = series?.getOrNull()
    fun keyOf(ep: XtreamApi.Episode) = "${item.key}:E:${ep.id}"
    fun isFinished(ep: XtreamApi.Episode): Boolean {
        val p = progressMap[keyOf(ep)] ?: return false
        return p.durationMs > 0 && p.positionMs.toFloat() / p.durationMs >= 0.95f
    }
    val episodes = info?.episodes.orEmpty()
    LaunchedEffect(item.key, episodes) { if (info != null) vm.setEpisodeQueue(item, episodes) }
    // Pick: resume the episode in progress, else the first unwatched one, else S1E1.
    val resumeEp = episodes.firstOrNull { ep -> val p = progressMap[keyOf(ep)]; p != null && !isFinished(ep) && p.positionMs > 30_000 }
    val nextEp = resumeEp ?: episodes.firstOrNull { ep -> keyOf(ep) !in lib.watched && !isFinished(ep) } ?: episodes.firstOrNull()
    val seasons = episodes.map { it.season }.distinct()
    // Keyed on the series, so opening a different one never inherits a stale season.
    var seasonIdx by remember(item.key, seasons.size) { mutableIntStateOf(seasons.indexOf(nextEp?.season ?: seasons.firstOrNull()).coerceAtLeast(0)) }
    val season = seasons.getOrNull(seasonIdx)

    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Column(Modifier.weight(0.42f).padding(end = 40.dp)) {
            DetailsHeader(item, meta, info?.plot, plotLines = 5)
            when {
                series == null -> CircularProgressIndicator(Modifier.padding(top = 28.dp).size(32.dp), color = Edge.Accent)
                info == null -> {
                    Text(
                        "Couldn't load episodes: ${series.exceptionOrNull()?.message ?: "not available for this source"}",
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 24.dp),
                    )
                    EdgeButton("Back", { vm.vodDetail.value = null }, Modifier.padding(top = 12.dp).focusRequester(focus))
                }
                nextEp != null -> {
                    val p = progressMap[keyOf(nextEp)]
                    val label = when {
                        p != null && p.positionMs > 30_000 -> "Resume S${nextEp.season} · E${nextEp.number}"
                        resumeEp == null && keyOf(nextEp) !in lib.watched && episodes.any { keyOf(it) in lib.watched } -> "Next S${nextEp.season} · E${nextEp.number}"
                        else -> "Play S${nextEp.season} · E${nextEp.number}"
                    }
                    val inList = vm.watchList.collectAsStateWithLifecycle().value.any { it.id == item.key }
                    Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EdgeButton(label, {
                            val t = vm.episodeTarget(item, nextEp)
                            vm.vodDetail.value = null
                            if (vm.debridAvailable) vm.playPreferred(t) else vm.playProvider(t)
                        }, Modifier.focusRequester(focus), primary = true, icon = Icons.Rounded.PlayArrow)
                        EdgeButton(if (inList) "In My List" else "My List", { vm.toggleMyList(item) }, icon = if (inList) Icons.Rounded.Check else Icons.Rounded.Add)
                    }
                    if (p != null && p.positionMs > 30_000) Text(
                        "${Format.clock((p.durationMs - p.positionMs).coerceAtLeast(0))} left",
                        style = MaterialTheme.typography.labelMedium, color = Edge.Faint, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        if (info != null) Column(Modifier.weight(0.58f)) {
            if (seasons.size > 1) LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons.size) { i ->
                    com.edgestream.tv.ui.components.FilterChip(i == seasonIdx, { seasonIdx = i }) {
                        Text(if (seasons[i] == 0) "Episodes" else "Season ${seasons[i]}", style = MaterialTheme.typography.labelLarge, maxLines = 1, color = Edge.Text)
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp, end = 8.dp),
            ) {
                items(episodes.filter { it.season == season }, key = { it.id }) { ep ->
                    EpisodeRow(vm, item, ep, progressMap[keyOf(ep)], watched = keyOf(ep) in lib.watched || isFinished(ep), isNext = ep.id == nextEp?.id) {
                        vm.toggleWatched(keyOf(ep))
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    vm: AppViewModel, item: VodEntity, ep: XtreamApi.Episode, progress: ProgressEntity?,
    watched: Boolean, isNext: Boolean, onToggleWatched: () -> Unit,
) {
    FocusSurface(
        onClick = {
            val t = vm.episodeTarget(item, ep)
            if (vm.debridAvailable) vm.openVersions(t) else { vm.vodDetail.value = null; vm.playProvider(t) }
        },
        onLongClick = onToggleWatched,
        modifier = Modifier.fillMaxWidth().height(104.dp), focusScale = 1.02f, glow = false,
    ) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                Logo(ep.image ?: item.poster, ep.title, Modifier.fillMaxSize(), ContentScale.Crop)
                if (progress != null && !watched) ProgressLine(
                    progress.positionMs.toFloat() / progress.durationMs.coerceAtLeast(1),
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(), 3.dp,
                )
            }
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val title = com.edgestream.tv.data.NameCleaner.episodeTitle(ep.title, item.name)
                    OneLine(if (title != null) "${ep.number}. $title" else "Episode ${ep.number}", Modifier.weight(1f, fill = false), MaterialTheme.typography.titleMedium)
                    if (isNext) Pill("Next", color = Edge.Accent2)
                    if (watched) androidx.compose.material3.Icon(Icons.Rounded.CheckCircle, "Watched", Modifier.size(18.dp), tint = Edge.Accent)
                }
                ep.plot?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                ep.durationSecs?.let { Text(Format.duration(it * 1000), style = MaterialTheme.typography.labelMedium, color = Edge.Faint, modifier = Modifier.padding(top = 2.dp)) }
            }
        }
    }
}
