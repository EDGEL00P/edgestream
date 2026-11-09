package com.edgestream.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgestream.tv.R
import com.edgestream.tv.ui.Edge
import kotlin.random.Random

private val Wordmark = FontFamily(Font(R.font.bebasneue_regular))

/**
 * The loading screen: the EdgeStream wordmark with a broadcast-glitch flicker (colour channels
 * slipping apart, the odd band of the picture torn sideways, scanlines), what's happening in
 * words, a progress bar that shows real progress when there is one, and the connection's
 * measured speed. Shown over [background] (the highlight reel, or the frozen picture).
 *
 * @param progress 0..1 when the step is measurable (a live cushion filling), null otherwise.
 */
@Composable
fun EdgeLoader(
    title: String?,
    step: String,
    progress: Float?,
    poster: String? = null,
    modifier: Modifier = Modifier,
    /** How dark the screen goes behind it: solid black, or a dim over the frozen picture. */
    dim: Float = 1f,
    background: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = dim))) {
        background()
        // Full edge-to-edge artwork: the poster's backdrop for films, the channel logo for live TV.
        if (!poster.isNullOrBlank()) {
            coil3.compose.AsyncImage(poster, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }
        // Scanlines and a vignette so text reads on any footage.
        Canvas(Modifier.fillMaxSize()) {
            var y = 0f
            while (y < size.height) { drawRect(Color.Black.copy(alpha = 0.18f), Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, 2f)); y += 5f }
            drawRect(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.85f)), center = center, radius = size.maxDimension * 0.6f))
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            GlitchWordmark()
            title?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color.White, fontSize = if (poster.isNullOrBlank()) 26.sp else 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (poster.isNullOrBlank()) 18.dp else 12.dp).widthIn(max = 760.dp))
            }
            LoadBar(step, progress, Modifier.padding(top = 22.dp).width(520.dp))
        }
    }
}

@Composable
private fun GlitchWordmark() {
    // A burst of glitch every second or two, steady in between, like a signal locking on.
    var tick by remember { mutableLongStateOf(0L) }
    var jitter by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var next = 0L
        while (true) withFrameMillis { t ->
            tick = t
            jitter = if (t < next) Random.nextFloat() else 0f
            if (t > next + 220) next = t + 900 + Random.nextLong(1_400)
        }
    }
    val accent = Edge.Accent
    Box(contentAlignment = Alignment.Center) {
        val split = 10f * jitter
        // Colour channels slipping apart: theme accent one way, white the other.
        Text("EDGESTREAM", color = accent.copy(alpha = 0.75f), fontFamily = Wordmark, fontSize = 120.sp, letterSpacing = 14.sp,
            modifier = Modifier.drawWithContent { translate(left = -split, top = split * 0.3f) { this@drawWithContent.drawContent() } })
        Text("EDGESTREAM", color = Edge.Accent2.copy(alpha = 0.6f * jitter), fontFamily = Wordmark, fontSize = 120.sp, letterSpacing = 14.sp,
            modifier = Modifier.drawWithContent { translate(left = split) { this@drawWithContent.drawContent() } })
        Text("EDGESTREAM", color = Color.White, fontFamily = Wordmark, fontSize = 120.sp, letterSpacing = 14.sp,
            modifier = Modifier.drawWithContent {
                if (jitter == 0f) { this@drawWithContent.drawContent(); return@drawWithContent }
                // Torn bands: the wordmark in slices, a few pushed sideways.
                val bands = 7
                val h = size.height / bands
                val seed = Random(tick / 60)
                repeat(bands) { i ->
                    val dx = if (seed.nextFloat() < 0.35f) (seed.nextFloat() - 0.5f) * 60f * jitter else 0f
                    clipRect(top = i * h, bottom = (i + 1) * h) { translate(left = dx) { this@drawWithContent.drawContent() } }
                }
            })
    }
}

@Composable
private fun LoadBar(step: String, progress: Float?, modifier: Modifier) {
    val context = LocalContext.current
    // The connection's measured speed (Media3's bandwidth meter, shared by every stream).
    var mbps by remember { mutableFloatStateOf(0f) }
    var sweep by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val meter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(context)
        var start = 0L
        while (true) withFrameMillis { t ->
            if (start == 0L) start = t
            sweep = ((t - start) % 1_400L) / 1_400f
            mbps = meter.bitrateEstimate / 1_000_000f
        }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(step.uppercase(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (progress != null) Text("${(progress * 100).toInt()}%", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))) {
            if (progress != null) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Edge.Accent))
            } else {
                // Unmeasurable step: a light running along the track.
                Box(Modifier.fillMaxSize().drawWithContent {
                    val w = size.width * 0.3f
                    val x = -w + (size.width + w) * sweep
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Edge.Accent, Color.Transparent), startX = x, endX = x + w))
                })
            }
        }
        if (mbps > 0f) Text("Connection %.1f Mbps".format(mbps), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp))
    }
}
