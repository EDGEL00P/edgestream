package com.edgestream.tv.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Finds black bars burned into the picture itself: a 4:3 show inside a 16:9 frame, a scope
 * film letterboxed for TV, or SD "windowboxed" with bars on every side. Container-level aspect
 * can't see these, so a small snapshot of the video surface is measured instead.
 */
object SmartFrame {
    /** Picture area inside the frame, as fractions of the frame (0..1). */
    data class Content(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
    }

    val FULL = Content(0f, 0f, 1f, 1f)

    private const val W = 160
    private const val H = 90
    private const val BLACK_LUMA = 26
    /** A row/column counts as bar when nearly all of it is black, so a small corner logo doesn't stop the scan. */
    private const val BAR_FRACTION = 0.94f
    /** Most crop accepted to get rid of bars entirely. */
    const val MAX_CROP = 0.10f

    /** One snapshot of [view]'s current frame, or null if the surface can't be read (DRM, tunnelling). */
    suspend fun snapshot(view: SurfaceView, reuse: Bitmap? = null): Bitmap? {
        if (!view.holder.surface.isValid || view.width == 0) return null
        val bmp = reuse?.takeIf { it.width == W && it.height == H } ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            runCatching {
                PixelCopy.request(view, bmp, { result ->
                    if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bmp else null)
                }, Handler(Looper.getMainLooper()))
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    /**
     * Measures the bars in [bmp]. Returns null when the frame can't be judged: a fade to black
     * or a mostly dark scene would otherwise look like huge bars.
     */
    fun measure(bmp: Bitmap): Content? {
        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        val dark = BooleanArray(px.size) { i ->
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000 < BLACK_LUMA
        }
        if (dark.count { it } > px.size * 0.75f) return null

        fun rowBar(y: Int) = (0 until W).count { dark[y * W + it] } >= W * BAR_FRACTION
        fun colBar(x: Int, y0: Int, y1: Int) = (y0 until y1).count { dark[it * W + x] } >= (y1 - y0) * BAR_FRACTION

        var top = 0; while (top < H / 2 && rowBar(top)) top++
        var bottom = H; while (bottom > H / 2 && rowBar(bottom - 1)) bottom--
        var left = 0; while (left < W / 2 && colBar(left, top, bottom)) left++
        var right = W; while (right > W / 2 && colBar(right - 1, top, bottom)) right--

        val c = Content(left / W.toFloat(), top / H.toFloat(), right / W.toFloat(), bottom / H.toFloat())
        // Under ~2 % per side is noise or overscan, not a bar.
        fun snap(v: Float, edge: Float) = if (kotlin.math.abs(v - edge) < 0.02f) edge else v
        return Content(snap(c.left, 0f), snap(c.top, 0f), snap(c.right, 1f), snap(c.bottom, 1f))
            .takeIf { it.width > 0.4f && it.height > 0.4f }
    }

    /**
     * Uniform zoom that removes the bars. Bars on every side (windowboxing) go completely; when
     * that would crop more than [MAX_CROP] (a 4:3 show inside 16:9) only the cheap side is
     * removed and the rest stays, so nothing is distorted or badly cut off.
     */
    fun zoomFor(c: Content): Float {
        if (c == FULL) return 1f
        val fitW = 1f / c.width
        val fitH = 1f / c.height
        val noCrop = minOf(fitW, fitH)
        val cover = maxOf(fitW, fitH)
        val cropAtCover = 1f - noCrop / cover
        return (if (cropAtCover <= MAX_CROP) cover else noCrop).coerceIn(1f, 2f)
    }
}
