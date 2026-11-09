package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * The universal catalogue behind Movies and Series: rows of real titles whether or not the
 * IPTV provider carries them, so anything can play through TorBox. Cinemeta (a public, keyless
 * catalogue) needs no key; TMDB adds "Now in cinemas" and "Coming soon" when the user has a
 * key. Every list is cached on disk for six hours, and an old copy is used when offline.
 */
object Catalog {

    data class Title(
        val id: String,
        val imdbId: String?,
        val title: String,
        val year: String?,
        val poster: String?,
        val backdrop: String?,
        val overview: String?,
        val rating: String?,
        /** ISO date; shown on "Coming soon" tiles. */
        val releaseDate: String?,
    )

    enum class Source { CINEMETA, TMDB }

    /** One row: where it comes from and how it's labelled. */
    data class Shelf(val id: String, val label: String, val movies: Boolean, val source: Source, val path: String, val comingSoon: Boolean = false)

    private const val CINEMETA = "https://v3-cinemeta.strem.io"
    private const val TMDB = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    /** The rows for movies or series, in page order. TMDB rows only appear with a key. */
    fun shelves(movies: Boolean, tmdbKey: Boolean): List<Shelf> {
        val year = java.time.LocalDate.now().year
        val t = if (movies) "movie" else "series"
        val tmdb = when {
            !tmdbKey -> emptyList()
            movies -> listOf(
                Shelf("tmdb_cinemas", "Now in cinemas", true, Source.TMDB, "movie/now_playing"),
                Shelf("tmdb_soon", "Coming soon", true, Source.TMDB, "movie/upcoming", comingSoon = true),
            )
            else -> listOf(Shelf("tmdb_onair", "New episodes this week", false, Source.TMDB, "tv/on_the_air"))
        }
        val genres = if (movies) listOf("Action", "Comedy", "Horror", "Sci-Fi") else listOf("Drama", "Comedy", "Crime", "Sci-Fi")
        val cinemeta = listOf(
            Shelf("cm_popular_$t", "Popular now", movies, Source.CINEMETA, "catalog/$t/top.json"),
            Shelf("cm_new_$t", "New in $year", movies, Source.CINEMETA, "catalog/$t/year/genre=$year.json"),
            Shelf("cm_top_$t", "Top rated of all time", movies, Source.CINEMETA, "catalog/$t/imdbRating.json"),
        ) + genres.map { g ->
            Shelf("cm_${g}_$t", "Popular in $g", movies, Source.CINEMETA, "catalog/$t/top/genre=${enc(g)}.json")
        }
        // Cinemas lead on Movies; coming soon sits after the main rows.
        return tmdb.filterNot { it.comingSoon }.take(1) + cinemeta.take(2) + tmdb.drop(1).filterNot { it.comingSoon } +
            cinemeta.drop(2) + tmdb.filter { it.comingSoon }
    }

    suspend fun load(shelf: Shelf, tmdbKey: String, region: String?, cacheDir: File): List<Title> = withContext(Dispatchers.IO) {
        val reg = region?.uppercase()?.takeIf { it.length == 2 } ?: "US"
        val file = File(cacheDir, "catalog_${shelf.id}_$reg.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < TTL_MS
        val raw = runCatching { if (fresh) file.readText() else fetch(shelf, tmdbKey, reg).also { file.writeText(it) } }
            .getOrElse { runCatching { file.readText() }.getOrNull() } ?: return@withContext emptyList()
        runCatching { parse(shelf, raw) }.getOrDefault(emptyList())
    }

    /**
     * Regional Discover: what's popular in the viewer's region, narrowed by original language
     * and a certification ceiling. Uses the v4 read token as a Bearer header when saved,
     * otherwise the v3 key in the query. Cached for six hours like the other TMDB rows.
     */
    suspend fun discover(
        movies: Boolean, apiKey: String, readToken: String,
        region: String, language: String, certification: String, cacheDir: File,
    ): List<Title> = withContext(Dispatchers.IO) {
        val reg = region.uppercase().takeIf { it.length == 2 } ?: return@withContext emptyList()
        if (readToken.isBlank() && apiKey.isBlank()) return@withContext emptyList()
        val q = buildList {
            if (readToken.isBlank()) add("api_key=${enc(apiKey)}")
            add("sort_by=popularity.desc")
            add("region=$reg")
            if (language.isNotBlank()) add("with_original_language=$language")
            if (certification.isNotBlank()) {
                add("certification_country=$reg")
                add("certification.lte=${enc(certification)}")
            }
        }.joinToString("&")
        val id = "regional_${if (movies) "movie" else "tv"}_${reg}_${language}_${certification}"
        val file = File(cacheDir, "catalog_$id.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < TTL_MS
        val raw = runCatching {
            if (fresh) file.readText()
            else {
                val b = Request.Builder().url("$TMDB/discover/${if (movies) "movie" else "tv"}?$q")
                if (readToken.isNotBlank()) b.header("Authorization", "Bearer $readToken")
                Http.client.newCall(b.build()).execute().use { r ->
                    if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
                    r.body.string()
                }.also { file.writeText(it) }
            }
        }.getOrElse { runCatching { file.readText() }.getOrNull() } ?: return@withContext emptyList()
        runCatching { parseTmdbResults(JSONObject(raw), movies) }.getOrDefault(emptyList())
    }

    /** Any title by name from the universal catalogue (not just the provider's list); never cached. */
    suspend fun search(query: String, movies: Boolean): List<Title> = withContext(Dispatchers.IO) {
        val t = if (movies) "movie" else "series"
        val shelf = Shelf("search_$t", "Search", movies, Source.CINEMETA, "catalog/$t/top/search=${enc(query.trim())}.json")
        try { parse(shelf, fetch(shelf, "", "US")) }
        catch (e: IOException) { android.util.Log.w("EdgeCatalog", "search failed", e); emptyList() }
        catch (e: org.json.JSONException) { android.util.Log.w("EdgeCatalog", "search reply unreadable", e); emptyList() }
    }

    private fun fetch(shelf: Shelf, key: String, region: String): String {
        val url = when (shelf.source) {
            Source.CINEMETA -> "$CINEMETA/${shelf.path}"
            Source.TMDB -> "$TMDB/${shelf.path}?api_key=${enc(key)}&region=$region"
        }
        return Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
            r.body.string()
        }
    }

    private fun parse(shelf: Shelf, raw: String): List<Title> {
        val root = JSONObject(raw)
        return when (shelf.source) {
            Source.CINEMETA -> list(root.optJSONArray("metas")) { o ->
                val name = o.optString("name").ifBlank { return@list null }
                val id = o.optString("imdb_id").ifBlank { o.optString("id") }
                Title(
                    id = "imdb:$id", imdbId = id.takeIf { it.startsWith("tt") }, title = name,
                    year = o.optString("year").ifBlank { o.optString("releaseInfo") }.take(4).ifBlank { null },
                    // The catalogue serves small posters; the medium size is sharp on a 4K TV.
                    poster = o.optString("poster").ifBlank { null }?.replace("/poster/small/", "/poster/medium/"),
                    backdrop = o.optString("background").ifBlank { null },
                    overview = o.optString("description").ifBlank { null },
                    rating = o.optString("imdbRating").ifBlank { null },
                    releaseDate = o.optString("released").take(10).ifBlank { null },
                )
            }
            Source.TMDB -> parseTmdbResults(root, shelf.movies)
        }
    }

    /**
     * TMDB titles from a discover/list/collection reply ("results", "items" or "parts"). A list
     * can mix films and shows; only the [movies] side is kept.
     */
    fun parseTmdbResults(root: JSONObject, movies: Boolean): List<Title> {
        val array = root.optJSONArray("results") ?: root.optJSONArray("items") ?: root.optJSONArray("parts")
        val want = if (movies) "movie" else "tv"
        return list(array) { o ->
            o.optString("media_type").takeIf { it.isNotEmpty() && it != want }?.let { return@list null }
            val name = o.optString(if (movies) "title" else "name").ifBlank { return@list null }
            val date = o.optString(if (movies) "release_date" else "first_air_date").ifBlank { null }
            Title(
                id = "tmdb:${o.optLong("id")}", imdbId = null, title = name, year = date?.take(4),
                poster = o.optString("poster_path").ifBlank { null }?.let { "${IMG}w342$it" },
                backdrop = o.optString("backdrop_path").ifBlank { null }?.let { "${IMG}w780$it" },
                overview = o.optString("overview").ifBlank { null },
                rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) },
                releaseDate = date,
            )
        }
    }

    // ---- collections ------------------------------------------------------------------------

    enum class Group(val label: String) {
        SERVICE("Streaming services"), STUDIO("Studios"), FRANCHISE("Franchises"), THEME("Moods & themes"), DECADE("Decades"),
    }

    /** A TMDB-backed collection tile. [query] is a discover query string, or "collection/<id>" for a franchise. */
    data class Collection(val id: String, val name: String, val group: Group, val query: String)

    /** Every collection for movies or series. TMDB only, so none without a key. */
    fun collections(movies: Boolean, region: String?): List<Collection> {
        val reg = region?.uppercase()?.takeIf { it.length == 2 } ?: "US"
        fun service(name: String, provider: Int) =
            Collection("svc_$provider", name, Group.SERVICE, "with_watch_providers=$provider&watch_region=$reg&sort_by=popularity.desc")
        val services = listOf(
            service("Netflix", 8), service("Prime Video", 9), service("Disney+", 337), service("Apple TV+", 350),
            service("Max", 1899), service("Paramount+", 531), service("Hulu", 15), service("Peacock", 386),
            service("Stan", 21), service("Binge", 385),
        )
        if (!movies) return services + listOf(
            Collection("tv_80s", "80s TV", Group.DECADE, "first_air_date.gte=1980-01-01&first_air_date.lte=1989-12-31&sort_by=vote_count.desc"),
            Collection("tv_90s", "90s TV", Group.DECADE, "first_air_date.gte=1990-01-01&first_air_date.lte=1999-12-31&sort_by=vote_count.desc"),
            Collection("tv_00s", "2000s TV", Group.DECADE, "first_air_date.gte=2000-01-01&first_air_date.lte=2009-12-31&sort_by=vote_count.desc"),
        )
        fun studio(name: String, company: Int) = Collection("studio_$company", name, Group.STUDIO, "with_companies=$company&sort_by=popularity.desc")
        fun franchise(name: String, id: Int) = Collection("fr_$id", name, Group.FRANCHISE, "collection/$id")
        fun theme(name: String, keyword: Int) = Collection("kw_$keyword", name, Group.THEME, "with_keywords=$keyword&sort_by=vote_count.desc")
        fun decade(label: String, from: Int) = Collection("dec_$from", label, Group.DECADE,
            "primary_release_date.gte=$from-01-01&primary_release_date.lte=${from + 9}-12-31&sort_by=vote_count.desc")
        return services + listOf(
            studio("Marvel Studios", 420), studio("Pixar", 3), studio("Studio Ghibli", 10342), studio("A24", 41077),
            studio("Lucasfilm", 1), studio("DreamWorks Animation", 521), studio("Blumhouse", 3172),
            franchise("James Bond", 645), franchise("Harry Potter", 1241), franchise("Star Wars", 10),
            franchise("The Lord of the Rings", 119), franchise("Fast & Furious", 9485), franchise("Jurassic Park", 328),
            franchise("Mission: Impossible", 87359), franchise("John Wick", 404609), franchise("Indiana Jones", 84),
            franchise("Pirates of the Caribbean", 295), franchise("Toy Story", 10194), franchise("Shrek", 2150),
            franchise("Rocky", 1575), franchise("Alien", 8091), franchise("Transformers", 8650),
            theme("Christmas", 207317), theme("Superheroes", 9715), theme("True stories", 9672), theme("Heists", 10051),
            theme("Time travel", 4379), theme("Zombies", 12377),
            decade("80s classics", 1980), decade("90s classics", 1990), decade("2000s favourites", 2000),
        )
    }

    /** The titles in [c], up to [pages] pages of TMDB results; cached like the rows. */
    suspend fun loadCollection(c: Collection, movies: Boolean, tmdbKey: String, cacheDir: File, pages: Int = 3): List<Title> =
        withContext(Dispatchers.IO) {
            if (tmdbKey.isBlank()) return@withContext emptyList()
            val type = if (movies) "movie" else "tv"
            val shelf = Shelf(c.id, c.name, movies, Source.TMDB, "")
            val urls = if (c.query.startsWith("collection/")) listOf("$TMDB/${c.query}?api_key=${enc(tmdbKey)}")
            else (1..pages).map { "$TMDB/discover/$type?api_key=${enc(tmdbKey)}&${c.query}&page=$it" }
            urls.flatMapIndexed { i, url ->
                val file = File(cacheDir, "collection_${c.id}_${type}_$i.json")
                val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < TTL_MS
                val raw = runCatching {
                    if (fresh) file.readText()
                    else Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
                        r.body.string()
                    }.also { file.writeText(it) }
                }.getOrElse { runCatching { file.readText() }.getOrNull() } ?: return@flatMapIndexed emptyList()
                runCatching {
                    val root = JSONObject(raw)
                    // A franchise lists its films in release order under "parts".
                    val parts = root.optJSONArray("parts")
                    if (parts != null) parse(shelf, JSONObject().put("results", parts).toString())
                        .sortedBy { it.releaseDate ?: "9999" }
                    else parse(shelf, raw)
                }.getOrDefault(emptyList())
            }.distinctBy { it.id }
        }

    private inline fun list(a: JSONArray?, map: (JSONObject) -> Title?): List<Title> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(map) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
