package com.edgestream.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Player prop leans. Lines come from ESPN's public odds feed (whichever book it quotes); each
 * player's projection is a recency-weighted average of their nflverse weekly stats (CC-BY 4.0),
 * joined on nflverse's ESPN id. There is no free archive of past prop lines, so these leans are
 * unvalidated: each one is locked and graded live (W/L/P + line movement) instead.
 */
object NflProps {
    private const val NFLVERSE = "https://github.com/nflverse/nflverse-data/releases/download"
    /** The same files cut to the columns read here (tools/sports/build_nfl.py, rebuilt every 6 h). */
    private const val SLIM = "https://github.com/EDGEL00P/edgestream/releases/download/sports"
    private val PLAYERS_URLS = listOf("$SLIM/nfl-players.csv", "$NFLVERSE/players/players.csv")
    private fun statsUrls(season: Int) = listOf("$SLIM/nfl-stats-$season.csv", "$NFLVERSE/stats_player/stats_player_week_$season.csv")
    private const val LOG_FILE = "nfl-props.json"
    private const val HOUR = 3_600_000L

    /** Recency weight per game; ~70% of the projection comes from the last four games. */
    const val ALPHA = 0.25
    const val MIN_GAMES = 4
    /** A lean needs the projection to put at least this much probability on one side. */
    const val LEAN = 0.60
    /**
     * The book's line is a strong prior (it prices news a stats average can't see), so the
     * projection only moves the estimate halfway off the line.
     */
    const val SHRINK = 0.5
    /**
     * A projection this far off the line usually means a role change or injury the market already
     * knows about (a returning starter), not value: skip it.
     */
    const val ROLE_CHANGE = 0.35

    enum class Market(val espnType: String, val label: String, val column: String, val minSd: Double) {
        PASS_YDS("Total Passing Yards (incl. overtime)", "Pass Yds", "passing_yards", 25.0),
        RUSH_YDS("Total Rushing Yards (incl. overtime)", "Rush Yds", "rushing_yards", 10.0),
        REC_YDS("Total Receiving Yards (incl. overtime)", "Rec Yds", "receiving_yards", 10.0),
        RECEPTIONS("Total Receptions (incl. overtime)", "Receptions", "receptions", 1.2),
    }

    /** [team] is nflverse's abbreviation for the side the player last played for. */
    data class Player(val gsis: String, val name: String, val position: String, val team: String = "")
    data class Projection(val mean: Double, val sd: Double, val games: Int)

    /** Every player's projection per market, plus the id join and the stats needed to grade. */
    class Book(
        val byEspn: Map<String, Player>,
        val projections: Map<Pair<String, Market>, Projection>,
        /** (nflverse game id, gsis) → that game's stat line, for grading. */
        val results: Map<Pair<String, String>, Map<Market, Double>>,
    )

    data class PropLine(val espnAthlete: String, val market: Market, val line: Double, val open: Double?)

    data class Lean(
        val espnGame: String,
        val espnAthlete: String,
        val nflGameId: String,
        val player: Player,
        val market: Market,
        val line: Double,
        val open: Double?,
        val projection: Projection,
        val overProb: Double,
    ) {
        val over: Boolean get() = overProb >= 0.5
        val confidence: Double get() = if (over) overProb else 1 - overProb
        val selection: String get() = "${player.name} ${if (over) "Over" else "Under"} ${NflPicks.trim(line)} ${market.label}"
    }

    data class LogSummary(val record: NflModel.Record, val avgMove: Double?, val open: Int)

    // ---- loading ------------------------------------------------------------------------------

    suspend fun loadBook(dir: File, season: Int, now: Long = System.currentTimeMillis()): Book? = withContext(Dispatchers.IO) {
        val players = cached(dir, "nflverse-players.csv", PLAYERS_URLS, 24 * HOUR, now) ?: return@withContext null
        val current = cached(dir, "nflverse-stats-$season.csv", statsUrls(season), 6 * HOUR, now)
        val last = cached(dir, "nflverse-stats-${season - 1}.csv", statsUrls(season - 1), 24 * HOUR, now)
        withContext(NflPicks.background) { buildBook(players, listOfNotNull(last, current)) }
    }

    /** The first of [urls] that answers wins; the cache covers all of them failing. */
    private suspend fun cached(dir: File, name: String, urls: List<String>, staleMs: Long, now: Long): String? {
        val file = File(dir, name)
        if (file.isFile && now - file.lastModified() < staleMs) return file.readText()
        val fresh = urls.firstNotNullOfOrNull { url ->
            try {
                Http.openSuspend(url) { it.readBytes().decodeToString() }.takeIf { it.length > 100 }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (fresh != null) {
            NflPicks.writeAtomic(file, fresh)
            return fresh
        }
        return file.takeIf(File::isFile)?.readText()
    }

    internal fun buildBook(playersCsv: String, statsCsvs: List<String>): Book {
        val byEspn = HashMap<String, Player>()
        csvRows(playersCsv) { col ->
            val espn = col("espn_id")?.substringBefore('.') ?: return@csvRows
            val gsis = col("gsis_id") ?: return@csvRows
            byEspn[espn] = Player(gsis, col("display_name") ?: return@csvRows, col("position").orEmpty(), col("latest_team").orEmpty())
        }
        // Chronological per player: (season, week) sort key → stat line.
        val history = HashMap<String, MutableList<Pair<Int, Map<Market, Double>>>>()
        val results = HashMap<Pair<String, String>, Map<Market, Double>>()
        val lastTeam = HashMap<String, Pair<Int, String>>()
        for (csv in statsCsvs) csvRows(csv) { col ->
            val gsis = col("player_id") ?: return@csvRows
            val season = col("season")?.toIntOrNull() ?: return@csvRows
            val week = col("week")?.toIntOrNull() ?: return@csvRows
            val line = Market.entries.associateWith { m -> col(m.column)?.toDoubleOrNull() ?: 0.0 }
            history.getOrPut(gsis) { ArrayList() } += (season * 100 + week) to line
            col("team")?.let { team ->
                if ((lastTeam[gsis]?.first ?: -1) < season * 100 + week) lastTeam[gsis] = (season * 100 + week) to team
            }
            col("game_id")?.let { results[it to gsis] = line }
        }
        val projections = HashMap<Pair<String, Market>, Projection>()
        for ((gsis, games) in history) {
            games.sortBy { it.first }
            for (m in Market.entries) {
                val values = games.map { it.second.getValue(m) }
                project(values, m)?.let { projections[gsis to m] = it }
            }
        }
        // A traded or re-signed player's current side comes from his latest game, not the roster file.
        val players = byEspn.mapValues { (_, p) -> lastTeam[p.gsis]?.let { p.copy(team = it.second) } ?: p }
        return Book(players, projections, results)
    }

    /** Recency-weighted mean and spread of [values] (oldest first); null when too few games. */
    internal fun project(values: List<Double>, market: Market): Projection? {
        // A market the player never records (a WR's passing yards) isn't one to project.
        if (values.size < MIN_GAMES || values.count { it > 0 } < MIN_GAMES) return null
        var mean = values.first()
        var variance = 0.0
        for (v in values.drop(1)) {
            val d = v - mean
            mean += ALPHA * d
            variance = (1 - ALPHA) * (variance + ALPHA * d * d)
        }
        return Projection(mean, max(sqrt(variance), max(market.minSd, 0.25 * mean)), values.size)
    }

    private inline fun csvRows(csv: String, row: ((String) -> String?) -> Unit) {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return
        val header = NflModel.splitCsv(lines.next()).withIndex().associate { (i, h) -> h.trim() to i }
        while (lines.hasNext()) {
            val f = NflModel.splitCsv(lines.next())
            row { name -> header[name]?.let(f::getOrNull)?.trim()?.ifEmpty { null } }
        }
    }

    // ---- ESPN prop lines ----------------------------------------------------------------------

    private data class Lines(val at: Long, val lines: List<PropLine>)
    private val lineCache = ConcurrentHashMap<String, Lines>()

    /** The O/U player lines ESPN quotes for one game; cached 30 min. Empty when none are posted. */
    suspend fun linesFor(espnGame: String, now: Long = System.currentTimeMillis()): List<PropLine> {
        lineCache[espnGame]?.takeIf { now - it.at < 30 * 60_000L }?.let { return it.lines }
        val lines = withContext(Dispatchers.IO) {
            runCatching {
                val base = "https://sports.core.api.espn.com/v2/sports/football/leagues/nfl/events/$espnGame/competitions/$espnGame/odds"
                val providers = Http.open(base) { JSONObject(it.readBytes().decodeToString()) }.optJSONArray("items") ?: JSONArray()
                // The pre-game book, not its in-play twin.
                val provider = (0 until providers.length()).map { providers.getJSONObject(it).optJSONObject("provider") }
                    .firstOrNull { it != null && !it.optString("name").contains("Live", ignoreCase = true) }
                    ?.optString("id")?.ifBlank { null } ?: return@runCatching emptyList()
                val out = ArrayList<PropLine>()
                var page = 1
                do {
                    val j = Http.open("$base/$provider/propBets?limit=1000&page=$page") { JSONObject(it.readBytes().decodeToString()) }
                    out += parseLines(j)
                    page++
                } while (page <= j.optInt("pageCount", 1) && page <= 4)
                out.distinctBy { it.espnAthlete to it.market }
            }.getOrDefault(emptyList())
        }
        lineCache[espnGame] = Lines(now, lines)
        return lines
    }

    internal fun parseLines(page: JSONObject): List<PropLine> {
        val items = page.optJSONArray("items") ?: return emptyList()
        val markets = Market.entries.associateBy { it.espnType }
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val market = markets[o.optJSONObject("type")?.optString("name")] ?: return@mapNotNull null
            val ref = o.optJSONObject("athlete")?.optString("\$ref") ?: return@mapNotNull null
            val athlete = Regex("""/athletes/(\d+)""").find(ref)?.groupValues?.get(1) ?: return@mapNotNull null
            val line = o.optJSONObject("current")?.optJSONObject("target")?.optDouble("value", Double.NaN)
                ?.takeIf { !it.isNaN() } ?: return@mapNotNull null
            val open = o.optJSONObject("open")?.optJSONObject("target")?.optDouble("value", Double.NaN)?.takeIf { !it.isNaN() }
            PropLine(athlete, market, line, open)
        }
    }

    /** Leans for one game: players whose projection puts [LEAN]+ on one side of the line. */
    fun leans(book: Book, espnGame: String, nflGameId: String, lines: List<PropLine>): List<Lean> = lines.mapNotNull { l ->
        val player = book.byEspn[l.espnAthlete] ?: return@mapNotNull null
        val p = book.projections[player.gsis to l.market] ?: return@mapNotNull null
        if (abs(p.mean - l.line) > ROLE_CHANGE * max(l.line, 1.0)) return@mapNotNull null
        val estimate = l.line + SHRINK * (p.mean - l.line)
        val over = 1 - NflModel.phi((l.line - estimate) / p.sd)
        Lean(espnGame, l.espnAthlete, nflGameId, player, l.market, l.line, l.open, p, over).takeIf { it.confidence >= LEAN }
    }.sortedByDescending { it.confidence }

    // ---- live grading -------------------------------------------------------------------------

    data class Locked(
        val espnGame: String,
        val nflGameId: String,
        val espnAthlete: String,
        val gsis: String,
        val market: Market,
        val over: Boolean,
        val line: Double,
        /** The latest line seen before kickoff: the close, once the game starts. */
        val lastLine: Double,
        val at: Long,
    )

    class PropLog(private val file: File) {
        fun read(): List<Locked> = runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val market = runCatching { Market.valueOf(o.getString("m")) }.getOrNull() ?: return@mapNotNull null
                Locked(o.getString("g"), o.getString("n"), o.getString("a"), o.getString("p"), market, o.getBoolean("o"), o.getDouble("l"), o.optDouble("c", o.getDouble("l")), o.optLong("at"))
            }
        }.getOrDefault(emptyList())

        /** Locks new leans at their current line and moves each open pick's closing line along. */
        fun update(leans: List<Lean>, lines: Map<String, List<PropLine>>, now: Long) {
            val existing = read()
            val key = { g: String, p: String, m: Market -> Triple(g, p, m) }
            val known = existing.associateBy { key(it.espnGame, it.gsis, it.market) }
            // [lines] only holds games still before kickoff, so a started game's last line is its close.
            val updated = existing.map { l ->
                val current = lines[l.espnGame]?.firstOrNull { it.market == l.market && it.espnAthlete == l.espnAthlete }?.line
                if (current != null && current != l.lastLine) l.copy(lastLine = current) else l
            }
            val added = leans.filter { key(it.espnGame, it.player.gsis, it.market) !in known }
                .map { Locked(it.espnGame, it.nflGameId, it.espnAthlete, it.player.gsis, it.market, it.over, it.line, it.line, now) }
            if (added.isEmpty() && updated == existing) return
            val arr = JSONArray()
            (updated + added).forEach {
                arr.put(
                    JSONObject().put("g", it.espnGame).put("n", it.nflGameId).put("a", it.espnAthlete).put("p", it.gsis).put("m", it.market.name)
                        .put("o", it.over).put("l", it.line).put("c", it.lastLine).put("at", it.at),
                )
            }
            NflPicks.writeAtomic(file, arr.toString())
        }

        fun summary(book: Book?, playedGames: Set<String>): LogSummary = summarize(read(), book, playedGames)
    }

    /**
     * W/L/P from the player's real stat line; a player with no stat line in a played game didn't
     * play and the pick is void. Move = how far the line went the pick's way before kickoff.
     */
    internal fun summarize(locked: List<Locked>, book: Book?, playedGames: Set<String>): LogSummary {
        var record = NflModel.Record.EMPTY
        var move = 0.0
        var open = 0
        for (l in locked) {
            move += if (l.over) l.lastLine - l.line else l.line - l.lastLine
            if (l.nflGameId !in playedGames || book == null) { open++; continue }
            val v = book.results[l.nflGameId to l.gsis]?.get(l.market) ?: continue
            record += when {
                v == l.line -> NflModel.Outcome.PUSH
                (v > l.line) == l.over -> NflModel.Outcome.WIN
                else -> NflModel.Outcome.LOSS
            }
        }
        return LogSummary(record, if (locked.isEmpty()) null else move / locked.size, open)
    }

    fun log(dir: File) = PropLog(File(dir, LOG_FILE))

    /** nflverse and ESPN spell two franchises differently. */
    private val ESPN_ABBR = mapOf("WAS" to "WSH", "LA" to "LAR")

    /** True when [player] plays for the team ESPN abbreviates [espnAbbr]. */
    fun playsFor(player: Player, espnAbbr: String): Boolean =
        (ESPN_ABBR[player.team] ?: player.team).equals(espnAbbr, ignoreCase = true)
}
