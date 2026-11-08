package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Subtitles from OpenSubtitles through a public subtitle add-on (no key, no account): SRT files
 * for a film or an episode, by IMDb id. The player side-loads them as ordinary text tracks.
 */
object OnlineSubtitles {
    private const val ADDON = "https://opensubtitles-v3.strem.io"

    data class Sub(val url: String, val language: String, val label: String)

    /**
     * Up to [limit] subtitles in [language] (ISO 639-1 like "en", or blank for English), best
     * first as OpenSubtitles ranks them. Empty when none exist or the service can't be reached.
     */
    suspend fun find(imdbId: String, season: Int?, episode: Int?, language: String, limit: Int = 3): List<Sub> = withContext(Dispatchers.IO) {
        val want = iso3(language.ifBlank { "en" })
        val path = if (season != null && episode != null) "series/$imdbId:$season:$episode" else "movie/$imdbId"
        val raw = runCatching {
            Http.client.newCall(Request.Builder().url("$ADDON/subtitles/$path.json").build()).execute().use { r ->
                if (r.isSuccessful) r.body.string() else null
            }
        }.getOrNull() ?: return@withContext emptyList()
        parse(raw, want, language.ifBlank { "en" }, limit)
    }

    internal fun parse(raw: String, iso3: String, iso2: String, limit: Int): List<Sub> {
        val list = runCatching { JSONObject(raw).optJSONArray("subtitles") }.getOrNull() ?: return emptyList()
        val name = java.util.Locale(iso2).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { iso2 }
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optString("lang") == iso3 }
            .mapNotNull { o -> o.optString("url").takeIf { it.startsWith("https://") } }
            .distinct().take(limit)
            .mapIndexed { i, url -> Sub(url, iso2, if (i == 0) "$name · Online" else "$name · Online ${i + 1}") }
    }

    private fun iso3(iso2: String): String = runCatching { java.util.Locale(iso2).isO3Language }.getOrDefault("eng").ifBlank { "eng" }
}
