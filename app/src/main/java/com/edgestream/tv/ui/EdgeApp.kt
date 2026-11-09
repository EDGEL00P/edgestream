package com.edgestream.tv.ui

import androidx.compose.material.icons.rounded.Alarm
import com.edgestream.tv.ui.components.requestFocusWithRetry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import com.edgestream.tv.data.ChannelNumbers
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import com.edgestream.tv.player.PlayerEngine
import com.edgestream.tv.ui.screens.AppsScreen

import com.edgestream.tv.ui.screens.PlayerScreen
import com.edgestream.tv.ui.screens.SearchScreen
import com.edgestream.tv.ui.screens.SettingsScreen
import com.edgestream.tv.ui.screens.VodScreen
import com.edgestream.tv.ui.screens.VodDetails
import com.edgestream.tv.data.db.VodKind
import kotlinx.coroutines.flow.drop

@Composable
fun EdgeApp(vm: AppViewModel = viewModel()) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val fullscreen by vm.fullscreen.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val vodDetail by vm.vodDetail.collectAsStateWithLifecycle()
    val versionSheet by vm.versionSheet.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val clock by vm.clock.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    var phoneRemote by remember { mutableStateOf(false) }
    // No provider yet: offer phone setup first. Skipping lasts until the app process restarts.
    var setupSkipped by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val onboarding = sources?.isEmpty() == true && !setupSkipped && !fullscreen
    val backdrop by vm.backdrop.collectAsStateWithLifecycle()
    val reels by vm.reels.collectAsStateWithLifecycle()
    // The slow move across each photo (off on low-tier boxes), shared with every frosted surface.
    val motionOn = DeviceTier.tier != DeviceTier.Tier.LOW
    // Stepped 10× a second, not every frame: across 90 s each step is a tenth of a pixel (invisible),
    // and the photo + glass redraw 6× less often than a per-frame animation would make them.
    var kb by remember(backdrop) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(backdrop, motionOn) {
        if (!motionOn) return@LaunchedEffect
        val start = android.os.SystemClock.uptimeMillis()
        while (kb < 1f) {
            delay(100)
            kb = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / vm.BACKDROP_SHOT_MS).coerceAtMost(1f)
        }
    }
    val backdropMotion: () -> com.edgestream.tv.ui.glass.KenBurns =
        remember(backdrop, motionOn) { { com.edgestream.tv.ui.glass.kenBurns(kb, backdrop?.index ?: 0, motionOn) } }
    // The glass shell: one surface; up-a-level Back, never history (ui/shell/Shell.kt).
    var shell by remember {
        mutableStateOf<com.edgestream.tv.ui.shell.ShellState>(
            com.edgestream.tv.ui.shell.tileFor(vm.screen.value)?.let { com.edgestream.tv.ui.shell.ShellState.Section(it) }
                ?: com.edgestream.tv.ui.shell.ShellState.Rest(com.edgestream.tv.ui.shell.DEFAULT_TILE),
        )
    }
    val dockFocus = remember { com.edgestream.tv.ui.shell.Tile.entries.associateWith { androidx.compose.ui.focus.FocusRequester() } }
    val sectionFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val scrollToTop = remember { ScrollToTopRegistry() }
    val rootFocus = remember { RootFocus() }
    val dueReminder by vm.dueReminder.collectAsStateWithLifecycle()
    val ringingAlarm by vm.ringingAlarm.collectAsStateWithLifecycle()
    // "Who's watching?" once per launch, only when the household has more than one profile.
    val profiles by com.edgestream.tv.data.Profiles.all.collectAsStateWithLifecycle()
    var profileChosen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val choosingProfile = profiles.size > 1 && !profileChosen
    val multiview by vm.multiview.collectAsStateWithLifecycle()
    // The TV may sleep or start its screensaver everywhere except while full-screen video plays
    // (EdgeStream is the home screen, so a permanent keep-awake would stop the TV ever sleeping).
    val playback by vm.player.state.collectAsStateWithLifecycle()
    val hostView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect { hostView.keepScreenOn = fullscreen && playback.isPlaying }
    androidx.compose.runtime.SideEffect { com.edgestream.tv.ui.components.FocusBeam.hostRoot = hostView.rootView }
    val weatherAlert by vm.weatherAlert.collectAsStateWithLifecycle()
    val backScope = androidx.compose.runtime.rememberCoroutineScope()
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    suspend fun rescueFocus() {
        val overlay = onboarding || phoneRemote || dueReminder != null || weatherAlert != null || ringingAlarm != null || choosingProfile
        if (!shouldRescueFocus(rootFocus.hasFocus, fullscreen, overlay)) return
        when (val s = shell) {
            is com.edgestream.tv.ui.shell.ShellState.Rest -> dockFocus.getValue(s.tile).requestFocusWithRetry()
            is com.edgestream.tv.ui.shell.ShellState.Section ->
                if (!sectionFocus.requestFocusWithRetry()) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
        }
    }
    fun dispatch(e: com.edgestream.tv.ui.shell.ShellEvent) {
        val before = shell
        val step = com.edgestream.tv.ui.shell.reduce(before, e)
        val after = step.state
        shell = after
        // vm.screen stays the source of truth for what is hosted: HOME at rest.
        if (after is com.edgestream.tv.ui.shell.ShellState.Section && e is com.edgestream.tv.ui.shell.ShellEvent.Open) {
            com.edgestream.tv.ui.shell.screenFor(after.tile)?.let { vm.screen.value = it }
        }
        if (after is com.edgestream.tv.ui.shell.ShellState.Rest && vm.screen.value != Screen.HOME) vm.screen.value = Screen.HOME
        // Landing back on the dock (from a section, or Back out of the band): focus that tile.
        val leftBand = (before as? com.edgestream.tv.ui.shell.ShellState.Rest)?.inBand == true
        if (after is com.edgestream.tv.ui.shell.ShellState.Rest && !after.inBand &&
            (before is com.edgestream.tv.ui.shell.ShellState.Section || (leftBand && e is com.edgestream.tv.ui.shell.ShellEvent.Back))
        ) backScope.launch { dockFocus.getValue(after.tile).requestFocusWithRetry() }
        when (step.effect) {
            com.edgestream.tv.ui.shell.ShellEffect.SCROLL_TO_TOP -> backScope.launch { scrollToTop.run() }
            com.edgestream.tv.ui.shell.ShellEffect.SHOW_EXIT_PROMPT -> vm.showNotice("Press Back again to exit")
            com.edgestream.tv.ui.shell.ShellEffect.EXIT -> activity?.finish()
            com.edgestream.tv.ui.shell.ShellEffect.OPEN_PHONE -> phoneRemote = true
            null -> Unit
        }
    }
    var booted by remember { mutableStateOf(false) }
    // Empty sources (no IPTV provider yet) is a valid state: the app is the launcher and the
    // provider is configured later in Settings or from a paired phone. Only "still loading" (null)
    // blocks readiness.
    val shellReady = booted && sources != null && !fullscreen && !choosingProfile && !onboarding
    LaunchedEffect(shellReady) {
        // Cold boot (or back from the player/onboarding): nothing else owns focus yet, so the
        // current dock tile takes it — the remote is never dead on arrival.
        val s = shell
        if (shellReady && s is com.edgestream.tv.ui.shell.ShellState.Rest) dockFocus.getValue(s.tile).requestFocusWithRetry()
    }
    // One handler for the shell's lifetime, so key presses don't hand the whole shell new params.
    val shellEvents: (com.edgestream.tv.ui.shell.ShellEvent) -> Unit = remember { { e -> dispatch(e) } }
    // Created once: this is a static CompositionLocal, and a new lambda every recomposition
    // would force the whole screen tree to recompose on every key press.
    val focusRescue: () -> Unit = remember { { backScope.launch { delay(120); rescueFocus() } } }
    LaunchedEffect(Unit) {
        // A details/versions overlay opened from the band leaves vm.screen at HOME, so the
        // screen-change rescue never fires; its focused node vanishing on close would strand focus.
        androidx.compose.runtime.snapshotFlow { vodDetail != null || versionSheet != null }
            .drop(1)
            .collect { open -> if (!open) { delay(120); rescueFocus() } }
    }
    LaunchedEffect(screen) {
        // Something outside the shell changed the screen (phone remote, deep link, a screen's link).
        val s = shell
        val inStep = (screen == Screen.HOME && s is com.edgestream.tv.ui.shell.ShellState.Rest) ||
            (s is com.edgestream.tv.ui.shell.ShellState.Section && com.edgestream.tv.ui.shell.tileFor(screen) == s.tile)
        if (!inStep) dispatch(com.edgestream.tv.ui.shell.ShellEvent.External(screen))
        // A section that never claimed focus (still loading) must not leave the remote dead.
        delay(1_500)
        rescueFocus()
    }
    // TV safe area: scale with the actual window instead of assuming one fixed margin. The
    // outermost 16 dp side / 8 dp top padding already covers part of it (see TvFrame).
    val window = LocalWindowInfo.current.containerSize
    val widthDp = with(LocalDensity.current) { window.width.toDp() }
    val heightDp = with(LocalDensity.current) { window.height.toDp() }
    val frame = remember(widthDp, heightDp, settings.overscanPercent) {
        tvFrame(widthDp.value, heightDp.value, settings.overscanPercent)
    }
    val overX = frame.overXDp.dp
    val overY = frame.overYDp.dp
    val screenStates = rememberSaveableStateHolder()
    val textEntry = remember { TextEntryState() }
    // A phone filling the focused text box replaces its value outright: no key-by-key typing,
    // so symbols in passwords and URLs arrive exactly.
    LaunchedEffect(textEntry) {
        com.edgestream.tv.remote.PhoneLink.commands.collect { c ->
            if (c is com.edgestream.tv.remote.PhoneLink.Command.SetField) textEntry.fill?.invoke(c.text)
        }
    }

    // Universal-remote channel entry: type a number on any browse screen and the OSD tunes it.
    var lastDirectionAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        // Paint the brand frame first, then let the full Home compose behind it.
        androidx.compose.runtime.withFrameNanos { }
        booted = true
    }

    // The interface voice follows its setting.
    LaunchedEffect(settings.uiSounds) { UiSounds.enabled = settings.uiSounds }
    LaunchedEffect(settings.effects) { DeviceTier.setOverride(settings.effects) }

    BackHandler(enabled = !fullscreen && sources != null) {
        dispatch(com.edgestream.tv.ui.shell.ShellEvent.Back(scrollToTop.atTop, android.os.SystemClock.uptimeMillis()))
    }

    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    val minimalScroll = remember(margin) { com.edgestream.tv.ui.components.MinimalScroll(margin) }
    // Adaptive density: fixed dp cards (232dp hero, 144dp posters) look "squashed together"
    // on 4K where the raw dp width is ~1920dp. Scale density so the same dp fills the
    // screen proportionally — 1080p baseline = 1.0, capped at 1.25 on 4K, never shrink small TVs.
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val adaptiveScale = frame.scale
    val scaledDensity = remember(baseDensity, adaptiveScale) {
        androidx.compose.ui.unit.Density(baseDensity.density * adaptiveScale, baseDensity.fontScale)
    }
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides scaledDensity,
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides minimalScroll,
        LocalSafeArea provides SafeArea(overX, overY),
        LocalTextEntry provides textEntry,
        LocalScrollToTop provides scrollToTop,
        LocalRootFocus provides rootFocus,
        // A beat first, so the focus change that caused the request has been applied.
        LocalFocusRescue provides focusRescue,
    ) {
    // Full-screen video sits on black; everything else lives in the softly lit glass room.
    AmbientBackground(Modifier.fillMaxSize().then(if (fullscreen) Modifier.background(Color.Black) else Modifier), plain = (backdrop != null || reels.isNotEmpty()) && !fullscreen) {
        // Real game highlights on Home (photos until then). An open section gets the still photo:
        // broadcast clips carry burned-in scoreboards that read through any shade over lists.
        val preview by vm.preview.collectAsStateWithLifecycle()
        val multiviewOn by vm.multiview.collectAsStateWithLifecycle()
        val backdropSound by vm.settings.collectAsStateWithLifecycle() // read .backdropSound at use site via stable local below
        val backdropSoundOn = backdropSound.backdropSound
        // A live preview replaces the reel (one decoder either way) and only while nothing else plays.
        val previewing = preview?.takeIf { !fullscreen && screen == Screen.HOME && !playback.isPlaying && multiviewOn == null }
        if (previewing != null) {
            androidx.compose.runtime.key(previewing.key) {
                com.edgestream.tv.ui.glass.LivePreview(previewing.streamUrl, onFailed = { vm.preview.value = null }, modifier = Modifier.fillMaxSize())
            }
        } else if (!fullscreen && motionOn && reels.isNotEmpty() && screen == Screen.HOME && !com.edgestream.tv.PressMode.on) {
            com.edgestream.tv.ui.glass.VideoBackdrop(reels, sound = backdropSoundOn, modifier = Modifier.fillMaxSize())
        } else if (!fullscreen && backdrop != null && !com.edgestream.tv.PressMode.on) {
            // Real photos, each with a slow documentary move, cross-fading to the next. The move is
            // read only in the draw phase (graphicsLayer), so nothing recomposes per frame.
            LaunchedEffect(backdrop) { backdrop?.let { if (com.edgestream.tv.ui.glass.LiveBlur.enabled) com.edgestream.tv.ui.glass.LiveBlur.publishStill(it.sharp) } }
            androidx.compose.animation.Crossfade(backdrop, animationSpec = androidx.compose.animation.core.tween(2_000), label = "backdrop") { b ->
                if (b != null) androidx.compose.foundation.Image(
                    b.sharp, null,
                    // Cached as one texture: the 10 Hz move only re-composites it, never re-renders the photo.
                    Modifier.fillMaxSize().graphicsLayer {
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        val k = if (b === backdrop) backdropMotion() else com.edgestream.tv.ui.glass.kenBurns(1f, b.index, motionOn)
                        scaleX = k.scale; scaleY = k.scale
                        translationX = k.dx * size.width; translationY = k.dy * size.height
                    },
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
        // Setup text sits straight on the picture; the shade runs edge to edge like the picture does.
        if (onboarding) Box(Modifier.fillMaxSize().background(com.edgestream.tv.ui.screens.OnboardingShade))
        // Non-video UI respects the safe area; the picture itself stays edge-to-edge.
        Box(
            Modifier.fillMaxSize().then(
                if (fullscreen) Modifier else Modifier.padding(horizontal = overX, vertical = overY),
            ).onFocusChanged { rootFocus.hasFocus = it.hasFocus }
            .onPreviewKeyEvent { e ->
                // Escape and gamepad B are Back everywhere (overlays and the player included).
                val code = e.nativeKeyEvent.keyCode
                if (code != android.view.KeyEvent.KEYCODE_BACK &&
                    com.edgestream.tv.ui.input.RemoteKeys.actionFor(code) == com.edgestream.tv.ui.input.RemoteAction.BACK
                ) {
                    if (e.type == KeyEventType.KeyUp) (activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
                    return@onPreviewKeyEvent true
                }
                if (fullscreen || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Text entry owns the number keys: Settings, Search, the first-run screen and any focused field.
                // null sources = still loading; don't block channel entry until we know there are none.
                val src = sources
                val hasSources = src == null || src.isNotEmpty()
                if (!digitsGoToPad(fullscreen, screen, hasSources, textEntry.focused)) {
                    if (digitOf(e.key) != null && com.edgestream.tv.data.Sports.traceOn) {
                        android.util.Log.d("EdgePad", "digit blocked: fullscreen=$fullscreen screen=$screen hasSources=$hasSources sources=${src?.size} text=${textEntry.focused}")
                    }
                    return@onPreviewKeyEvent false
                }
                // Controlled key repeat: deliberate single steps, then a capped cadence. The
                // focused highlight never outruns the logical focus. Tiered for weak boxes.
                val direction = e.key == Key.DirectionLeft || e.key == Key.DirectionRight ||
                    e.key == Key.DirectionUp || e.key == Key.DirectionDown
                if (direction) {
                    val now = android.os.SystemClock.uptimeMillis()
                    val gap = when (DeviceTier.tier) {
                        DeviceTier.Tier.FULL -> 45L
                        DeviceTier.Tier.BALANCED -> 60L
                        DeviceTier.Tier.LOW -> 75L
                    }
                    if (e.nativeKeyEvent.repeatCount > 0 && now - lastDirectionAt < gap) return@onPreviewKeyEvent true
                    lastDirectionAt = now
                }
                val digit = digitOf(e.key)
                when {
                    digit != null -> {
                        vm.channelPad.append('0' + digit)
                        if (com.edgestream.tv.data.Sports.traceOn) {
                            android.util.Log.d("EdgePad", "digit=$digit pad=${vm.channelPad.state.value.digits} match=${vm.channelPad.state.value.match?.name} index=${vm.lcnIndex.value.size} channels=${vm.channels.value.size}")
                        }
                        true
                    }
                    vm.channelPad.active && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) -> {
                        vm.channelPad.commit()
                        true
                    }
                    vm.channelPad.active && (e.key == Key.Backspace || e.key == Key.Delete) -> {
                        vm.channelPad.backspace()
                        true
                    }
                    else -> false
                }
            },
        ) {
        if (!booted) {
            // EdgeStream is the home launcher: no splash. One empty frame, then Home.
        } else {
        when {
            fullscreen -> multiview?.let { com.edgestream.tv.ui.screens.MultiviewScreen(vm, it) } ?: PlayerScreen(vm)
            // Setup is offered, never forced: skip it and EdgeStream is still the home launcher.
            onboarding -> com.edgestream.tv.ui.screens.OnboardingScreen(vm) { setupSkipped = true }
            // One surface: photo, ambient top, glass band + dock; tiles open into sections.
            else -> com.edgestream.tv.ui.shell.ShellScreen(vm, shell, dockFocus, sectionFocus, onEvent = shellEvents) {
                // Preserve each section's rememberSaveable state (scroll, fields, lazy lists).
                screenStates.SaveableStateProvider(screen.name) {
                    // Plain switch, no transition: an animated crossfade holds the entering
                    // screen's nodes in a state that refuses D-pad focus on this platform.
                    when (screen) {
                        Screen.HOME -> Unit
                        Screen.LIVE, Screen.GUIDE -> com.edgestream.tv.ui.live.LiveTvPage(vm)
                        Screen.MOVIES -> VodScreen(vm, VodKind.MOVIE)
                        Screen.SERIES -> VodScreen(vm, VodKind.SERIES)
                        Screen.CALENDAR -> com.edgestream.tv.ui.screens.CalendarScreen(vm)
                        Screen.TIMELINE, Screen.SPORTS -> com.edgestream.tv.ui.sports.SportsHubPage(vm)
                        Screen.BETTING -> com.edgestream.tv.ui.screens.BettingScreen(vm)
                        Screen.RADAR -> com.edgestream.tv.ui.screens.RadarScreen(vm)
                        Screen.DEBRID -> com.edgestream.tv.ui.screens.DebridScreen(vm)
                        Screen.APPS -> AppsScreen(vm)
                        Screen.SYSTEM -> com.edgestream.tv.ui.screens.SystemScreen(vm)
                        Screen.SEARCH -> SearchScreen(vm)
                        Screen.SETTINGS -> SettingsScreen(vm)
                    }
                }
            }
        }
        // Over the content so section shades can't hide it; screen-blended, so text stays white.
        if (!fullscreen) com.edgestream.tv.ui.components.RoomLightOverlay(Modifier.fillMaxSize())
        if (!fullscreen) com.edgestream.tv.ui.components.FocusBeamOverlay(Modifier.fillMaxSize())
        ChannelPadOsd(vm, Modifier.align(Alignment.TopEnd))
        if (!fullscreen) vodDetail?.let { VodDetails(vm, it) }
        if (phoneRemote) PhoneRemoteDialog { phoneRemote = false }
        if (textEntry.focused && !fullscreen && !onboarding) PhoneTypeHint(Modifier.align(Alignment.BottomEnd))
        if (!fullscreen) versionSheet?.let { com.edgestream.tv.ui.screens.VersionsSheet(vm, it) }
        // Never over full-screen video: sync progress waits until you leave the picture.
        StartingScreen(vm)
        // Banners stack at the top right: a notice first, sync progress under it.
        Column(Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NoticeToast(vm, Modifier)
            // Never over full-screen video: sync progress waits until you leave the picture.
            if (!fullscreen) SyncToast(vm, Modifier)
            // Over full-screen video a warning is a banner, not a popup that takes the remote.
            if (fullscreen) WeatherAlertBanner(vm)
        }
        ReminderPopup(vm)
        AlarmPopup(vm)
        if (choosingProfile && !fullscreen) com.edgestream.tv.ui.screens.WhoIsWatching { profileChosen = true }
        if (!fullscreen) WeatherAlertPopup(vm)
        }
        }
    }
    }
}

/** Channel-number OSD. The only place that collects the pad and the channel index. */
@Composable
private fun ChannelPadOsd(vm: AppViewModel, modifier: Modifier) {
    val pad by vm.channelPad.state.collectAsStateWithLifecycle()
    if (pad.digits.isEmpty()) return
    val index by vm.lcnIndex.collectAsStateWithLifecycle()
    val match = pad.match
    Column(
        modifier.padding(32.dp)
            .shadow(28.dp, RoundedCornerShape(20.dp), clip = false)
            .glass(RoundedCornerShape(20.dp), Edge.Smoke).padding(horizontal = 26.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(pad.digits, fontSize = 52.sp, fontWeight = FontWeight.Black, color = Edge.Text)
        Text(
            when {
                match != null -> "CH ${ChannelNumbers.lcn(match)}  ·  ${match.name}"
                index.isEmpty() -> "Loading channels"
                else -> "No match for ${pad.digits}"
            },
            style = MaterialTheme.typography.labelMedium, color = if (match != null) Edge.Accent else Edge.Faint,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (match != null) Text("Press OK to tune", style = MaterialTheme.typography.labelSmall, color = Edge.Faint)
    }
}

@Composable
private fun WeatherAlertBanner(vm: AppViewModel) {
    val alert by vm.weatherAlert.collectAsStateWithLifecycle()
    var last by remember { mutableStateOf<com.edgestream.tv.data.Trackers.Warning?>(null) }
    alert?.let { last = it }
    androidx.compose.runtime.LaunchedEffect(alert) {
        if (alert != null) { kotlinx.coroutines.delay(8_000); vm.dismissWeatherAlert() }
    }
    Banner(alert != null, last?.title.orEmpty(), last?.detail.orEmpty())
}

@Composable
private fun SyncToast(vm: AppViewModel, modifier: Modifier) {
    val status by vm.status.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    // Routine background refreshes stay silent; only the first load and failures are worth a banner.
    val worthShowing = status.message.isNotEmpty() && (status.failed || (status.running && channels.isEmpty()))
    var dismissed by remember(status) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(status) {
        if (!status.running && status.message.isNotEmpty()) { kotlinx.coroutines.delay(6_000); dismissed = true }
    }
    var last by remember { mutableStateOf("") }
    if (status.message.isNotEmpty()) last = status.message
    Banner(worthShowing && !dismissed, if (status.running) "Updating" else "Update failed", last, working = status.running, modifier = modifier)
}

/**
 * The one notification style (tvOS banner): slides in at the top right with the EdgeStream mark,
 * a short title and one line of detail; work in progress gets a thin running line underneath.
 */
@Composable
private fun Banner(visible: Boolean, title: String, detail: String, working: Boolean = false, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible, modifier,
        enter = androidx.compose.animation.slideInHorizontally { it / 2 } + fadeIn(),
        exit = androidx.compose.animation.slideOutHorizontally { it / 2 } + fadeOut(),
    ) {
        Column(
            Modifier.width(420.dp).shadow(24.dp, RoundedCornerShape(18.dp), clip = false)
                .glass(RoundedCornerShape(18.dp), Edge.Solid.copy(alpha = 0.92f)),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Edge.Accent), contentAlignment = Alignment.Center) {
                    com.edgestream.tv.ui.components.EdgeMark(Modifier.size(24.dp))
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = Edge.Text, maxLines = 1)
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = Edge.Text.copy(alpha = 0.72f),
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (working) androidx.compose.material3.LinearProgressIndicator(
                Modifier.fillMaxWidth().height(3.dp), color = Edge.Accent, trackColor = Color.Transparent,
            )
        }
    }
}

/** Hosts the shared player's video. Only one of these should be on screen at a time. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoSurface(engine: PlayerEngine, modifier: Modifier = Modifier, frostVideo: Boolean = false) {
    val aspect by engine.aspect.collectAsStateWithLifecycle()
    val videoAspect by engine.videoAspect.collectAsStateWithLifecycle()
    val state by engine.state.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf<PlayerView?>(null) }

    // AndroidView.update is not guaranteed to be the recomposition edge that carries the new
    // aspect value; this explicit effect makes every Picture choice apply to both the full-screen
    // player and the Home mini player.
    androidx.compose.runtime.LaunchedEffect(view, aspect, videoAspect, state.isLive) {
        view?.resizeMode = engine.resizeModeFor(aspect, videoAspect, state.isLive)
    }

    // Fit is explicit: AUTO is a hard fit and no snapshot-driven zoom is applied. Re-measuring
    // burned-in bars every couple of seconds could oscillate, and non-integer scaling left
    // artifacts on the right and bottom edges. Use the Picture menu's Stretch/Zoom modes.
    androidx.compose.runtime.LaunchedEffect(view) {
        val pv = view ?: return@LaunchedEffect
        val frame = pv.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame) ?: return@LaunchedEffect
        frame.animate().cancel()
        frame.scaleX = 1f
        frame.scaleY = 1f
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setBackgroundColor(android.graphics.Color.BLACK)
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                player = engine.exo
                // Stream subtitles, streaming style: white type with a drop shadow, no black
                // box, sitting clear of the screen edge like every commercial app.
                findViewById<androidx.media3.ui.SubtitleView>(androidx.media3.ui.R.id.exo_subtitles)?.apply {
                    setApplyEmbeddedStyles(false)
                    setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                            android.graphics.Color.BLACK,
                            null,
                        ),
                    )
                    setBottomPaddingFraction(0.08f)
                }
            }.also { view = it }
        },
        update = {
            it.resizeMode = engine.resizeModeFor(aspect, videoAspect, state.isLive)
            if (it.player !== engine.exo) it.player = engine.exo
            // Best-effort frost under the player's panels: a real blur where the device renders
            // through a texture layer; harmless where the surface compositor ignores it.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                it.setRenderEffect(
                    if (frostVideo) android.graphics.RenderEffect.createBlurEffect(26f, 26f, android.graphics.Shader.TileMode.CLAMP) else null,
                )
            }
        },
        onRelease = { it.player = null; view = null },
        modifier = modifier,
    )
}

/** A title starting while its copy is found: its artwork edge to edge, the loading bar over it. */
@Composable
private fun StartingScreen(vm: AppViewModel) {
    val title = vm.starting.collectAsStateWithLifecycle().value ?: return
    BackHandler { vm.cancelStarting() }
    val step by vm.startStep.collectAsStateWithLifecycle()
    val poster = vm.startingPoster.collectAsStateWithLifecycle().value
    // An overlay: the screen you picked the title on stays visible under the shade.
    com.edgestream.tv.ui.components.KineticLoader(
        com.edgestream.tv.data.NameCleaner.tidyTitle(title), step.ifBlank { "Starting" }, poster,
    )
}

/** Brief confirmations ("Reminder set…"), shown over everything including full-screen video. */
@Composable
private fun NoticeToast(vm: AppViewModel, modifier: Modifier) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    var last by remember { mutableStateOf("") }
    notice?.let { last = it }
    Banner(notice != null, "EdgeStream", last, modifier = modifier)
}

/**
 * "Starting now" for a reminded programme. A dialog window, so when it closes focus goes back to
 * whatever had it (the guide cell, the video) instead of being lost.
 */
@Composable
private fun ReminderPopup(vm: AppViewModel) {
    val r by vm.dueReminder.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val reminder = r ?: return
    val watch = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = { vm.dismissReminder() }) {
        androidx.compose.runtime.LaunchedEffect(reminder.id) { kotlinx.coroutines.delay(80); runCatching { watch.requestFocus() } }
        Column(
            Modifier.width(560.dp).shadow(40.dp, EdgeShapes.Panel, clip = false)
                .glass(EdgeShapes.Panel, Edge.Solid.copy(alpha = 0.96f)).padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.NotificationsActive, null, Modifier.size(20.dp), tint = Edge.Gold)
                Text("  STARTING NOW", style = MaterialTheme.typography.labelSmall, color = Edge.Gold)
            }
            Text(reminder.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Text(
                "${reminder.channelName}  ·  ${Format.range(reminder.start, reminder.stop, settings.use24h)}",
                style = MaterialTheme.typography.bodyLarge, color = Edge.Dim, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.edgestream.tv.ui.components.EdgeButton(
                    "Watch now", { vm.watchReminder() }, Modifier.focusRequester(watch), primary = true,
                    icon = androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                )
                com.edgestream.tv.ui.components.EdgeButton("Dismiss", { vm.dismissReminder() })
            }
        }
    }
}

/** A calendar alarm ringing: the entry, its time, Snooze 10 min or Stop. Rings until answered. */
@Composable
private fun AlarmPopup(vm: AppViewModel) {
    val ringing by vm.ringingAlarm.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val a = ringing ?: return
    val stop = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = { vm.dismissAlarm() }) {
        androidx.compose.runtime.LaunchedEffect(a.alarmAt) { kotlinx.coroutines.delay(80); runCatching { stop.requestFocus() } }
        Column(Modifier.width(560.dp).glass(EdgeShapes.Panel, Edge.Solid.copy(alpha = 0.96f)).padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Alarm, null, Modifier.size(22.dp), tint = Edge.Accent)
                Text(
                    "  " + (a.event.time?.let { com.edgestream.tv.ui.screens.formatTime(it, settings.use24h) } ?: "All day"),
                    style = MaterialTheme.typography.titleMedium, color = Edge.Accent,
                )
            }
            Text(a.event.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.edgestream.tv.ui.components.EdgeButton("Stop", { vm.dismissAlarm() }, Modifier.focusRequester(stop), primary = true)
                com.edgestream.tv.ui.components.EdgeButton("Snooze 10 Min", { vm.snoozeAlarm(10) })
            }
        }
    }
}

@Composable
private fun WeatherAlertPopup(vm: AppViewModel) {
    val alert by vm.weatherAlert.collectAsStateWithLifecycle()
    val w = alert ?: return
    val dismiss = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = { vm.dismissWeatherAlert() }) {
        androidx.compose.runtime.LaunchedEffect(w.title) { kotlinx.coroutines.delay(80); runCatching { dismiss.requestFocus() } }
        Column(
            Modifier.width(620.dp).shadow(40.dp, EdgeShapes.Panel, clip = false)
                .glass(EdgeShapes.Panel, Edge.Solid.copy(alpha = 0.97f)).padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Rounded.Warning, null,
                    Modifier.size(22.dp), tint = Edge.Alert,
                )
                Text("  ${w.severity.uppercase()} WEATHER ALERT", style = MaterialTheme.typography.labelSmall, color = Edge.Alert)
            }
            Text(
                w.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (w.detail.isNotBlank()) {
                Text(
                    w.detail,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Edge.Dim,
                    maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                "Source: ${w.source} · real-time public warning feed",
                style = MaterialTheme.typography.labelMedium,
                color = Edge.Faint,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.edgestream.tv.ui.components.EdgeButton(
                    "Dismiss", { vm.dismissWeatherAlert() }, Modifier.focusRequester(dismiss), primary = true,
                )
                com.edgestream.tv.ui.components.EdgeButton(
                    "View radar", { vm.dismissWeatherAlert(); vm.screen.value = Screen.RADAR },
                )
            }
        }
    }
}

