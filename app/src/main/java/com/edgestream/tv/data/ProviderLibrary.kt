package com.edgestream.tv.data

import java.io.File
import kotlin.math.abs

/**
 * The IPTV provider's films and series as a play source, never a library to browse: On Demand
 * shows the universal catalogue, and pressing Play looks the title up here by name and year.
 *
 * One small index file per source and kind ("movie", "series", "episode"), one title per line:
 * `key \t year \t ref`, where ref is a movie's stream URL, an Xtream series id, or an episode's
 * stream URL (episode keys carry "|season|episode"). Files older than [TTL_MS] are rebuilt.
 */
class ProviderLibrary(private val dir: File) {

    data class Hit(val key: String, val year: String?, val ref: String)

    private val loaded = HashMap<String, Pair<Long, Map<String, List<Hit>>>>()

    private fun file(sourceId: Long, kind: String) = File(dir, "provider_${sourceId}_$kind.tsv")

    fun isFresh(sourceId: Long, kind: String): Boolean =
        file(sourceId, kind).let { it.exists() && System.currentTimeMillis() - it.lastModified() < TTL_MS }

    /** Replaces one index atomically: a lookup never reads half a file. */
    fun write(sourceId: Long, kind: String, hits: Sequence<Hit>): Int {
        val target = file(sourceId, kind)
        val tmp = File(dir, "${target.name}.tmp")
        var n = 0
        tmp.bufferedWriter().use { w ->
            hits.forEach { h ->
                if (h.key.isEmpty() || '\t' in h.ref || '\n' in h.ref) return@forEach
                w.append(h.key).append('\t').append(h.year.orEmpty()).append('\t').append(h.ref).append('\n'); n++
            }
        }
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        synchronized(loaded) { loaded.remove(target.path) }
        return n
    }

    fun forget(sourceId: Long) {
        listOf("movie", "series", "episode").forEach { k -> file(sourceId, k).let { synchronized(loaded) { loaded.remove(it.path) }; it.delete() } }
    }

    /** The best entry for [title] in one index: same year first, then a year either side. */
    fun find(sourceId: Long, kind: String, title: String, year: String?, suffix: String = ""): Hit? {
        val hits = index(sourceId, kind)[key(title) + suffix] ?: return null
        if (year == null) return hits.first()
        hits.firstOrNull { it.year == year }?.let { return it }
        val target = year.toIntOrNull()
        return hits.firstOrNull { h ->
            val y = h.year?.toIntOrNull() ?: return@firstOrNull true
            target == null || abs(y - target) <= 1
        }
    }

    private fun index(sourceId: Long, kind: String): Map<String, List<Hit>> {
        val f = file(sourceId, kind)
        if (!f.exists()) return emptyMap()
        synchronized(loaded) { loaded[f.path]?.takeIf { it.first == f.lastModified() }?.let { return it.second } }
        val map = HashMap<String, MutableList<Hit>>()
        f.forEachLine { line ->
            val p = line.split('\t', limit = 3)
            if (p.size == 3) map.getOrPut(p[0]) { ArrayList(1) } += Hit(p[0], p[1].ifEmpty { null }, p[2])
        }
        synchronized(loaded) { loaded[f.path] = f.lastModified() to map }
        return map
    }

    companion object {
        const val TTL_MS = 24 * 60 * 60 * 1000L
        private val TRAILING_YEAR = Regex("""[(\[]?\b(19|20)\d{2}\b[)\]]?\s*$""")
        private val EPISODE = Regex("""^(.*?)[\s._-]*\bS(\d{1,2})\s*E(\d{1,3})\b""", RegexOption.IGNORE_CASE)

        /** Title key: the provider's tidied name, lower-case letters and digits, no trailing year. */
        fun key(title: String): String =
            TRAILING_YEAR.replace(NameCleaner.tidyTitle(title).lowercase(), "").replace("&", "and").filter { it.isLetterOrDigit() }

        fun episodeSuffix(season: Int, episode: Int) = "|$season|$episode"

        /** "Fire Country S05E01 Hot Hot Heat" → (Fire Country, 5, 1): how M3U playlists list episodes. */
        fun episodeOf(name: String): Triple<String, Int, Int>? =
            EPISODE.find(NameCleaner.tidyTitle(name))?.let { m -> Triple(m.groupValues[1].trim(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }

        fun yearOf(name: String): String? = Regex("""\b(19|20)\d{2}\b""").findAll(name).lastOrNull()?.value
    }
}
