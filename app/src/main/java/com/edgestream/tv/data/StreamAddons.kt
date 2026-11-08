package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Stream add-ons (catalog/stream add-on manifests), configured by the
 * user with their TorBox key. Given an IMDb id, each addon answers with ready-to-play links that
 * TorBox serves for a torrent or Usenet release of that film or episode, the same way any add-on client
 * find "debrid versions" of a title.
 *
 * Protocol: `GET {base}/stream/{movie|series}/{id}.json` → `{ "streams": [ { name, title |
 * description, url, behaviorHints: { filename, videoSize, proxyHeaders: { request } } } ] }`,
 * where a series id is `tt123:season:episode`. Streams with only an `infoHash` are peer-to-peer
 * and skipped: EdgeStream plays debrid links only.
 */
object StreamAddons {

    data class Manifest(val url: String, val name: String, val types: List<String>)

    data class Stream(
        val addon: String,
        /** First line as the addon labels it, e.g. "[TB+] Comet 2160p". */
        val name: String,
        /** Release details: file name, size, seeders, languages. */
        val details: String,
        val url: String,
        val headers: Map<String, String>,
        /** true = ready to play now; false = TorBox must download it first; null = not stated. */
        val cached: Boolean?,
        val usenet: Boolean,
        /** Vertical resolution, 0 when unknown. */
        val resolution: Int,
        val sizeBytes: Long?,
        val hdr: Boolean,
        /** Position in the addon's own (already sorted) list, used to break ties. */
        val rank: Int,
    ) {
        val score: Int get() {
            var s = when (cached) { true -> 1_000; null -> 400; false -> 0 }
            s += when {
                resolution >= 2160 -> 300
                resolution >= 1080 -> 280
                resolution >= 720 -> 150
                resolution > 0 -> 50
                else -> 100
            }
            // Very large remuxes are the ones that stutter on a weak line; nudge them down.
            if ((sizeBytes ?: 0) > 40L * 1024 * 1024 * 1024) s -= 120
            return s - rank
        }

        /** [url] with any headers the addon asked for, in the "url|Header=value" form the player reads. */
        val playUrl: String get() =
            if (headers.isEmpty()) url
            else url + "|" + headers.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }

        /** Safe to start without asking: a cached link, or one the addon doesn't mark either way. */
        val autoPlayable: Boolean get() = cached != false
    }

    /** Accepts a manifest URL or an addon page/base URL, with or without stremio://. */
    fun base(url: String): String = url.trim()
        .replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
        .substringBefore('?').removeSuffix("/")
        .removeSuffix("/manifest.json").removeSuffix("/configure").removeSuffix("/")

    suspend fun manifest(url: String): Manifest = withContext(Dispatchers.IO) {
        val b = base(url)
        val m = getJson("$b/manifest.json")
        val types = m.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val resources = m.optJSONArray("resources")?.let { a ->
            (0 until a.length()).map { i -> a.optJSONObject(i)?.optString("name") ?: a.optString(i) }
        }.orEmpty()
        if ("stream" !in resources) throw IOException("This addon doesn't provide streams")
        Manifest(b, m.optString("name").ifBlank { b.substringAfter("://").substringBefore('/') }, types)
    }

    /**
     * Cached, ready-to-play streams from every addon at once, best first. Anything TorBox would
     * have to download first, or that the addon doesn't mark as cached, is left out. A slow or
     * broken addon is dropped, not waited on.
     */
    suspend fun streams(addons: List<String>, type: String, id: String): List<Stream> = coroutineScope {
        addons.map { url ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(20_000) { runCatching { fetch(base(url), type, id) }.getOrNull() }.orEmpty()
            }
        }.awaitAll().flatten().filter { it.cached == true }.sortedByDescending { it.score }
    }

    private fun fetch(base: String, type: String, id: String): List<Stream> {
        val json = getJson("$base/stream/$type/$id.json")
        val arr = json.optJSONArray("streams") ?: return emptyList()
        val label = base.substringAfter("://").substringBefore('/')
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val name = o.optString("name").trim()
            val details = (o.optString("description").ifBlank { o.optString("title") }).trim()
            val hints = o.optJSONObject("behaviorHints")
            val text = "$name\n$details\n${hints?.optString("filename").orEmpty()}"
            val headers = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
                h.keys().asSequence().associateWith { h.optString(it) }
            }.orEmpty()
            Stream(
                addon = label, name = name.ifBlank { label }, details = details, url = url, headers = headers,
                cached = cachedFlag(text), usenet = USENET.containsMatchIn(text),
                resolution = resolution(text),
                sizeBytes = hints?.optLong("videoSize")?.takeIf { it > 0 } ?: size(text),
                hdr = HDR.containsMatchIn(text), rank = i,
            )
        }
    }

    // Addons disagree on labels, so read the common ones: Torrentio "[TB+]" vs "[TB download]",
    // Comet/AIOStreams "⚡" vs "⏳", and words like cached / instant / uncached.
    private val CACHED = Regex("""\[(TB|DL|RD|PM|AD)\+]|⚡|\binstant\b|(?<!un)cached""", RegexOption.IGNORE_CASE)
    private val UNCACHED = Regex("""\[(TB|DL|RD|PM|AD) download]|⏳|\buncached\b|\bdownload(ing)?\b""", RegexOption.IGNORE_CASE)
    private val USENET = Regex("""usenet|\bnzb\b|📰""", RegexOption.IGNORE_CASE)
    private val HDR = Regex("""\bHDR(10\+?)?\b|\bDV\b|dolby ?vision""", RegexOption.IGNORE_CASE)

    internal fun cachedFlag(text: String): Boolean? = when {
        UNCACHED.containsMatchIn(text) && !CACHED.containsMatchIn(text) -> false
        CACHED.containsMatchIn(text) -> true
        else -> null
    }

    fun resolution(text: String): Int = when {
        Regex("""\b(2160p?|4k|uhd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 2160
        Regex("""\b1440p\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 1440
        Regex("""\b(1080p?|fhd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 1080
        Regex("""\b720p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 720
        Regex("""\b(480p|576p|sd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 480
        else -> 0
    }

    internal fun size(text: String): Long? {
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|GB|MB)\b""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].uppercase()) { "TB" -> 1L shl 40; "GB" -> 1L shl 30; else -> 1L shl 20 }
        return (n * mult).toLong()
    }

    private fun getJson(url: String): JSONObject =
        Http.client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
            JSONObject(r.body.string())
        }
}
