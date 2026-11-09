package com.edgestream.tv.launcher

import android.service.dreams.DreamService
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import com.edgestream.tv.data.Sports
import com.edgestream.tv.data.SportsReels
import com.edgestream.tv.ui.glass.VideoBackdrop
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.edgestream.tv.data.PublicApis
import com.edgestream.tv.ui.AmbientBackground
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeTheme
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.WeatherGlyph
import kotlinx.coroutines.delay

/**
 * EdgeStream as the TV's screen saver: real game highlights with the time, weather and live
 * scores over them. Any key or the Home button wakes it.
 */
class EdgeDream : DreamService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /**
     * The system can call onDreamingStopped after onDetachedFromWindow. Once destroyed, the
     * lifecycle stays destroyed; a late event used to throw and take the whole app down.
     */
    private fun move(event: Lifecycle.Event) {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.handleLifecycleEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Compose needs a saved-state owner on the view tree; it must be restored before the
        // lifecycle leaves INITIALIZED.
        savedState.performRestore(null)
        setInteractive(false)
        isFullscreen = true
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@EdgeDream)
            setViewTreeViewModelStoreOwner(this@EdgeDream)
            setViewTreeSavedStateRegistryOwner(this@EdgeDream)
            setContent { EdgeTheme { DreamScreen() } }
        }
        setContentView(view)
        move(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        move(Lifecycle.Event.ON_START)
    }

    override fun onDreamingStopped() {
        move(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        move(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDetachedFromWindow()
    }
}

/**
 * The screensaver: real ESPN highlights full screen, muted, each one fading up out of black
 * with its headline as a lower third; a big clock, the weather and live scores over a soft
 * shade. The overlay drifts a little every minute so nothing sits still on an OLED. With no
 * highlights (offline), the ambient room shows instead.
 */
@Composable
private fun DreamScreen() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(15_000) }
    }

    var weather by remember { mutableStateOf<PublicApis.Weather?>(null) }
    LaunchedEffect(Unit) {
        // Same place as Home: the one picked in Settings, else the typed city, else the IP guess.
        // Network calls run off the main thread (they threw there and the line never showed).
        weather = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val s = com.edgestream.tv.EdgeStreamApp.instance.settings.value
            val loc = s.weatherPlace?.toLocation()
                ?: s.weatherCity.trim().takeIf { it.isNotEmpty() }?.let { runCatching { PublicApis.geocode(it) }.getOrNull() }
                ?: runCatching { PublicApis.location() }.getOrNull()
            loc?.let { runCatching { PublicApis.weather(it) }.getOrNull() }
        }
    }

    var reels by remember { mutableStateOf<List<SportsReels.Reel>>(emptyList()) }
    LaunchedEffect(Unit) { reels = SportsReels.load() }

    var games by remember { mutableStateOf(Sports.cachedGames().orEmpty()) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { Sports.games() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { games = it }
            delay(120_000)
        }
    }
    val live = games.filter { it.state == Sports.State.LIVE }.take(4)

    // Each new clip rises out of black, and its headline slides in a beat later.
    var clip by remember { mutableStateOf<SportsReels.Reel?>(null) }
    val veil = remember { Animatable(1f) }
    val caption = remember { Animatable(0f) }
    LaunchedEffect(clip) {
        if (clip == null) return@LaunchedEffect
        caption.snapTo(0f)
        veil.snapTo(1f)
        veil.animateTo(0f, tween(1400))
        caption.animateTo(1f, tween(900))
    }

    // Burn-in guard: the whole overlay walks a small square, one step a minute.
    val step = ((now / 60_000) % 4).toInt()
    val driftX = listOf(0, 14, 14, 0)[step].dp
    val driftY = listOf(0, 0, 10, 10)[step].dp

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (reels.isEmpty()) {
            AmbientBackground(Modifier.fillMaxSize())
        } else {
            VideoBackdrop(reels, sound = false, modifier = Modifier.fillMaxSize(), onClip = { clip = it })
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (reels.isEmpty()) 0f else veil.value)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f)),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.5f to Color.Transparent)))
        // Scores sit top right, often over bright ice or sky: shade from the top too.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.4f to Color.Transparent)))

        Box(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp).offset(driftX, driftY)) {
            // Live scores, top right.
            if (live.isNotEmpty()) {
                Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.edgestream.tv.ui.components.LiveBadge(compact = true)
                    live.forEach { g ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${g.away.abbr} ${g.away.score ?: "–"}  ${g.home.abbr} ${g.home.score ?: "–"}",
                                style = MaterialTheme.typography.headlineSmall, color = Color.White,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(g.detail, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                        }
                    }
                }
            }

            // Clock, date and weather, bottom left.
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(
                    Format.time(now, use24h = false),
                    style = MaterialTheme.typography.displayMedium.copy(fontSize = 120.sp, lineHeight = 112.sp), color = Color.White,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(now).uppercase(), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp), color = Color.White.copy(alpha = 0.85f))
                    weather?.let { w ->
                        Spacer(Modifier.width(18.dp))
                        WeatherGlyph(w.code, glyphSize = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("${w.tempC.toInt()}°  ${w.city}", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                    }
                }
            }

            // The clip's real headline, bottom right, sliding in.
            clip?.let { c ->
                Column(
                    Modifier.align(Alignment.BottomEnd).widthIn(max = 440.dp)
                        .graphicsLayer { alpha = caption.value; translationX = (1f - caption.value) * 40.dp.toPx() },
                    horizontalAlignment = Alignment.End,
                ) {
                    Text("HIGHLIGHT  ·  ESPN", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Edge.Accent)
                    Box(Modifier.padding(vertical = 6.dp).width(48.dp).height(3.dp).background(Edge.Accent))
                    Text(c.title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.End, maxLines = 3)
                }
            }
        }
    }
}

/** A real icon for the WMO weather code. */
