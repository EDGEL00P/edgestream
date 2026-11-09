package com.edgestream.tv.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import coil3.compose.AsyncImage
import com.edgestream.tv.data.Sports
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.LocalSafeArea
import com.edgestream.tv.ui.glass
import androidx.compose.ui.draw.shadow
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.NowPlaying
import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import com.edgestream.tv.ui.digitOf
import com.edgestream.tv.ui.VideoSurface
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Logo
import com.edgestream.tv.ui.components.OneLine
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.components.ProgressLine
import com.edgestream.tv.ui.components.TeamLogo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What is on top of the video. Exactly one layer at a time, so every key has one meaning:
 *  NONE      the remote zaps/seeks directly; OK opens the control bar
 *  BAR       focusable buttons along the bottom; hides itself after a few idle seconds
 *  CHANNELS  channel list on the left
 *  AUDIO / SUBTITLES / PICTURE  a short list on the right
 * Back always closes the top layer, then leaves full screen.
 */
private enum class Layer { NONE, BAR, CHANNELS, AUDIO, SUBTITLES, PICTURE, GUIDE, GAMECAST }

private const val BAR_IDLE_MS = 4_000L
/** Channel list and the audio / subtitle / picture lists. */
private const val PANEL_IDLE_MS = 10_000L
/** The guide over live TV: long enough to read, then back to the picture. */
private const val GUIDE_IDLE_MS = 30_000L
/** The in-game stats panel: long enough to read a drive, gone once the remote is down. */
private const val GAMECAST_IDLE_MS = 45_000L

@Composable
fun PlayerScreen(vm: AppViewModel) {
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val st by vm.player.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val clock by vm.clock.collectAsStateWithLifecycle()
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()

    // One badge set for the whole player: match the release name when there is one, plus the
    // real video/audio tracks, so the strip reflects whatever is actually playing.
    val badgeConfig = remember(
        settings.badgePack, settings.contentRegion, settings.contentLanguage,
        settings.contentCertification, settings.audioLanguage, vm.countryCode,
    ) { com.edgestream.tv.data.StreamBadges.config(settings, vm.countryCode) }
    val badgeSet by produceState(com.edgestream.tv.data.StreamBadges.NONE, badgeConfig) {
        value = com.edgestream.tv.data.StreamBadges.load(
            com.edgestream.tv.EdgeStreamApp.instance.cacheDir, badgeConfig.url, badgeConfig.extras,
        )
    }
    val badges = remember(badgeSet, np, st.videoInfo, st.videoHeight, st.audio) {
        val head = when (val p = np) {
            is NowPlaying.Live -> p.channel.name + " " + (p.catchup?.title ?: "")
            is NowPlaying.Vod -> p.title + " " + (p.subtitle ?: "") + " " + p.badgeText
            null -> ""
        }
        val tracks = com.edgestream.tv.data.StreamBadges.describe(
            st.videoInfo, st.videoHeight, st.audio.map { it.label },
        )
        badgeSet.match("$head $tracks", limit = 6)
    }

    val live = np as? NowPlaying.Live
    val isLiveTv = live != null && live.catchup == null

    // What game, if any, is on the channel being watched. The Gamecast panel and the score bug
    // read from here; non-sports channels never trigger a sports fetch.
    var sportsGame by remember { mutableStateOf<Sports.Game?>(null) }
    LaunchedEffect(isLiveTv, live?.channel?.key) {
        sportsGame = null
        val channel = live?.channel ?: return@LaunchedEffect
        if (!isLiveTv) return@LaunchedEffect
        val title = vm.epgFor(channel)?.first?.title
        Sports.cachedGames()?.let { board ->
            sportsGame = Sports.gameForChannel(board, channel, title)?.let { if (vm.settings.value.hideScores) Sports.spoilerFree(it) else it }
        }
        if (!Sports.isSportsChannel(channel) && !Sports.isSportsTitle(title) && sportsGame == null) return@LaunchedEffect
        while (true) {
            val program = vm.epgFor(channel)?.first?.title
            val board = runCatching { Sports.gamesForChannel(channel, program) }.getOrDefault(emptyList())
            sportsGame = Sports.gameForChannel(board, channel, program)?.let { if (vm.settings.value.hideScores) Sports.spoilerFree(it) else it }
            delay(30_000)
        }
    }

    // Opened straight into the guide when that's what was asked for (rail, GUIDE key).
    var layer by remember { mutableStateOf(if (vm.guideOverlay.value) Layer.GUIDE else Layer.NONE) }
    LaunchedEffect(sportsGame?.id) {
        if (layer == Layer.GAMECAST && sportsGame == null) layer = Layer.NONE
    }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var toastUntil by remember { mutableLongStateOf(0L) }
    var digits by remember { mutableStateOf("") }
    var skipFocused by remember { mutableStateOf(false) }
    // Seek bubble (films/episodes): where a Left/Right press lands, over the artwork.
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    // A 400 ms tick for the time bar and toast. Only the leaf composables read its value, so the
    // ticker never recomposes the whole player screen (the old one re-evaluated everything at
    // 2.5 Hz for the entire time the player was open).
    val tick = remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }

    fun touch() { lastInput = System.currentTimeMillis() }
    fun flashInfo() { toastUntil = System.currentTimeMillis() + 4_000 }

    // Keep the clock fresh and hide the bar when the remote has been idle. The ticker
    // stops with the screen (STARTED) so a paused app burns no frames in the background.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                tick.longValue = System.currentTimeMillis()
                // Every overlay goes away by itself, like a set-top box: nothing stays over the
                // picture once the remote has been put down.
                val idle = System.currentTimeMillis() - lastInput
                val limit = when (layer) {
                    Layer.NONE -> Long.MAX_VALUE
                    Layer.BAR -> BAR_IDLE_MS
                    Layer.CHANNELS, Layer.AUDIO, Layer.SUBTITLES, Layer.PICTURE -> PANEL_IDLE_MS
                    Layer.GUIDE -> GUIDE_IDLE_MS
                    Layer.GAMECAST -> GAMECAST_IDLE_MS
                }
                if (idle > limit) layer = Layer.NONE
                delay(400)
            }
        }
    }
    // A brief info toast whenever the channel or film changes.
    LaunchedEffect(live?.channel?.key, (np as? NowPlaying.Vod)?.key) { flashInfo() }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_600)
        digits.toIntOrNull()?.let(vm::jumpToNumber)
        digits = ""
    }
    var wasGuide by remember { mutableStateOf(layer == Layer.GUIDE) }
    LaunchedEffect(layer) {
        if (layer == Layer.NONE) runCatching { rootFocus.requestFocus() }
        // Only a guide that was open and has now closed clears the request.
        if (wasGuide && layer != Layer.GUIDE) vm.guideOverlay.value = false
        wasGuide = layer == Layer.GUIDE
    }
    // Opened from the nav rail or elsewhere while live TV plays.
    val guideRequested by vm.guideOverlay.collectAsStateWithLifecycle()
    LaunchedEffect(guideRequested) { if (guideRequested) layer = Layer.GUIDE }

    BackHandler {
        when {
            digits.isNotEmpty() -> digits = ""
            layer == Layer.AUDIO || layer == Layer.SUBTITLES || layer == Layer.PICTURE -> { layer = Layer.BAR; touch() }
            layer != Layer.NONE -> layer = Layer.NONE
            else -> vm.exitFullscreen()
        }
    }

    // Side panels and the guide slide over the picture; frost the video behind them so the
    // smoked-glass sheets read as genuinely frosted.
    val frostVideo = layer in setOf(Layer.CHANNELS, Layer.AUDIO, Layer.SUBTITLES, Layer.PICTURE, Layer.GAMECAST, Layer.GUIDE)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Any key while an overlay is open counts as activity, so the bar doesn't vanish mid-use.
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) touch(); false }
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || layer != Layer.NONE) return@onKeyEvent false
                // A skip pill has focus: let D-pad focus run (OK = skip, Back returns to the root).
                if (skipFocused) return@onKeyEvent false
                val exo = vm.player.exo
                val digit = digitOf(e.key)
                when {
                    digit != null && isLiveTv -> { digits = (digits + digit).take(5); true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                        if (st.error != null && !st.buffering) vm.player.retry() else layer = Layer.BAR
                        true
                    }
                    e.key == Key.MediaPlayPause || e.key == Key.MediaPlay || e.key == Key.MediaPause -> {
                        if (exo.isPlaying) exo.pause() else exo.play(); flashInfo(); true
                    }
                    // The SHIELD remote has no GUIDE button (and the system swallows GUIDE anyway),
                    // so its menu button opens the guide over live TV.
                    e.key == Key.Menu -> { layer = if (isLiveTv) Layer.GUIDE else Layer.BAR; true }
                    // INFO is the natural remote shortcut for the in-game stats panel.
                    e.key == Key.Info -> { if (sportsGame != null) layer = Layer.GAMECAST else flashInfo(); true }
                    isLiveTv && (e.key == Key.DirectionUp || e.key == Key.ChannelUp) -> { vm.zap(+1); true }
                    isLiveTv && (e.key == Key.DirectionDown || e.key == Key.ChannelDown) -> { vm.zap(-1); true }
                    isLiveTv && e.key == Key.DirectionLeft -> { layer = Layer.CHANNELS; true }
                    isLiveTv && e.key == Key.DirectionRight -> { layer = Layer.BAR; true }
                    !isLiveTv && (e.key == Key.DirectionLeft || e.key == Key.MediaRewind) -> {
                        val target = (exo.currentPosition - 10_000).coerceAtLeast(0)
                        exo.seekTo(target); seekPreview = target; flashInfo(); true
                    }
                    !isLiveTv && (e.key == Key.DirectionRight || e.key == Key.MediaFastForward) -> {
                        val target = exo.currentPosition + 30_000
                        exo.seekTo(target); seekPreview = target; flashInfo(); true
                    }
                    !isLiveTv && (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> { layer = Layer.BAR; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        VideoSurface(vm.player, Modifier.fillMaxSize(), frostVideo = frostVideo)

        // Skip intro / recap / credits pills, floating over the picture. One fetch feeds both
        // overlays: Up Next opens where the credits start and stands in for "Skip credits".
        val vod = np as? NowPlaying.Vod
        val segments by produceState<com.edgestream.tv.data.IntroSegments?>(
            null, vod?.key, settings.useIntroDb, vod?.imdbId, vod?.tmdbId, vod?.season, vod?.episode,
        ) {
            value = if (vod != null && settings.useIntroDb) {
                com.edgestream.tv.data.TheIntroDb.forPlayback(vod.imdbId, vod.tmdbId, vod.season, vod.episode)
            } else null
        }
        var upNextShowing by remember(vod?.key) { mutableStateOf(false) }
        VodSkips(
            vm, np, tick, rootFocus, segments, upNextShowing,
            onFocus = { skipFocused = it },
            Modifier.align(Alignment.BottomStart).padding(bottom = 96.dp, start = 48.dp),
        )
        UpNextCard(vm, np, tick, segments?.credits?.firstOrNull()?.startMs, onShowing = { upNextShowing = it })

        // Over the frozen picture, and for live TV how full the cushion is. Once the picture
        // actually plays, the loader goes whatever the buffering flag says.
        if (st.buffering && st.error == null && !st.isPlaying) com.edgestream.tv.ui.components.EdgeLoader(
            title = st.title,
            progress = if (st.isLive && st.cushionMs > 0) (st.bufferedMs.toFloat() / st.cushionMs).coerceIn(0f, 1f) else null,
            poster = (np as? NowPlaying.Vod)?.poster ?: (np as? NowPlaying.Live)?.channel?.logo,
            dim = 0.6f,
        )
        ErrorCard(st.error, st.buffering, isLiveTv, Modifier.align(Alignment.Center))

        // Seek bubble (films/episodes): where the jump lands, over the title's artwork.
        seekPreview?.let { target ->
            val poster = (np as? NowPlaying.Vod)?.poster
            val total = vm.player.exo.duration.coerceAtLeast(0)
            androidx.compose.runtime.LaunchedEffect(target) {
                kotlinx.coroutines.delay(1_400); seekPreview = null
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 48.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xE6000000)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                poster?.let {
                    coil3.compose.AsyncImage(it, null, Modifier.size(width = 72.dp, height = 108.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(14.dp))
                }
                Column {
                    Text(Format.clock(target), style = MaterialTheme.typography.headlineMedium, color = Edge.Text)
                    if (total > 0) Text("of ${Format.clock(total)}", style = MaterialTheme.typography.labelMedium, color = Edge.Dim)
                }
            }
        }

        // Auto captions, streaming style: clean white type with a soft shadow, no box.
        val caption by vm.player.captions.text.collectAsStateWithLifecycle()
        if (settings.autoCaptions && caption.isNotBlank() && layer != Layer.GUIDE) {
            Text(
                caption,
                style = MaterialTheme.typography.titleLarge.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.85f),
                        blurRadius = 10f,
                        offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                    ),
                ),
                color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (layer == Layer.BAR) 300.dp else 84.dp, start = 200.dp, end = 200.dp),
            )
        }

        if (digits.isNotEmpty()) {
            Text(
                digits, fontSize = 56.sp, fontWeight = FontWeight.Black, color = Edge.Text,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
                    .shadow(28.dp, RoundedCornerShape(20.dp), clip = false)
                    .glass(RoundedCornerShape(20.dp), Edge.Smoke).padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }

        // Info-only toast (no buttons) after zapping or seeking with the bar closed.
        InfoToast(
            vm, np, st.videoInfo, settings.use24h, clock, st.isPlaying, favs,
            badges = badges,
            sportsGame = sportsGame, show = layer == Layer.NONE, toastUntil = toastUntil,
            tick = tick, modifier = Modifier.align(Alignment.BottomCenter),
        )

        AnimatedVisibility(
            layer == Layer.BAR, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut(),
        ) {
            BarLayer(
                vm = vm, np = np, videoInfo = st.videoInfo, use24h = settings.use24h, clock = clock,
                playing = st.isPlaying, favs = favs, isLiveTv = isLiveTv, live = live,
                badges = badges, sportsGame = sportsGame, tick = tick,
                onLayer = { layer = it; touch() },
            )
        }

        AnimatedVisibility(layer == Layer.GUIDE, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            GuideScreen(vm, overlay = true) { layer = Layer.NONE }
        }

        AnimatedVisibility(
            layer == Layer.CHANNELS && live != null, Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            if (live != null) ChannelPanel(vm, live, clock, favs) { layer = Layer.NONE }
        }

        AnimatedVisibility(
            layer == Layer.GAMECAST && live != null && sportsGame != null, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            val game = sportsGame
            if (live != null && game != null) GamecastPanel(game, live.channel, hideScores = settings.hideScores)
        }

        AnimatedVisibility(
            layer == Layer.AUDIO || layer == Layer.SUBTITLES || layer == Layer.PICTURE, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            when (layer) {
                Layer.AUDIO -> ChoiceSheet("Audio", st.audio.map { it.label to it.selected }.ifEmpty { listOf("Default" to true) }) { i ->
                    st.audio.getOrNull(i)?.let { vm.player.selectTrack(it, C.TRACK_TYPE_AUDIO) }
                }
                Layer.SUBTITLES -> {
                    // Off, then auto captions (generated on the TV), then the stream's own tracks.
                    val auto = settings.autoCaptions
                    val captionStatus by vm.player.captions.status.collectAsStateWithLifecycle()
                    val autoLabel = when (val c = captionStatus) {
                        is com.edgestream.tv.player.AutoCaptions.Status.Downloading -> "Auto captions · downloading ${c.percent}%"
                        is com.edgestream.tv.player.AutoCaptions.Status.Failed -> "Auto captions · ${c.message}"
                        else -> "Auto captions (English, on-device)"
                    }
                    // Films and episodes can fetch subtitles online (OpenSubtitles) until some are attached.
                    val canFindOnline = vm.nowPlaying.collectAsStateWithLifecycle().value is NowPlaying.Vod && st.subtitles.none { "Online" in it.label }
                    val tracks = st.subtitles.map { it.label to (!auto && it.selected) }
                    ChoiceSheet(
                        "Subtitles",
                        listOf("Off" to (!auto && st.subtitles.none { it.selected }), autoLabel to auto) + tracks +
                            (if (canFindOnline) listOf("Find Online Subtitles" to false) else emptyList()),
                    ) { i ->
                        when {
                            i == 0 -> { vm.updateSettings { it.copy(autoCaptions = false) }; vm.player.selectTrack(null, C.TRACK_TYPE_TEXT) }
                            i == 1 -> { vm.player.selectTrack(null, C.TRACK_TYPE_TEXT); vm.updateSettings { it.copy(autoCaptions = true) } }
                            i - 2 < st.subtitles.size -> st.subtitles.getOrNull(i - 2)?.let { vm.updateSettings { s -> s.copy(autoCaptions = false) }; vm.player.selectTrack(it, C.TRACK_TYPE_TEXT) }
                            else -> { vm.updateSettings { it.copy(autoCaptions = false) }; vm.findOnlineSubtitles() }
                        }
                    }
                }
                else -> {
                    val aspect by vm.player.aspect.collectAsStateWithLifecycle()
                    ChoiceSheet("Picture", com.edgestream.tv.player.AspectMode.entries.map { it.label to (it == aspect) }) { i ->
                        vm.player.aspect.value = com.edgestream.tv.player.AspectMode.entries[i]
                    }
                }
            }
        }
    }
}

/** Dark band along the bottom: opaque enough that white text reads over any picture. */
@Composable
private fun Scrim(content: @Composable () -> Unit) {
    val safe = LocalSafeArea.current
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color(0xCC000000), 1f to Color(0xF5000000)))
            .padding(
                start = 48.dp + safe.horizontal, end = 48.dp + safe.horizontal,
                top = 72.dp + safe.vertical, bottom = 32.dp + safe.vertical,
            ),
    ) { content() }
}

@Composable
private fun ErrorCard(error: String?, buffering: Boolean, isLiveTv: Boolean, modifier: Modifier) {
    // While it's still trying, the loading ring says enough; a card only for a real failure.
    if (error == null || buffering) return
    Column(
        modifier.shadow(32.dp, EdgeShapes.Panel, clip = false).glass(EdgeShapes.Panel, Edge.Smoke.copy(alpha = 0.9f)).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Stream unavailable", style = MaterialTheme.typography.headlineSmall, color = Edge.Text)
        Text(
            error, style = MaterialTheme.typography.bodyMedium, color = Edge.Text.copy(alpha = 0.78f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).widthIn(max = 520.dp),
        )
        Text(
            if (isLiveTv) "OK  retry     ▲ ▼  change channel     BACK  exit" else "OK  retry     BACK  exit",
            style = MaterialTheme.typography.labelMedium, color = Edge.Accent, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * The info-only toast (no buttons) after zapping or seeking with the bar closed. It owns the
 * tick read, so the 400 ms ticker refreshes this small subtree alone — the whole player screen
 * no longer re-evaluates at 2.5 Hz.
 */
@Composable
private fun InfoToast(
    vm: AppViewModel, np: NowPlaying?, videoInfo: String, use24h: Boolean, clock: Long,
    playing: Boolean, favs: Set<String>, sportsGame: Sports.Game?, show: Boolean,
    toastUntil: Long, tick: androidx.compose.runtime.State<Long>, modifier: Modifier = Modifier,
    badges: List<com.edgestream.tv.data.StreamBadges.Badge> = emptyList(),
) {
    val active = tick.value >= 0 && show && System.currentTimeMillis() < toastUntil
    AnimatedVisibility(active, modifier, enter = fadeIn(), exit = fadeOut()) {
        Scrim { NowInfo(vm, np, videoInfo, use24h, clock, tick.value, playing, favs, badges = badges, sportsGame = sportsGame) }
    }
}

/**
 * The control bar with the channel/film info above it. Owns the tick read so the seek bar and
 * time labels refresh every 400 ms without recomposing the video surface or any other overlay.
 */
/**
 * Skip intro / skip credits pills for episodes. The intro is learned per series: the first time
 * the viewer marks "Set intro end" in the control bar, every later episode offers to skip to it.
 */
@Composable
private fun VodSkips(
    vm: AppViewModel, np: NowPlaying?, tick: androidx.compose.runtime.State<Long>,
    rootFocus: FocusRequester,
    segments: com.edgestream.tv.data.IntroSegments?,
    upNextShowing: Boolean,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vod = np as? NowPlaying.Vod ?: return
    val seriesKey = vod.key.substringBefore(":E:", "")
    tick.value // the 400 ms ticker refreshes position here, scoped to this overlay
    val exo = vm.player.exo
    val position = exo.currentPosition
    val duration = exo.duration.coerceAtLeast(0)
    val localIntro = if (segments?.intro.isNullOrEmpty() && seriesKey.isNotEmpty() && seriesKey != vod.key) {
        com.edgestream.tv.data.IntroSkip.forSeries(seriesKey)
    } else null
    // Media chapters aren't exposed by Media3 1.11.1 (Timeline.Window has no chapters field),
    // so chapter jumping stays with the D-pad seek keys.
    val pills = com.edgestream.tv.data.IntroSkip.pills(position, duration, segments, localIntro, upNextShowing)
    if (pills.isEmpty()) return

    // The pills take focus themselves (Netflix-style): OK skips, Back returns to the player root.
    var pillFocused by remember { mutableStateOf(false) }
    val firstPill = remember(vod.key, pills.first().first) { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(pills.first().first) { runCatching { firstPill.requestFocus() } }
    BackHandler(enabled = pillFocused) { runCatching { rootFocus.requestFocus() } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pills.forEachIndexed { i, (label, target) ->
            SkipPill(label, if (i == 0) firstPill else null, onFocus = { pillFocused = it; onFocus(it) }) { exo.seekTo(target) }
        }
    }
}

@Composable
private fun SkipPill(
    label: String,
    requester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick, shape = EdgeShapes.Chip,
        color = Edge.Smoke.copy(alpha = 0.96f), focusedColor = Edge.Text, focusScale = 1.05f,
        modifier = if (requester != null) Modifier.focusRequester(requester) else Modifier,
    ) { f ->
        androidx.compose.runtime.LaunchedEffect(f) { onFocus(f) }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                Icons.Rounded.SkipNext, label, Modifier.size(18.dp),
                tint = if (f) Edge.Bg else Edge.Accent,
            )
            Text(
                "  $label", style = MaterialTheme.typography.labelLarge,
                color = if (f) Edge.Bg else Edge.Text, maxLines = 1,
            )
        }
    }
}

/**
 * Up Next: at the credits (last 30 s) the next episode's still, its number and
 * title, and a Next Episode button that fills over a 15 s countdown, then plays. Cancel or
 * Back keeps watching the credits. Text sits on a soft corner shade, never a panel.
 */
@Composable
private fun UpNextCard(
    vm: AppViewModel, np: NowPlaying?, tick: androidx.compose.runtime.State<Long>,
    creditsStartMs: Long?,
    onShowing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vod = np as? NowPlaying.Vod ?: return
    val settings by vm.settings.collectAsStateWithLifecycle()
    if (!(vod.key.contains(":E:") || vod.key.startsWith("debrid:")) || !settings.autoNextEpisode) return
    tick.value
    val exo = vm.player.exo
    val duration = exo.duration.coerceAtLeast(0)
    val remaining = duration - exo.currentPosition
    var dismissed by remember(vod.key) { mutableStateOf(false) }
    val suppress by vm.suppressAutoNext.collectAsStateWithLifecycle()
    // From where the credits start when TheIntroDB knows it, else the last 30 s.
    val inCredits = creditsStartMs != null && exo.currentPosition >= creditsStartMs && creditsStartMs < duration
    if (duration < 60_000L || (remaining > 30_000L && !inCredits) || remaining < 0L || dismissed || suppress == vod.key) return
    val upNext by produceState<AppViewModel.UpNext?>(null, vod.key) { value = runCatching { vm.upNextForCurrent() }.getOrNull() }
    val next = upNext ?: return
    androidx.compose.runtime.DisposableEffect(vod.key) { onShowing(true); onDispose { onShowing(false) } }

    // A real countdown from when the card appears, one second at a time.
    var secondsLeft by remember(vod.key) { androidx.compose.runtime.mutableIntStateOf(UP_NEXT_SECONDS) }
    LaunchedEffect(vod.key) {
        while (secondsLeft > 0) { delay(1_000); secondsLeft-- }
        next.play()
    }
    val fill by androidx.compose.animation.core.animateFloatAsState(
        1f - secondsLeft / UP_NEXT_SECONDS.toFloat(), androidx.compose.animation.core.tween(1_000, easing = androidx.compose.animation.core.LinearEasing), label = "upNext",
    )
    BackHandler { vm.cancelAutoNext(); dismissed = true }
    val playFocus = remember(vod.key) { FocusRequester() }
    LaunchedEffect(vod.key) { delay(80); runCatching { playFocus.requestFocus() } }

    Box(modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xE6000000), Color.Transparent), center = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY), radius = 1600f))) {
        val safe = LocalSafeArea.current
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 56.dp + safe.horizontal, bottom = 56.dp + safe.vertical).width(420.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Logo(next.image, next.series, Modifier.fillMaxWidth().height(236.dp).clip(RoundedCornerShape(12.dp)), ContentScale.Crop)
            Text("UP NEXT · SEASON ${next.season} · EPISODE ${next.episode}", style = MaterialTheme.typography.labelMedium, color = Edge.Accent, maxLines = 1)
            OneLine(next.title ?: next.series, style = MaterialTheme.typography.titleLarge, color = Edge.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // The primary button fills left to right as the countdown runs.
                FocusSurface(
                    onClick = { next.play() }, modifier = Modifier.height(52.dp).width(240.dp).focusRequester(playFocus),
                    shape = RoundedCornerShape(50), color = Edge.Text.copy(alpha = 0.18f), focusedColor = Edge.Text.copy(alpha = 0.30f), glow = false,
                ) { _ ->
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Edge.Text))
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(Icons.Rounded.PlayArrow, null, Modifier.size(22.dp), tint = if (fill > 0.5f) Edge.Bg else Edge.Text)
                        Text("Next Episode · $secondsLeft", style = MaterialTheme.typography.labelLarge, color = if (fill > 0.5f) Edge.Bg else Edge.Text, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                com.edgestream.tv.ui.components.EdgeButton("Cancel", { vm.cancelAutoNext(); dismissed = true })
            }
        }
    }
}

private const val UP_NEXT_SECONDS = 15

@Composable
private fun BarLayer(
    vm: AppViewModel, np: NowPlaying?, videoInfo: String, use24h: Boolean, clock: Long,
    playing: Boolean, favs: Set<String>, isLiveTv: Boolean, live: NowPlaying.Live?,
    sportsGame: Sports.Game?, tick: androidx.compose.runtime.State<Long>,
    onLayer: (Layer) -> Unit,
    badges: List<com.edgestream.tv.data.StreamBadges.Badge> = emptyList(),
) {
    val now = tick.value
    Scrim {
        // The control bar brings its own focusable seek bar; don't draw a second one above it.
        NowInfo(vm, np, videoInfo, use24h, clock, now, playing, favs, badges = badges, timeBar = false, sportsGame = sportsGame)
        ControlBar(
            vm = vm, isLiveTv = isLiveTv, live = live, favs = favs, playing = playing,
            tick = now,
            sportsGame = sportsGame, use24h = use24h,
            onLayer = onLayer,
        )
    }
}

/** Channel/programme or film details with progress; shared by the toast and the control bar. */
@Composable
private fun NowInfo(
    vm: AppViewModel, np: NowPlaying?, videoInfo: String, use24h: Boolean, clock: Long,
    tick: Long, playing: Boolean, favs: Set<String>, timeBar: Boolean = true,
    sportsGame: Sports.Game? = null,
    badges: List<com.edgestream.tv.data.StreamBadges.Badge> = emptyList(),
) {
    // Recomposed by the ticker; the position itself is read live so seeks show instantly.
    val position = if (tick >= 0) vm.player.exo.currentPosition else 0L
    val duration = vm.player.exo.duration.coerceAtLeast(0)
    if (badges.isNotEmpty()) Row(
        Modifier.padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badges.forEach { b ->
            if (b.image.isEmpty()) Pill(b.name, color = Edge.Accent)
            else AsyncImage(b.image, b.name, Modifier.height(28.dp), contentScale = ContentScale.FillHeight)
        }
    }
    when (np) {
        is NowPlaying.Live -> {
            val ch = np.channel
            val epg = vm.epgFor(ch)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(ch.logo, ch.name, Modifier.size(width = 88.dp, height = 60.dp))
                Column(Modifier.padding(start = 18.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${com.edgestream.tv.data.ChannelNumbers.lcn(ch)}  ", style = MaterialTheme.typography.headlineSmall, color = Edge.Accent)
                        OneLine(ch.name, Modifier.weight(1f, fill = false), MaterialTheme.typography.headlineSmall, Edge.Text)
                        vm.badge(ch.key)?.quality?.let { Pill(it, Modifier.padding(start = 10.dp), Edge.Text.copy(alpha = 0.78f)) }
                        if (ch.key in favs) Text("  ★", color = Edge.Gold, fontSize = 18.sp)
                    }
                    val catchup = np.catchup
                    val now = epg?.first
                    when {
                        catchup != null -> {
                            OneLine(catchup.title, style = MaterialTheme.typography.titleLarge, color = Edge.Text)
                            Text("Catch-up · ${Format.day(catchup.start)} ${Format.range(catchup.start, catchup.stop, use24h)}", style = MaterialTheme.typography.labelMedium, color = Edge.Text.copy(alpha = 0.78f))
                        }
                        now != null -> {
                            OneLine(now.title, style = MaterialTheme.typography.titleLarge, color = Edge.Text)
                            now.description?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").trim(),
                                    style = MaterialTheme.typography.bodyMedium, color = Edge.Text.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                "${Format.range(now.start, now.stop, use24h)} · ${Format.duration(now.stop - clock)} left" +
                                    (epg.second?.let { "   ·   Next: ${Format.time(it.start, use24h)} ${it.title}" } ?: ""),
                                style = MaterialTheme.typography.labelMedium, color = Edge.Text.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        else -> Text(com.edgestream.tv.data.NameCleaner.tidyGroup(ch.groupName), style = MaterialTheme.typography.bodyMedium, color = Edge.Text.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Format.time(clock, use24h), style = MaterialTheme.typography.headlineSmall, color = Edge.Text)
                    // Commercial meta: where the channel is from + what's playing (language/codec via track).
                    com.edgestream.tv.data.NameCleaner.tidyGroup(ch.groupName).substringBefore(" | ").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = Edge.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (videoInfo.isNotEmpty()) Text(videoInfo, style = MaterialTheme.typography.labelMedium, color = Edge.Text.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (np.catchup != null) { if (timeBar) TimeBar(position, duration, vm.player.exo.bufferedPosition, use24h = use24h) }
            else epg?.first?.let { ProgressLine(Format.fraction(it.start, it.stop, clock), Modifier.fillMaxWidth().padding(top = 14.dp), 4.dp, Edge.Text.copy(alpha = 0.30f)) }
            if (sportsGame != null && np.catchup == null) ScoreBug(sportsGame)
        }
        is NowPlaying.Vod -> {
            // Title-card continuity: the same display face the On Demand hero used.
            val title = com.edgestream.tv.data.NameCleaner.tidyTitle(np.title)
            OneLine(
                title.substringBefore(" · "),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = com.edgestream.tv.ui.glass.ClockFont, fontSize = 44.sp, letterSpacing = 0.5.sp,
                ),
                color = Edge.Text,
            )
            val episodeNo = title.substringAfter(" · ", "").takeIf { it.isNotBlank() }
                ?.replace(Regex("""S(\d+)E(\d+)"""), "Season $1 · Episode $2")
            val episodeLine = listOfNotNull(episodeNo, np.subtitle).joinToString("  ·  ")
            if (episodeLine.isNotBlank()) OneLine(episodeLine, Modifier.padding(top = 4.dp), MaterialTheme.typography.titleMedium, Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA))
            if (videoInfo.isNotEmpty()) Text(videoInfo, style = MaterialTheme.typography.labelLarge, color = Edge.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            if (timeBar) TimeBar(position, duration, vm.player.exo.bufferedPosition, use24h = use24h)
            // Teach the intro once: with the bar open during the intro, mark where it ends and
            // every later episode of this series offers "Skip intro".
            if (timeBar) {
                val seriesKey = np.key.substringBefore(":E:", "")
                val unmarked = seriesKey.isNotEmpty() && seriesKey != np.key &&
                    com.edgestream.tv.data.IntroSkip.forSeries(seriesKey) == null
                if (unmarked && position in 10_000..(4 * 60_000L)) {
                    com.edgestream.tv.ui.components.EdgeButton(
                        "Set intro end",
                        {
                            com.edgestream.tv.data.IntroSkip.save(seriesKey, position)
                            vm.showNotice("Intro saved for this series")
                        },
                        Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        null -> Unit
    }
}

/** The live score strip under the channel info, with real team logos. */
@Composable
private fun ScoreBug(game: Sports.Game) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${game.league.name.uppercase()} \u00b7 ${game.detail.ifBlank { Sports.statusText(game) }}",
            style = MaterialTheme.typography.labelSmall, color = Edge.Accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        ScoreTeam(game.away)
        Text("\u2013", style = MaterialTheme.typography.labelLarge, color = Edge.Dim, modifier = Modifier.padding(horizontal = 8.dp))
        ScoreTeam(game.home)
    }
}

@Composable
private fun ScoreTeam(team: Sports.Team) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(team.logo, team.name, size = 22.dp)
        Spacer(Modifier.size(6.dp))
        Text(
            team.abbr.ifBlank { team.name }, style = MaterialTheme.typography.labelLarge,
            color = Edge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        team.score?.let {
            Text(
                it, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold, color = teamColorHex(team) ?: Edge.Text,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * The full-screen Gamecast: the same analyst body as the Sports sheet, over the live picture.
 * Same width, padding and header as the sheet so the two surfaces are indistinguishable;
 * it refreshes itself while the game is live, scrolls with Up/Down while focused, and closes
 * with the player's idle timer.
 */
@Composable
private fun GamecastPanel(game: Sports.Game, channel: ChannelEntity, hideScores: Boolean) {
    val (insight, loaded) = rememberGamecastInsight(game)

    // Takes focus while open, so Up/Down scroll the analysis instead of being eaten by the root.
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val panelFocus = remember { FocusRequester() }
    LaunchedEffect(game.id) { delay(60); runCatching { panelFocus.requestFocus() } }

    Column(
        Modifier.width(520.dp).fillMaxHeight()
            .background(Edge.Solid)
            .focusRequester(panelFocus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = with(density) { 140.dp.toPx() }
                when (e.key) {
                    Key.DirectionDown -> if (scroll.canScrollForward) { scope.launch { scroll.scrollBy(step) }; true } else false
                    Key.DirectionUp -> if (scroll.canScrollBackward) { scope.launch { scroll.scrollBy(-step) }; true } else false
                    else -> false
                }
            }
            .verticalScroll(scroll)
            .padding(horizontal = 28.dp, vertical = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (game.league.logo != null) {
                AsyncImage(game.league.logo, game.league.name, Modifier.size(24.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.size(10.dp))
            }
            Text(
                "${game.league.name.uppercase()} \u00b7 ${Sports.statusText(game)}",
                style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (channel.logo != null) {
                AsyncImage(channel.logo, channel.name, Modifier.size(width = 66.dp, height = 40.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreTeam(game.away)
            Text("at", style = MaterialTheme.typography.labelMedium, color = Edge.Dim, modifier = Modifier.padding(horizontal = 12.dp))
            ScoreTeam(game.home)
        }
        Spacer(Modifier.size(20.dp))
        SportsGamecast(game, insight, loaded, maxPlays = 16, hideScores = hideScores)
    }
}

/**
 * The timeline, streaming-app style: a slim track with what's buffered and what's played, a
 * knob while it has focus, elapsed time left, time remaining right and when it ends between.
 */
@Composable
private fun TimeBar(position: Long, duration: Long, buffered: Long = 0, focused: Boolean = false, use24h: Boolean = false) {
    val known = duration > 0
    val played = if (known) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val loaded = if (known) (buffered.toFloat() / duration).coerceIn(played, 1f) else played
    val track = if (focused) 8.dp else 5.dp
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(50)).background(Edge.Text.copy(alpha = 0.22f)))
            Box(Modifier.fillMaxWidth(loaded).height(track).clip(RoundedCornerShape(50)).background(Edge.Text.copy(alpha = 0.38f)))
            Box(Modifier.fillMaxWidth(played).height(track).clip(RoundedCornerShape(50)).background(Edge.Accent))
            // The knob rides the played edge while the timeline has focus.
            if (focused) Box(Modifier.padding(start = (maxWidth - 18.dp) * played).size(18.dp).clip(CircleShape).background(Edge.Text))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Format.clock(position), style = MaterialTheme.typography.labelLarge, color = Edge.Text)
            Spacer(Modifier.weight(1f))
            if (known) Text(
                "Ends at ${Format.time(System.currentTimeMillis() + (duration - position).coerceAtLeast(0), use24h)}",
                style = MaterialTheme.typography.labelMedium, color = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA),
            )
            Spacer(Modifier.weight(1f))
            Text(if (known) "−" + Format.clock((duration - position).coerceAtLeast(0)) else "--:--", style = MaterialTheme.typography.labelLarge, color = Edge.Text)
        }
    }
}

@Composable
private fun ControlBar(
    vm: AppViewModel, isLiveTv: Boolean, live: NowPlaying.Live?, favs: Set<String>, playing: Boolean,
    tick: Long, sportsGame: Sports.Game?, use24h: Boolean,
    onLayer: (Layer) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(40); runCatching { first.requestFocus() } }
    val exo = vm.player.exo
    val position = if (tick >= 0) exo.currentPosition else 0L
    val duration = exo.duration.coerceAtLeast(0)
    val np = vm.nowPlaying.collectAsStateWithLifecycle().value

    if (!isLiveTv) SeekBar(position, duration, exo.bufferedPosition, use24h, onSeek = { exo.seekTo((exo.currentPosition + it).coerceIn(0, duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) })

    // Transport on the left, picture and sound on the right: one row of icons; the focused
    // button names itself underneath.
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.Top) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            if (isLiveTv) {
                CtrlButton(Icons.AutoMirrored.Rounded.List, "Channels", Modifier.focusRequester(first)) { onLayer(Layer.CHANNELS) }
                CtrlButton(Icons.Rounded.CalendarViewWeek, "Guide") { onLayer(Layer.GUIDE) }
                if (sportsGame != null) CtrlButton(Icons.Rounded.QueryStats, "Game Stats") { onLayer(Layer.GAMECAST) }
                if (live != null) {
                    val fav = live.channel.key in favs
                    CtrlButton(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (fav) "Favourite" else "Add to Favourites") { vm.toggleFavorite(live.channel.key) }
                }
            } else {
                CtrlButton(Icons.Rounded.Replay10, "Back 10s") { exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)) }
                CtrlButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.focusRequester(first), primary = true) { if (exo.isPlaying) exo.pause() else exo.play() }
                CtrlButton(Icons.Rounded.Forward30, "Forward 30s") { exo.seekTo(exo.currentPosition + 30_000) }
                val episode = (np as? NowPlaying.Vod)?.let { it.key.contains(":E:") || it.season != null } == true
                if (episode) CtrlButton(Icons.Rounded.SkipNext, "Next Episode") { vm.playNextEpisode() }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            CtrlButton(Icons.Rounded.ClosedCaption, "Subtitles") { onLayer(Layer.SUBTITLES) }
            CtrlButton(Icons.Rounded.Audiotrack, "Audio") { onLayer(Layer.AUDIO) }
            CtrlButton(Icons.Rounded.AspectRatio, "Picture") { onLayer(Layer.PICTURE) }
            CtrlButton(Icons.Rounded.Refresh, "Reload") { vm.player.retry() }
        }
    }
}

/** A focusable timeline: ◀ ▶ step back/forward, holding accelerates. */
@Composable
private fun SeekBar(position: Long, duration: Long, buffered: Long, use24h: Boolean, onSeek: (Long) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var streak by remember { mutableStateOf(0) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) { if (e.type == KeyEventType.KeyUp) streak = 0; return@onKeyEvent false }
                val step = when { streak > 12 -> 120_000L; streak > 5 -> 30_000L; else -> 10_000L }
                when (e.key) {
                    Key.DirectionLeft -> { streak++; onSeek(-step); true }
                    Key.DirectionRight -> { streak++; onSeek(step); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) { TimeBar(position, duration, buffered, focused, use24h) }
}

/**
 * A round icon button whose name shows under it only while focused, so the row stays quiet. The
 * label slot is always reserved: moving focus along the row never shifts the layout.
 */
@Composable
private fun CtrlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier,
    primary: Boolean = false, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (primary) 72.dp else 56.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        FocusSurface(
            onClick = onClick, modifier = modifier.size(size).onFocusChanged { focused = it.isFocused },
            shape = CircleShape,
            // The transport's centre: accent-filled like every commercial player's play button.
            color = if (primary) Edge.Accent else Edge.Text.copy(alpha = 0.12f),
            focusedColor = if (primary) Edge.Accent else Edge.Text, focusScale = 1.08f, glow = false,
        ) { f ->
            androidx.compose.material3.Icon(
                icon, label, Modifier.align(Alignment.Center).size(if (primary) 36.dp else 26.dp),
                tint = if (primary) Edge.OnAccent else if (f) Edge.Bg else Edge.Text,
            )
        }
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = Edge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp).graphicsLayer { alpha = if (focused) 1f else 0f },
        )
    }
}

/** Opaque right-hand list for audio, subtitles or picture; the current choice is ticked. */
@Composable
private fun ChoiceSheet(title: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(title) { delay(40); runCatching { first.requestFocus() } }
    val selectedIndex = options.indexOfFirst { it.second }.coerceAtLeast(0)
    // Floats inset from the edge as a smoked-glass sheet, so the picture stays visible around it.
    Column(
        Modifier.padding(16.dp).width(400.dp).fillMaxHeight()
            .shadow(36.dp, EdgeShapes.Panel, clip = false)
            .glass(EdgeShapes.Panel, Edge.Smoke.copy(alpha = 0.88f))
            .padding(start = 24.dp, top = 28.dp, end = 24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Edge.Text)
        Text("BACK to return", style = MaterialTheme.typography.labelMedium, color = Edge.Faint, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            itemsIndexed(options) { i, (label, selected) ->
                FocusSurface(
                    onClick = { onPick(i) },
                    modifier = Modifier.fillMaxWidth().height(54.dp).then(if (i == selectedIndex) Modifier.focusRequester(first) else Modifier),
                    color = Edge.Text.copy(alpha = 0.08f), focusedColor = Edge.Text, focusScale = 1.02f, glow = false,
                ) { f ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OneLine(label, Modifier.weight(1f), MaterialTheme.typography.titleMedium, if (f) Edge.Bg else Edge.Text)
                        if (selected) androidx.compose.material3.Icon(Icons.Rounded.Check, "Selected", Modifier.size(22.dp), tint = if (f) Edge.Bg else Edge.Accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelPanel(vm: AppViewModel, live: NowPlaying.Live, clock: Long, favs: Set<String>, close: () -> Unit) {
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val list = live.list
    val idx = list.indexOfFirst { it.key == live.channel.key }.coerceAtLeast(0)
    val state = rememberLazyListState((idx - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(40); runCatching { focus.requestFocus() } }
    Column(
        Modifier.padding(16.dp).width(470.dp).fillMaxHeight()
            .shadow(36.dp, EdgeShapes.Panel, clip = false)
            .glass(EdgeShapes.Panel, Edge.Smoke.copy(alpha = 0.88f))
            .padding(start = 20.dp, top = 24.dp, end = 12.dp),
    ) {
        // Name the list the channel was opened from, not just the playing channel's own group.
        val title = remember(list) { list.asSequence().map { it.groupName }.distinct().take(2).toList().singleOrNull()?.let { com.edgestream.tv.data.NameCleaner.tidyGroup(it) } ?: "All channels" }
        Text(title, style = MaterialTheme.typography.titleLarge, color = Edge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${list.size} channels · hold OK to favourite", style = MaterialTheme.typography.labelMedium, color = Edge.Faint, maxLines = 1, modifier = Modifier.padding(bottom = 12.dp))
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 28.dp, end = 8.dp)) {
            itemsIndexed(list, key = { _, c -> c.key }) { i, ch ->
                val airing = nowNext[ch.epgId]?.first
                LaunchedEffect(ch.key, airing == null) { if (airing == null) { delay(400); vm.ensureLiveEpg(ch) } }
                ChannelRow(
                    ch, nowNext[ch.epgId], clock, isFav = ch.key in favs, playing = ch.key == live.channel.key,
                    badge = vm.badge(ch.key),
                    modifier = if (i == idx) Modifier.focusRequester(focus) else Modifier,
                    onClick = { vm.playChannel(ch, list); close() },
                    onLongClick = { vm.toggleFavorite(ch.key) },
                )
            }
        }
    }
}
