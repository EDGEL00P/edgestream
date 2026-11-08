package com.edgestream.tv.data

import android.content.SharedPreferences

/**
 * One-time setup for a sports-first living-room box. It runs once per install so a later choice
 * is never overwritten; the user's own settings win from then on.
 */
object RecommendedSetup {
    private const val KEY = "presetV1"

    fun applyIfNeeded(settings: Settings, prefs: SharedPreferences) {
        if (prefs.getBoolean(KEY, false)) return
        settings.update { current ->
            current.copy(
                theme = "primetime",
                overscanPercent = 5,
                livePreview = true,
                previewDelayMs = 1_500,
                effects = "auto",
                uiSounds = true,
                use24h = true,
                englishOnly = true,
                hideAdult = true,
                hideOffline = true,
                radarSatellite = false,
                epgRefreshHours = 12,
                autoGuides = true,
                startScreen = "HOME",
            )
        }
        prefs.edit().putBoolean(KEY, true).apply()
    }
}
