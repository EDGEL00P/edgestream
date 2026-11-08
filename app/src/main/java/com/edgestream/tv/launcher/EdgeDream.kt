package com.edgestream.tv.launcher

import android.service.dreams.DreamService
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
 * EdgeStream as the TV's screen saver: a quiet, near-black ambient screen — the mark drifting
 * slowly, the time large and dim, the weather underneath. Any key or the Home button wakes it;
 * nothing animates fast enough to worry an OLED.
 */
class EdgeDream : DreamService(), LifecycleOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setInteractive(false)
        isFullscreen = true
        isScreenBright = false
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@EdgeDream)
            setViewTreeViewModelStoreOwner(this@EdgeDream)
            setContent { EdgeTheme { DreamScreen() } }
        }
        setContentView(view)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onDreamingStopped() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDetachedFromWindow()
    }
}

/** The screensaver itself: no focusables, minimal motion, real data only. */
@Composable
private fun DreamScreen() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(30_000) }
    }

    var weather by remember { androidx.compose.runtime.mutableStateOf<PublicApis.Weather?>(null) }
    LaunchedEffect(Unit) {
        weather = runCatching { PublicApis.location() }
            .getOrNull()?.let { loc -> runCatching { PublicApis.weather(loc) }.getOrNull() }
    }

    val drift by androidx.compose.animation.core.rememberInfiniteTransition(label = "dream")
        .animateFloat(1f, 1.045f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(22_000),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ), label = "drift")

    AmbientBackground(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.graphicsLayer { scaleX = drift; scaleY = drift }
                    .size(64.dp),
            ) {
                com.edgestream.tv.ui.components.EdgeMark(Modifier.fillMaxSize())
            }
            Text(
                Format.time(now, use24h = true),
                style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
                color = Edge.Text.copy(alpha = 0.92f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 28.dp),
            )
            Text(
                Format.day(now),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp),
                color = Edge.Dim,
                modifier = Modifier.padding(top = 4.dp),
            )
            weather?.let { w ->
                Row(
                    Modifier.padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    WeatherGlyph(w.code, glyphSize = 20.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "${w.tempC.toInt()}°C  ·  ${w.description}  ·  ${w.city}",
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        color = Edge.Dim,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A real icon for the WMO weather code. */
