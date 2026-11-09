package com.edgestream.tv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Shared motion token — a physical spring for D-pad focus on FULL, a snappy tween elsewhere.
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

}
