package com.edgestream.tv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Shared motion tokens — springs over tweens for gesture-coupled motion (D-pad focus,
 * hero crossfades, bar breathing). Tiered so weak boxes stay fast and flat.
 */
object Motion {
    /** Focus scale/alpha: physical spring on FULL, snappy tween elsewhere. */
    fun <T> focusSpring(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        when (DeviceTier.tier) {
            DeviceTier.Tier.FULL -> spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
            DeviceTier.Tier.BALANCED -> spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            )
            DeviceTier.Tier.LOW -> tween(
                durationMillis = 90,
                easing = FastOutSlowInEasing,
            )
        }

    /** Hero art crossfade — cinematic, not instant: FastOutSlowIn, spring-finished. */
    fun heroFade(): androidx.compose.animation.core.FiniteAnimationSpec<Float> =
        tween(durationMillis = 520, easing = FastOutSlowInEasing)

    /** Bar breathing / height — soft spring so it feels alive, not linear. */
    fun <T> barSpring(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)

    /** Shelf stagger entry delay per index (capped on LOW tier). */
    fun staggerDelayMs(index: Int): Int = when (DeviceTier.tier) {
        DeviceTier.Tier.LOW -> 0
        else -> (index * 90).coerceAtMost(420)
    }

    /** Progress fill — springy but settles fast so it never feels laggy. */
    fun <T> progressSpring(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
}
