package com.edgestream.tv.data

import com.edgestream.tv.EdgeStreamApp
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One skip window from TheIntroDB. A null [endMs] means "to the end of the media". */
data class IntroSegment(val startMs: Long, val endMs: Long?)

/** The four segment types TheIntroDB publishes. Any list may be empty. */
data class IntroSegments(
    val intro: List<IntroSegment> = emptyList(),
    val recap: List<IntroSegment> = emptyList(),
    val credits: List<IntroSegment> = emptyList(),
    val preview: List<IntroSegment> = emptyList(),
) {
    val isEmpty: Boolean get() = intro.isEmpty() && recap.isEmpty() && credits.isEmpty() && preview.isEmpty()
}

/**
 * Parses the raw v4 response:
 * `{ "intro": [{ "start_ms": 30000, "end_ms": 90000 }], "credits": [...] }`.
 * Null starts normalize to 0; null ends stay null ("end of media").
 */
internal fun parseIntroSegments(json: String): IntroSegments = runCatching {
    val root = JSONObject(json)
    fun list(name: String): List<IntroSegment> {
        val array = root.optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val start = if (item.isNull("start_ms")) 0L else item.optLong("start_ms", 0L)
            val end = if (item.isNull("end_ms")) null else item.optLong("end_ms", 0L).takeIf { it > start }
            IntroSegment(start, end)
        }
    }
    IntroSegments(list("intro"), list("recap"), list("credits"), list("preview"))
}.getOrDefault(IntroSegments())

/**
 * Community intro/recap/credits/preview timestamps from TheIntroDB.
 *
 * Reads are unauthenticated; submissions are not implemented here. Responses are cached in
 * memory and on disk for a week so zapping/replaying a title does not hit the API again.
 */
object TheIntroDb {
    private const val BASE = "https://api.theintrodb.org/v4/media"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 500

    private data class Entry(val at: Long, val segments: IntroSegments)

    private val memory = HashMap<String, Entry>()
    private val lock = Any()

    private val cacheFile: File
        get() = File(EdgeStreamApp.instance.cacheDir, "introdb.json")

    /**
     * Segments for a film (no season/episode) or an episode. Returns null when there is no usable
     * identity, the API has no submissions, or the request fails — callers fall back to local data.
     */
    suspend fun forPlayback(imdbId: String?, tmdbId: Int?, season: Int?, episode: Int?): IntroSegments? =
        withContext(Dispatchers.IO) {
            if (imdbId.isNullOrBlank() && tmdbId == null) return@withContext null
            val key = cacheKey(imdbId, tmdbId, season, episode)
            cached(key)?.let { return@withContext it }
            val url = buildString {
                append(BASE).append('?')
                if (!imdbId.isNullOrBlank()) {
                    append("imdb_id=").append(URLEncoder.encode(imdbId, "UTF-8"))
                } else {
                    append("tmdb_id=").append(tmdbId)
                }
                if (season != null && episode != null) {
                    append("&season=").append(season)
                    append("&episode=").append(episode)
                }
            }
            val parsed = runCatching {
                Http.open(url) { input -> parseIntroSegments(input.bufferedReader().use { it.readText() }) }
            }.getOrNull()?.takeUnless { it.isEmpty } ?: return@withContext null
            synchronized(lock) {
                memory[key] = Entry(System.currentTimeMillis(), parsed)
                saveLocked()
            }
            parsed
        }

    internal fun cacheKey(imdbId: String?, tmdbId: Int?, season: Int?, episode: Int?): String {
        val id = if (!imdbId.isNullOrBlank()) "imdb:$imdbId" else "tmdb:$tmdbId"
        return if (season != null && episode != null) "$id:s$season:e$episode" else id
    }

    private fun cached(key: String): IntroSegments? {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            memory[key]?.let { entry ->
                if (now - entry.at <= TTL_MS) return entry.segments
                memory.remove(key)
            }
            val disk = loadLocked()[key] ?: return null
            if (now - disk.at > TTL_MS) return null
            memory[key] = disk
            return disk.segments
        }
    }

    private fun loadLocked(): Map<String, Entry> = runCatching {
        if (!cacheFile.exists()) return emptyMap()
        val root = JSONObject(cacheFile.readText())
        buildMap {
            root.keys().forEach { key ->
                val item = root.optJSONObject(key) ?: return@forEach
                val segments = IntroSegments(
                    parseArray(item.optJSONArray("intro")),
                    parseArray(item.optJSONArray("recap")),
                    parseArray(item.optJSONArray("credits")),
                    parseArray(item.optJSONArray("preview")),
                )
                if (!segments.isEmpty) put(key, Entry(item.optLong("at", 0L), segments))
            }
        }
    }.getOrDefault(emptyMap())

    private fun parseArray(array: JSONArray?): List<IntroSegment> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val start = if (item.isNull("start_ms")) 0L else item.optLong("start_ms", 0L)
            val end = if (item.isNull("end_ms")) null else item.optLong("end_ms", 0L).takeIf { it > start }
            IntroSegment(start, end)
        }
    }

    private fun saveLocked() {
        runCatching {
            if (memory.size > MAX_ENTRIES) {
                val keep = memory.entries.sortedByDescending { it.value.at }.take(MAX_ENTRIES)
                memory.clear()
                keep.forEach { (key, entry) -> memory[key] = entry }
            }
            val root = JSONObject()
            memory.forEach { (key, entry) ->
                root.put(key, JSONObject().apply {
                    put("at", entry.at)
                    put("intro", array(entry.segments.intro))
                    put("recap", array(entry.segments.recap))
                    put("credits", array(entry.segments.credits))
                    put("preview", array(entry.segments.preview))
                })
            }
            cacheFile.writeText(root.toString())
        }
    }

    private fun array(segments: List<IntroSegment>): JSONArray = JSONArray().apply {
        segments.forEach { segment ->
            put(JSONObject().apply {
                put("start_ms", segment.startMs)
                put("end_ms", segment.endMs ?: JSONObject.NULL)
            })
        }
    }
}
