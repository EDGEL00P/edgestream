package com.edgestream.tv.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import com.edgestream.tv.MainActivity
import com.edgestream.tv.R

/**
 * Wakes the app for the next calendar alarm. The ring itself (pop-up + sound) is shown by the app
 * (AppViewModel polls the calendar); this only makes sure the app is awake and in front when an
 * alarm is due, even if another app is on screen or the process was killed.
 */
object CalendarAlarms {
    private const val REQUEST = 7301

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, CalendarAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Schedules the one wake-up for [atMs], or cancels it when null. */
    fun schedule(context: Context, atMs: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context)
        if (atMs == null) { am.cancel(pi); return }
        // Android 12+ gates exact alarms behind a user grant; fall back to an inexact wake there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }
}

/** Brings EdgeStream to the front; its calendar poll then shows the alarm. */
class CalendarAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_CALENDAR_ALARM, true),
        )
    }

    companion object { const val EXTRA_CALENDAR_ALARM = "calendar_alarm" }
}

/** The ring: the box's alarm tone (or the app's own chime when it has none), looping until stopped. */
class AlarmSound(private val context: Context) {
    private var player: MediaPlayer? = null

    fun start() {
        if (player != null) return
        val tone = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        player = (tone?.let { runCatching { MediaPlayer().apply { setAudioAttributes(attrs); setDataSource(context, it); prepare() } }.getOrNull() }
            ?: MediaPlayer.create(context, R.raw.ui_confirm, attrs, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE))
            ?.apply { isLooping = true; start() }
    }

    fun stop() {
        player?.run { runCatching { stop() }; release() }
        player = null
    }
}
