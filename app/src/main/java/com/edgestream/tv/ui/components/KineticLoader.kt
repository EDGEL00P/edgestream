package com.edgestream.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.LocalSafeArea
import kotlinx.coroutines.delay

/**
 * "Starting" as a channel ident over the screen you came from: a soft shade from the left, an
 * accent NOW tag, the title's words slamming in and stacking in heavy type, an accent bar
 * sweeping under them, and the poster sliding in on the right. Theme colours only.
 */
@Composable
fun KineticLoader(title: String, step: String, poster: String?, modifier: Modifier = Modifier) {
    val lines = remember(title) { kineticLines(title) }
    val safe = LocalSafeArea.current
    Box(
        modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.55f to Color.Black.copy(alpha = 0.72f), 1f to Color.Black.copy(alpha = 0.35f)),
        ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 72.dp + safe.horizontal, end = 72.dp + safe.horizontal, top = 48.dp + safe.vertical, bottom = 48.dp + safe.vertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SlamIn(0) {
                    Text(
                        "NOW", color = Edge.OnAccent, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Edge.Accent).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    // Heavy uppercase runs ~0.62 em per letter: fit the longest line to the column.
                    val longest = lines.maxOf { it.length }.coerceAtLeast(4)
                    val size = (maxWidth.value / (longest * 0.62f)).coerceIn(44f, 112f).sp
                    Column {
                        lines.forEachIndexed { i, line ->
                            SlamIn(1 + i) {
                                Text(
                                    line, color = Color.White, fontSize = size, lineHeight = size * 0.95f,
                                    fontWeight = FontWeight.Black, letterSpacing = (-1).sp, maxLines = 1, overflow = TextOverflow.Clip,
                                )
                            }
                        }
                    }
                }
                SweepBar(Modifier.padding(top = 18.dp).width(420.dp).height(8.dp))
                Text(
                    step, color = Edge.Text.copy(alpha = 0.85f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp),
                )
                RunningLine(Modifier.padding(top = 10.dp).width(420.dp).height(3.dp))
            }
            if (!poster.isNullOrBlank()) SlideIn {
                coil3.compose.AsyncImage(
                    poster, title, Modifier.padding(start = 48.dp).fillMaxHeight(0.78f).width(360.dp).clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

/** Title words stacked into at most three lines of up to ~14 letters, uppercase. */
internal fun kineticLines(title: String): List<String> {
    val words = title.uppercase().split(' ').filter { it.isNotBlank() }.ifEmpty { return listOf("") }
    val lines = mutableListOf<String>()
    for (w in words) {
        val last = lines.lastOrNull()
        if (last != null && last.length + 1 + w.length <= 14) lines[lines.lastIndex] = "$last $w" else lines += w
    }
    if (lines.size <= 3) return lines
    return lines.take(2) + lines.drop(2).joinToString(" ")
}

/** Each part lands in turn: drops in large and settles to size with a short overshoot. */
@Composable
private fun SlamIn(order: Int, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(order * 110L)
        a.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f))
    }
    Box(
        Modifier.graphicsLayer {
            val p = a.value
            alpha = p.coerceIn(0f, 1f)
            scaleX = 1.35f - 0.35f * p; scaleY = scaleX
            translationX = (1f - p) * -60f
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
        },
    ) { content() }
}

@Composable
private fun SlideIn(content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(220); a.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = a.value; translationX = (1f - a.value) * 140f }) { content() }
}

/** An accent bar that sweeps out, holds, and sweeps away, over and over. */
@Composable
private fun SweepBar(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "sweep")
    val p by t.animateFloat(0f, 2f, infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "p")
    val accent = Edge.Accent
    androidx.compose.foundation.Canvas(modifier) {
        val start = (p - 1f).coerceIn(0f, 1f) * size.width
        val end = p.coerceIn(0f, 1f) * size.width
        if (end > start) drawRect(accent, androidx.compose.ui.geometry.Offset(start, 0f), androidx.compose.ui.geometry.Size(end - start, size.height))
    }
}

/** A thin indeterminate line under the step, so it's obvious work is happening. */
@Composable
private fun RunningLine(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "run")
    val x by t.animateFloat(-0.3f, 1f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "x")
    BoxWithConstraints(modifier.background(Edge.Text.copy(alpha = 0.14f))) {
        Box(
            Modifier.fillMaxHeight().width(maxWidth * 0.3f)
                .graphicsLayer { translationX = x * constraints.maxWidth }.background(Edge.Text),
        )
    }
}
