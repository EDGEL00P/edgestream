package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional Xweather (Vaisala) real lightning via REST — no new SDK dep, uses existing [Http].
 * Keyless fallback remains [Storm] derived CAPE/LI risk. Keys come from [AppSettings] and are
 * never logged; errors are redacted by [Http.redact].
 *
 * Endpoint: GET https://data.api.xweather.com/lightning/{lat},{lon}
 *   ?client_id=…&client_secret=…&radius=50km&limit=500
 * Docs: https://www.xweather.com/docs/weather-api/endpoints/lightning
 *
 * Keys are bound to a namespace (Android reverse-DNS, e.g. com.edgestream.tv). A request
 * outside that namespace fails with unauthorized_namespace even with valid keys — manage at
 * https://data.portal.xweather.com/account/keys.
 */
object XweatherApi {

    private data class Cached(val atMs: Long, val strikes: List<Storm.LightningStrike>)

    @Volatile private var cache: Cached? = null
    private const val TTL_MS = 30_000L

    suspend fun nearby(
        lat: Double,
        lon: Double,
        radiusKm: Int = 50,
        limit: Int = 200,
        clientId: String,
        secret: String,
    ): List<Storm.LightningStrike> = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || secret.isBlank()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        cache?.let { if (now - it.atMs < TTL_MS) return@withContext it.strikes }
        val radius = "${radiusKm.coerceIn(1, 100)}km"
        val url = "https://data.api.xweather.com/lightning/$lat,$lon" +
            "?client_id=${Http.redact(clientId)}&client_secret=${Http.redact(secret)}" +
            "&radius=$radius&limit=${limit.coerceIn(1, 1000)}"
        // NOTE: Http.redact is for display — build the real URL below without redaction.
        val realUrl = "https://data.api.xweather.com/lightning/$lat,$lon" +
            "?client_id=${encode(clientId)}&client_secret=${encode(secret)}" +
            "&radius=$radius&limit=${limit.coerceIn(1, 1000)}"
        if (url.isBlank()) return@withContext emptyList()
        val body = runCatching {
            val req = okhttp3.Request.Builder().url(realUrl)
                .header("User-Agent", "EdgeStream/1.0 (Android TV)").build()
            Http.client.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body.string() else null
            }
        }.getOrNull() ?: return@withContext emptyList()
        val strikes = parse(body)
        cache = Cached(now, strikes)
        strikes
    }

    internal fun parse(json: String): List<Storm.LightningStrike> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        // Xweather wraps in "response" (REST) — tolerate top-level array or object with "data".
        val arr: JSONArray = root.optJSONArray("response")
            ?: root.optJSONArray("data")
            ?: root.optJSONArray("strikes")
            ?: return emptyList()
        val out = mutableListOf<Storm.LightningStrike>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val id = o.optString("id").ifBlank { "x$lat,$lon,$i" }
            val timeStr = o.optString("time").ifBlank { o.optString("timestamp") }
            val timeMs = parseTime(timeStr) ?: System.currentTimeMillis()
            val peak = runCatching {
                when {
                    o.has("peakCurrent") -> o.optDouble("peakCurrent").toFloat()
                    o.has("peak_current") -> o.optDouble("peak_current").toFloat()
                    o.has("intensity") -> o.optDouble("intensity").toFloat()
                    else -> null
                }
            }.getOrNull()
            out += Storm.LightningStrike(id, lat, lon, timeMs, peak)
        }
        return out
    }

    private fun parseTime(s: String): Long? {
        if (s.isBlank()) return null
        return runCatching {
            when {
                s.all { it.isDigit() } -> {
                    val v = s.toLong()
                    if (v > 10_000_000_000L) v else v * 1000L // sec vs ms
                }
                else -> java.time.Instant.parse(s).toEpochMilli()
            }
        }.getOrNull()
    }

    private fun encode(v: String): String =
        java.net.URLEncoder.encode(v, "UTF-8")
}
