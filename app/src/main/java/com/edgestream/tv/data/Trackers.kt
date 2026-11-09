package com.edgestream.tv.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Real, keyless warning and tracker feeds:
 *  - BOM Queensland warnings RSS (official warnings for the current location in QLD)
 *  - US NWS active alerts (public domain, country=US only)
 *  - GDACS active tropical cyclones (UN/European Commission)
 *
 * Lightning is a derived risk from Open-Meteo CAPE/LI, not a strike detector; the UI labels it
 * that way.
 */
object Trackers {
    data class Warning(val title: String, val detail: String, val severity: String, val source: String)
    data class Cyclone(
        val name: String, val alert: String, val windKph: Int, val km: Int,
        val bearing: String, val from: String, val to: String,
    )
    data class Lightning(val level: String, val cape: Double, val liftedIndex: Double)

    private const val UA = "EdgeStream/1.0 (Android TV; weather radar)"

    suspend fun warnings(country: String?, lat: Double, lon: Double): List<Warning> = withContext(Dispatchers.IO) {
        when (country?.uppercase()) {
            "US", "USA" -> nws(lat, lon)
            "AU", "AUS", "AUSTRALIA" -> if (inQueensland(lat, lon)) bomQld() else emptyList()
            else -> emptyList()
        }
    }

    suspend fun cyclones(lat: Double, lon: Double): List<Cyclone> = withContext(Dispatchers.IO) {
        runCatching {
            val from = LocalDate.now().minusDays(60).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val to = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val json = getJson("https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?eventlist=TC&fromDate=$from&toDate=$to")
            val features = json?.optJSONArray("features") ?: return@runCatching emptyList()
            (0 until features.length()).mapNotNull { i ->
                val f = features.optJSONObject(i) ?: return@mapNotNull null
                val p = f.optJSONObject("properties") ?: return@mapNotNull null
                if (p.optString("iscurrent") != "true") return@mapNotNull null
                val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
                val cLon = c.optDouble(0)
                val cLat = c.optDouble(1)
                val km = haversineKm(lat, lon, cLat, cLon)
                if (km > 3000) return@mapNotNull null
                Cyclone(
                    name = p.optString("eventname").ifBlank { p.optString("name") },
                    alert = p.optString("alertlevel").ifBlank { "Unknown" },
                    windKph = p.optJSONObject("severitydata")?.optDouble("severity", 0.0)?.roundToInt() ?: 0,
                    km = km.roundToInt(),
                    bearing = compass(cLat, cLon, lat, lon),
                    from = p.optString("fromdate").take(10),
                    to = p.optString("todate").take(10),
                )
            }.sortedBy { it.km }.take(3)
        }.getOrDefault(emptyList())
    }

    fun lightning(detail: PublicApis.WeatherDetail?): Lightning {
        val cape = detail?.capeJkg ?: Double.NaN
        val li = detail?.liftedIndex ?: Double.NaN
        val level = when {
            cape.isNaN() && li.isNaN() -> "Unknown"
            cape >= 2500 -> "Severe"
            cape >= 1000 || li <= -4 -> "High"
            cape >= 300 || li <= -2 -> "Moderate"
            else -> "Low"
        }
        return Lightning(level, cape, li)
    }

    private fun inQueensland(lat: Double, lon: Double) =
        lat in -29.5..-9.0 && lon in 138.0..154.5

    private fun nws(lat: Double, lon: Double): List<Warning> = runCatching {
        val body = get(
            "https://api.weather.gov/alerts/active?point=$lat,$lon",
            mapOf("Accept" to "application/geo+json", "User-Agent" to "EdgeStream/1.0 (edgestream.tv)"),
        ) ?: return@runCatching emptyList()
        val features = JSONObject(body).optJSONArray("features") ?: return@runCatching emptyList()
        (0 until features.length()).mapNotNull { i ->
            val p = features.optJSONObject(i)?.optJSONObject("properties") ?: return@mapNotNull null
            val event = p.optString("event")
            val severity = p.optString("severity")
            if (severity in setOf("Minor", "Unknown")) return@mapNotNull null
            Warning(
                title = event.ifBlank { p.optString("headline") },
                detail = clean(p.optString("headline").ifBlank { p.optString("description") }).take(220),
                severity = severity.ifBlank { "Warning" },
                source = "US NWS",
            )
        }.distinctBy { it.title }.take(6)
    }.getOrDefault(emptyList())

    private fun bomQld(): List<Warning> = runCatching {
        val body = get("https://www.bom.gov.au/fwo/IDZ00056.warnings_qld.xml") ?: return@runCatching emptyList()
        Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(body).mapNotNull { m ->
            val item = m.groupValues[1]
            val title = tag(item, "title")
            if (title.isBlank()) return@mapNotNull null
            val desc = clean(tag(item, "description"))
            val severity = when {
                title.contains("major", true) || desc.contains("major", true) -> "Major"
                title.contains("severe", true) || desc.contains("severe", true) -> "Severe"
                title.contains("moderate", true) || desc.contains("moderate", true) -> "Moderate"
                else -> "Warning"
            }
            Warning(title, desc.take(220), severity, "BOM QLD")
        }.distinctBy { it.title }.take(6).toList()
    }.getOrDefault(emptyList())

    private fun tag(item: String, tag: String): String =
        Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)
            ?.removePrefix("<![CDATA[")?.removeSuffix("]]>")?.trim().orEmpty()

    private fun clean(text: String): String = text
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        Http.client.newCall(builder.build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body.string()
        }
    }.getOrNull()

    private fun getJson(url: String): JSONObject? = get(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun compass(lat: Double, lon: Double, fromLat: Double, fromLon: Double): String {
        val dLat = lat - fromLat
        val dLon = lon - fromLon
        val deg = Math.toDegrees(atan2(dLon, dLat))
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((deg % 360 + 360) % 360) / 45).toInt().coerceIn(0, 7)]
    }
}
