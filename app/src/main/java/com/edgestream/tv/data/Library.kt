package com.edgestream.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The library: My List, watched episodes and the last episode per series so
 * "Up next" and "Continue Watching" can offer the next episode without re-deriving it.
 *
 * Keys are stable across refreshes: provider VOD keys ("6:M:123", "6:S:456:E:789") and
 * TorBox library keys ("movie|...", "series|...", "debrid:t1:2"). Persisted as JSON in a
 * single file, like the reminder store, so no database migration is needed.
 */
data class LastEpisode(
    val seriesKey: String,
    val episodeKey: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val poster: String?,
    val updatedAt: Long,
)

data class LibraryState(
    val myList: Set<String> = emptySet(),
    val watched: Set<String> = emptySet(),
    val lastEpisode: Map<String, LastEpisode> = emptyMap(),
)

object Library {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    @Volatile private var loaded = false

    private fun file(): File = File(com.edgestream.tv.EdgeStreamApp.instance.filesDir, "library.json")

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = file()
        val restored = runCatching {
            if (!f.exists()) return@runCatching null
            val o = JSONObject(f.readText())
            val myList = o.optJSONArray("myList")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val watched = o.optJSONArray("watched")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val last = LinkedHashMap<String, LastEpisode>()
            o.optJSONArray("lastEpisode")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.getJSONObject(i)
                    val le = LastEpisode(
                        seriesKey = e.getString("sk"),
                        episodeKey = e.getString("ek"),
                        title = e.optString("t"),
                        season = e.optInt("s"), episode = e.optInt("e"),
                        poster = e.optString("p").ifEmpty { null },
                        updatedAt = e.optLong("u"),
                    )
                    last[le.seriesKey] = le
                }
            }
            LibraryState(myList, watched, last)
        }.getOrNull()
        if (restored != null) {
            _state.value = restored
            return
        }
        // First run: migrate the old debrid-only list so nothing is lost.
        val legacy = com.edgestream.tv.EdgeStreamApp.instance
            .getSharedPreferences("edgestream", Context.MODE_PRIVATE)
            .getStringSet("debrid_list", emptySet()).orEmpty()
        _state.value = LibraryState(myList = legacy)
        saveLocked(_state.value)
    }

    private fun saveLocked(s: LibraryState) {
        runCatching {
            val o = JSONObject()
                .put("myList", JSONArray().apply { s.myList.forEach { put(it) } })
                .put("watched", JSONArray().apply { s.watched.forEach { put(it) } })
                .put("lastEpisode", JSONArray().apply {
                    s.lastEpisode.values.forEach { le ->
                        put(
                            JSONObject().put("sk", le.seriesKey).put("ek", le.episodeKey)
                                .put("t", le.title).put("s", le.season).put("e", le.episode)
                                .put("p", le.poster.orEmpty()).put("u", le.updatedAt),
                        )
                    }
                })
            file().writeText(o.toString())
        }
    }

    private fun update(transform: (LibraryState) -> LibraryState) {
        ensureLoaded()
        synchronized(this) {
            val next = transform(_state.value)
            _state.value = next
            saveLocked(next)
        }
    }

    fun snapshot(): LibraryState {
        ensureLoaded()
        return _state.value
    }

    fun isWatched(key: String): Boolean {
        ensureLoaded()
        return key in _state.value.watched
    }

    fun markWatched(key: String) {
        if (key.isBlank()) return
        ensureLoaded()
        if (key in _state.value.watched) return
        update { it.copy(watched = it.watched + key) }
    }

    fun markUnwatched(key: String) {
        if (key.isBlank()) return
        ensureLoaded()
        if (key !in _state.value.watched) return
        update { it.copy(watched = it.watched - key) }
    }

    fun rememberEpisode(seriesKey: String, episodeKey: String, title: String, season: Int, episode: Int, poster: String?) {
        if (seriesKey.isBlank() || episodeKey.isBlank() || seriesKey == episodeKey) return
        ensureLoaded()
        val le = LastEpisode(seriesKey, episodeKey, title, season, episode, poster, System.currentTimeMillis())
        update { it.copy(lastEpisode = it.lastEpisode + (seriesKey to le)) }
    }

    fun lastEpisode(seriesKey: String): LastEpisode? {
        ensureLoaded()
        return _state.value.lastEpisode[seriesKey]
    }

    /** Series the viewer started, most recent first — the source for "Up next". */
    fun recentSeries(limit: Int = 12): List<LastEpisode> {
        ensureLoaded()
        return _state.value.lastEpisode.values.sortedByDescending { it.updatedAt }.take(limit)
    }
}
