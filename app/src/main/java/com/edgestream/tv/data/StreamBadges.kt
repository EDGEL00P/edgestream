package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The viewer's stream badges: a JSON badge set hosted on GitHub with one regex per badge
 * (4K, Dolby Vision, Atmos, Remux…). Read live from the repository and cached locally; the
 * cache only ever holds a validated file, written atomically, and a bad update never
 * displaces the last good set.
 */
class StreamBadges internal constructor(private val filters: List<Filter>) {

    data class Badge(val name: String, val image: String)
    internal data class Filter(val order: Int, val pattern: Regex, val badge: Badge)

    internal fun isEmpty() = filters.isEmpty()

    /** Badges for one stream, in the file's group order, at most [limit]. */
    fun match(text: String, limit: Int = 8): List<Badge> =
        filters.asSequence().filter { it.pattern.containsMatchIn(text) }.map { it.badge }.distinctBy { it.image }.take(limit).toList()

    companion object {
        const val URL = "https://raw.githubusercontent.com/leonevz/Elite-Badges/main/badges.json"
        private const val TTL_MS = 24 * 60 * 60 * 1000L
        /** Tagged by source, so switching badge sets never serves the old set from cache. */
        internal val CACHE_FILE = "badges-" + Integer.toHexString(URL.hashCode()) + ".json"
        val NONE = StreamBadges(emptyList())

        /** Real playback metadata -> text the release patterns can match. */
        fun describe(videoInfo: String, videoHeight: Int, audio: List<String>): String = buildList {
            add(videoInfo)
            when {
                videoHeight >= 2160 -> add("2160p 4K UHD")
                videoHeight >= 1080 -> add("1080p FHD")
                videoHeight >= 720 -> add("720p")
                videoHeight > 0 -> add("480p SD")
            }
            addAll(audio)
        }.joinToString(" ")

        suspend fun load(cacheDir: File): StreamBadges = withContext(Dispatchers.IO) {
            val file = File(cacheDir, CACHE_FILE)
            val cachedRaw = runCatching { file.readText() }.getOrNull()
            val fresh = cachedRaw?.let(::parseOrNull) != null &&
                System.currentTimeMillis() - file.lastModified() < TTL_MS
            val downloaded = if (fresh) null else runCatching {
                Http.client.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                    if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
                    r.body.string()
                }
            }.getOrNull()

            val (badges, commit) = resolve(cachedRaw, downloaded)
            if (commit != null) {
                // Unique temp: the player and the versions sheet can load at the same time.
                val tmp = File.createTempFile(CACHE_FILE, ".tmp", cacheDir)
                runCatching {
                    tmp.writeText(commit)
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    file.setLastModified(System.currentTimeMillis())
                }.onFailure { tmp.delete() }
            }
            badges
        }

        /** Pure: which set to use, and which raw text (if any) is safe to commit to disk. */
        internal fun resolve(cachedRaw: String?, downloadedRaw: String?): Pair<StreamBadges, String?> {
            val cached = cachedRaw?.let(::parseOrNull)
            val updated = downloadedRaw?.let(::parseOrNull)
            val commit = if (downloadedRaw != null && updated != null && downloadedRaw != cachedRaw) downloadedRaw else null
            return (updated ?: cached ?: NONE) to commit
        }

        private fun parseOrNull(raw: String): StreamBadges? =
            runCatching { parse(raw) }.getOrNull()?.takeIf { !it.isEmpty() }

        internal fun parse(raw: String): StreamBadges {
            val root = JSONObject(raw)
            val groups = root.optJSONArray("groups")
            val order = HashMap<String, Int>()
            if (groups != null) for (i in 0 until groups.length()) groups.optJSONObject(i)?.optString("id")?.let { order[it] = i }
            val list = root.optJSONArray("filters") ?: return NONE
            val filters = (0 until list.length()).mapNotNull { i ->
                val f = list.optJSONObject(i) ?: return@mapNotNull null
                if (!f.optBoolean("isEnabled", true) || f.optString("type", "filter") != "filter") return@mapNotNull null
                val image = f.optString("imageURL").ifBlank { return@mapNotNull null }
                // One malformed pattern skips that badge, never the whole set.
                val pattern = runCatching { Regex(f.optString("pattern")) }.getOrNull() ?: return@mapNotNull null
                Filter(order[f.optString("groupId")] ?: Int.MAX_VALUE, pattern, Badge(f.optString("name"), image))
            }.sortedBy { it.order }
            return StreamBadges(filters)
        }
    }
}
