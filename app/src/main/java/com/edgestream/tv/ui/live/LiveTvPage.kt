package com.edgestream.tv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.LocalScrollToTop
import com.edgestream.tv.ui.components.requestFocusWithRetry
import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA
import com.edgestream.tv.ui.input.hoverFocus
import com.edgestream.tv.ui.input.remoteClickable
import kotlinx.coroutines.launch

private const val HOUR = 3_600_000L
private const val HALF_HOUR = 1_800_000L
private const val WINDOW = 3 * HOUR
private val ROW_HEIGHT = 40.dp
private val STATION_WIDTH = 210.dp
private val Dim get() = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA)

private fun alignDown(t: Long, step: Long) = t / step * step

/**
 * Live TV (the user's guide concept): a compact "on now" strip, filter chips, then a real guide
 * grid — 7 channels visible, a now-line, the live programme lit with its progress. OK plays live,
 * plays an earlier show from the start (catch-up channels) or sets a reminder for a later one.
 * Right/Left at the window edge (or FF/REW) move the guide an hour.
 */
@Composable
fun LiveTvPage(vm: AppViewModel) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val guideVersion by vm.guideVersion.collectAsStateWithLifecycle()
    val clock by vm.clock.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val chips = remember(groups) { guideChips(groups) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val group = picked?.takeIf { g -> chips.any { it.first == g } }
        ?: chips.firstOrNull { it.first == AppViewModel.RECENT }?.first ?: chips.firstOrNull()?.first
    val channels = remember(group, groups) { group?.let { vm.channelsIn(it) }.orEmpty() }

    val nowSlot = alignDown(clock - HALF_HOUR, HALF_HOUR)
    var windowStart by rememberSaveable { mutableLongStateOf(nowSlot) }
    val windowEnd = windowStart + WINDOW
    val cache = remember(guideVersion) { mutableStateMapOf<String, List<ProgrammeEntity>>() }
    var focused by remember { mutableStateOf<Pair<ChannelEntity, GuideBlock>?>(null) }
    var pending by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }

    // Back from deep in the guide: first to the top and to now, then up to the dock.
    val scrollToTop = LocalScrollToTop.current
    val toTop: suspend () -> Unit = remember {
        { windowStart = nowSlot; listState.scrollToItem(0); firstFocus.requestFocusWithRetry() }
    }
    DisposableEffect(scrollToTop, toTop) {
        scrollToTop.register(atTop = { listState.firstVisibleItemIndex == 0 && windowStart == nowSlot }, scrollToTop = toTop)
        onDispose { scrollToTop.unregister(toTop) }
    }
    LaunchedEffect(channels.isNotEmpty()) { if (channels.isNotEmpty()) firstFocus.requestFocusWithRetry() }

    fun shift(by: Long, rowKey: String, focusAt: Long, catchupDays: Int) {
        val earliest = nowSlot - catchupDays.coerceIn(0, 14) * 24 * HOUR
        val next = (windowStart + by).coerceIn(earliest, nowSlot + 3 * 24 * HOUR)
        if (next == windowStart) return
        windowStart = next
        pending = rowKey to focusAt.coerceIn(next, next + WINDOW - 1)
    }

    fun activate(ch: ChannelEntity, b: GuideBlock) {
        val p = b.programme ?: return vm.playChannel(ch, channels)
        when {
            clock in p.start until p.stop -> vm.playChannel(ch, channels)
            p.stop <= clock && ch.catchupDays > 0 -> vm.playCatchup(ch, p)
            p.stop <= clock -> vm.showNotice("No catch-up on ${ch.name}")
            else -> vm.toggleReminder(ch, p)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NowStrip(focused, clock, settings.use24h, Modifier.fillMaxWidth().height(88.dp))
        ChipRow(chips, group) { picked = it; windowStart = nowSlot; scope.launch { listState.scrollToItem(0) } }
        TimeHeader(windowStart, windowEnd, clock, settings.use24h)
        if (channels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No channels in this group yet", color = Dim, fontSize = 17.sp)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(channels, key = { it.key }) { ch ->
                val bucket = windowStart / (6 * HOUR)
                val cacheKey = "${ch.key}@$bucket"
                LaunchedEffect(cacheKey, guideVersion) {
                    if (cacheKey !in cache) {
                        val from = bucket * 6 * HOUR - 6 * HOUR
                        cache[cacheKey] = runCatching { vm.repo.channelGuide(ch, from, from + 18 * HOUR) }.getOrDefault(emptyList())
                    }
                }
                val blocks = remember(cache[cacheKey], windowStart) { guideBlocks(cache[cacheKey].orEmpty(), windowStart, windowEnd) }
                GuideRow(
                    ch, blocks, windowStart, windowEnd, clock, settings.use24h,
                    firstFocus = if (ch.key == channels.first().key) firstFocus else null,
                    pending = pending, onPendingDone = { pending = null },
                    onFocus = { b -> focused = ch to b },
                    onShift = { by, at -> shift(by, ch.key, at, ch.catchupDays) },
                    onClick = { b -> activate(ch, b) },
                )
            }
        }
    }
}

@Composable
private fun NowStrip(focused: Pair<ChannelEntity, GuideBlock>?, now: Long, use24h: Boolean, modifier: Modifier) {
    Row(modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val ch = focused?.first
        val b = focused?.second
        val p = b?.programme
        AsyncImage(ch?.logo, ch?.name, Modifier.size(width = 96.dp, height = 60.dp), contentScale = ContentScale.Fit)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            val state = when {
                p == null -> ""
                now in p.start until p.stop -> "LIVE NOW"
                p.stop <= now -> "EARLIER"
                else -> "LATER"
            }
            Text(listOfNotNull(ch?.let { "CH ${it.number}  ${it.name}" }, state.ifEmpty { null }).joinToString("  ·  ").uppercase(),
                color = Edge.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p?.title ?: ch?.name ?: "Live TV", color = Edge.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = p?.let {
                val range = "${Format.time(it.start, use24h)} – ${Format.time(it.stop, use24h)}"
                if (now in it.start until it.stop) "$range  ·  ${timeLeftLabel(now, it.stop)}" else range
            }
            Text(listOfNotNull(time, p?.description).joinToString("  ·  "), color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val hint = when {
            p == null || ch == null -> "OK  Watch"
            now in p.start until p.stop -> "OK  Watch live"
            p.stop <= now -> if (ch.catchupDays > 0) "OK  Play from start" else "No catch-up"
            else -> "OK  Remind me"
        }
        Text(hint, color = Edge.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Edge.Accent).padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun ChipRow(chips: List<Pair<String, Int>>, current: String?, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chips, key = { it.first }) { (name, count) ->
            var f by remember { mutableStateOf(false) }
            val sel = name == current
            val shape = RoundedCornerShape(50)
            Text(
                "${name.trimStart('★', '↺', '≡', ' ')}  $count",
                color = if (sel || f) Edge.OnAccent else Edge.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier
                    .background(if (sel || f) Edge.Accent else Edge.Text.copy(alpha = 0.08f), shape)
                    .then(if (f) Modifier.border(2.dp, Edge.Text, shape) else Modifier)
                    .onFocusChanged { f = it.isFocused }
                    .remoteClickable { onPick(name) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TimeHeader(from: Long, to: Long, now: Long, use24h: Boolean) {
    Row(Modifier.fillMaxWidth().height(22.dp)) {
        Text("CHANNEL", color = Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.width(STATION_WIDTH))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxSize()) {
                var t = from
                while (t < to) {
                    val live = now in t until t + HALF_HOUR
                    Text(Format.time(t, use24h) + if (live) "  NOW" else "", color = if (live) Edge.Accent else Dim, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    t += HALF_HOUR
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    ch: ChannelEntity,
    blocks: List<GuideBlock>,
    from: Long,
    to: Long,
    now: Long,
    use24h: Boolean,
    firstFocus: FocusRequester?,
    pending: Pair<String, Long>?,
    onPendingDone: () -> Unit,
    onFocus: (GuideBlock) -> Unit,
    onShift: (Long, Long) -> Unit,
    onClick: (GuideBlock) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.width(STATION_WIDTH - 6.dp).fillMaxHeight().background(Edge.Text.copy(alpha = 0.07f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(ch.logo, null, Modifier.size(width = 44.dp, height = 28.dp), contentScale = ContentScale.Fit)
            Text("${ch.number}", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            Text(ch.name, color = Edge.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp))
        }
        // Now-line drawn across the programme area.
        Row(
            Modifier.weight(1f).fillMaxHeight().drawBehind {
                if (now in from until to) {
                    val x = size.width * (now - from) / (to - from).toFloat()
                    drawLine(Edge.Accent, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                }
            },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            blocks.forEachIndexed { i, b ->
                val req = remember(b.start, ch.key) { FocusRequester() }
                LaunchedEffect(pending, b.start) {
                    val pend = pending ?: return@LaunchedEffect
                    if (pend.first == ch.key && pend.second >= b.start && pend.second < b.end) { req.requestFocusWithRetry(); onPendingDone() }
                }
                val isLiveCell = b.programme != null && now in b.start until b.end
                ProgrammeCell(
                    b, now, use24h, narrow = b.fraction(from, to) < 0.07f,
                    Modifier.weight(b.fraction(from, to).coerceAtLeast(0.001f))
                        .then(if (firstFocus != null && (isLiveCell || (i == 0 && blocks.none { it.programme != null && now in it.start until it.end }))) Modifier.focusRequester(firstFocus) else Modifier)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                e.key == Key.DirectionRight && i == blocks.lastIndex -> { onShift(HOUR, b.end); true }
                                e.key == Key.DirectionLeft && i == 0 -> { onShift(-HOUR, b.start - 1); true }
                                e.key == Key.MediaFastForward -> { onShift(HOUR, b.start + HOUR); true }
                                e.key == Key.MediaRewind -> { onShift(-HOUR, b.start - HOUR); true }
                                else -> false
                            }
                        },
                    req, onFocus = { onFocus(b) }, onClick = { onClick(b) },
                )
            }
        }
    }
}

@Composable
private fun ProgrammeCell(b: GuideBlock, now: Long, use24h: Boolean, narrow: Boolean, modifier: Modifier, requester: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val p = b.programme
    val live = p != null && now in p.start until p.stop
    val past = p != null && p.stop <= now
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.fillMaxHeight()
            .graphicsLayer { val s = if (f) 1.03f else 1f; scaleX = s; scaleY = s }
            .background(
                when {
                    f -> Edge.Accent
                    live -> Edge.Text.copy(alpha = 0.14f)
                    past -> Edge.Text.copy(alpha = 0.04f)
                    else -> Edge.Text.copy(alpha = 0.08f)
                },
                shape,
            )
            .then(if (f) Modifier.border(2.dp, Edge.Text, shape) else Modifier)
            .hoverFocus(requester)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .remoteClickable(onClick)
            .padding(horizontal = 10.dp),
    ) {
        // Too thin for words (a few minutes of gap): just the coloured cell.
        if (!narrow) Column(Modifier.align(Alignment.CenterStart)) {
            Text(p?.title ?: "No listing", color = when { f -> Edge.OnAccent; past -> Dim; else -> Edge.Text },
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null) Text("${Format.time(p.start, use24h)} – ${Format.time(p.stop, use24h)}",
                color = if (f) Edge.OnAccent.copy(alpha = 0.85f) else Dim, fontSize = 12.sp, maxLines = 1)
        }
        if (live) Box(
            Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp).fillMaxWidth(progressOf(now, p!!.start, p.stop)).height(3.dp)
                .background(if (f) Edge.OnAccent else Edge.Accent, RoundedCornerShape(2.dp)),
        )
    }
}

