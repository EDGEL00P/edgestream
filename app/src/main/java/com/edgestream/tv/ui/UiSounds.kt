package com.edgestream.tv.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.edgestream.tv.R

/**
 * The app's interface voice: a soft filtered tick on focus and a round, low confirm tap.
 * Both are tiny pre-rendered WAVs loaded into SoundPool, so playback is low-latency and
 * polyphonic without the clicks of a streaming AudioTrack. Deliberately quiet.
 */
object UiSounds {
    /** Set from the settings flow in EdgeApp. */
    var enabled by mutableStateOf(true)

    private var pool: SoundPool? = null
    private var focusId = 0
    private var confirmId = 0
    private var lastFocus = 0L

    fun init(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val soundPool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
        pool = soundPool
        focusId = soundPool.load(context.applicationContext, R.raw.ui_focus, 1)
        confirmId = soundPool.load(context.applicationContext, R.raw.ui_confirm, 1)
    }

    fun focus() {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastFocus < 45) return
        lastFocus = now
        play(focusId, 0.25f)
    }

    fun click() {
        if (!enabled) return
        play(confirmId, 0.38f)
    }

    private fun play(id: Int, volume: Float) {
        if (id == 0) return
        runCatching { pool?.play(id, volume, volume, 1, 0, 1f) }
    }
}
