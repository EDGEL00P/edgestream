package com.edgestream.tv.ui.glass

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.edgestream.tv.ui.DeviceTier

/**
 * Live frosted glass on Android 11, which has no backdrop blur. The full-screen background (the
 * highlight video, a live preview or a photo) is sampled small and often, blurred on a worker
 * thread, and every glass panel draws its own slice of that blurred frame behind itself.
 * The blur is so heavy that sampling ~12 times a second is indistinguishable from live.
 */
object LiveBlur {
    /** The blurred background, full-screen aspect; null means no glass (panels draw solid). */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    /** How much larger than the screen the background is drawn (the reel is scaled to crop bugs). */
    var sourceScale = 1f
        private set

    /** Off on the weakest devices, where even small readbacks cost frames. */
    val enabled: Boolean get() = DeviceTier.tier != DeviceTier.Tier.LOW

    const val SAMPLE_W = 160
    const val SAMPLE_H = 90
    private const val RADIUS = 5
    private const val PASSES = 3

    // Two output bitmaps, alternated, so the one being drawn is never the one being written.
    private val out = arrayOf(
        Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888),
    )
    private var next = 0
    private val a = IntArray(SAMPLE_W * SAMPLE_H)
    private val b = IntArray(SAMPLE_W * SAMPLE_H)

    fun clear() { frame = null }

    /** Blurs [sample] (exactly SAMPLE_W × SAMPLE_H) and publishes it. Call off the main thread. */
    @Synchronized
    fun publish(sample: Bitmap, scale: Float) {
        sample.getPixels(a, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
        blur(a, b, SAMPLE_W, SAMPLE_H, RADIUS, PASSES)
        val target = out[next]
        target.setPixels(a, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
        next = 1 - next
        sourceScale = scale
        frame = target.asImageBitmap()
    }

    /** A still image as the background: blurred once. */
    suspend fun publishStill(image: ImageBitmap) = withContext(Dispatchers.Default) {
        val small = Bitmap.createScaledBitmap(image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false), SAMPLE_W, SAMPLE_H, true)
        publish(small, 1f)
        small.recycle()
    }

    /**
     * Separable box blur, [passes] times (three box passes approximate a Gaussian), in place on
     * [px] using [tmp]. Edges clamp.
     */
    internal fun blur(px: IntArray, tmp: IntArray, w: Int, h: Int, r: Int, passes: Int) {
        repeat(passes) {
            boxH(px, tmp, w, h, r)
            boxV(tmp, px, w, h, r)
        }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sr += p shr 16 and 0xFF; sg += p shr 8 and 0xFF; sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val sub = src[row + (x - r).coerceAtLeast(0)]
                sr += (add shr 16 and 0xFF) - (sub shr 16 and 0xFF)
                sg += (add shr 8 and 0xFF) - (sub shr 8 and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sr += p shr 16 and 0xFF; sg += p shr 8 and 0xFF; sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val sub = src[(y - r).coerceAtLeast(0) * w + x]
                sr += (add shr 16 and 0xFF) - (sub shr 16 and 0xFF)
                sg += (add shr 8 and 0xFF) - (sub shr 8 and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}

/**
 * Samples [view] (the video background) ~12 times a second while composed. The readback is a
 * tiny 160×90 copy, taken on the main thread as TextureView requires; the blur runs off it.
 */
@Composable
fun SampleForGlass(view: TextureView?, scale: Float) {
    if (view == null || !LiveBlur.enabled) return
    LaunchedEffect(view) {
        val sample = Bitmap.createBitmap(LiveBlur.SAMPLE_W, LiveBlur.SAMPLE_H, Bitmap.Config.ARGB_8888)
        try {
            while (isActive) {
                if (view.isAvailable) {
                    view.getBitmap(sample)
                    withContext(Dispatchers.Default) { LiveBlur.publish(sample, scale) }
                }
                delay(80)
            }
        } finally {
            LiveBlur.clear()
            sample.recycle()
        }
    }
}
