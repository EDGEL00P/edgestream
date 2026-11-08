package com.edgestream.tv.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgestream.tv.data.BackdropImages
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA
import com.edgestream.tv.ui.glass.SHADE_SECTION
import com.edgestream.tv.ui.glass.SHADE_STRIP
import com.edgestream.tv.ui.glass.SHADE_TEXT
import com.edgestream.tv.ui.input.hoverFocus
import com.edgestream.tv.ui.input.remoteClickable
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

val BAND_HEIGHT = 166.dp

/** False inside a kept-but-hidden band: its cards must not take focus. */
val LocalBandActive = androidx.compose.runtime.compositionLocalOf { true }
val DOCK_HEIGHT = 74.dp

/** An open section's inset inside the safe area; full-screen art bleeds past it. */
val SECTION_PAD = 8.dp

/** The always-on score/headline strip under the dock (height + gap above it). */
val TICKER_HEIGHT = 30.dp
private val TICKER_GAP = 6.dp
private val GAP = 12.dp
private val PANEL_RADIUS = 26.dp

/** The preview band slides away after this long with the remote idle on the dock. */
const val BAND_IDLE_MS = 6_000L

private fun Tile.icon(): ImageVector = when (this) {
    Tile.SEARCH -> Icons.Rounded.Search
    Tile.SETTINGS -> Icons.Rounded.Settings
    Tile.PHONE -> Icons.Rounded.PhoneAndroid
    Tile.APPS -> Icons.Rounded.Apps
    Tile.LIVE -> Icons.Rounded.LiveTv
    Tile.SPORTS -> Icons.Rounded.SportsSoccer
    Tile.MOVIES -> Icons.Rounded.Movie
    Tile.WEATHER -> Icons.Rounded.Cloud
    Tile.ODDS -> Icons.Rounded.QueryStats
    Tile.CALENDAR -> Icons.Rounded.CalendarMonth
}

/**
 * The one surface: the photo under a gradient shade, the top deck, the preview band and the dock
 * — text straight on the shade, no panels. The band hides itself when the remote sits idle on the
 * dock. Opening a tile grows the band panel over the whole screen (draw phase only) and swaps in
 * the section; focusable content is never animated, only the shade.
 */
@Composable
fun ShellScreen(
    vm: AppViewModel,
    state: ShellState,
    dockFocus: Map<Tile, FocusRequester>,
    sectionFocus: FocusRequester,
    onEvent: (ShellEvent) -> Unit,
    hosted: @Composable () -> Unit,
) {
    val inSection = state is ShellState.Section
    val rest = state as? ShellState.Rest
    val expand by animateFloatAsState(if (inSection) 1f else 0f, tween(if (inSection) 320 else 220), label = "sheet")

    // Auto-hide: any dock key or tile change shows the band; idle on the dock hides it.
    var bandShown by remember { mutableStateOf(true) }
    var dockKeys by remember { mutableIntStateOf(0) }
    LaunchedEffect(rest?.tile, rest?.inBand, dockKeys) {
        bandShown = true
        if (rest != null && !rest.inBand) { delay(BAND_IDLE_MS); bandShown = false }
    }
    val shown by animateFloatAsState(if (bandShown || inSection) 1f else 0f, tween(if (bandShown) 260 else 420), label = "band")

    val inputs by vm.bandInputs.collectAsStateWithLifecycle()
    val tile = rest?.tile ?: DEFAULT_TILE
    // The band follows the dock only once the remote pauses: holding Right across five tiles
    // must not rebuild five rows of cards and images (that was a slow frame per key press).
    var bandTile by remember { mutableStateOf(tile) }
    LaunchedEffect(tile) { if (bandTile != tile) { delay(160); bandTile = tile } }
    // Every section's band is built once and kept; switching tiles only changes which one shows
    // (rebuilding a row of cards per key press was ~20 ms of recording on the SHIELD).
    val allRows = remember(inputs) { Tile.entries.associateWith { bandRows(it, inputs) } }
    val rows = allRows.getValue(bandTile)
    var focusedItem by remember { mutableStateOf<BandItem?>(null) }
    val spotlight = if (rest?.inBand == true) focusedItem else rows.firstOrNull()?.items?.firstOrNull()
    // At rest, the room takes the spotlit game's colours; open sections light it themselves.
    val roomGame = (spotlight as? BandItem.Game)?.game
    LaunchedEffect(roomGame?.id, rest != null) { if (rest != null) com.edgestream.tv.ui.components.RoomLight.of(roomGame) }
    // Rest on a live game and its channel starts behind Home; a short dwell so passing focus
    // doesn't spin streams up and down.
    val previewLookup by vm.sportsLookup.collectAsStateWithLifecycle()
    val livePreviewGame = roomGame?.takeIf { rest?.inBand == true && it.state == com.edgestream.tv.data.Sports.State.LIVE }
    // Keyed on the lookup too: it fills in once the guide is matched, which can trail launch.
    LaunchedEffect(livePreviewGame?.id, previewLookup) {
        vm.preview.value = null
        val g = livePreviewGame ?: return@LaunchedEffect
        delay(1_200)
        vm.preview.value = previewLookup.find(g)
        if (com.edgestream.tv.data.Sports.traceOn) android.util.Log.d("EdgePreview", "${g.id} -> ${vm.preview.value?.name ?: "no channel"}")
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.preview.value = null } }

    Box(Modifier.fillMaxSize()) {
        Shade(expand = { expand }, shown = { shown })
        if (state is ShellState.Section) {
            // A section's own screen may still be loading when it opens; step focus into its
            // first item (never leave it on the empty container, where OK would do nothing).
            var childFocused by remember { mutableStateOf(false) }
            val focusManager = LocalFocusManager.current
            LaunchedEffect(state.tile) {
                delay(250)
                repeat(14) {
                    if (childFocused) return@LaunchedEffect
                    runCatching { sectionFocus.requestFocus(); focusManager.moveFocus(FocusDirection.Enter) }
                    delay(150)
                }
            }
            // The section rises out of the dock: a short zoom-and-fade from the bottom edge.
            val rise = remember(state.tile) { androidx.compose.animation.core.Animatable(if (com.edgestream.tv.ui.DeviceTier.tier == com.edgestream.tv.ui.DeviceTier.Tier.LOW) 1f else 0f) }
            LaunchedEffect(state.tile) { rise.animateTo(1f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            Box(
                Modifier.fillMaxSize().padding(SECTION_PAD).graphicsLayer {
                    val k = rise.value
                    alpha = k
                    scaleX = 0.94f + 0.06f * k; scaleY = 0.94f + 0.06f * k
                    translationY = (1f - k) * 48.dp.toPx()
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                }.focusRequester(sectionFocus)
                    .onFocusChanged { childFocused = it.hasFocus && !it.isFocused }
                    .focusGroup(),
            ) { hosted() }
        } else if (rest != null) {
            Column(Modifier.fillMaxSize()) {
                AmbientTop(vm, spotlight, tile, Modifier.fillMaxWidth().weight(1f).padding(bottom = GAP).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen })
                val headlines by vm.headlines.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                // Scores never show twice: while the Sports band is up, the strip runs headlines only.
                val sportsBandUp = bandShown && bandTile == Tile.SPORTS
                val tickerPages = remember(inputs.games, headlines, sportsBandUp) {
                    tickerPages(if (sportsBandUp) emptyList() else inputs.games, headlines, perPage = 3)
                }
                Box(
                    Modifier.fillMaxWidth().height(BAND_HEIGHT).graphicsLayer {
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        alpha = shown
                        translationY = (1 - shown) * 24.dp.toPx()
                    },
                ) {
                    Tile.entries.forEach { t ->
                        val active = t == bandTile
                        // Utility tiles have no rows; their single empty-state band is composed only when shown.
                        if (t.utility && !active) return@forEach
                        key(t) {
                            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (active) 1f else 0f }) {
                                androidx.compose.runtime.CompositionLocalProvider(LocalBandActive provides active) {
                                    Band(
                                        vm, rest.copy(tile = t, inBand = rest.inBand && active), allRows.getValue(t),
                                        onFocusItem = { focusedItem = it }, onEvent = { if (active) onEvent(it) },
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(GAP))
                val other by vm.otherNowPlaying.collectAsStateWithLifecycle()
                Dock(rest.tile, rest.inBand, dockFocus, onEvent, other, onKey = { dockKeys++ }, Modifier.fillMaxWidth().height(DOCK_HEIGHT).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen })
                Spacer(Modifier.height(TICKER_GAP))
                Ticker(tickerPages, settings.use24h, Modifier.fillMaxWidth().height(TICKER_HEIGHT).graphicsLayer())
            }
        }
    }
}

/**
 * The only thing between the photo and the text: the theme's darkest colour as a vertical shade.
 * At least [SHADE_TEXT] behind the top deck's text and (while it shows) the band, near-solid under
 * the dock and ticker, clear between. Opening a section blends to one even shade ([expand]).
 */
@Composable
private fun Shade(expand: () -> Float, shown: () -> Float) {
    val ink = Edge.palette.bg
    Box(
        Modifier.fillMaxSize().drawBehind {
            val h = size.height
            val fade = 96.dp.toPx()
            val bandTop = h - (BAND_HEIGHT + GAP + DOCK_HEIGHT + TICKER_GAP + TICKER_HEIGHT).toPx()
            val dockTop = h - (DOCK_HEIGHT + TICKER_GAP + TICKER_HEIGHT).toPx()
            val deckEnd = DECK_TEXT_END * h
            val band = shown(); val e = expand()
            fun ramp(y: Float, start: Float, full: Float) = ((y - start) / (full - start)).coerceIn(0f, 1f)
            // One alpha profile, sampled: the strongest of the deck, band and dock shades at each height.
            val stops = Array(SHADE_SAMPLES + 1) { i ->
                val f = i / SHADE_SAMPLES.toFloat(); val y = f * h
                val deck = SHADE_TEXT * (1 - ramp(y, deckEnd, deckEnd + fade))
                val bandA = SHADE_TEXT * band * ramp(y, bandTop - fade, bandTop)
                val dock = SHADE_STRIP * ramp(y, dockTop - fade, dockTop)
                val rest = maxOf(deck, bandA, dock)
                f to ink.copy(alpha = rest + (SHADE_SECTION - rest) * e)
            }
            // The shell sits inside the TV safe area but the photo runs edge to edge: overdraw the
            // margins (the gradient clamps to its end colours there).
            val bleed = 160.dp.toPx()
            drawRect(
                Brush.verticalGradient(*stops, startY = 0f, endY = h),
                topLeft = Offset(-bleed, -bleed), size = Size(size.width + 2 * bleed, h + 2 * bleed),
            )
        },
    )
}

private const val SHADE_SAMPLES = 32

/** Fraction of the screen height the top deck's text occupies (clock, calendar, Spotlight). */
private const val DECK_TEXT_END = 0.40f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Band(
    vm: AppViewModel,
    state: ShellState.Rest,
    rows: List<BandRow>,
    onFocusItem: (BandItem) -> Unit,
    onEvent: (ShellEvent) -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tile = state.tile
    if (rows.isEmpty()) {
        // A utility tile (Apps, Search…) is already named in the Spotlight above: say nothing twice.
        if (tile.utility) return
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(tile.label, color = Edge.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Nothing to preview yet · press OK to open",
                color = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA), fontSize = 15.sp,
            )
        }
        return
    }
    val items = remember(rows) { rows.flatMap { r -> r.items.mapIndexed { i, it -> (if (i == 0) r.title else null) to it } } }
    val requesters = remember(items) { items.associate { it.second.id to FocusRequester() } }
    val first = requesters.getValue(items.first().second.id)
    key(tile) {
        val listState = rememberLazyListState()
        // While the remote is on the dock the band shows its first row from the start
        // (games arriving mid-load must not leave it scrolled sideways).
        LaunchedEffect(items, state.inBand) { if (!state.inBand) listState.scrollToItem(0) }
        LazyRow(
            Modifier.fillMaxSize()
                .onFocusChanged { if (it.hasFocus != state.inBand) onEvent(if (it.hasFocus) ShellEvent.EnterBand else ShellEvent.LeaveBand) }
                .focusRestorer(first),
            state = listState,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.second.id }) { _, (header, item) ->
                Column(Modifier.onFocusChanged { if (it.hasFocus) onFocusItem(item) }) {
                    Text(header ?: "", color = Edge.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.height(24.dp))
                    val req = requesters.getValue(item.id)
                    when (item) {
                        // While a game previews behind Home, OK takes its channel full screen; otherwise Sports opens.
                        is BandItem.Game -> GameCard(item.game, req) {
                            val ch = vm.preview.value
                            if (ch != null) vm.playChannel(ch, vm.zapListFor(ch)) else onEvent(ShellEvent.Open(Tile.SPORTS))
                        }
                        is BandItem.Channel -> ChannelCard(item, req) { vm.playChannel(item.channel, item.row) }
                        is BandItem.Resume -> ResumeCard(item, req) { vm.resume(item.p) }
                        is BandItem.Watch -> WatchCard(item, req) { vm.openWatchItem(item.item) }
                        is BandItem.Vod -> VodCard(item, req) { vm.vodDetail.value = item.item }
                        is BandItem.Alert -> AlertCard(item, req) { onEvent(ShellEvent.Open(Tile.WEATHER)) }
                        is BandItem.Day -> DayCard(item, settings.fahrenheit, req) { onEvent(ShellEvent.Open(Tile.WEATHER)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Dock(
    current: Tile,
    bandFocused: Boolean,
    dockFocus: Map<Tile, FocusRequester>,
    onEvent: (ShellEvent) -> Unit,
    other: com.edgestream.tv.system.OtherNowPlaying?,
    onKey: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) onKey(); false }
            .focusProperties { onEnter = { dockFocus.getValue(current).requestFocus() } }
            .focusGroup()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile.entries.filter { it.utility }.forEach { UtilityIcon(it, dockFocus.getValue(it), onEvent) }
        Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(40.dp).background(Edge.Text.copy(alpha = 0.22f)))
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tile.entries.filterNot { it.utility }.forEach {
                SectionTile(it, marked = bandFocused && it == current, dockFocus.getValue(it), onEvent)
            }
        }
        other?.let { NowPlayingChip(it, Modifier.padding(start = 12.dp)) }
    }
}

/** Another app's media at the dock's end (ported from EdgeLauncher): art, title, OK = play/pause. */
@Composable
private fun NowPlayingChip(np: com.edgestream.tv.system.OtherNowPlaying, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.width(230.dp).height(50.dp)
            .background(if (focused) Edge.Accent else Edge.Text.copy(alpha = 0.10f), shape)
            .onFocusChanged { focused = it.isFocused }
            .remoteClickable { np.playPause() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        np.art?.let { androidx.compose.foundation.Image(it, null, Modifier.size(38.dp).clip(RoundedCornerShape(8.dp))) }
            ?: Icon(Icons.Rounded.Apps, null, Modifier.size(28.dp), tint = Edge.Text)
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(np.title, color = if (focused) Edge.OnAccent else Edge.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text((if (np.playing) "▶ " else "❚❚ ") + np.subtitle.ifBlank { np.appLabel }, color = if (focused) Edge.OnAccent else Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA),
                fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun UtilityIcon(tile: Tile, requester: FocusRequester, onEvent: (ShellEvent) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.padding(horizontal = 3.dp).size(44.dp).clip(CircleShape)
            .background(if (focused) Edge.Text else Edge.Text.copy(alpha = 0.08f))
            .hoverFocus(requester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onEvent(ShellEvent.Focus(tile)) }
            .remoteClickable { onEvent(ShellEvent.Open(tile)) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(tile.icon(), tile.label, tint = if (focused) Edge.Bg else Edge.Text, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SectionTile(tile: Tile, marked: Boolean, requester: FocusRequester, onEvent: (ShellEvent) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1f else 0f, spring(stiffness = 600f), label = "lift")
    val shape = RoundedCornerShape(16.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .graphicsLayer {
                    val s = 1f + 0.12f * lift
                    scaleX = s; scaleY = s
                    translationY = -8.dp.toPx() * lift
                    this.shape = shape; clip = true
                }
                .height(46.dp)
                .background(if (focused) Edge.Accent else Edge.Text.copy(alpha = 0.10f))
                .hoverFocus(requester)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onEvent(ShellEvent.Focus(tile)) }
                .remoteClickable { onEvent(ShellEvent.Open(tile)) }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // Labels carry the tabs; the icon lights up only on the active one, so every tab fits
            // inside the safe area without scrolling.
            if (focused || marked) {
                Icon(tile.icon(), null, tint = if (focused) Edge.OnAccent else Edge.Text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(tile.label, color = if (focused) Edge.OnAccent else Edge.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        // Marker: which tile the band belongs to — on focus and while browsing its band.
        Box(
            Modifier.padding(top = 3.dp).size(width = 18.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (focused || marked) Edge.Accent else Color.Transparent),
        )
    }
}
