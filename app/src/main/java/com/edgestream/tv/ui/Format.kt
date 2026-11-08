package com.edgestream.tv.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Format {
    fun time(ms: Long, use24h: Boolean): String =
        SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(ms))

    fun range(start: Long, stop: Long, use24h: Boolean) = "${time(start, use24h)} – ${time(stop, use24h)}"

    /** A compact HUD stamp, e.g. "THU 05 NOV", for the home deck. */
    fun stamp(ms: Long): String =
        SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(Date(ms)).uppercase(Locale.getDefault())

    fun day(ms: Long): String {
        val c = Calendar.getInstance()
        val today = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
        c.timeInMillis = ms
        val that = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
        c.timeInMillis = System.currentTimeMillis() - 86_400_000
        val yesterday = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
        c.timeInMillis = System.currentTimeMillis() + 86_400_000
        val tomorrow = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
        return when (that) {
            today -> "Today"
            yesterday -> "Yesterday"
            tomorrow -> "Tomorrow"
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
        }
    }

    fun duration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun ago(ms: Long): String {
        if (ms <= 0) return "never"
        val mins = (System.currentTimeMillis() - ms) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 1440 -> "${mins / 60}h ago"
            else -> "${mins / 1440}d ago"
        }
    }

    fun fraction(start: Long, stop: Long, now: Long = System.currentTimeMillis()): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}
