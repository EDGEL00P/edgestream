package com.edgestream.tv.data

import org.json.JSONObject

/**
 * Which weather alerts the viewer has already dismissed, kept across restarts so the same
 * warning doesn't come back every launch or every 15-minute check.
 *
 * Lightning is one alert whatever the distance (its title carries the km, which changes every
 * check) and returns after [LIGHTNING_MS]; any other warning stays dismissed for [WARNING_MS].
 */
class AlertMemory(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dismissed: MutableMap<String, Long> = runCatching {
        val o = JSONObject(load() ?: "{}")
        o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    fun key(w: Trackers.Warning): String =
        if (w.source == LIGHTNING_SOURCE) "$LIGHTNING_SOURCE|lightning" else "${w.source}|${w.title}".take(180)

    fun isDismissed(w: Trackers.Warning): Boolean {
        val at = dismissed[key(w)] ?: return false
        return now() - at < if (w.source == LIGHTNING_SOURCE) LIGHTNING_MS else WARNING_MS
    }

    fun dismiss(w: Trackers.Warning) {
        val t = now()
        dismissed[key(w)] = t
        // Expired entries are dropped as new ones arrive, so the stored map stays small.
        dismissed.entries.removeAll { t - it.value >= WARNING_MS }
        save(JSONObject(dismissed as Map<*, *>).toString())
    }

    companion object {
        const val LIGHTNING_SOURCE = "Xweather"
        const val LIGHTNING_MS = 2 * 3_600_000L
        const val WARNING_MS = 24 * 3_600_000L
    }
}
