package com.edgestream.tv.ui.screens

import com.edgestream.tv.ui.components.requestFocusWithRetry
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgestream.tv.data.NameCleaner
import com.edgestream.tv.data.db.VodKind
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.BrowseData
import com.edgestream.tv.ui.BrowseItem
import com.edgestream.tv.ui.BrowsePage
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.components.CardSize
import com.edgestream.tv.ui.components.CollectionTile
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Logo
import com.edgestream.tv.ui.components.Shelf
import com.edgestream.tv.ui.components.TitleTile
import com.edgestream.tv.ui.components.WideTile
import com.edgestream.tv.ui.components.loadingPulse
import com.edgestream.tv.ui.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Movies and Series as one page: a stepping marquee of featured titles, curated rows (Now in
 * cinemas, New releases, Trending, Top rated, Coming soon), Recently added, then genre and
 * collection tiles that open a full grid. Everything is built from the shared card system.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrowseScreen(vm: AppViewModel, kind: VodKind, onArt: (String?) -> Unit = {}) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val debrid by vm.debrid.collectAsStateWithLifecycle()
    val continuing by vm.continueWatching.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    // On Demand is the universal catalogue; the provider is only a play source.
    val page by produceState<BrowsePage?>(null, kind, settings.tmdbApiKey) {
        value = runCatching { BrowseData.load(vm, kind) }.getOrNull()
    }
    var openCollection by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var openFolder by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    val openCat = page?.catalogCollections?.flatMap { it.second }?.firstOrNull { it.id == openCollection }

    val scope = rememberCoroutineScope()
    fun openItem(item: BrowseItem) {
        val key = item.vodKey
        if (key != null) scope.launch { vm.repo.vodByKey(key)?.let { vm.vodDetail.value = it } }
        else vm.openUniversal(item, movies = kind == VodKind.MOVIE)
    }
    if (openCat != null) { CollectionGrid(vm, kind, openCat, ::openItem) { openCollection = null }; return }
    val folder = page?.groups?.flatMap { it.folders }?.firstOrNull { it.id == openFolder }
    if (folder != null) { FolderPage(vm, folder, onArt, ::openItem) { openFolder = null }; return }

    var featured by remember { mutableStateOf<BrowseItem?>(null) }
    val p = page
    Box(Modifier.fillMaxSize()) {
        // VodScreen draws the featured title's art edge to edge behind the whole page.
        val art = (featured ?: p?.marquee?.firstOrNull())?.let { it.backdrop ?: it.poster }
        LaunchedEffect(art) { onArt(art) }
        if (p == null) { Skeleton(); return@Box }

        val firstFocus = remember { FocusRequester() }
        val listState = rememberLazyListState()
        // Back from deep in the page climbs to the top (first tile) before the dock.
        val scrollToTop = com.edgestream.tv.ui.LocalScrollToTop.current
        val toTop: suspend () -> Unit = remember(listState, firstFocus) {
            { listState.animateScrollToItem(0); runCatching { firstFocus.requestFocus() } }
        }
        androidx.compose.runtime.DisposableEffect(scrollToTop, toTop) {
            scrollToTop.register(atTop = { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }, scrollToTop = toTop)
            onDispose { scrollToTop.unregister(toTop) }
        }
        // Shelves: Continue Watching first, then Up Next + My List, then curated rows.
        // Hoisted out of the LazyColumn DSL: remember/produceState are composable-only.
        val cloudForFilter = debrid as? AppViewModel.DebridState.Ready
        val cloudMovieIds = remember(cloudForFilter) {
            cloudForFilter?.library?.movies.orEmpty().flatMap { m -> m.files.map { it.id } }.toHashSet()
        }
        val cloudSeriesIds = remember(cloudForFilter) {
            cloudForFilter?.library?.series.orEmpty().flatMap { s -> s.episodes.map { it.file.id } }.toHashSet()
        }
        fun isKind(key: String): Boolean {
            if (key.startsWith("debrid:")) {
                if (cloudForFilter == null) return true
                val fid = key.removePrefix("debrid:")
                return if (kind == VodKind.MOVIE) fid in cloudMovieIds else fid in cloudSeriesIds
            }
            return if (kind == VodKind.MOVIE) ":M:" in key else ":S:" in key
        }
        val cw = remember(continuing, kind, debrid) { continuing.filter { isKind(it.key) }.take(20) }
        val upNext = remember(lib.lastEpisode, kind) {
            lib.lastEpisode.values.filter { last ->
                kind == VodKind.SERIES && (":S:" in last.seriesKey) && !last.seriesKey.startsWith("debrid:")
            }.sortedByDescending { it.updatedAt }.take(12)
        }
        val upNextVods by produceState<Map<String, com.edgestream.tv.data.db.VodEntity>>(emptyMap(), upNext) {
            value = vm.repo.vodByKeys(upNext.map { it.seriesKey }).associateBy { it.key }
        }
        val saved by vm.watchList.collectAsStateWithLifecycle()
        val mine = remember(saved, kind) { saved.filter { it.movie == (kind == VodKind.MOVIE) } }
        val myKeys = mine.map { it.id }
        val myVods by produceState<Map<String, com.edgestream.tv.data.db.VodEntity>>(emptyMap(), myKeys) {
            value = vm.repo.vodByKeys(myKeys.filter { ":M:" in it || ":S:" in it }).associateBy { it.key }
        }
        // The marquee's first tile is built a frame or two after the page; keep asking until it
        // takes focus. Only on the first paint: the curated phase must never yank the scroll.
        var primed by remember(kind) { mutableStateOf(false) }
        LaunchedEffect(p) {
            if (primed) return@LaunchedEffect
            primed = true
            listState.scrollToItem(0)
            firstFocus.requestFocusWithRetry(tries = 20, gapMs = 50)
        }
        LazyColumn(
            Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(start = 14.dp, end = 40.dp, top = 18.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (p.marquee.isNotEmpty()) item(key = "marquee") {
                HeroBanner(p.marquee, firstFocus, onFocus = { featured = it }, onOpen = ::openItem)
            }
            if (cw.isNotEmpty()) item(key = "cw") {
                Shelf("Continue Watching · ${cw.size}") {
                    items(cw, key = { it.key }) { prog ->
                        ContinueTile(prog, onClick = { vm.resume(prog) }, onLongClick = { vm.removeContinue(prog.key) })
                    }
                }
            }
            if (upNext.isNotEmpty()) item(key = "upnext") {
                Shelf("Up Next") {
                    items(upNext, key = { it.seriesKey }) { last ->
                        val vod = upNextVods[last.seriesKey]
                        FocusSurface(
                            onClick = { if (vod != null) vm.vodDetail.value = vod else vm.showNotice("Still loading\u2026") },
                            modifier = Modifier.width(com.edgestream.tv.ui.components.CardSize.Poster).aspectRatio(2f / 3f),
                            shape = EdgeShapes.Poster, focusScale = 1.08f,
                        ) { f ->
                            Logo(vod?.poster ?: last.poster, vod?.name ?: last.title, Modifier.fillMaxSize(), ContentScale.Crop)
                            com.edgestream.tv.ui.components.Pill("Next", Modifier.align(Alignment.TopStart).padding(8.dp), Edge.Accent2)
                            if (f || vod?.poster == null) {
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xF0000000))))
                                Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                                    Text("Next: S${last.season} · E${last.episode + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(com.edgestream.tv.data.NameCleaner.tidyTitle(vod?.name ?: last.title), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            if (myKeys.isNotEmpty()) item(key = "mylist") {
                Shelf("My List") {
                    items(myKeys, key = { it }) { key ->
                        val vod = myVods[key]
                        val w = mine.first { it.id == key }
                        val cloud = (debrid as? AppViewModel.DebridState.Ready)?.library?.let { lib ->
                            (lib.movies + lib.series).firstOrNull { it.key == key }
                        }
                        FocusSurface(
                            onClick = {
                                when {
                                    vod != null -> vm.vodDetail.value = vod
                                    cloud != null -> vm.screen.value = com.edgestream.tv.ui.Screen.DEBRID
                                    else -> vm.openWatchItem(w)
                                }
                            },
                            onLongClick = { vm.toggleWatchList(w) },
                            modifier = Modifier.width(com.edgestream.tv.ui.components.CardSize.Poster).aspectRatio(2f / 3f),
                            shape = EdgeShapes.Poster, focusScale = 1.08f,
                        ) { f ->
                            Logo(vod?.poster ?: cloud?.poster ?: w.poster, vod?.name ?: cloud?.displayName ?: w.title, Modifier.fillMaxSize(), ContentScale.Crop)
                            if (cloud != null) com.edgestream.tv.ui.components.Pill("Cloud", Modifier.align(Alignment.TopStart).padding(8.dp), Edge.Accent2)
                            if (f || (vod?.poster == null && cloud?.poster == null && w.poster == null)) {
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xF0000000))))
                                Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                                    Text(com.edgestream.tv.data.NameCleaner.tidyTitle(vod?.name ?: cloud?.displayName ?: w.title), style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            p.shelves.forEach { shelf ->
                item(key = "shelf:${shelf.title}") {
                    Shelf(com.edgestream.tv.data.NameCleaner.tidyTitle(shelf.title), count = shelf.items.size) {
                        items(shelf.items, key = { it.id }) { item ->
                            TitleTile(
                                item.title, item.poster,
                                // ★ marks titles your provider carries; everything else plays through TorBox.
                                badge = when {
                                    shelf.comingSoon -> item.releaseDate?.let(::shortDate) ?: "Soon"
                                    item.vodKey != null -> "★"
                                    else -> null
                                },
                                meta = listOfNotNull(item.year, item.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null },
                                dimmed = shelf.comingSoon,
                                onFocus = { featured = item },
                            ) { openItem(item) }
                        }
                    }
                }
            }
            // One library: the provider's titles and the TorBox cloud side by side.
            val cloud = debrid as? AppViewModel.DebridState.Ready
            val cloudTitles = cloud?.library?.let { if (kind == VodKind.MOVIE) it.movies else it.series }.orEmpty()
            if (cloudTitles.isNotEmpty()) item(key = "cloud") {
                Shelf("Cloud Library") {
                    items(cloudTitles, key = { it.key }) { t ->
                        FocusSurface(
                            onClick = {
                                val episode = if (kind == VodKind.MOVIE) null else t.episodes.firstOrNull()
                                val file = if (kind == VodKind.MOVIE) t.files.firstOrNull() else episode?.file
                                if (file != null) {
                                    vm.playVod(
                                        "debrid:${file.id}", t.displayName, t.poster, file.url, t.displayYear,
                                        imdbId = t.meta?.imdbId, season = episode?.season, episode = episode?.episode,
                                    )
                                }
                            },
                            modifier = Modifier.width(com.edgestream.tv.ui.components.CardSize.Poster).aspectRatio(2f / 3f),
                            shape = EdgeShapes.Poster, focusScale = 1.08f,
                        ) { f ->
                            Logo(t.poster, t.displayName, Modifier.fillMaxSize(), ContentScale.Crop)
                            com.edgestream.tv.ui.components.Pill("Ready", Modifier.align(Alignment.TopStart).padding(8.dp), Edge.Accent2)
                            if (f || t.poster == null) {
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xF0000000))))
                                Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                                    Text(t.displayName, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            // The viewer's own collections: each folder opens its sources as rows.
            p.groups.forEach { group ->
                item(key = "collection:${group.id}") {
                    Shelf(group.title) {
                        items(group.folders, key = { it.id }) { f ->
                            WideTile(
                                f.title, image = f.cover,
                                caption = if (f.sources.size > 1) "${f.sources.size} lists" else "1 list",
                                onFocus = { onArt(f.hero ?: f.cover) },
                            ) { openFolder = f.id }
                        }
                    }
                }
            }
            // Universal collections (TMDB): streaming services, studios, franchises, themes, decades.
            p.catalogCollections.forEach { (group, list) ->
                item(key = "cc:${group.name}") {
                    Shelf(group.label) {
                        items(list, key = { it.id }) { c ->
                            if (group == com.edgestream.tv.data.Catalog.Group.SERVICE) CollectionTile(c.name, null) { openCollection = c.id }
                            else WideTile(c.name, caption = group.label, tint = tintFor(c.name)) { openCollection = c.id }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The hero: the featured title large over its backdrop, with its meta, story and
 * actions; steps to the next title every 6 s until the viewer takes the remote.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HeroBanner(items: List<BrowseItem>, first: FocusRequester, onFocus: (BrowseItem) -> Unit, onOpen: (BrowseItem) -> Unit) {
    var current by remember(items) { mutableStateOf(items.first()) }
    var focusedIn by remember { mutableStateOf(false) }
    LaunchedEffect(items, focusedIn) {
        while (!focusedIn && items.size > 1) {
            delay(6_000)
            val next = items[(items.indexOf(current) + 1).mod(items.size)]
            current = next
            onFocus(next)
        }
    }
    val c = current
    Row(Modifier.onFocusChanged { focusedIn = it.hasFocus }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Commercial hero: the poster's artwork beside the title, like every streaming app.
        Logo(c.poster, c.title, Modifier.height(190.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)), ContentScale.Crop)
        Column(Modifier.padding(start = 26.dp).weight(1f)) {
        Text(
            com.edgestream.tv.data.NameCleaner.tidyTitle(c.title),
            style = MaterialTheme.typography.displayMedium,
            fontFamily = com.edgestream.tv.ui.glass.ClockFont,
            fontSize = 72.sp, lineHeight = 74.sp, letterSpacing = 0.8.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(c.year, c.rating?.let { "★ $it" }).joinToString("  ·  ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelLarge, color = Edge.Accent, modifier = Modifier.padding(top = 6.dp))
        c.overview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).width(720.dp))
        }
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.edgestream.tv.ui.components.EdgeButton(
                "Play", { onOpen(c) }, Modifier.focusRequester(first), primary = true,
                icon = Icons.Rounded.PlayArrow,
            )
            com.edgestream.tv.ui.components.EdgeButton("Details", { onOpen(c) }, icon = Icons.Rounded.Movie)
        }
        if (items.size > 1) {
            val idx = items.indexOf(current).coerceAtLeast(0)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.indices.forEach { i ->
                    Box(
                        Modifier.size(width = if (i == idx) 20.dp else 6.dp, height = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (i == idx) Edge.Accent else Edge.Faint.copy(alpha = 0.5f)),
                    )
                }
            }
        }
        }
    }
}

/** A collection folder: one row per source, its cover art behind the page. */
@Composable
private fun FolderPage(
    vm: AppViewModel, folder: com.edgestream.tv.data.CuratedCollections.Folder, onArt: (String?) -> Unit,
    onOpen: (BrowseItem) -> Unit, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(folder.id) { onArt(folder.hero ?: folder.cover) }
    val key = vm.settings.collectAsStateWithLifecycle().value.tmdbApiKey
    val cache = com.edgestream.tv.EdgeStreamApp.instance.cacheDir
    val first = remember { FocusRequester() }
    LazyColumn(
        Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(start = 14.dp, end = 40.dp, top = 18.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "title") {
            Text(folder.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
        folder.sources.forEachIndexed { i, source ->
            item(key = "src:$i") {
                val titles by produceState<List<com.edgestream.tv.data.Catalog.Title>?>(null, source) {
                    value = com.edgestream.tv.data.CuratedCollections.titles(source, key, cache)
                }
                if (i == 0) LaunchedEffect(titles?.isNotEmpty()) { if (titles?.isNotEmpty() == true) first.requestFocusWithRetry() }
                val list = titles
                when {
                    list == null -> Text("Loading ${source.title}…", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.padding(start = 8.dp))
                    list.isEmpty() -> Unit
                    else -> Shelf(source.title.ifBlank { folder.title }) {
                        items(list.size, key = { list[it].id }) { n ->
                            val t = list[n]
                            TitleTile(t.title, t.poster, if (i == 0 && n == 0) Modifier.focusRequester(first) else Modifier, onFocus = { onArt(t.backdrop ?: folder.hero) }) {
                                onOpen(BrowseItem(t.id, t.title, t.year, t.poster, t.backdrop, t.overview, t.rating, t.releaseDate, vodKey = null, imdbId = t.imdbId))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A universal collection opened from its tile: its titles as a poster grid, a star on the provider's. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CollectionGrid(vm: AppViewModel, kind: VodKind, c: com.edgestream.tv.data.Catalog.Collection, onOpen: (BrowseItem) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val items by produceState<List<BrowseItem>?>(null, c.id) { value = runCatching { BrowseData.collectionItems(vm, kind, c) }.getOrDefault(emptyList()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(items) { if (!items.isNullOrEmpty()) first.requestFocusWithRetry(tries = 20, gapMs = 50) }
    Column(Modifier.fillMaxSize().padding(start = 32.dp, top = 28.dp)) {
        Text(c.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 40.dp))
        val list = items
        Text(
            when { list == null -> "Loading…"; list.isEmpty() -> "Nothing here right now"; else -> "${list.size} titles · Back to return" },
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp),
        )
        if (!list.isNullOrEmpty()) LazyVerticalGrid(
            GridCells.Adaptive(CardSize.Poster),
            Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp, end = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(CardSize.RowGap),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(list, key = { it.id }) { item ->
                TitleTile(
                    item.title, item.poster, if (item.id == list.first().id) Modifier.focusRequester(first) else Modifier,
                    badge = if (item.vodKey != null) "★" else null,
                    meta = listOfNotNull(item.year, item.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null },
                ) { onOpen(item) }
            }
        }
    }
}

/** Placeholder rows at final size while the page loads, so nothing jumps when it arrives. */
@Composable
private fun Skeleton() {
    Column(Modifier.padding(start = 32.dp, top = 76.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(CardSize.RowGap)) {
            repeat(3) { Box(Modifier.width(420.dp).aspectRatio(16f / 9f).glass(EdgeShapes.Card).loadingPulse()) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CardSize.RowGap)) {
            repeat(8) { Box(Modifier.width(CardSize.Poster).height(225.dp).glass(EdgeShapes.Poster).loadingPulse()) }
        }
    }
}

/** Each genre gets its own steady colour, picked from the palette's two hues. */
@Composable
private fun tintFor(name: String): Color {
    val hues = listOf(Edge.Accent, Edge.Accent2, Edge.Gold, Edge.Live)
    return hues[Math.floorMod(name.hashCode(), hues.size)]
}

@Composable
private fun ContinueTile(prog: com.edgestream.tv.data.db.ProgressEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusSurface(
        onClick = onClick, onLongClick = onLongClick,
        modifier = Modifier.width(com.edgestream.tv.ui.components.CardSize.Poster).aspectRatio(2f / 3f),
        shape = EdgeShapes.Poster, focusScale = 1.08f,
    ) { f ->
        Logo(prog.poster, prog.title, Modifier.fillMaxSize(), ContentScale.Crop)
        val se = Regex("""S(\d+)E(\d+)""").find(prog.title)?.let { "S${it.groupValues[1]} · E${it.groupValues[2]}" }
        val left = (prog.durationMs - prog.positionMs).coerceAtLeast(0)
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            val sub = listOfNotNull(se, if (left > 0) "${com.edgestream.tv.ui.Format.clock(left)} left" else null).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            com.edgestream.tv.ui.components.ProgressLine(
                prog.positionMs.toFloat() / prog.durationMs.coerceAtLeast(1),
                Modifier.fillMaxWidth().padding(top = 4.dp), 3.dp,
            )
        }
        if (f) {
            Text(
                com.edgestream.tv.data.NameCleaner.tidyTitle(prog.title),
                style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(8.dp),
            )
        }
    }
}

private fun shortDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
}.getOrDefault(iso)
