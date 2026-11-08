package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * Plays the user's own debrid cloud library (TorBox), dressed with metadata from Cinemeta, the
 * public, keyless metadata catalogue (IMDb ids, ratings, posters, backdrops, title logos and
 * episode lists; no key needed).
 *
 * Only files already in the account are listed; nothing is searched for or added.
 */
object Debrid {

    /** One playable video file in the account. */
    data class CloudFile(val id: String, val fileName: String, val folder: String, val size: Long, val url: String)

    /** What a file name says about itself. */
    data class Parsed(val title: String, val year: String?, val season: Int?, val episode: Int?) {
        val isEpisode get() = season != null && episode != null
    }

    /** Cinemeta's description of a film or show. */
    data class Meta(
        val imdbId: String,
        val type: String,
        val name: String,
        val year: String?,
        val poster: String?,
        val background: String?,
        val logo: String?,
        val description: String?,
        val rating: String?,
        val runtime: String?,
        val genres: List<String>,
        val cast: List<String>,
        val director: List<String>,
        val episodes: Map<Pair<Int, Int>, EpisodeMeta>,
    )

    data class EpisodeMeta(val name: String?, val thumbnail: String?, val overview: String?)

    data class Episode(val season: Int, val episode: Int, val file: CloudFile, val meta: EpisodeMeta?)

    /** A film or show in the library. [meta] is null when Cinemeta had no match. */
    data class Title(
        val key: String,
        val type: String,
        val name: String,
        val year: String?,
        val meta: Meta?,
        /** Films: the copies of the film (largest first). Shows: unused. */
        val files: List<CloudFile>,
        val episodes: List<Episode>,
        val added: Int,
    ) {
        val poster get() = meta?.poster
        val displayName get() = meta?.name ?: name
        val displayYear get() = meta?.year ?: year
    }

    data class Library(val movies: List<Title>, val series: List<Title>)

    // ---- TorBox ----------------------------------------------------------------------------

    private const val TORBOX = "https://api.torbox.app/v1/api"
    private val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg")

    /**
     * Every video file in the account's torrents, usenet and web downloads. Streams use TorBox's
     * permalinks, which redirect to a fresh CDN link each time they're opened, so a saved
     * "continue watching" entry keeps working.
     */
    fun torboxFiles(apiKey: String): List<CloudFile> {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "No debrid API key" }
        val kinds = listOf(
            Triple("torrents", "torrent_id", "t"),
            Triple("usenet", "usenet_id", "u"),
            Triple("webdl", "web_id", "w"),
        )
        val out = ArrayList<CloudFile>()
        var firstError: Exception? = null
        for ((kind, idParam, tag) in kinds) {
            val items = try {
                torboxGet("$TORBOX/$kind/mylist?bypass_cache=true", key)
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                continue
            }
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                // Only what has finished arriving in the cloud can be played.
                if (item.has("download_present") && !item.optBoolean("download_present")) continue
                val itemId = item.optLong("id")
                val folder = item.optString("name")
                val files = item.optJSONArray("files") ?: continue
                for (f in 0 until files.length()) {
                    val file = files.optJSONObject(f) ?: continue
                    val name = file.optString("short_name").ifEmpty { file.optString("name").substringAfterLast('/') }
                    val size = file.optLong("size")
                    if (!isFeatureVideo(name, file.optString("mimetype"), size)) continue
                    val fileId = file.optLong("id")
                    val url = "$TORBOX/$kind/requestdl?token=${enc(key)}&$idParam=$itemId&file_id=$fileId&redirect=true"
                    out += CloudFile("$tag$itemId:$fileId", name, folder, size, url)
                }
            }
        }
        if (out.isEmpty() && firstError != null) throw firstError
        return out
    }

    private fun torboxGet(url: String, key: String): JSONArray {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
        Http.client.newCall(req).execute().use { r ->
            val body = r.body.string()
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IOException("The debrid service returned HTTP ${r.code}")
            if (!json.optBoolean("success", r.isSuccessful)) {
                val why = json.optString("detail").ifEmpty { json.optString("error") }
                throw IOException(if (json.optString("error") == "BAD_TOKEN" || r.code == 401 || r.code == 403) "The debrid service didn't accept the API key" else "Debrid service: $why")
            }
            return when (val data = json.opt("data")) {
                is JSONArray -> data
                is JSONObject -> JSONArray().put(data)
                else -> JSONArray()
            }
        }
    }

    private fun isFeatureVideo(name: String, mime: String, size: Long): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in VIDEO_EXT && !mime.startsWith("video/")) return false
        // Samples, trailers and extras clutter a library; real episodes are rarely under 40 MB.
        if (size in 1 until 40L * 1024 * 1024) return false
        return !EXTRA_VIDEO.containsMatchIn(name)
    }

    // Compiled once: these run for every file in the library, and compiling a pattern per call
    // was most of the library's CPU at launch.
    private val EXTRA_VIDEO = Regex("""(?i)(^|[\s._\-\[(])(sample|trailer|featurette|extras?)([\s._\-\])]|$)""")
    private val SEASON_TAIL = Regex("""(?i)\b(S\d{1,2}|Season \d{1,2})\b.*$""")
    private val LEADING_TAG = Regex("""^\s*\[[^]]*]\s*""")
    private val INITIAL_DOT = Regex("""(?<=\b\p{L})\.(?=\p{L}\.)""")
    private val SPACES = Regex("""\s+""")
    private val TRAILING_YEAR = Regex("""[(\[]?\b(19|20)\d{2}\b[)\]]?\s*$""")
    private val BRACKETED = Regex("""[\[(][^\])]*[\])]""")
    private val TRAILING_PUNCT = Regex("""[\s\-–:(\[]+$""")
    private val NON_ALNUM = Regex("""[^a-z0-9]""")

    // ---- file names ------------------------------------------------------------------------

    private val EPISODE = Regex("""(?i)\bS(\d{1,2})\s?[._\- ]?\s?E(\d{1,3})\b|\b(\d{1,2})x(\d{2,3})\b""")
    private val YEAR = Regex("""(?<![0-9])(19[2-9]\d|20[0-4]\d)(?![0-9])""")
    private val JUNK = Regex(
        """(?i)\b(2160p|1080p|720p|576p|480p|4k|uhd|hdr10?\+?|hdr|dv|dovi|web[- ]?dl|web[- ]?rip|webrip|web|bluray|blu[- ]?ray|bdrip|brrip|remux|hdtv|dvdrip|x264|x265|h\.?264|h\.?265|hevc|avc|aac\d?(\.\d)?|ac3|eac3|ddp?\d?(\.\d)?|dts(-hd)?|atmos|truehd|10bit|proper|repack|extended|unrated|imax|multi|subs?)\b.*$""",
    )

    /** "Dune.Part.Two.2024.2160p.WEB-DL.mkv" -> Dune Part Two (2024); "Show.S02E05.mkv" -> Show, 2x5. */
    fun parse(fileName: String, folder: String = ""): Parsed {
        val base = fileName.substringBeforeLast('.').let { if (it.length < fileName.length - 6) fileName else it }
        val clean = tidy(base)
        EPISODE.find(clean)?.let { m ->
            val season = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toInt()
            val episode = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toInt()
            var title = stripYear(clean.substring(0, m.range.first))
            // Files inside a season pack are often just "S01E02.mkv"; the folder carries the name.
            if (title.isBlank()) title = stripYear(
                tidy(folder).let { f -> EPISODE.find(f)?.let { f.substring(0, it.range.first) } ?: f }
                    .replace(SEASON_TAIL, ""),
            )
            return Parsed(cleanTitle(title), null, season, episode)
        }
        // The last year after the start is the release year: "2001 A Space Odyssey 1968",
        // "Blade Runner 2049 2017".
        val year = YEAR.findAll(clean).lastOrNull { it.range.first > 0 }
        val title = if (year != null) clean.substring(0, year.range.first) else clean.replace(JUNK, "")
        return Parsed(cleanTitle(title), year?.value, null, null)
    }

    private fun tidy(s: String) = s
        .replace(LEADING_TAG, "")
        .replace(INITIAL_DOT, "\u0000") // keep initials like S.H.I.E.L.D together
        .replace('.', ' ').replace('_', ' ').replace("\u0000", ".")
        .replace(SPACES, " ").trim()

    private fun stripYear(s: String) = s.replace(TRAILING_YEAR, "").trim()

    private fun cleanTitle(s: String) = s
        .replace(JUNK, "")
        .replace(BRACKETED, "")
        .replace(TRAILING_PUNCT, "")
        .replace(SPACES, " ").trim()

    // ---- Cinemeta --------------------------------------------------------------------------

    private const val CINEMETA = "https://v3-cinemeta.strem.io"

    /** Finds the IMDb entry for a title, preferring one released in [year]. */
    fun cinemetaFind(type: String, title: String, year: String?): String? {
        if (title.isBlank()) return null
        val url = "$CINEMETA/catalog/$type/top/search=${enc(title)}.json"
        val metas = (getJson(url) as? JSONObject)?.optJSONArray("metas") ?: return null
        val all = (0 until metas.length()).mapNotNull { metas.optJSONObject(it) }
        val wanted = norm(title)
        val best = all.firstOrNull { year != null && it.optString("releaseInfo").startsWith(year) && norm(it.optString("name")) == wanted }
            ?: all.firstOrNull { year != null && it.optString("releaseInfo").startsWith(year) }
            ?: all.firstOrNull { norm(it.optString("name")) == wanted }
            ?: all.firstOrNull()
        return best?.optString("id")?.takeIf { it.startsWith("tt") }
    }

    fun cinemetaMeta(type: String, imdbId: String): Meta? {
        val m = (getJson("$CINEMETA/meta/$type/$imdbId.json") as? JSONObject)?.optJSONObject("meta") ?: return null
        val episodes = HashMap<Pair<Int, Int>, EpisodeMeta>()
        m.optJSONArray("videos")?.let { v ->
            for (i in 0 until v.length()) {
                val e = v.optJSONObject(i) ?: continue
                val s = e.optInt("season", -1); val n = e.optInt("episode", e.optInt("number", -1))
                if (s < 0 || n < 0) continue
                episodes[s to n] = EpisodeMeta(
                    e.optString("name").ifEmpty { e.optString("title") }.ifEmpty { null },
                    e.optString("thumbnail").ifEmpty { null },
                    e.optString("overview").ifEmpty { e.optString("description") }.ifEmpty { null },
                )
            }
        }
        fun list(k: String) = m.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        return Meta(
            imdbId = imdbId, type = type,
            name = m.optString("name"),
            year = m.optString("year").ifEmpty { m.optString("releaseInfo") }.take(4).ifEmpty { null },
            // Cinemeta's default posters are small; metahub serves the same art larger.
            poster = m.optString("poster").ifEmpty { null }?.replace("/poster/small/", "/poster/medium/"),
            background = m.optString("background").ifEmpty { null },
            logo = m.optString("logo").ifEmpty { null },
            description = m.optString("description").ifEmpty { null },
            rating = m.optString("imdbRating").ifEmpty { null },
            runtime = m.optString("runtime").ifEmpty { null },
            genres = list("genres").ifEmpty { list("genre") },
            cast = list("cast"),
            director = list("director"),
            episodes = episodes,
        )
    }

    private fun norm(s: String) = s.lowercase().replace(NON_ALNUM, "")

    // ---- the library -----------------------------------------------------------------------

    /**
     * Lists the account and matches every title to Cinemeta. Matches are remembered in
     * [cacheDir] so a refresh only looks up titles that are new to the library.
     */
    suspend fun library(apiKey: String, cacheDir: File): Library = withContext(Dispatchers.IO) {
        val files = torboxFiles(apiKey)
        val cache = MetaCache(File(cacheDir, "cinemeta-matches.json"))

        val parsed = files.map { it to parse(it.fileName, it.folder) }
        val order = files.withIndex().associate { it.value.id to it.index }
        val movieGroups = parsed.filter { !it.second.isEpisode }.groupBy { "movie|${norm(it.second.title)}|${it.second.year.orEmpty()}" }
        val showGroups = parsed.filter { it.second.isEpisode }.groupBy { "series|${norm(it.second.title)}" }

        val gate = Semaphore(6)
        suspend fun resolve(key: String, type: String, p: Parsed): Meta? = gate.withPermit {
            cache.get(key)?.let { id -> if (id.isEmpty()) return@withPermit null else return@withPermit cache.meta(type, id) }
            val id = runCatching { cinemetaFind(type, p.title, p.year) }.getOrNull()
            cache.put(key, id.orEmpty())
            id?.let { cache.meta(type, it) }
        }

        coroutineScope {
            val movies = movieGroups.map { (key, group) ->
                async {
                    val p = group.first().second
                    val meta = resolve(key, "movie", p)
                    Title(key, "movie", p.title, p.year, meta, group.map { it.first }.sortedByDescending { it.size }, emptyList(), order.getValue(group.first().first.id))
                }
            }
            val series = showGroups.map { (key, group) ->
                async {
                    val p = group.first().second
                    val meta = resolve(key, "series", p)
                    val episodes = group
                        .groupBy { requireNotNull(it.second.season) to requireNotNull(it.second.episode) }
                        .map { (se, copies) -> Episode(se.first, se.second, copies.maxBy { it.first.size }.first, meta?.episodes?.get(se)) }
                        .sortedWith(compareBy({ it.season }, { it.episode }))
                    Title(key, "series", p.title, null, meta, emptyList(), episodes, order.getValue(group.first().first.id))
                }
            }
            val result = Library(
                // Same film matched twice under different file names: show it once.
                movies.awaitAll().groupBy { it.meta?.imdbId ?: it.key }.map { (_, t) ->
                    t.first().copy(files = t.flatMap { it.files }.sortedByDescending { it.size })
                }.sortedBy { it.added },
                series.awaitAll().groupBy { it.meta?.imdbId ?: it.key }.map { (_, t) ->
                    t.first().copy(episodes = t.flatMap { it.episodes }.distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode })))
                }.sortedBy { it.added },
            )
            cache.save()
            result
        }
    }

    /** Remembers "title -> IMDb id" matches on disk, and Cinemeta records for this session. */
    private class MetaCache(private val file: File) {
        private val ids: MutableMap<String, String> = runCatching {
            val j = JSONObject(file.readText()); j.keys().asSequence().associateWith { j.getString(it) }.toMutableMap()
        }.getOrElse { HashMap() }.let { java.util.Collections.synchronizedMap(it) }

        fun get(key: String): String? = ids[key]
        fun put(key: String, id: String) { ids[key] = id }
        fun meta(type: String, id: String): Meta? = metas["$type|$id"]
            ?: runCatching { cinemetaMeta(type, id) }.getOrNull()?.also { metas["$type|$id"] = it }
        fun save() { runCatching { file.writeText(JSONObject(ids.toMap()).toString()) } }

        companion object {
            private val metas = java.util.concurrent.ConcurrentHashMap<String, Meta>()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getJson(url: String): Any? =
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
            val text = r.body.string().trim()
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        }
}
