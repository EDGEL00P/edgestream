package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * League tables from ESPN's public standings (no key). Columns suit the sport: points tables
 * (football, rugby, AFL) show GP W D L DIFF PTS; hockey adds OTL; US leagues show W L PCT GB STRK.
 */
object Standings {
    data class Row(val rank: Int, val abbr: String, val name: String, val logo: String?, val values: List<String>)
    data class Table(val group: String, val columns: List<String>, val rows: List<Row>)

    /** Header → the stat names different feeds use for it. */
    private val POINTS = listOf(
        "GP" to listOf("gamesPlayed"), "W" to listOf("wins", "gamesWon"), "D" to listOf("ties", "gamesDrawn"),
        "L" to listOf("losses", "gamesLost"), "DIFF" to listOf("pointDifferential", "pointsDifference", "differential"),
        "PTS" to listOf("points"),
    )
    private val HOCKEY = listOf(
        "GP" to listOf("gamesPlayed"), "W" to listOf("wins"), "L" to listOf("losses"),
        "OTL" to listOf("otLosses", "OTLosses"), "PTS" to listOf("points"),
    )
    private val US = listOf(
        "W" to listOf("wins"), "L" to listOf("losses"), "T" to listOf("ties"), "PCT" to listOf("winPercent"),
        "GB" to listOf("gamesBehind"), "STRK" to listOf("streak"),
    )

    private class Entry(val abbr: String, val name: String, val logo: String?, val stats: Map<String, String>, val order: Int)

    fun parse(json: String, league: Sports.League): List<Table> = try {
        val root = JSONObject(json)
        val groups = root.optJSONArray("children")?.let { c -> (0 until c.length()).map { c.getJSONObject(it) } } ?: listOf(root)
        val parsed = groups.map { g -> g.optString("name") to entries(g.optJSONObject("standings")?.optJSONArray("entries")) }
        val all = parsed.flatMap { it.second }
        val spec = when {
            league.path.startsWith("hockey/") -> HOCKEY
            all.any { "points" in it.stats } -> POINTS
            else -> US
        }
        // A column only appears when the feed actually reports it (NBA has no ties).
        val cols = spec.filter { (_, names) -> all.any { e -> names.any { it in e.stats } } }
        parsed.filter { it.second.isNotEmpty() }.map { (name, list) ->
            val sorted = list.sortedWith(compareBy({ it.stats["rank"]?.toIntOrNull() ?: it.stats["playoffSeed"]?.toIntOrNull() ?: Int.MAX_VALUE }, { it.order }))
            Table(
                name.ifBlank { league.name }, cols.map { it.first },
                sorted.mapIndexed { i, e -> Row(i + 1, e.abbr, e.name, e.logo, cols.map { (_, names) -> names.firstNotNullOfOrNull { e.stats[it] } ?: "–" }) },
            )
        }
    } catch (_: JSONException) { emptyList() }

    private fun entries(a: JSONArray?): List<Entry> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val o = a!!.getJSONObject(i)
        val team = o.optJSONObject("team") ?: return@mapNotNull null
        val stats = o.optJSONArray("stats")?.let { s ->
            (0 until s.length()).map { s.getJSONObject(it) }.filter { it.has("name") }.associate { it.getString("name") to it.optString("displayValue") }
        }.orEmpty()
        Entry(
            team.optString("abbreviation"), team.optString("displayName", team.optString("name")),
            team.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.takeIf(String::isNotBlank), stats, i,
        )
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Table>>>()
    private const val TTL_MS = 30 * 60_000L

    suspend fun load(league: Sports.League): List<Table> = withContext(Dispatchers.IO) {
        cache[league.id]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.let { return@withContext it.second }
        try {
            val tables = Http.open("https://site.api.espn.com/apis/v2/sports/${league.path}/standings") { parse(it.reader().readText(), league) }
            if (tables.isNotEmpty()) cache[league.id] = System.currentTimeMillis() to tables
            tables
        } catch (e: IOException) {
            android.util.Log.w("EdgeStandings", "standings unavailable for ${league.id}", e)
            cache[league.id]?.second.orEmpty()
        }
    }
}
