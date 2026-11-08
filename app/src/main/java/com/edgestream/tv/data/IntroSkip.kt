package com.edgestream.tv.data

import android.content.Context

/**
 * Learns where a series' intro ends, then offers "Skip intro" on every later episode.
 *
 * The viewer marks the spot once (Settings of a sort: press "Set intro end" while the intro
 * plays); the marker is stored per series and applied automatically from then on. No fingerprints,
 * no cloud — it just works, and it's always the viewer's own call.
 */
object IntroSkip {
    private const val KEY_PREFIX = "intro:"

    /** The saved intro length for [seriesKey], or null before the viewer has marked one. */
    fun forSeries(seriesKey: String): Long? {
        if (seriesKey.isBlank()) return null
        val prefs = com.edgestream.tv.EdgeStreamApp.instance
            .getSharedPreferences("edgestream", Context.MODE_PRIVATE)
        return prefs.getLong(KEY_PREFIX + seriesKey, -1L).takeIf { it > 0 }
    }

    fun save(seriesKey: String, ms: Long) {
        if (seriesKey.isBlank() || ms <= 0) return
        com.edgestream.tv.EdgeStreamApp.instance
            .getSharedPreferences("edgestream", Context.MODE_PRIVATE)
            .edit().putLong(KEY_PREFIX + seriesKey, ms).apply()
    }
}
