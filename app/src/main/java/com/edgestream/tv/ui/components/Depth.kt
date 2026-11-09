package com.edgestream.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.edgestream.tv.ui.DeviceTier

/**
 * The depth every widget shares: on focus it lifts toward the viewer, tilts back in perspective
 * and casts a shadow, and a sheen of light sweeps once across its glass.
 */
@Composable
fun rememberLift(focused: Boolean): State<Float> =
    animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 420f), label = "lift")

/** Perspective tilt, lift and shadow for [lift] (0 resting, 1 focused); [scale] grows with it. */
fun Modifier.depth(lift: () -> Float, shape: Shape, scale: Float = 1.06f, tilt: Float = 7f): Modifier = graphicsLayer {
    val l = lift()
    val s = 1f + (scale - 1f) * l
    scaleX = s; scaleY = s
    rotationX = tilt * l
    cameraDistance = 14f * density
    translationY = -6.dp.toPx() * l
    shadowElevation = 22.dp.toPx() * l
    this.shape = shape
    clip = true
}

/** One diagonal sweep of light across the surface each time it gains focus. */
@Composable
fun BoxScope.Sheen(focused: Boolean) {
    if (DeviceTier.tier == DeviceTier.Tier.LOW) return
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused) { sweep.snapTo(0f); sweep.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) } else sweep.snapTo(0f)
    }
    Canvas(Modifier.matchParentSize()) {
        val p = sweep.value
        if (p <= 0f || p >= 1f) return@Canvas
        val band = size.width * 0.35f
        val x = -band + (size.width + band * 2) * p
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.22f), 1f to Color.Transparent,
                start = Offset(x - band, 0f), end = Offset(x, size.height),
            ),
        )
    }
}
