package com.edgestream.tv.ui

/**
 * The outermost padding the UI already has (scaled dp): the header bar and Home's column start
 * 16 dp in from the sides and the bar sits 8 dp down. The safe area adds only the difference.
 */
const val PAGE_PAD_X_DP = 16f
const val PAGE_PAD_Y_DP = 8f

/**
 * How the TV canvas is drawn: [scale] enlarges dp on big panels (1080p baseline = 1.0, capped at
 * 1.25 on 4K), and [overXDp]/[overYDp] are the extra safe-area margins beyond the page padding,
 * expressed in the *scaled* units they are applied in, so the configured percentage is what
 * actually lands on screen.
 */
data class TvFrame(val scale: Float, val overXDp: Float, val overYDp: Float)

fun tvFrame(widthDp: Float, heightDp: Float, overscanPercent: Int): TvFrame {
    val scale = (widthDp / 1440f).coerceIn(1f, 1.25f)
    val fraction = overscanPercent / 100f
    return TvFrame(
        scale = scale,
        overXDp = (widthDp * fraction / scale - PAGE_PAD_X_DP).coerceAtLeast(0f),
        overYDp = (heightDp * fraction / scale - PAGE_PAD_Y_DP).coerceAtLeast(0f),
    )
}
