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

    /** How long before the viewer's own intro mark "Skip intro" is offered. */
    const val LOCAL_INTRO_LEAD_MS = 120_000L

    /**
     * The skip pills to show at [position], as (label, seek target). [localIntro] is the viewer's
     * own mark for the series, used only when TheIntroDB has no intro. While Up Next is showing it
     * stands in for "Skip credits".
     */
    fun pills(
        position: Long, duration: Long, segments: IntroSegments?, localIntro: Long?, upNextShowing: Boolean,
    ): List<Pair<String, Long>> {
        val active = { s: IntroSegment -> position >= s.startMs && position < (s.endMs ?: duration) }
        val toEnd = (duration - 400).coerceAtLeast(position + 1)
        return buildList {
            segments?.intro?.firstOrNull(active)?.let { add("Skip intro" to (it.endMs ?: duration)) }
            segments?.recap?.firstOrNull(active)?.let { add("Skip recap" to (it.endMs ?: duration)) }
            if (!upNextShowing) segments?.credits?.firstOrNull(active)?.let { add("Skip credits" to (it.endMs ?: toEnd)) }
            segments?.preview?.firstOrNull(active)?.let { add("Skip preview" to (it.endMs ?: duration)) }
            // One mark per series and cold opens vary: offer it only in the run-up to that spot.
            if (none { it.first == "Skip intro" } && localIntro != null &&
                position < localIntro && position >= localIntro - LOCAL_INTRO_LEAD_MS
            ) add("Skip intro" to localIntro)
            if (!upNextShowing && none { it.first == "Skip credits" } && segments?.credits.isNullOrEmpty() &&
                duration > 20 * 60_000L && duration - position < 2 * 60_000L
            ) add("Skip credits" to toEnd)
        }
    }
}
