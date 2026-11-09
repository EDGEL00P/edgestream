package com.edgestream.tv.ui.screens

import androidx.compose.ui.focus.focusRestorer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.edgestream.tv.data.Debrid
import com.edgestream.tv.data.Library
import com.edgestream.tv.ui.AmbientBackground
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.AppViewModel.DebridState
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.Screen
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.EmptyState
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Logo
import com.edgestream.tv.ui.components.OneLine
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.components.ProgressLine
import com.edgestream.tv.ui.components.Shelf
import kotlinx.coroutines.delay

/** The user's own TorBox cloud, laid out like a streaming service. */
@Composable
fun DebridScreen(vm: AppViewModel) {
    val state by vm.debrid.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Debrid.Title?>(null) }

    when (val s = state) {
        DebridState.NoKey -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            EmptyState("Connect your debrid account", "Add your debrid key in Settings → Extras to watch the films and series in your cloud.", icon = Icons.Rounded.CloudQueue)
            EdgeButton("Open Settings", { vm.screen.value = Screen.SETTINGS }, Modifier.padding(top = 24.dp), primary = true, icon = Icons.Rounded.Settings)
        }
        DebridState.Loading -> EmptyState("Loading your library", "Fetching your cloud library and matching titles to Cinemeta…", Modifier.fillMaxSize(), icon = Icons.Rounded.CloudQueue)
        is DebridState.Failed -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            EmptyState("Couldn't load your library", s.message, icon = Icons.Rounded.CloudQueue)
            EdgeButton("Try Again", { vm.refreshDebrid() }, Modifier.padding(top = 24.dp), primary = true, icon = Icons.Rounded.Refresh)
        }
        is DebridState.Ready -> Library(vm, s.library) { open = it }
    }
    open?.let { t -> TitleDetails(vm, t) { open = null } }
}

@Composable
private fun Library(vm: AppViewModel, lib: Debrid.Library, onOpen: (Debrid.Title) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { delay(60); runCatching { first.requestFocus() } } }
    if (lib.movies.isEmpty() && lib.series.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            EmptyState("Your cloud has no videos yet", "Films and episodes appear here once they've finished downloading to your cloud.", icon = Icons.Rounded.CloudQueue)
            EdgeButton("Refresh", { vm.refreshDebrid() }, Modifier.padding(top = 24.dp).focusRequester(first), icon = Icons.Rounded.Refresh)
        }
        return
    }
    var focused by remember { mutableStateOf(lib.movies.firstOrNull() ?: lib.series.firstOrNull()) }
    // The library: search, My list and Continue watching above the full shelves.
    var query by rememberSaveable { mutableStateOf("") }
    val watching by vm.continueWatching.collectAsStateWithLifecycle()
    val libState by vm.library.collectAsStateWithLifecycle()
    val debridWatching = watching.filter { it.key.startsWith("debrid:") }
    val saved by vm.watchList.collectAsStateWithLifecycle()
    val inList = (lib.movies + lib.series).filter { t -> saved.any { it.id == t.key } }
    val q = query.trim()
    val films = if (q.isEmpty()) lib.movies else lib.movies.filter { it.displayName.contains(q, true) }
    val shows = if (q.isEmpty()) lib.series else lib.series.filter { it.displayName.contains(q, true) }
    // Up next: series with a last episode and a following one in the cloud.
    val upNext = remember(libState.lastEpisode, lib) {
        libState.lastEpisode.values.mapNotNull { last ->
            val series = lib.series.firstOrNull { it.key == last.seriesKey } ?: return@mapNotNull null
            val ordered = series.episodes.sortedWith(compareBy({ it.season }, { it.episode }))
            val idx = ordered.indexOfFirst { it.file.id == last.episodeKey.removePrefix("debrid:") }
            val next = if (idx >= 0) ordered.getOrNull(idx + 1) else ordered.firstOrNull()
            if (next == null) null else series to next
        }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(focused?.meta?.background ?: focused?.poster, Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.75f).align(Alignment.TopEnd))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 28.dp, end = 42.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Column(Modifier.heightIn(min = 150.dp)) {
                    com.edgestream.tv.ui.components.ScreenHeader("Cloud", status = {
                        Text(
                            "${lib.movies.size} FILMS \u00b7 ${lib.series.size} SERIES",
                            style = MaterialTheme.typography.labelSmall, color = Edge.Accent, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    })
                    androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                    SearchField(query, placeholder = "Search your cloud\u2026", onChange = { query = it })
                }
            }
            if (debridWatching.isNotEmpty()) item {
                Shelf("Continue Watching") {
                    items(debridWatching, key = { it.key }) { p ->
                        FocusSurface(
                            onClick = { vm.resume(p) },
                            onLongClick = { vm.removeContinue(p.key) },
                            modifier = Modifier.width(com.edgestream.tv.ui.components.CardSize.Poster).aspectRatio(2f / 3f),
                            shape = EdgeShapes.Poster, focusScale = 1.08f,
                        ) {
                            Logo(p.poster, p.title, Modifier.fillMaxSize(), ContentScale.Crop)
                            val left = (p.durationMs - p.positionMs).coerceAtLeast(0)
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                val se = Regex("""S(\d+)E(\d+)""").find(p.title)?.let { "S${it.groupValues[1]} · E${it.groupValues[2]}" }
                                Text(
                                    listOfNotNull(se, if (left > 0) "${Format.clock(left)} left" else null).joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                ProgressLine(
                                    p.positionMs.toFloat() / p.durationMs.coerceAtLeast(1),
                                    Modifier.fillMaxWidth().padding(top = 4.dp), 3.dp,
                                )
                            }
                        }
                    }
                }
            }
            if (upNext.isNotEmpty()) item {
                Shelf("Up Next") {
                    items(upNext, key = { it.first.key }) { (series, next) ->
                        FocusSurface(
                            onClick = { onOpen(series) },
                            modifier = Modifier.width(com.edgestream.tv.ui.components.CardSize.Poster).aspectRatio(2f / 3f),
                            shape = EdgeShapes.Poster, focusScale = 1.08f,
                        ) { f ->
                            Logo(next.meta?.thumbnail ?: series.poster, next.meta?.name ?: series.displayName, Modifier.fillMaxSize(), ContentScale.Crop)
                            Pill("Next", Modifier.align(Alignment.TopStart).padding(8.dp), Edge.Accent2)
                            if (f || next.meta?.thumbnail == null) {
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xF0000000))))
                                Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                                    Text("S${next.season} · E${next.episode}", style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(series.displayName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            if (inList.isNotEmpty()) item {
                Shelf("My List") {
                    itemsIndexed(inList, key = { _, t -> t.key }) { i, t ->
                        PosterTile(
                            t, Modifier.then(if (i == 0 && debridWatching.isEmpty() && upNext.isEmpty()) Modifier.focusRequester(first) else Modifier).onFocusChanged { if (it.isFocused) focused = t },
                            onLongClick = { vm.toggleMyList(t.key, t.displayName, t.poster, movie = t.key.startsWith("movie|")) },
                        ) { onOpen(t) }
                    }
                }
            }
            if (films.isNotEmpty()) item {
                Shelf("Films") {
                    itemsIndexed(films, key = { _, t -> t.key }) { i, t ->
                        PosterTile(
                            t, Modifier.then(if (i == 0 && inList.isEmpty() && debridWatching.isEmpty() && upNext.isEmpty()) Modifier.focusRequester(first) else Modifier).onFocusChanged { if (it.isFocused) focused = t },
                            onLongClick = { vm.toggleMyList(t.key, t.displayName, t.poster, movie = t.key.startsWith("movie|")) },
                        ) { onOpen(t) }
                    }
                }
            }
            if (shows.isNotEmpty()) item {
                Shelf("Series") {
                    itemsIndexed(shows, key = { _, t -> t.key }) { i, t ->
                        PosterTile(
                            t, Modifier.onFocusChanged { if (it.isFocused) focused = t },
                            onLongClick = { vm.toggleMyList(t.key, t.displayName, t.poster, movie = t.key.startsWith("movie|")) },
                        ) { onOpen(t) }
                    }
                }
            }
            item { EdgeButton("Refresh Library", { vm.refreshDebrid() }, icon = Icons.Rounded.Refresh) }
        }
    }
}

@Composable
private fun PosterTile(t: Debrid.Title, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val watched = t.episodes.any { Library.isWatched("debrid:${it.file.id}") }
    FocusSurface(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(com.edgestream.tv.ui.components.CardSize.Poster).aspectRatio(2f / 3f), shape = EdgeShapes.Poster, focusScale = 1.08f) { f ->
        Logo(t.poster, t.displayName, Modifier.fillMaxSize(), ContentScale.Crop)
        Pill("Ready", Modifier.align(Alignment.TopStart).padding(8.dp), Edge.Accent2)
        t.meta?.rating?.let { Pill("★ $it", Modifier.align(Alignment.TopEnd).padding(8.dp), Edge.Gold) }
        if (watched) Pill("✓", Modifier.align(Alignment.BottomEnd).padding(8.dp), Edge.Accent)
        if (f || t.poster == null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xF0000000))))
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(t.displayName, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = if (t.type == "series") "${t.episodes.size} episode${if (t.episodes.size == 1) "" else "s"}" else t.displayYear
                sub?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** "2024 · 2h 47m · Action, Adventure  IMDb 8.4" */
@Composable
private fun MetaLine(t: Debrid.Title, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        t.meta?.rating?.let { Pill("IMDb $it", Modifier.padding(end = 12.dp), Edge.Gold) }
        val bits = listOfNotNull(
            t.displayYear,
            t.meta?.runtime,
            t.meta?.genres?.take(3)?.joinToString(", ")?.ifEmpty { null },
            if (t.type == "series") "${t.episodes.map { it.season }.distinct().size} season(s) in your cloud" else null,
        )
        Text(bits.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Artwork that dissolves into the ambient light at its left and bottom edges. */
@Composable
private fun Backdrop(url: String?, modifier: Modifier) {
    Crossfade(url, modifier, animationSpec = tween(600), label = "backdrop") { u ->
        if (u != null) AsyncImage(
            u, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = 0.55f }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.55f to Color.Black), blendMode = BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.6f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        )
    }
}

@Composable
private fun TitleDetails(vm: AppViewModel, t: Debrid.Title, close: () -> Unit) {
    BackHandler { close() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(t.key) { repeat(10) { delay(60); runCatching { playFocus.requestFocus() } } }
    val lib by vm.library.collectAsStateWithLifecycle()

    AmbientBackground(Modifier.fillMaxSize()) {
        Backdrop(t.meta?.background ?: t.poster, Modifier.fillMaxWidth(0.75f).fillMaxHeight().align(Alignment.TopEnd))
        Row(Modifier.fillMaxSize().padding(start = 56.dp, top = 44.dp, end = 48.dp)) {
            Column(Modifier.weight(1f)) {
                val logo = t.meta?.logo
                if (logo != null) AsyncImage(logo, t.displayName, Modifier.height(110.dp).widthIn(max = 520.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                else Text(t.displayName, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MetaLine(t, Modifier.padding(top = 16.dp))
                t.meta?.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp).widthIn(max = 720.dp))
                }
                t.meta?.director?.takeIf { it.isNotEmpty() }?.let { Text("Director  ${it.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
                t.meta?.cast?.takeIf { it.isNotEmpty() }?.let { Text("Starring  ${it.take(5).joinToString(", ")}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                if (t.meta == null) Text("No metadata match on Cinemeta: shown by file name.", style = MaterialTheme.typography.labelMedium, color = Edge.Faint, modifier = Modifier.padding(top = 8.dp))

                val inList = vm.watchList.collectAsStateWithLifecycle().value.any { it.id == t.key }
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EdgeButton(if (inList) "In My List" else "My List", { vm.toggleMyList(t.key, t.displayName, t.poster, movie = t.key.startsWith("movie|")) }, icon = if (inList) Icons.Rounded.Check else Icons.Rounded.Add)
                }

                if (t.type == "movie") MovieActions(vm, t, playFocus) else Episodes(vm, t, playFocus)
            }
        }
    }
}

@Composable
private fun MovieActions(vm: AppViewModel, t: Debrid.Title, focus: FocusRequester) {
    var version by remember { mutableIntStateOf(0) }
    val file = t.files.getOrNull(version) ?: t.files.firstOrNull()
    if (file == null) {
        Text(
            "No playable file in this entry.", style = MaterialTheme.typography.bodyMedium,
            color = Edge.Faint, modifier = Modifier.padding(top = 24.dp),
        )
        return
    }
    val key = "debrid:${file.id}"
    val progress by produceState<com.edgestream.tv.data.db.ProgressEntity?>(null, key) { value = vm.repo.progress(key) }
    fun play(fromStart: Boolean) = vm.playVod(
        key, t.displayName, t.poster, file.url, t.displayYear, fromStart,
        imdbId = t.meta?.imdbId,
    )

    Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val p = progress
        if (p != null && p.positionMs > 30_000) {
            EdgeButton("Resume from ${Format.clock(p.positionMs)}", { play(false) }, Modifier.focusRequester(focus), primary = true, icon = Icons.Rounded.PlayArrow)
            EdgeButton("Start Over", { play(true) }, icon = Icons.Rounded.Replay)
        } else {
            EdgeButton("Play", { play(false) }, Modifier.focusRequester(focus), primary = true, icon = Icons.Rounded.PlayArrow)
        }
        val watched = Library.isWatched(key)
        EdgeButton(if (watched) "Mark as Unwatched" else "Mark as Watched", { vm.toggleWatched(key) }, icon = Icons.Rounded.CheckCircle)
        if (p != null) EdgeButton("Remove from Continue Watching", { vm.removeContinue(key) }, icon = Icons.Rounded.Close)
    }
    progress?.let { p ->
        ProgressLine(
            p.positionMs.toFloat() / p.durationMs.coerceAtLeast(1),
            Modifier.width(420.dp).padding(top = 16.dp), 4.dp,
        )
        Text(
            "${Format.clock(p.positionMs)} watched · ${Format.clock((p.durationMs - p.positionMs).coerceAtLeast(0))} left",
            style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (t.files.size > 1) LazyRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(t.files) { i, f ->
            val sel = i == version
            com.edgestream.tv.ui.components.FilterChip(sel, { version = i }) {
                Text(
                    versionLabel(f), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    color = Edge.Text,
                )
            }
        }
    }
}

private fun versionLabel(f: Debrid.CloudFile): String {
    val q = Regex("""(?i)\b(2160p|4k|1080p|720p|480p)\b""").find(f.fileName)?.value?.uppercase()?.replace("4K", "2160P")
    val hdr = Regex("""(?i)\b(DV|DoVi|HDR10\+?|HDR)\b""").find(f.fileName)?.value?.uppercase()
    val gb = "%.1f GB".format(f.size / 1_073_741_824.0)
    return listOfNotNull(q?.lowercase(), hdr, gb).joinToString(" · ")
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Episodes(vm: AppViewModel, t: Debrid.Title, focus: FocusRequester) {
    val seasons = remember(t) { t.episodes.map { it.season }.distinct() }
    val ordered = remember(t) { t.episodes.sortedWith(compareBy({ it.season }, { it.episode })) }
    val epKeys = remember(t) { ordered.map { "debrid:${it.file.id}" } }
    val progressMap by produceState<Map<String, com.edgestream.tv.data.db.ProgressEntity>>(emptyMap(), t.key, epKeys) {
        value = vm.repo.progressMap(epKeys)
    }
    val lib by vm.library.collectAsStateWithLifecycle()
    fun finished(ep: Debrid.Episode): Boolean {
        val p = progressMap["debrid:${ep.file.id}"] ?: return false
        return p.durationMs > 0 && p.positionMs.toFloat() / p.durationMs >= 0.95f
    }
    val resumeEp = ordered.firstOrNull { ep ->
        val p = progressMap["debrid:${ep.file.id}"]
        p != null && !finished(ep) && p.positionMs > 30_000
    }
    val nextEp = resumeEp ?: ordered.firstOrNull { ep -> "debrid:${ep.file.id}" !in lib.watched && !finished(ep) } ?: ordered.firstOrNull()
    var season by remember(t.key) { mutableIntStateOf(seasons.indexOf(nextEp?.season ?: seasons.firstOrNull()).coerceAtLeast(0)) }
    val seasonValue = seasons.getOrNull(season)
    // Primary action.
    if (nextEp != null) {
        val p = progressMap["debrid:${nextEp.file.id}"]
        val label = when {
            p != null && p.positionMs > 30_000 -> "Resume S${nextEp.season} · E${nextEp.episode} · ${Format.clock((p.durationMs - p.positionMs).coerceAtLeast(0))} left"
            else -> "Play S${nextEp.season} · E${nextEp.episode}"
        }
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            EdgeButton(
                label,
                { vm.playVod(
                    "debrid:${nextEp.file.id}", "${t.displayName} · S${nextEp.season}E${nextEp.episode}",
                    nextEp.meta?.thumbnail ?: t.poster, nextEp.file.url, nextEp.meta?.name ?: "Episode ${nextEp.episode}",
                    imdbId = t.meta?.imdbId, season = nextEp.season, episode = nextEp.episode,
                ) },
                Modifier.focusRequester(focus), primary = true, icon = Icons.Rounded.PlayArrow,
            )
        }
        (nextEp.meta?.name ?: "").ifBlank { "" }.let { name ->
            if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.labelLarge, color = Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
    LazyRow(Modifier.padding(top = 14.dp).focusRestorer(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(seasons) { i, s ->
            val sel = i == season
            com.edgestream.tv.ui.components.FilterChip(sel, { season = i }) {
                Text(
                    if (s == 0) "Specials" else "Season $s", style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    color = Edge.Text,
                )
            }
        }
    }
    LazyColumn(Modifier.padding(top = 12.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 28.dp, end = 8.dp)) {
        items(ordered.filter { it.season == seasonValue }, key = { it.file.id }) { ep ->
            val key = "debrid:${ep.file.id}"
            val name = ep.meta?.name ?: "Episode ${ep.episode}"
            val watched = key in lib.watched || finished(ep)
            val p = progressMap[key]
            val isNext = ep.file.id == nextEp?.file?.id
            FocusSurface(
                onClick = {
                    vm.playVod(
                        key, "${t.displayName} · S${ep.season}E${ep.episode}",
                        ep.meta?.thumbnail ?: t.poster, ep.file.url, name,
                        imdbId = t.meta?.imdbId, season = ep.season, episode = ep.episode,
                    )
                },
                onLongClick = { vm.toggleWatched(key) },
                modifier = Modifier.fillMaxWidth().height(96.dp), focusScale = 1.02f, glow = false,
            ) {
                Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Logo(ep.meta?.thumbnail ?: t.meta?.background, name, Modifier.fillMaxHeight().aspectRatio(16f / 9f), ContentScale.Crop)
                        if (watched) Pill("✓", Modifier.align(Alignment.BottomEnd).padding(4.dp), Edge.Accent)
                    }
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OneLine("${ep.episode}.  $name", style = MaterialTheme.typography.titleMedium)
                            if (isNext) Pill("Next", color = Edge.Accent2)
                            if (watched) OneLine("✓", Modifier, MaterialTheme.typography.titleMedium, Edge.Accent)
                        }
                        ep.meta?.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                        if (p != null && !watched) {
                            Text(
                                "${Format.clock(p.positionMs)} watched · ${Format.clock((p.durationMs - p.positionMs).coerceAtLeast(0))} left",
                                style = MaterialTheme.typography.labelMedium, color = Edge.Faint, modifier = Modifier.padding(top = 2.dp),
                            )
                            ProgressLine(p.positionMs.toFloat() / p.durationMs.coerceAtLeast(1), Modifier.fillMaxWidth().padding(top = 6.dp), 3.dp)
                        }
                        if (watched) Text("Watched", style = MaterialTheme.typography.labelMedium, color = Edge.Accent, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("%.1f GB".format(ep.file.size / 1_073_741_824.0), style = MaterialTheme.typography.labelMedium, color = Edge.Faint)
                }
            }
        }
    }
}
