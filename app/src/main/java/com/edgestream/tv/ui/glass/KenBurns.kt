package com.edgestream.tv.ui.glass

/** One frame of the slow documentary move: zoom about the centre, then a shift (fractions of the screen). */
data class KenBurns(val scale: Float, val dx: Float, val dy: Float) {
    companion object { val STILL = KenBurns(1f, 0f, 0f) }
}

/**
 * The move for [progress] (0→1 across one photo's time on screen) of photo [index]: zoom
 * 1.04 → 1.12 while panning; direction alternates photo to photo. The pan never exceeds the
 * zoom's spare margin, so the photo's edge can never show.
 */
fun kenBurns(progress: Float, index: Int, motion: Boolean = true): KenBurns {
    if (!motion) return KenBurns.STILL
    val p = progress.coerceIn(0f, 1f)
    val scale = 1.04f + 0.08f * p
    val margin = (scale - 1f) / 2f
    val sweep = 2f * p - 1f
    val h = if (index % 2 == 0) 1f else -1f
    val v = if ((index / 2) % 2 == 0) 1f else -1f
    return KenBurns(scale, h * margin * sweep * 0.9f, v * margin * sweep * 0.4f)
}

