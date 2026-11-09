package com.edgestream.tv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Focus memory with two layers:
 *  - an in-process map for instant tab/row restoration
 *  - a debounced SharedPreferences copy so the focused card survives process death
 *
 * Row keys are stable strings ("home:continue", "live:channels"); values are item keys.
 * Writes are coalesced (350 ms) because a held D-pad can change focus dozens of times.
 */
object FocusMemory {
    private const val PREFS = "edgestream"
    private const val KEY = "focusMemory"
    private const val SEP = "\u001F"

    private val rows = HashMap<String, String>()
    private var prefs: android.content.SharedPreferences? = null
    private val handler = Handler(Looper.getMainLooper())
    private val save = Runnable { persist() }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.getStringSet(KEY, emptySet()).orEmpty().forEach { entry ->
            val i = entry.indexOf(SEP)
            if (i > 0) rows[entry.substring(0, i)] = entry.substring(i + 1)
        }
    }

    fun remember(row: String, item: String) {
        if (rows[row] == item) return
        rows[row] = item
        handler.removeCallbacks(save)
        handler.postDelayed(save, 350)
    }

    fun last(row: String): String? = rows[row]

    private fun persist() {
        val values = rows.map { (k, v) -> "$k$SEP$v" }.toSet()
        prefs?.edit()?.putStringSet(KEY, values)?.apply()
    }
}
