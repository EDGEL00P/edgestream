package com.edgestream.tv.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.edgestream.tv.R
import com.edgestream.tv.ui.DeviceTier

/**
 * A dock icon that moves: useAnimations' outline icons (MIT; licence in
 * assets/licenses/useanimations.txt), tinted to the theme. It plays once each time it gains
 * focus and rests on its first frame; low-tier devices keep it still.
 */
@Composable
fun DockIcon(name: String, tint: Color, focused: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(DOCK_RAW.getValue(name)))
    val progress by animateLottieCompositionAsState(
        composition, isPlaying = focused && DeviceTier.tier != DeviceTier.Tier.LOW,
        restartOnPlay = true, iterations = 1,
    )
    val colour = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(LottieProperty.COLOR_FILTER, SimpleColorFilter(tint.toArgb()), "**"),
    )
    LottieAnimation(composition, { if (focused) progress else 0f }, modifier.size(size), dynamicProperties = colour)
}

private val DOCK_RAW = mapOf(
    "search" to R.raw.dock_search, "settings" to R.raw.dock_settings, "remote" to R.raw.dock_remote,
    "apps" to R.raw.dock_apps, "live" to R.raw.dock_live, "sports" to R.raw.dock_sports, "odds" to R.raw.dock_odds,
    "ondemand" to R.raw.dock_ondemand, "weather" to R.raw.dock_weather, "calendar" to R.raw.dock_calendar,
    "voice" to R.raw.dock_voice,
)
