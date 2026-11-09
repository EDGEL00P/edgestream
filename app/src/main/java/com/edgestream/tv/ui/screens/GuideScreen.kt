package com.edgestream.tv.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeLayout
import com.edgestream.tv.ui.components.FocusDimGroup
import com.edgestream.tv.ui.glass
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.LocalSafeArea
import com.edgestream.tv.ui.SafeArea
import com.edgestream.tv.ui.NowPlaying
import com.edgestream.tv.ui.components.EmptyState
import com.edgestream.tv.ui.components.FilterChip
import com.edgestream.tv.ui.components.FilterChipText
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Logo
import com.edgestream.tv.ui.components.OneLine
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.components.PreviewFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy

private const val WINDOW_MIN = 120L
private const val STEP_MS = 30 * 60_000L
private const val ROW_HEIGHT = 68

private data class Focused(val channel: ChannelEntity, val programme: ProgrammeEntity?)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GuideScreen(vm: AppViewModel, overlay: Boolean = false, onClose: () -> Unit = {}) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val allChannels by vm.channels.collectAsStateWithLifecycle()
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val clock by vm.clock.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val group by vm.guideGroup.collectAsStateWithLifecycle()
    val guideVersion by vm.guideVersion.collectAsStateWithLifecycle()
    // "All channels" is a search bucket, not a TV group.
    val shownGroups = remember(groups) { groups.filterNot { it.first == AppViewModel.ALL } }
    LaunchedEffect(shownGroups) {
        if (shownGroups.isNotEmpty() && shownGroups.none { it.first == group }) {
            vm.guideGroup.value = shownGroups.firstOrNull { it.first == AppViewModel.RECENT && it.second > 0 }?.first
                ?: shownGroups.firstOrNull { it.first == AppViewModel.FAVOURITES && it.second > 0 }?.first
                ?: shownGroups.first().first
        }
    }
    // Channels without a guide file still appear, with their provider listing or a placeholder.
    val channels = remember(group, allChannels, favs, groups, guideVersion) {
        vm.channelsIn(group)
    }

    val nowAligned = remember { System.currentTimeMillis() / STEP_MS * STEP_MS }
    var viewStart by remember { mutableLongStateOf(nowAligned) }
    val viewEnd = viewStart + WINDOW_MIN * 60_000
    val maxCatchup = remember(channels) { (channels.maxOfOrNull { it.catchupDays } ?: 0).coerceAtMost(14) }
    val earliest = nowAligned - maxCatchup * 86_400_000L - STEP_MS
    val latest = nowAligned + 3 * 86_400_000L

    // Programmes are loaded per channel in 12-hour buckets, as rows scroll into view.
    val cache = remember { mutableStateMapOf<String, List<ProgrammeEntity>>() }
    var focused by remember { mutableStateOf<Focused?>(null) }

    val listState = rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    val playingKey = (nowPlaying as? NowPlaying.Live)?.channel?.key
    LaunchedEffect(group, channels.isNotEmpty()) {
        if (channels.isEmpty()) return@LaunchedEffect
        val idx = channels.indexOfFirst { it.key == playingKey }.coerceAtLeast(0)
        listState.scrollToItem((idx - 2).coerceAtLeast(0))
        // The target row may not be laid out on the first frame; keep asking until a cell has
        // focus, otherwise focus falls to the first chip above the grid.
        focused = null
        repeat(20) {
            delay(50)
            runCatching { gridFocus.requestFocus() }
            if (focused != null) return@LaunchedEffect
        }
    }

    // Set-top-box navigation: Up/Down keep the time you were looking at instead of jumping to
    // whichever cell happens to be geometrically nearest. [anchor] is that time; [pending] is
    // the row that should take focus next.
    var anchor by remember { mutableStateOf<Long?>(null) }
    var pending by remember { mutableStateOf<Int?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Over live video the guide is a solid panel: text over moving video must stay readable.
    // Inset matches every tab (EdgeLayout): EdgeApp gives the nav 40dp, content sits 14dp in.
    val backdrop = if (overlay) Modifier.background(Edge.Bg.copy(alpha = 0.97f)) else Modifier
    // Over live video the root UI padding is not applied, so the overlay adds the safe area itself.
    val safe = if (overlay) LocalSafeArea.current else SafeArea()
    Column(Modifier.fillMaxSize().then(backdrop).padding(
        start = EdgeLayout.PageEdge + EdgeLayout.ContentInset + safe.horizontal,
        top = EdgeLayout.PageTop + safe.vertical,
        end = EdgeLayout.PageEdge + safe.horizontal,
        bottom = EdgeLayout.PageBottom + safe.vertical,
    )) {
        GuideHeader(focused, settings.use24h, clock, vm, showPreview = !overlay && settings.livePreview && nowPlaying is NowPlaying.Live)

        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 64.dp),
        ) {
            item(key = "__now") {
                val atNow = viewStart == nowAligned
                FilterChipText("Now", atNow, onClick = { anchor = null; viewStart = System.currentTimeMillis() / STEP_MS * STEP_MS })
            }
            items(shownGroups, key = { it.first }) { (name, _) ->
                val sel = name == group
                com.edgestream.tv.ui.components.FilterChip(sel, { vm.guideGroup.value = name; cache.clear() }, memoryKey = "guide:group|$name") {
                    com.edgestream.tv.ui.components.GroupLabel(
                        name, Edge.Text, Modifier,
                    )
                }
            }
        }

        if (channels.isEmpty()) {
            EmptyState("No channels in this group", "Pick another group above.", Modifier.fillMaxSize()); return@Column
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Readable mini-guide over live video: balanced columns, larger type (fonts bumped below).
            val channelCol = (maxWidth * 0.22f).coerceIn(200.dp, 320.dp)
            val timeline = maxWidth - channelCol
            val dpPerMin = timeline / WINDOW_MIN.toFloat()

            Column(Modifier.fillMaxSize()) {
                TimeRuler(viewStart, channelCol, dpPerMin, settings.use24h, clock)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRestorer(gridFocus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val f = focused ?: return@onPreviewKeyEvent false
                            val start = f.programme?.start ?: viewStart
                            val stop = f.programme?.stop ?: viewEnd
                            when (e.key) {
                                Key.DirectionUp, Key.DirectionDown -> {
                                    val row = channels.indexOfFirst { it.key == f.channel.key }
                                    val target = row + if (e.key == Key.DirectionUp) -1 else 1
                                    // Up from the top row leaves for the group chips.
                                    if (row < 0 || target !in channels.indices) return@onPreviewKeyEvent e.key == Key.DirectionDown
                                    // On something airing now, stay on "now" as you move; otherwise keep the programme's start time.
                                    if (anchor == null) anchor = if (start <= clock && stop > clock) clock else maxOf(start, viewStart)
                                    pending = target
                                    // A row that's partly on screen is scrolled into view when it takes
                                    // focus; one that isn't composed yet has to be scrolled to first.
                                    val visible = listState.layoutInfo.visibleItemsInfo
                                    if (visible.none { it.index == target }) scope.launch {
                                        listState.scrollBy((if (e.key == Key.DirectionDown) 1 else -1) * (visible.firstOrNull()?.size ?: 0).toFloat())
                                    }
                                    true
                                }
                                Key.DirectionRight -> { anchor = null; if (stop >= viewEnd && viewStart < latest) { viewStart += STEP_MS; true } else false }
                                // Back in time only where there is something to watch (catch-up);
                                // otherwise Left leaves the grid for the menu, as on a set-top box.
                                Key.DirectionLeft -> { anchor = null; if (start <= viewStart && viewStart > earliest &&
                                    (viewStart > nowAligned || f.channel.catchupDays > 0)) { viewStart -= STEP_MS; true } else false }
                                else -> false
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(channels, key = { _, c -> c.key }) { i, ch ->
                        val bucket = viewStart / (12 * 3_600_000L)
                        // Keyed on the guide version too, so rows that loaded before the guide
                        // arrived (or before a refresh finished) pick up the new data.
                        val cacheKey = "${ch.key}@$bucket@$guideVersion@${ch.epgId}"
                        val prevKey = remember(ch.key) { mutableStateOf<String?>(null) }
                        LaunchedEffect(cacheKey) {
                            if (cacheKey !in cache) {
                                val from = bucket * 12 * 3_600_000L - 6 * 3_600_000L
                                val list = vm.repo.programmesFor(listOfNotNull(ch.epgId), from, from + 30 * 3_600_000L)
                                cache[cacheKey] = list
                                prevKey.value?.let { if (it != cacheKey) cache.remove(it) }
                                prevKey.value = cacheKey
                                // Bound the per-row cache: a long session browsing many days would
                                // otherwise keep every channel × every 12-hour window in memory.
                                // Far-off buckets drop out (they reload instantly); anything on
                                // screen now is kept.
                                if (cache.size > 300) {
                                    val visibleKeys = listState.layoutInfo.visibleItemsInfo
                                        .mapNotNull { info -> channels.getOrNull(info.index)?.key }
                                    val keep = cache.keys.filterTo(mutableSetOf()) { key ->
                                        val bucketText = key.substringAfter('@', "").substringBefore('@')
                                        val b = bucketText.toIntOrNull() ?: bucket
                                        b in (bucket - 1)..(bucket + 1) || visibleKeys.any { key.startsWith("$it@") }
                                    }
                                    (cache.keys - keep).forEach { cache.remove(it) }
                                }
                            }
                            // Nothing airing right now: ask the provider for its live listing.
                            if (cache[cacheKey].orEmpty().none { it.start <= clock && it.stop > clock }) vm.ensureLiveEpg(ch)
                        }
                        val rowProgrammes = cache[cacheKey] ?: prevKey.value?.let { cache[it] }.orEmpty()
                        val isTarget = if (playingKey != null && channels.any { it.key == playingKey }) ch.key == playingKey else i == 0
                        GuideRow(
                            vm, ch, rowProgrammes, viewStart, viewEnd, channelCol, dpPerMin, clock,
                            focusRequester = if (isTarget) gridFocus else null,
                            takeFocusAt = if (pending == i) (anchor ?: clock) else null,
                            onPlaceholder = focused?.let { it.channel.key == ch.key && it.programme == null } == true,
                            onTookFocus = { pending = null },
                            onFocus = { focused = Focused(ch, it) },
                            channels = channels,
                            use24h = settings.use24h,
                            afterPlay = onClose,
                        )
                    }
                }
            }
            val nowMin = (clock - viewStart) / 60_000f
            if (nowMin in 0f..WINDOW_MIN.toFloat()) {
                Canvas(Modifier.matchParentSize()) {
                    val x = channelCol.toPx() + dpPerMin.toPx() * nowMin
                    drawLine(
                        Edge.Live.copy(alpha = 0.45f),
                        Offset(x, 32.dp.toPx()), Offset(x, size.height),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideHeader(f: Focused?, use24h: Boolean, clock: Long, vm: AppViewModel, showPreview: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 112.dp)) {
        Column(Modifier.weight(1f)) { GuideHeaderText(f, use24h, clock, vm) }
        // Picture-in-picture of what's playing, so browsing the guide never stops the programme.
        // Fixed height: fillMaxHeight here would stretch to the whole screen, as the row has no max.
        if (showPreview) {
            val ch = f?.channel
            PreviewFrame(
                engine = vm.player,
                channel = ch,
                now = f?.programme ?: ch?.let { vm.epgFor(it)?.first },
                clock = clock,
                modifier = Modifier.padding(start = 24.dp).height(112.dp).aspectRatio(16f / 9f),
                showVideo = ch != null,
            )
        }
    }
}

@Composable
private fun GuideHeaderText(f: Focused?, use24h: Boolean, clock: Long, vm: AppViewModel) {
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        if (f == null) {
            Text("Guide", style = MaterialTheme.typography.titleMedium, color = Edge.Dim); return@Column
        }
        // On an empty cell, still show what the channel has on now (e.g. from the live listing).
        val p = f.programme ?: vm.epgFor(f.channel)?.first
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                p == null -> Unit
                p.start <= clock && p.stop > clock -> Pill("On now", color = Edge.Live)
                p.stop <= clock && vm.repo.canCatchUp(f.channel, p) -> Pill("Catch-up", color = Edge.Accent2)
                p.stop <= clock -> Pill("Ended", color = Edge.Faint)
                else -> Pill(Format.day(p.start), color = Edge.Gold)
            }
            Text(
                "  ${f.channel.name}" + (p?.let { " · ${Format.day(it.start)} ${Format.range(it.start, it.stop, use24h)} · ${Format.duration(it.stop - it.start)}" } ?: "") +
                    (p?.let { listOfNotNull(it.year?.toString(), it.rating).joinToString(" · ") }?.let { if (it.isEmpty()) "" else " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (p != null && p.start > clock) Text(
                if (reminders.any { it.channelKey == f.channel.key && it.start == p.start }) "     Reminder set  ·  OK to cancel" else "     OK to set a reminder",
                style = MaterialTheme.typography.labelMedium, color = Edge.Gold, maxLines = 1,
            )
        }
        Text(p?.title ?: f.channel.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        p?.subtitle?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Text(
            p?.description ?: p?.category ?: "No programme information.",
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TimeRuler(viewStart: Long, channelCol: Dp, dpPerMin: Dp, use24h: Boolean, clock: Long) {
    Box(Modifier.fillMaxWidth().height(32.dp)) {
        // Anchored above the channel column it labels, not floating over the timeline.
        Text(
            Format.day(viewStart), style = MaterialTheme.typography.labelLarge, color = Edge.Accent,
            modifier = Modifier.width(channelCol).padding(end = 12.dp),
            textAlign = TextAlign.End, maxLines = 1,
        )
        for (i in 0 until (WINDOW_MIN / 30).toInt()) {
            val t = viewStart + i * STEP_MS
            Text(
                Format.time(t, use24h), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.offset(x = channelCol + dpPerMin * (i * 30f) + 6.dp),
            )
        }
        val nowMin = (clock - viewStart) / 60_000f
        if (nowMin in 0f..WINDOW_MIN.toFloat()) {
            Box(Modifier.offset(x = channelCol + dpPerMin * nowMin - 1.dp, y = 22.dp).size(2.dp, 10.dp).background(Edge.Live))
        }
    }
}

@Composable
private fun GuideRow(
    vm: AppViewModel,
    ch: ChannelEntity,
    programmes: List<ProgrammeEntity>,
    viewStart: Long,
    viewEnd: Long,
    channelCol: Dp,
    dpPerMin: Dp,
    clock: Long,
    focusRequester: FocusRequester?,
    /** When set, this row grabs focus on the cell airing at this time (or the nearest one). */
    takeFocusAt: Long?,
    onTookFocus: () -> Unit,
    /** Focus is on this row's "no information" placeholder, which vanishes when the listing loads. */
    onPlaceholder: Boolean,
    onFocus: (ProgrammeEntity?) -> Unit,
    channels: List<ChannelEntity>,
    use24h: Boolean,
    afterPlay: () -> Unit,
) {
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    FocusDimGroup {
        Row(Modifier.fillMaxWidth().height(ROW_HEIGHT.dp)) {
        Row(
            Modifier.width(channelCol).fillMaxHeight().padding(end = 6.dp).glass(RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Category edge strip: the channel's own genre colour, like a broadcast EPG sidebar.
            genreColor(ch.groupName, ch.name)?.let { c ->
                Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(50)).background(c))
                Box(Modifier.width(8.dp).height(1.dp))
            }
            // Big providers number into the tens of thousands; never wrap.
            Text("${com.edgestream.tv.data.ChannelNumbers.lcn(ch)}", style = MaterialTheme.typography.labelMedium, color = Edge.Faint, maxLines = 1, softWrap = false, modifier = Modifier.width(48.dp))
            Logo(ch.logo, ch.name, Modifier.size(width = 48.dp, height = 34.dp))
            OneLine(ch.name, Modifier.padding(start = 8.dp), MaterialTheme.typography.titleSmall)
        }
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))) {
            val visible = programmes.filter { it.stop > viewStart && it.start < viewEnd }
            val moveFocus = remember { FocusRequester() }
            // When the listing arrives under a focused placeholder, the placeholder disappears and
            // focus with it; hand it straight to the programme on now instead of losing it.
            val focusAt = takeFocusAt ?: if (onPlaceholder && visible.isNotEmpty()) clock else null
            val moveTarget = focusAt?.let { t ->
                visible.firstOrNull { it.start <= t && it.stop > t }
                    ?: visible.minByOrNull { minOf(kotlin.math.abs(it.start - t), kotlin.math.abs(it.stop - t)) }
            }
            if (focusAt != null) LaunchedEffect(focusAt, moveTarget?.start) {
                repeat(10) {
                    androidx.compose.runtime.withFrameNanos { }
                    // requestFocus() reports false (it doesn't throw) until the cell is attached.
                    if (runCatching { moveFocus.requestFocus() }.getOrDefault(false)) { onTookFocus(); return@LaunchedEffect }
                }
                onTookFocus()
            }
            // The now-line sits behind the programme cells so it never cuts through titles.
            val nowMinBehind = (clock - viewStart) / 60_000f
            if (nowMinBehind in 0f..WINDOW_MIN.toFloat()) {
                Box(Modifier.offset(x = dpPerMin * nowMinBehind - 1.dp).width(2.dp).fillMaxHeight().background(Edge.Live.copy(alpha = 0.55f)))
            }
            // Half-hour structure, as on a broadcast EPG grid.
            repeat((WINDOW_MIN / 30).toInt() - 1) { i ->
                Box(
                    Modifier.offset(x = dpPerMin * ((i + 1) * 30f)).width(1.dp).fillMaxHeight()
                        .background(Edge.Text.copy(alpha = 0.05f)),
                )
            }
            if (visible.isEmpty()) {
                FocusSurface(
                    onClick = { vm.playChannel(ch, channels); afterPlay() },
                    modifier = Modifier.fillMaxSize()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .then(if (takeFocusAt != null) Modifier.focusRequester(moveFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) onFocus(null) },
                    shape = RoundedCornerShape(10.dp), focusScale = 1f, glow = false,
                ) { f ->
                    Text(
                        if (ch.epgId == null) "No guide data" else "No information", style = MaterialTheme.typography.bodyMedium,
                        color = if (f) Edge.Text else Edge.Faint, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                    )
                }
            }
            visible.forEach { p ->
                val s = maxOf(p.start, viewStart)
                val e = minOf(p.stop, viewEnd)
                val x = dpPerMin * ((s - viewStart) / 60_000f)
                val w = dpPerMin * ((e - s) / 60_000f)
                val isNow = p.start <= clock && p.stop > clock
                val catchup = p.stop <= clock && vm.repo.canCatchUp(ch, p)
                val past = p.stop <= clock
                val upcoming = p.start > clock
                val reminded = reminders.any { it.channelKey == ch.key && it.start == p.start }
                // Category first; when the guide has none, the channel's own group is a good hint.
                val genre = genreColor(p.category, p.title) ?: genreColor(ch.groupName, ch.name)
                FocusSurface(
                    onClick = {
                        when {
                            isNow -> vm.playChannel(ch, channels)
                            catchup -> vm.playCatchup(ch, p)
                            // Like a set-top box: OK on something that hasn't started sets a reminder.
                            upcoming -> vm.toggleReminder(ch, p)
                        }
                        if (isNow || catchup) afterPlay()
                    },
                    onLongClick = { vm.toggleFavorite(ch.key) },
                    modifier = Modifier
                        .offset(x = x)
                        .width((w - 2.dp).coerceAtLeast(1.dp))
                        .fillMaxHeight()
                        .then(if (focusRequester != null && (isNow || (p === visible.first() && visible.none { v -> v.start <= clock && v.stop > clock }))) Modifier.focusRequester(focusRequester) else Modifier)
                        .then(if (p === moveTarget) Modifier.focusRequester(moveFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) onFocus(p) },
                    memoryKey = "guide:cell|${ch.key}:${p.start}",
                    shape = RoundedCornerShape(8.dp),
                    color = run {
                        val tinted = genre?.copy(alpha = 0.13f)?.compositeOver(Edge.Panel) ?: Edge.Panel
                        when {
                            isNow -> genre?.copy(alpha = 0.24f)?.compositeOver(Edge.PanelHi) ?: Edge.PanelHi
                            past -> tinted.copy(alpha = 0.55f).compositeOver(Edge.Bg)
                            else -> tinted
                        }
                    },
                    focusedColor = Edge.Accent.copy(alpha = 0.22f).compositeOver(Edge.PanelHi), focusScale = 1f, glow = false,
                ) { f ->
                    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (catchup) androidx.compose.material3.Icon(Icons.Rounded.History, "Catch-up", Modifier.padding(end = 4.dp).size(16.dp), tint = Edge.Accent2)
                            if (reminded) androidx.compose.material3.Icon(Icons.Rounded.NotificationsActive, "Reminder set", Modifier.padding(end = 4.dp).size(16.dp), tint = Edge.Gold)
                            OneLine(
                                p.title, style = MaterialTheme.typography.titleSmall,
                                color = if (f) Edge.Text else if (past) Edge.Faint else Edge.Text,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (isNow) com.edgestream.tv.ui.components.LiveBadge(Modifier.padding(start = 6.dp), compact = true)
                        }
                        OneLine(
                            p.subtitle?.takeIf { it.isNotBlank() } ?: p.category?.takeIf { it.isNotBlank() } ?: Format.range(p.start, p.stop, use24h),
                            style = MaterialTheme.typography.labelLarge, color = Edge.Faint,
                        )
                    }
                    if (isNow) Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth(Format.fraction(p.start, p.stop, clock)).height(2.dp).background(Edge.AccentGradient),
                    )
                    // Genre strip down the left edge, as on broadcast EPGs.
                    genre?.let { c ->
                        Box(Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight(0.6f).clip(RoundedCornerShape(50)).background(c.copy(alpha = if (past) 0.4f else 0.9f)))
                    }
                }
            }
        }
    }
    }
}

private val SPORT = Regex("""sport|football|soccer|basketball|baseball|hockey|nfl|nba|mlb|nhl|cricket|rugby|tennis|golf|racing|boxing|ufc|wrestling|motorsport|f1\b""", RegexOption.IGNORE_CASE)
private val NEWS = Regex("""news|current affairs|weather|politic""", RegexOption.IGNORE_CASE)
private val FILM = Regex("""movie|film|cinema""", RegexOption.IGNORE_CASE)
private val KIDS = Regex("""kids|children|animation|cartoon|family""", RegexOption.IGNORE_CASE)
private val ENTERTAINMENT = Regex("""reality|competition|game show|talk show|variety|entertainment|series|drama|comedy|sitcom""", RegexOption.IGNORE_CASE)
private val DOCUMENTARY = Regex("""documentary|nature|history|science|crime|investigation|travel|food|cooking|home|garden|lifestyle|music""", RegexOption.IGNORE_CASE)

/** Broadcast-style genre colour from the guide's category (or, failing that, the title). */
private fun genreColor(category: String?, title: String): Color? {
    val text = category ?: title
    // Distinct broadcast hues, nudged 18% towards the active palette so they still belong
    // to the selected theme instead of fighting it.
    val base = when {
        SPORT.containsMatchIn(text) -> Color(0xFF2E8B78)
        NEWS.containsMatchIn(text) -> Color(0xFF3F7D4F)
        FILM.containsMatchIn(text) -> Color(0xFF4C5FA8)
        KIDS.containsMatchIn(text) -> Color(0xFFB07A2E)
        ENTERTAINMENT.containsMatchIn(text) -> Color(0xFF6B4FA8)
        DOCUMENTARY.containsMatchIn(text) -> Color(0xFF3F6A8A)
        else -> return null
    }
    return lerp(base, Edge.Accent, 0.18f)
}
