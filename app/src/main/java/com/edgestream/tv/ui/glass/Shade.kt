package com.edgestream.tv.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.edgestream.tv.R

/**
 * No panels: text sits straight on the photo over the theme's darkest colour as a gradient shade.
 * Wherever text sits the shade is at least this strong, which keeps primary text ≥ 7:1 and
 * secondary ≥ 4.5:1 on every palette even over a pure-white photo (ShadeTest).
 */
const val SHADE_TEXT = 0.72f

/** Under the dock and ticker at the very bottom: the one near-solid strip. */
const val SHADE_STRIP = 0.9f

/**
 * A section open full screen (guide, lists, cards, settings): the near-solid strip long lists
 * sit on. The highlight video behind stays a hint of light, never motion that competes with rows.
 */
const val SHADE_SECTION = 0.95f

/** Secondary text never goes fainter than this (readable from the sofa). */
const val SECONDARY_TEXT_ALPHA = 0.78f

/** The tall condensed face for the big clock; everything else stays Inter. */
val ClockFont = FontFamily(Font(R.font.bebasneue_regular))

/** [over] at [alpha] composited onto opaque [under] (sRGB source-over, as the GPU draws it). */
fun composite(under: Color, over: Color, alpha: Float): Color = Color(
    red = over.red * alpha + under.red * (1 - alpha),
    green = over.green * alpha + under.green * (1 - alpha),
    blue = over.blue * alpha + under.blue * (1 - alpha),
)

/** WCAG contrast ratio between two opaque colours. */
fun contrastRatio(fg: Color, bg: Color): Double {
    val a = fg.luminance() + 0.05
    val b = bg.luminance() + 0.05
    return (maxOf(a, b) / minOf(a, b)).toDouble()
}
