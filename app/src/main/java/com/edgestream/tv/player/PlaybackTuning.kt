package com.edgestream.tv.player

import androidx.media3.common.PlaybackException

/** How much the player buffers, in ms: keep loading below [minMs], stop at [maxMs]. */
data class BufferPlan(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int)

/**
 * The buffer setting is a live-TV knob (how close to the live edge to sit). It must not become
 * the refill trigger: with a 0.5 s floor a 50–80 Mbps debrid remux drains, the audio track
 * underruns and the picture stalls. So the player always keeps loading up to 30 s ahead (live
 * streams can't run ahead of the edge anyway, so latency is untouched), starts fast, and
 * resumes after a stall only with a safe cushion.
 */
fun bufferPlan(settingMs: Int): BufferPlan {
    val target = settingMs.coerceIn(500, 60_000)
    val min = maxOf(target, 30_000)
    return BufferPlan(minMs = min, maxMs = maxOf(target * 3, 60_000), startMs = minOf(800, target), rebufferMs = 2_500)
}

/**
 * Bytes the player may hold ahead: a share of this process's heap, not Media3's 128 MB default,
 * which on a 192 MB heap left 4K remuxes fighting the UI for memory (blocking GC pauses,
 * stalls). Time stays the real limit; this is only the ceiling.
 */
fun bufferBudgetBytes(maxHeapBytes: Long): Int =
    (maxHeapBytes * 0.35).toLong().coerceIn(32L shl 20, 160L shl 20).toInt()

/**
 * Tunnelled playback (hardware A/V sync) smooths live sport on the SHIELD, but with Dolby
 * passthrough and 4K HEVC/Dolby Vision files its audio clock can freeze and the player times out.
 * So the setting only applies to live channels.
 */
fun tunnelFor(setting: Boolean, isLive: Boolean): Boolean = setting && isLive

/** Film/episode failures that a fresh start from the same position fixes (a hung audio track, a stuck decoder). */
fun recoverableOnDemand(errorCode: Int): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_TIMEOUT,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
)
