package com.edgestream.tv.ui.components

import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.edgestream.tv.data.Sports
import com.edgestream.tv.ui.DeviceTier
import com.edgestream.tv.ui.Edge

/**
 * One focus highlight for the whole app. The focused [FocusSurface] reports where it is; the
 * overlay at the root glides there, so the eye follows focus instead of hunting for it.
 * Surfaces in another window (dialogs) can't be reached by the overlay and draw their own edge.
 */
object FocusBeam {
    /** The main window's root view; only surfaces in that window are beamed. */
    var hostRoot: View? = null
    var target by mutableStateOf<Rect?>(null)
        private set
    private var owner: Any? = null

    fun claim(who: Any, bounds: Rect) {
        owner = who
        val t = target
        // Ignore sub-pixel/layout jitter (focus-scale animation, scroll settle) to avoid recomposing
        // the overlay on every frame.
        if (t == null || kotlin.math.abs(t.left - bounds.left) > 1.5f || kotlin.math.abs(t.top - bounds.top) > 1.5f ||
            kotlin.math.abs(t.width - bounds.width) > 1.5f || kotlin.math.abs(t.height - bounds.height) > 1.5f
        ) target = bounds
    }
    fun release(who: Any) { if (owner === who) { owner = null; target = null } }
}

@Composable
fun FocusBeamOverlay(modifier: Modifier = Modifier) {
    val target = FocusBeam.target
    val shown by animateFloatAsState(if (target != null) 1f else 0f, tween(160), label = "beamAlpha")
    val rect = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        // Appearing from nothing: start in place rather than flying in from a stale spot.
        if (rect.value == Rect.Zero || shown < 0.05f) rect.snapTo(t)
        else {
            val v = rect.value
            if (kotlin.math.abs(v.left - t.left) > 0.5f || kotlin.math.abs(v.top - t.top) > 0.5f ||
                kotlin.math.abs(v.width - t.width) > 0.5f || kotlin.math.abs(v.height - t.height) > 0.5f
            ) rect.animateTo(t, spring(dampingRatio = 0.86f, stiffness = 620f))
        }
    }
    val glow = DeviceTier.tier != DeviceTier.Tier.LOW
    Box(
        modifier.drawBehind {
            if (shown <= 0f) return@drawBehind
            val r = rect.value
            val pad = 3.dp.toPx()
            val box = Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
            val radius = CornerRadius(14.dp.toPx())
            val accent = Edge.Accent
            if (glow) {
                // A soft halo from a few widening, fading strokes: cheap, and no blur needed.
                for (i in 3 downTo 1) {
                    drawRoundRect(
                        accent.copy(alpha = 0.10f * shown / i), box.topLeft, box.size, radius,
                        style = Stroke(width = (i * 5).dp.toPx()),
                    )
                }
            }
            drawRoundRect(accent.copy(alpha = shown), box.topLeft, box.size, radius, style = Stroke(width = 2.dp.toPx()))
            // The lit leading edge.
            val bar = 4.dp.toPx()
            drawRoundRect(
                accent.copy(alpha = shown), Offset(box.left - bar / 2, box.top + box.height * 0.2f),
                Size(bar, box.height * 0.6f), CornerRadius(bar / 2),
            )
        },
    )
}

/**
 * The room takes on the focused game's colours: two soft pools of the teams' own colours wash
 * in from the corners and cross-fade as focus moves. Null colours fade the room back to neutral.
 */
object RoomLight {
    var colors by mutableStateOf<Pair<Color, Color>?>(null)
        private set

    fun of(game: Sports.Game?) {
        colors = game?.let { g -> lit(g.away)?.let { a -> a to (lit(g.home) ?: a) } }
    }

    /** A team's colour that actually shows as light: navy or black primaries fall back to the alternate. */
    private fun lit(t: Sports.Team): Color? {
        val main = hex(t.color)
        val alt = hex(t.altColor)
        return if (main != null && main.luminance() < 0.06f && alt != null && alt.luminance() > main.luminance()) alt else main ?: alt
    }

    fun clear() { colors = null }

    private fun hex(h: String?): Color? =
        h?.takeIf { it.length == 6 }?.let { runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull() }
}

@Composable
fun RoomLightOverlay(modifier: Modifier = Modifier) {
    val c = RoomLight.colors
    val a by animateColorAsState(c?.first?.copy(alpha = 0.24f) ?: Color.Transparent, tween(900), label = "roomA")
    val b by animateColorAsState(c?.second?.copy(alpha = 0.18f) ?: Color.Transparent, tween(900), label = "roomB")
    Box(
        modifier.graphicsLayer { blendMode = BlendMode.Screen }.drawBehind {
            if (a.alpha <= 0.01f && b.alpha <= 0.01f) return@drawBehind
            val w = size.width
            val h = size.height
            val pa = Offset(w * 0.94f, h * 0.06f)
            val pb = Offset(w * 0.72f, h * 1.02f)
            drawRect(Brush.radialGradient(listOf(a, Color.Transparent), pa, w * 0.58f))
            drawRect(Brush.radialGradient(listOf(b, Color.Transparent), pb, w * 0.52f))
        },
    )
}
