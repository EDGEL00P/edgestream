package com.edgestream.tv.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.edgestream.tv.R
import com.edgestream.tv.ui.DeviceTier

/**
 * One weather icon treatment everywhere: Meteocons' animated fill icons (Bas Milius, MIT;
 * licence in assets/licenses/meteocons.txt), looping. [night] picks the moon variants where they
 * exist; on low-tier devices the icon holds still instead of animating.
 */
@Composable
fun WeatherGlyph(code: Int, modifier: Modifier = Modifier, glyphSize: Dp = 18.dp, night: Boolean = false) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(RAW.getValue(weatherAnimation(code, night))))
    LottieAnimation(
        composition, modifier.size(glyphSize),
        iterations = LottieConstants.IterateForever,
        isPlaying = DeviceTier.tier != DeviceTier.Tier.LOW,
    )
}

/** Night for current conditions without an is_day flag: before 6:00 or from 19:00, local time. */
fun isNightNow(hour: Int = java.time.LocalTime.now().hour): Boolean = hour < 6 || hour >= 19

/** The Meteocons fill icon for an Open-Meteo (WMO) weather code. */
internal fun weatherAnimation(code: Int, night: Boolean): String = when (code) {
    0 -> if (night) "clear-night" else "clear-day"
    1, 2 -> if (night) "partly-cloudy-night" else "partly-cloudy-day"
    3 -> "overcast"
    45, 48 -> "fog"
    in 51..57 -> "drizzle"
    61, 63 -> "rain"
    65 -> "extreme-rain"
    66, 67 -> "sleet"
    in 71..77 -> "snow"
    80, 81 -> if (night) "rain" else "partly-cloudy-day-rain"
    82 -> "extreme-rain"
    85, 86 -> if (night) "snow" else "partly-cloudy-day-snow"
    95 -> "thunderstorms-rain"
    96, 99 -> "hail"
    else -> "overcast"
}

private val RAW = mapOf(
    "clear-day" to R.raw.meteo_clear_day, "clear-night" to R.raw.meteo_clear_night,
    "partly-cloudy-day" to R.raw.meteo_partly_cloudy_day, "partly-cloudy-night" to R.raw.meteo_partly_cloudy_night,
    "overcast" to R.raw.meteo_overcast, "fog" to R.raw.meteo_fog, "drizzle" to R.raw.meteo_drizzle,
    "rain" to R.raw.meteo_rain, "extreme-rain" to R.raw.meteo_extreme_rain, "sleet" to R.raw.meteo_sleet,
    "snow" to R.raw.meteo_snow, "partly-cloudy-day-rain" to R.raw.meteo_partly_cloudy_day_rain,
    "partly-cloudy-day-snow" to R.raw.meteo_partly_cloudy_day_snow,
    "thunderstorms-rain" to R.raw.meteo_thunderstorms_rain, "hail" to R.raw.meteo_hail,
)
