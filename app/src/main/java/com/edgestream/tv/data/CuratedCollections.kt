package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * The viewer's curated collections (a JSON definition hosted on GitHub):
 * groups of folders, each with its own cover art and one or more TMDB sources. Read live from
 * the repository and cached for a day, so editing the file there changes the TV.
 *
 * Trakt sources need a Trakt key the app doesn't have; they are left out, and a folder made only
 * of Trakt lists is hidden rather than shown empty.
 */
object CuratedCollections {
    const val URL = "https://raw.githubusercontent.com/EDGEL00P/nuvio-art/main/nuvio-collections.json"
    private const val TTL_MS = 24 * 60 * 60 * 1000L
    private const val TMDB = "https://api.themoviedb.org/3"

    data class Group(val id: String, val title: String, val folders: List<Folder>)
    data class Folder(val id: String, val title: String, val cover: String?, val hero: String?, val sources: List<Source>)
    data class Source(
        val type: String, val title: String, val movies: Boolean, val tmdbId: Long?,
        val sortBy: String?, val filters: Map<String, String>,
    )

    /** The collections, or an empty list when neither the network nor the cache has them. */
    suspend fun load(cacheDir: File): List<Group> = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "curated-collections.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < TTL_MS
        val raw = runCatching {
            if (fresh) file.readText()
            else Http.client.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
                r.body.string()
            }.also { file.writeText(it) }
        }.getOrElse { runCatching { file.readText() }.getOrNull() } ?: return@withContext emptyList()
        runCatching { parse(raw) }.getOrDefault(emptyList())
    }

    internal fun parse(raw: String): List<Group> {
        val groups = JSONArray(raw)
        return (0 until groups.length()).mapNotNull { gi ->
            val g = groups.optJSONObject(gi) ?: return@mapNotNull null
            val folders = g.optJSONArray("folders") ?: return@mapNotNull null
            val list = (0 until folders.length()).mapNotNull { fi ->
                val f = folders.optJSONObject(fi) ?: return@mapNotNull null
                val sources = f.optJSONArray("sources")?.let { a -> (0 until a.length()).mapNotNull { source(a.optJSONObject(it)) } }.orEmpty()
                if (sources.isEmpty()) return@mapNotNull null
                Folder(
                    f.optString("id"), tidy(f.optString("title")),
                    f.optString("coverImageUrl").ifBlank { null }, f.optString("heroBackdropUrl").ifBlank { null }, sources,
                )
            }
            // Organised: alphabetical, with same-titled folders merged (first wins).
            val organised = list.sortedBy { it.title.lowercase() }.distinctBy { it.title.lowercase() }
            if (organised.isEmpty()) null else Group(g.optString("id"), tidy(g.optString("title")), organised)
        }
    }

    private fun tidy(s: String): String =
        NameCleaner.tidyTitle(s).replace(Regex("""\s+"""), " ").trim()

    private fun source(o: JSONObject?): Source? {
        if (o == null || o.optString("provider") != "tmdb") return null
        val filters = o.optJSONObject("filters")?.let { f -> f.keys().asSequence().associateWith { k -> f.get(k).toString() } }.orEmpty()
        return Source(
            o.optString("tmdbSourceType"), tidy(o.optString("title")), o.optString("mediaType") != "TV",
            o.optLong("tmdbId").takeIf { it > 0 }, o.optString("sortBy").ifBlank { null }, filters,
        )
    }

    /** Collection filter names → TMDB discover parameters (TV uses air dates where films use release dates). */
    private fun discoverParams(s: Source): List<Pair<String, String>> = buildList {
        val tv = !s.movies
        for ((k, v) in s.filters) {
            val name = when (k) {
                "withGenres" -> "with_genres"; "withoutGenres" -> "without_genres"
                "withKeywords" -> "with_keywords"; "withoutKeywords" -> "without_keywords"
                "voteCountGte" -> "vote_count.gte"; "voteAverageGte" -> "vote_average.gte"
                "watchRegion" -> "watch_region"; "withWatchProviders" -> "with_watch_providers"
                "withOriginalLanguage" -> "with_original_language"; "withOriginCountry" -> "with_origin_country"
                "withNetworks" -> "with_networks"; "withCompanies" -> "with_companies"; "withoutCompanies" -> "without_companies"
                "releaseDateGte" -> if (tv) "first_air_date.gte" else "primary_release_date.gte"
                "releaseDateLte" -> if (tv) "first_air_date.lte" else "primary_release_date.lte"
                else -> null
            } ?: continue
            add(name to v)
        }
        when (s.type) {
            "NETWORK" -> s.tmdbId?.let { add("with_networks" to it.toString()) }
            "COMPANY" -> s.tmdbId?.let { add("with_companies" to it.toString()) }
            "PERSON" -> s.tmdbId?.let { add("with_cast" to it.toString()) }
            "DIRECTOR" -> s.tmdbId?.let { add("with_crew" to it.toString()) }
        }
        s.sortBy?.takeIf { it.contains('.') }?.let { add("sort_by" to it) }
    }

    /** The TMDB request for one source's first [page]; null for a type this app can't read. */
    internal fun url(s: Source, key: String, page: Int = 1): String? {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val k = "api_key=${enc(key)}"
        return when (s.type) {
            "LIST" -> s.tmdbId?.let { "$TMDB/list/$it?$k&page=$page" }
            "COLLECTION" -> s.tmdbId?.let { "$TMDB/collection/$it?$k" }
            "DISCOVER", "NETWORK", "COMPANY", "PERSON", "DIRECTOR" ->
                "$TMDB/discover/${if (s.movies) "movie" else "tv"}?$k&page=$page&" + discoverParams(s).joinToString("&") { (n, v) -> "$n=${enc(v)}" }
            else -> null
        }
    }

    /** One source's titles (two pages), cached like the catalogue rows. */
    suspend fun titles(s: Source, key: String, cacheDir: File): List<Catalog.Title> = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext emptyList()
        val pages = if (s.type == "COLLECTION") 1 else 2
        (1..pages).flatMap { page ->
            val url = url(s, key, page) ?: return@flatMap emptyList()
            val file = File(cacheDir, "collection_${url.substringAfter("/3/").replace(Regex("api_key=[^&]*&?"), "").hashCode()}.json")
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < TTL_MS
            val raw = runCatching {
                if (fresh) file.readText()
                else Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
                    r.body.string()
                }.also { file.writeText(it) }
            }.getOrElse { runCatching { file.readText() }.getOrNull() } ?: return@flatMap emptyList()
            runCatching { Catalog.parseTmdbResults(JSONObject(raw), s.movies) }.getOrDefault(emptyList())
        }.distinctBy { it.id }.let { list -> if (s.type == "COLLECTION") list.sortedBy { it.releaseDate ?: "9999" } else list }
    }
}
