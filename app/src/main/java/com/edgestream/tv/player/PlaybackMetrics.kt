package com.edgestream.tv.player

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Debug-build playback measurements, logged under the EdgePlay tag: time from play() to the
 * first rendered frame, which decoders were chosen and how long they took to start, buffering
 * stalls and dropped frames. Numbers to tune against, instead of guesses.
 */
@OptIn(UnstableApi::class)
class PlaybackMetrics : AnalyticsListener {
    private var startedAt = 0L
    private var label = ""
    private var stalls = 0
    private var stallStart = 0L
    private var stalledMs = 0L
    private var dropped = 0
    private var firstFrame = false

    fun begin(what: String) {
        startedAt = SystemClock.elapsedRealtime()
        label = what
        stalls = 0; stalledMs = 0; dropped = 0; firstFrame = false
    }

    private fun since() = SystemClock.elapsedRealtime() - startedAt

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        if (firstFrame || startedAt == 0L) return
        firstFrame = true
        Log.d(TAG, "first frame ${since()} ms  $label")
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        Log.d(TAG, "video decoder $decoderName init ${initializationDurationMs} ms at ${since()} ms")
    }

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        Log.d(TAG, "audio decoder $decoderName init ${initializationDurationMs} ms")
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
        Log.d(TAG, "video ${format.sampleMimeType} ${format.width}x${format.height} ${format.frameRate} fps ${format.bitrate / 1000} kbps")
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = SystemClock.elapsedRealtime()
        if (state == Player.STATE_BUFFERING && firstFrame) { stalls++; stallStart = now }
        if (state == Player.STATE_READY && stallStart != 0L) { stalledMs += now - stallStart; stallStart = 0L }
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { dropped += droppedFrames }

    /** One summary line when a stream ends or is replaced. */
    fun summary() {
        if (startedAt == 0L) return
        Log.d(TAG, "summary $label: ${since() / 1000}s watched, $stalls stalls (${stalledMs} ms), $dropped dropped frames")
    }

    private companion object { const val TAG = "EdgePlay" }
}
