package com.edgestream.tv.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.NowPlaying
import com.edgestream.tv.ui.VideoSurface
import com.edgestream.tv.ui.FocusMemory
import com.edgestream.tv.ui.components.EmptyState
import com.edgestream.tv.ui.components.FocusDimGroup
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Logo
import com.edgestream.tv.ui.components.OneLine
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.components.PreviewFrame
import com.edgestream.tv.ui.components.ProgressLine
import com.edgestream.tv.ui.components.SectionTitle
import kotlinx.coroutines.delay

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveScreen(vm: AppViewModel) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val allChannels by vm.channels.collectAsStateWithLifecycle()
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val clock by vm.clock.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val group by vm.liveGroup.collectAsStateWithLifecycle()
    // "All channels" is a search bucket, not a TV group: real guides list categories.
    val shownGroups = remember(groups) { groups.filterNot { it.first == AppViewModel.ALL } }

    // Land on something useful when All channels is no longer in the rail.
    LaunchedEffect(shownGroups) {
        if (shownGroups.isNotEmpty() && shownGroups.none { it.first == group }) {
            vm.liveGroup.value = shownGroups.firstOrNull { it.first == AppViewModel.RECENT && it.second > 0 }?.first
                ?: shownGroups.firstOrNull { it.first == AppViewModel.FAVOURITES && it.second > 0 }?.first
                ?: shownGroups.first().first
        }
    }

    val channels = remember(group, allChannels, favs, groups) { vm.channelsIn(group) }
    var focused by remember { mutableStateOf<ChannelEntity?>(null) }
    val playingKey = (nowPlaying as? NowPlaying.Live)?.channel?.key

    // Start the preview once focus has rested on a channel for a moment.
    LaunchedEffect(focused?.key) {
        val ch = focused ?: return@LaunchedEffect
        delay(settings.previewDelayMs.toLong())
        vm.preview(ch, channels)
    }

    val channelFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Back from deep in the channel list scrolls it to the top and focuses the group list
    // (the top-left of the screen) before the dock.
    val scrollToTop = com.edgestream.tv.ui.LocalScrollToTop.current
    val toTop: suspend () -> Unit = remember(listState, groupFocus) {
        { listState.animateScrollToItem(0); runCatching { groupFocus.requestFocus() } }
    }
    androidx.compose.runtime.DisposableEffect(scrollToTop, toTop) {
        scrollToTop.register(atTop = { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }, scrollToTop = toTop)
        onDispose { scrollToTop.unregister(toTop) }
    }
    LaunchedEffect(group, channels.isNotEmpty()) {
        if (channels.isEmpty()) return@LaunchedEffect
        val remembered = FocusMemory.last("live:channel:$group")
        val idx = remembered?.let { key -> channels.indexOfFirst { it.key == key } }
            ?.takeIf { it >= 0 }
            ?: channels.indexOfFirst { it.key == playingKey }.coerceAtLeast(0)
        listState.scrollToItem((idx - 3).coerceAtLeast(0))
        if (remembered == null) {
            delay(32)
            runCatching { channelFocus.requestFocus() }
        }
    }

    Row(Modifier.fillMaxSize().padding(start = 16.dp, top = 18.dp, end = 42.dp, bottom = 26.dp)) {
        // ---- groups -----------------------------------------------------------------------
        Column(Modifier.weight(0.23f).fillMaxHeight()) {
            SectionTitle("Groups")
            FocusDimGroup {
                LazyColumn(
                    Modifier.fillMaxHeight().focusRestorer(groupFocus),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
                ) {
                    itemsIndexed(shownGroups, key = { _, g -> g.first }) { _, (name, count) ->
                        val selected = name == group
                        FocusSurface(
                            onClick = { vm.liveGroup.value = name },
                            modifier = Modifier.fillMaxWidth().height(50.dp).then(if (selected) Modifier.focusRequester(groupFocus) else Modifier),
                            color = if (selected) Edge.PanelHi else Color.Transparent, selected = selected, focusScale = 1.02f,
                            memoryKey = "live:group|$name",
                        ) { f ->
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                com.edgestream.tv.ui.components.GroupLabel(name, if (f || selected) Edge.Text else Edge.Dim, Modifier.weight(1f))
                                Text("$count", style = MaterialTheme.typography.labelMedium, maxLines = 1, color = if (selected) Edge.Accent else Edge.Faint)
                            }
                        }
                    }
                }
            }
        }

        // ---- channels -----------------------------------------------------------------------
        Column(Modifier.weight(0.43f).fillMaxHeight().padding(horizontal = 8.dp)) {
            SectionTitle("${group.trimStart('★', '↺', '≡', ' ')} · ${channels.size}")
            if (channels.isEmpty()) {
                EmptyState(
                    "Nothing here yet",
                    if (group == AppViewModel.FAVOURITES) "Hold OK on any channel to add it to Favourites." else "This group has no channels.",
                    Modifier.fillMaxSize(),
                )
            } else FocusDimGroup {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxHeight().focusRestorer(channelFocus),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
                ) {
                    itemsIndexed(channels, key = { _, c -> c.key }) { i, ch ->
                        val isTarget = if (playingKey != null && channels.any { it.key == playingKey }) ch.key == playingKey else i == 0
                        val airing = nowNext[ch.epgId]?.first
                        // Rows the guide files don't cover ask the provider while they're on screen.
                        LaunchedEffect(ch.key, airing == null) { if (airing == null) { delay(400); vm.ensureLiveEpg(ch) } }
                        ChannelRow(
                            ch, nowNext[ch.epgId], clock, isFav = ch.key in favs, playing = ch.key == playingKey,
                            badge = vm.badge(ch.key), use24h = settings.use24h,
                            memoryKey = "live:channel:$group|${ch.key}",
                            modifier = Modifier
                                .then(if (isTarget) Modifier.focusRequester(channelFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) focused = ch },
                            onClick = { vm.playChannel(ch, channels) },
                            onLongClick = { vm.toggleFavorite(ch.key) },
                        )
                    }
                }
            }
        }

        // ---- preview & details ----------------------------------------------------------------
        Column(Modifier.weight(0.34f).fillMaxHeight().padding(start = 8.dp).verticalScroll(rememberScrollState())) {
            val shown = focused ?: (nowPlaying as? NowPlaying.Live)?.channel
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            ) {
                PreviewFrame(
                    engine = vm.player,
                    channel = shown,
                    now = shown?.let { vm.epgFor(it)?.first },
                    clock = clock,
                    modifier = Modifier.fillMaxSize(),
                    showVideo = settings.livePreview && nowPlaying is NowPlaying.Live,
                )
                PreviewStatus(vm, Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
            if (shown != null) ChannelDetails(vm, shown, settings.use24h, isFav = shown.key in favs)
        }
    }
}

@Composable
private fun PreviewStatus(vm: AppViewModel, modifier: Modifier) {
    val st by vm.player.state.collectAsStateWithLifecycle()
    when {
        st.error != null -> Pill(st.error.orEmpty().take(48), modifier, Edge.Alert)
        st.buffering -> Pill("Loading…", modifier, Edge.Gold)
        st.isPlaying -> Pill("● Live", modifier, Edge.Live)
    }
}

@Composable
fun ChannelRow(
    ch: ChannelEntity,
    epg: Pair<com.edgestream.tv.data.db.ProgrammeEntity?, com.edgestream.tv.data.db.ProgrammeEntity?>?,
    clock: Long,
    isFav: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
    badge: com.edgestream.tv.data.CleanName? = null,
    use24h: Boolean = true,
    memoryKey: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(82.dp),
        memoryKey = memoryKey,
        color = if (playing) Edge.Accent.copy(alpha = 0.08f) else Edge.Panel,
        // Full-width rows sit in clipped lists, where a glow would be cut into a hard band.
        selected = playing, focusScale = 1.02f, glow = false,
    ) { f ->
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${com.edgestream.tv.data.ChannelNumbers.lcn(ch)}", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false,
                color = if (f) Edge.Accent else Edge.Faint, modifier = Modifier.width(54.dp),
            )
            Logo(ch.logo, ch.name, Modifier.size(width = 60.dp, height = 44.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OneLine(ch.name, Modifier.weight(1f, fill = false), MaterialTheme.typography.titleMedium)
                    badge?.quality?.let { Pill(it, Modifier.padding(start = 6.dp), if (it == "4K") Edge.Gold else Edge.Dim) }
                    if (badge?.tags?.any { it.contains("geo", true) } == true) Pill("Geo", Modifier.padding(start = 4.dp), Edge.Alert)
                    if (isFav) Text("  ★", color = Edge.Gold, fontSize = 12.sp)
                    if (ch.catchupDays > 0) Text("  ⟲", color = Edge.Accent2, fontSize = 12.sp)
                }
                val now = epg?.first
                val next = epg?.second
                if (now != null) {
                    OneLine(now.title, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(
                            Format.fraction(now.start, now.stop, clock),
                            if (next != null) Modifier.weight(0.45f) else Modifier.weight(1f), 2.dp,
                        )
                        next?.let {
                            Text(
                                "  NEXT ${Format.time(it.start, use24h)}  ${it.title}",
                                style = MaterialTheme.typography.labelSmall, color = Edge.Faint,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    OneLine(com.edgestream.tv.data.NameCleaner.tidyGroup(ch.groupName), style = MaterialTheme.typography.bodyMedium, color = Edge.Faint)
                    next?.let {
                        OneLine(
                            "NEXT ${Format.time(it.start, use24h)}  ${it.title}",
                            style = MaterialTheme.typography.labelSmall, color = Edge.Faint,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
            if (playing) Text("  ▶", color = Edge.Accent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChannelDetails(vm: AppViewModel, ch: ChannelEntity, use24h: Boolean, isFav: Boolean) {
    val clock by vm.clock.collectAsStateWithLifecycle()
    val upcoming by produceState(emptyList<com.edgestream.tv.data.db.ProgrammeEntity>(), ch.key, clock / 600_000) {
        value = emptyList()
        delay(250) // don't hit the provider's short EPG for every channel the user scrolls past
        value = vm.repo.channelGuide(ch, clock, clock + 12 * 3_600_000L)
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(ch.logo, ch.name, Modifier.size(width = 64.dp, height = 44.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                OneLine(ch.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    buildString {
                        append("${com.edgestream.tv.data.ChannelNumbers.lcn(ch)} · ${com.edgestream.tv.data.NameCleaner.tidyGroup(ch.groupName)}")
                        vm.badge(ch.key)?.let { b -> (listOfNotNull(b.quality) + b.tags).forEach { append(" · $it") } }
                        if (ch.catchupDays > 0) append(" · ${ch.catchupDays}-day catch-up")
                        if (isFav) append(" · ★ Favourite")
                    },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val now = upcoming.firstOrNull { it.start <= clock && it.stop > clock }
        if (now != null) {
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Now", color = Edge.Accent)
                Text(
                    "  ${Format.range(now.start, now.stop, use24h)}" +
                        listOfNotNull(now.year?.toString(), now.rating).joinToString("") { "  ·  $it" },
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(now.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            now.subtitle?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ProgressLine(Format.fraction(now.start, now.stop, clock), Modifier.fillMaxWidth().padding(top = 8.dp))
            now.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            Text("No guide information for this channel.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 14.dp))
        }
        val later = upcoming.filter { it.start > clock }.take(4)
        if (later.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionTitle("Up Next")
            later.forEach { p ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(Format.time(p.start, use24h), style = MaterialTheme.typography.labelMedium, color = Edge.Accent, modifier = Modifier.width(72.dp))
                    OneLine(p.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("OK  watch full screen     Hold OK  ${if (isFav) "remove from" else "add to"} favourites", style = MaterialTheme.typography.labelMedium, color = Edge.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}