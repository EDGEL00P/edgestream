package com.edgestream.tv.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One decoded backdrop photo and its place in the rotation (drives its Ken Burns move). */
class BackdropImages(val sharp: ImageBitmap, val index: Int = 0)

/**
 * The Home backdrop: real game photography from ESPN's public league news (keyless) — NFL, NRL,
 * NHL, NBA, FIFA, Premier League and AFL taking turns — cached on disk for six hours and shown one
 * after another. Offline, the saved photos are used; with none saved the caller keeps the themed
 * ambient background.
 */
object Backdrop {
    private const val FRESH_MS = 6 * 3_600_000L
    private const val PER_LEAGUE = 4
    private val LEAGUES = listOf(
        "football/nfl", "rugby-league/3", "hockey/nhl", "basketball/nba",
        "soccer/fifa.world", "soccer/eng.1", "australian-football/afl",
    )
    private fun newsUrl(path: String) = "https://site.api.espn.com/apis/site/v2/sports/$path/news?limit=50"

    /** ESPN's full-width (1296 px) wire photos only: no thumbnails, headshots or graphics. */
    private val ACTION_PHOTO =
        Regex("""^https://a\.espncdn\.com/photo/\d{4}/\d{4}/r\d+(?:_\d+)?_1296x(?:729|864)_(?:16-9|3-2)\.jpg$""")

    fun parseEspnPhotos(json: String): List<String> = try {
        val articles = JSONObject(json).optJSONArray("articles") ?: JSONArray()
        (0 until articles.length()).flatMap { a ->
            val images = articles.optJSONObject(a)?.optJSONArray("images") ?: JSONArray()
            (0 until images.length()).mapNotNull { i -> images.optJSONObject(i)?.optString("url")?.takeIf { ACTION_PHOTO.matches(it) } }
        }.distinct()
    } catch (_: JSONException) { emptyList() }

    /** Round-robin across leagues so no sport hogs the rotation. */
    fun interleave(perSource: List<List<String>>, perLeague: Int): List<String> {
        val capped = perSource.map { it.take(perLeague) }
        return (0 until (capped.maxOfOrNull { it.size } ?: 0)).flatMap { i -> capped.mapNotNull { it.getOrNull(i) } }
    }

    fun isFresh(savedAtMs: Long, nowMs: Long): Boolean = nowMs >= savedAtMs && nowMs - savedAtMs < FRESH_MS

    /** The saved photos in rotation order, refreshed from ESPN at most every six hours. */
    suspend fun loadAll(context: Context): List<File> = withContext(Dispatchers.IO) {
        // Retired caches: Bing landscapes and the stock sport clips (~60 MB).
        listOf("backdrops", "reels").forEach { File(context.filesDir, it).takeIf(File::exists)?.deleteRecursively() }
        val dir = File(context.filesDir, "sport_photos").apply { mkdirs() }
        val meta = File(dir, "meta")
        val savedAt = meta.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (!isFresh(savedAt, now)) {
            val perLeague = coroutineScope {
                LEAGUES.map { path ->
                    async {
                        try { Http.open(newsUrl(path)) { parseEspnPhotos(it.reader().readText()) } }
                        catch (e: IOException) { android.util.Log.w("EdgeBackdrop", "no photos for $path", e); emptyList() }
                    }
                }.awaitAll()
            }
            val keep = interleave(perLeague, PER_LEAGUE).mapIndexedNotNull { i, url ->
                val f = File(dir, "photo_${url.hashCode().toUInt()}.jpg")
                try {
                    if (!f.exists()) {
                        val bytes = Http.open(url) { it.readBytes() }
                        if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) return@mapIndexedNotNull null
                        File(dir, "tmp_$i").apply { writeBytes(bytes); renameTo(f) }
                    }
                } catch (e: IOException) { android.util.Log.w("EdgeBackdrop", "photo download failed", e); return@mapIndexedNotNull null }
                f.setLastModified(now - i * 1_000L) // rotation order: newest timestamp first
                f
            }
            if (keep.isNotEmpty()) {
                dir.listFiles { f -> f.name.startsWith("photo_") && f !in keep }?.forEach { it.delete() }
                meta.writeText(now.toString())
            }
        }
        dir.listFiles { f -> f.name.startsWith("photo_") }.orEmpty().sortedByDescending { it.lastModified() }
    }

    /** Decodes one saved photo (off the main thread). */
    suspend fun decode(file: File, index: Int): BackdropImages? = withContext(Dispatchers.Default) {
        BitmapFactory.decodeFile(file.path)?.let { BackdropImages(it.asImageBitmap(), index) }
    }
}
