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
        val glitch = rememberGlitch()
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            GlitchWordmark(glitch, progress)
            title?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color.White, fontSize = if (poster.isNullOrBlank()) 26.sp else 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (poster.isNullOrBlank()) 18.dp else 12.dp).widthIn(max = 760.dp))
            }
            LoadStatus(step, progress, Modifier.padding(top = 18.dp).width(620.dp))
        }
    }
}

/** The loader's one glitch clock: bursts that get more violent the longer the wait. */
private class Glitch {
    var tick by mutableLongStateOf(0L)
    var jitter by mutableFloatStateOf(0f)
    var start = 0L
}

@Composable
private fun rememberGlitch(): Glitch {
    val g = remember { Glitch() }
    LaunchedEffect(Unit) {
        var next = 0L
        while (true) withFrameMillis { t ->
            if (g.start == 0L) g.start = t
            g.tick = t
            // A signal struggling to lock: up to twice as hard after eight seconds of waiting,
            // and the bursts come closer together.
            val strain = 1f + ((t - g.start) / 8_000f).coerceIn(0f, 1f)
            g.jitter = if (t < next) (0.45f + Random.nextFloat() * 0.55f) * strain else 0f
            if (t > next + 280) next = t + (1_100 / strain).toLong() + Random.nextLong((1_200 / strain).toLong())
        }
    }
    return g
}

/**
 * EDGESTREAM is the progress bar, lit like a strobe: one letter per tenth of the load. Lit
 * letters burn white, the next one strobes before it locks on, the rest wait dim. When the step
 * can't be measured the strobe chases through the word. Glitch bursts tear the whole word.
 */
@Composable
private fun GlitchWordmark(g: Glitch, progress: Float?) {
    val tick = g.tick
    val jitter = g.jitter
    val word = "EDGESTREAM"
    val lit = strobeLit(progress, tick - g.start, word.length)
    val strobeOn = (tick / STROBE_MS) % 2 == 0L
    val split = 22f * jitter
    Box(contentAlignment = Alignment.Center) {
        // Colour channels slipping apart behind the letters during a burst.
        if (jitter > 0f) {
            Text(word, color = Edge.Accent.copy(alpha = (0.85f * jitter).coerceAtMost(0.85f)), fontFamily = Wordmark, fontSize = 120.sp, letterSpacing = 14.sp,
                modifier = Modifier.drawWithContent { translate(left = -split, top = split * 0.25f) { this@drawWithContent.drawContent() } })
            Text(word, color = Edge.Accent2.copy(alpha = (0.7f * jitter).coerceAtMost(0.7f)), fontFamily = Wordmark, fontSize = 120.sp, letterSpacing = 14.sp,
                modifier = Modifier.drawWithContent { translate(left = split, top = -split * 0.2f) { this@drawWithContent.drawContent() } })
        }
        Row(
            Modifier.drawWithContent {
                if (jitter == 0f) { drawContent(); return@drawWithContent }
                val bands = 11
                val h = size.height / bands
                val seed = Random(tick / 50)
                repeat(bands) { b ->
                    val dx = if (seed.nextFloat() < 0.5f) (seed.nextFloat() - 0.5f) * 140f * jitter else 0f
                    clipRect(top = b * h, bottom = (b + 1) * h) { translate(left = dx) { this@drawWithContent.drawContent() } }
                }
            },
        ) {
            word.forEachIndexed { i, ch ->
                val (alpha, color) = when {
                    i < lit.full -> 1f to Color.White
                    i == lit.full && lit.striking -> (if (strobeOn) 1f else 0.2f) to (if (strobeOn) Color.White else Edge.Accent)
                    else -> 0.14f to Color.White
                }
                Text(
                    ch.toString(), color = color.copy(alpha = alpha), fontFamily = Wordmark, fontSize = 120.sp,
                    modifier = Modifier.padding(horizontal = 7.dp),
                )
            }
        }
    }
}

/** How many letters burn, and whether the next is mid-strobe. */
internal data class StrobeLit(val full: Int, val striking: Boolean)

/**
 * Letters lit for [progress] (0..1) across [letters]; with no progress, a chase that sweeps the
 * word every [STROBE_CHASE_MS] of [elapsedMs].
 */
internal fun strobeLit(progress: Float?, elapsedMs: Long, letters: Int): StrobeLit {
    val f = (progress?.coerceIn(0f, 1f) ?: ((elapsedMs % STROBE_CHASE_MS) / STROBE_CHASE_MS.toFloat())) * letters
    val full = f.toInt().coerceAtMost(letters)
    return StrobeLit(full, full < letters && f - full > 0.05f)
}

private const val STROBE_MS = 55L
internal const val STROBE_CHASE_MS = 1_800L

/** The step and how far along it is, under the wordmark, with the measured connection speed. */
@Composable
private fun LoadStatus(step: String, progress: Float?, modifier: Modifier) {
    val context = LocalContext.current
    var mbps by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val meter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(context)
        while (true) { mbps = meter.bitrateEstimate / 1_000_000f; kotlinx.coroutines.delay(500) }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(step.uppercase(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (mbps > 0f) Text("%.1f Mbps".format(mbps), color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(end = 16.dp))
        if (progress != null) Text("${(progress * 100).toInt()}%", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
