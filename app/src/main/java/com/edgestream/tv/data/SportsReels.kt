package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Real game highlights for the moving Home backdrop: ESPN's public league news lists highlight
 * videos, and its clip endpoint gives each one's adaptive HLS stream (keyless). Leagues take
 * turns; streamed, never downloaded.
 */
object SportsReels {
    data class Reel(val id: String, val title: String, val stream: String)

    private val LEAGUES = listOf(
        "football/nfl", "rugby-league/3", "hockey/nhl", "basketball/nba",
        "soccer/fifa.world", "soccer/eng.1", "australian-football/afl",
    )
    private const val PER_LEAGUE = 3

    /** Game-highlight videos in a league news feed, newest first: (clip id, headline). */
    fun parseMediaIds(json: String): List<Pair<String, String>> = try {
        val a = JSONObject(json).optJSONArray("articles") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optString("type") == "Media" && it.has("id") }
            .map { it.get("id").toString() to it.optString("headline") }
            // Game action only: studio shows and press conferences aren't "real events".
            .filter { "highlight" in it.second.lowercase() }
    } catch (_: JSONException) { emptyList() }

    /** The clip's adaptive HLS stream, else its MP4. */
    fun parseClipStream(json: String): String? = try {
        val root = JSONObject(json)
        val v = root.optJSONArray("videos")?.optJSONObject(0) ?: root
        val src = v.optJSONObject("links")?.optJSONObject("source")
        src?.optJSONObject("HLS")?.optString("href")?.takeIf { it.startsWith("https://") }
            ?: src?.optJSONObject("HD")?.optString("href")?.takeIf { it.startsWith("https://") }
            ?: src?.optString("href")?.takeIf { it.startsWith("https://") }
    } catch (_: JSONException) { null }

    suspend fun load(): List<Reel> = withContext(Dispatchers.IO) {
        coroutineScope {
            val perLeague = LEAGUES.map { path ->
                async {
                    try {
                        val ids = Http.open("https://site.api.espn.com/apis/site/v2/sports/$path/news?limit=50") { parseMediaIds(it.reader().readText()) }
                        ids.take(PER_LEAGUE).mapNotNull { (id, title) ->
                            Http.open("https://content.core.api.espn.com/v1/video/clips/$id") { parseClipStream(it.reader().readText()) }?.let { Reel(id, title, it) }
                        }
                    } catch (e: IOException) { android.util.Log.w("EdgeReels", "no highlights for $path", e); emptyList() }
                }
            }.awaitAll()
            (0 until (perLeague.maxOfOrNull { it.size } ?: 0)).flatMap { i -> perLeague.mapNotNull { it.getOrNull(i) } }
        }
    }
}
