package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * The viewer's stream badges (a JSON badge set hosted on GitHub): each enabled
 * filter is a pattern over a stream's name and details with the badge image to show when it
 * matches (4K, Dolby Vision, Atmos, Remux…). Read live from the repository, cached for a week.
 */
class StreamBadges internal constructor(private val filters: List<Filter>) {

    data class Badge(val name: String, val image: String)
    internal data class Filter(val order: Int, val pattern: Regex, val badge: Badge)

    /** Badges for one stream, in the file's group order, at most [limit]. */
    fun match(text: String, limit: Int = 8): List<Badge> =
        filters.asSequence().filter { it.pattern.containsMatchIn(text) }.map { it.badge }.distinctBy { it.image }.take(limit).toList()

    companion object {
        const val URL = "https://raw.githubusercontent.com/EDGEL00P/nuvio-badges/main/badges.json"
        private const val TTL_MS = 7 * 24 * 60 * 60 * 1000L
        val NONE = StreamBadges(emptyList())

        suspend fun load(cacheDir: File): StreamBadges = withContext(Dispatchers.IO) {
            val file = File(cacheDir, "stream-badges.json")
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < TTL_MS
            val raw = runCatching {
                if (fresh) file.readText()
                else Http.client.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                    if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
                    r.body.string()
                }.also { file.writeText(it) }
            }.getOrElse { runCatching { file.readText() }.getOrNull() } ?: return@withContext NONE
            runCatching { parse(raw) }.getOrDefault(NONE)
        }

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
