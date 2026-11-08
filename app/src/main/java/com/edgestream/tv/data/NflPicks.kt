package com.edgestream.tv.data

import com.edgestream.tv.data.NflModel.Rule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * NFL Picks for the Betting board: the [NflModel] rating picks every upcoming game's winner, and
 * the pre-registered [Rule]s flag value against the market line. Every value pick shown before
 * kickoff is locked into a local log and later graded against the closing line from games.csv.
 */
object NflPicks {
    const val GAMES_URL = "https://github.com/nflverse/nflverse-data/releases/download/schedules/games.csv"
    private const val CACHE_FILE = "nflverse-games.csv"
    private const val LOG_FILE = "nfl-picks.json"
    private const val STALE_MS = 6 * 3_600_000L
    /** Hourly forecasts are only worth trusting this far out. */
    private const val WIND_HORIZON_MS = 7 * 86_400_000L

    class Model(
        val rows: List<NflModel.Row>,
        predictions: List<NflModel.Prediction>,
        val rules: List<NflModel.RuleRecord>,
        val tiers: List<NflModel.TierRecord>,
        val backtest: NflModel.Backtest?,
    ) {
        val byEspn: Map<String, NflModel.Prediction> = predictions.mapNotNull { p -> p.row.espnId?.let { it to p } }.toMap()
    }

    data class ValuePick(val rule: Rule, val selection: String, val line: Double, val windMph: Int?, val modelAgrees: Boolean = false)

    data class Pick(
        val game: Sports.Game,
        val espnId: String,
        val winner: String,
        val winProb: Double,
        val modelHomeMargin: Double,
        val marketHomeMargin: Double?,
        val marketHomeProb: Double?,
        val totalLine: Double?,
        val tier: NflModel.Tier?,
        val values: List<ValuePick>,
    )

    data class LogSummary(val record: NflModel.Record, val avgClv: Double?, val open: Int)

    data class Board(
        val picks: List<Pick>,
        val rules: List<NflModel.RuleRecord>,
        val tiers: List<NflModel.TierRecord>,
        val backtest: NflModel.Backtest?,
        val log: LogSummary,
    )

    fun build(rows: List<NflModel.Row>): Model {
        val predictions = NflModel.replay(rows)
        return Model(rows, predictions, NflModel.ruleRecords(rows), NflModel.tierRecords(predictions), NflModel.backtest(predictions))
    }

    /** games.csv from the cache, re-downloaded when older than 6 h; the cache covers a failed download. */
    suspend fun loadModel(dir: File, now: Long = System.currentTimeMillis()): Model? = withContext(Dispatchers.IO) {
        val cache = File(dir, CACHE_FILE)
        val fresh = cache.isFile && now - cache.lastModified() < STALE_MS
        val text = if (fresh) cache.readText() else {
            try {
                Http.openSuspend(GAMES_URL) { it.readBytes().decodeToString() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
                ?.takeIf { it.startsWith("game_id,") }
                ?.also { writeAtomic(cache, it) }
                ?: cache.takeIf(File::isFile)?.readText()
        } ?: return@withContext null
        withContext(Dispatchers.Default) { NflModel.parse(text).takeIf { it.isNotEmpty() }?.let(::build) }
    }

    /**
     * ESPN's line text ("KC -3.5", "EVEN", "PK") as the market's expected home margin. Null when
     * it names neither team.
     */
    fun homeLine(details: String, homeAbbr: String, awayAbbr: String): Double? {
        val t = details.trim().uppercase(Locale.US)
        if (t == "EVEN" || t == "PK" || t == "PICK") return 0.0
        val m = Regex("""^([A-Z]{2,4})\s+([-+]?\d+(?:\.\d+)?)$""").find(t) ?: return null
        val favouredBy = -(m.groupValues[2].toDoubleOrNull() ?: return null)
        return when (m.groupValues[1]) {
            homeAbbr.uppercase(Locale.US) -> favouredBy
            awayAbbr.uppercase(Locale.US) -> -favouredBy
            else -> null
        }
    }

    /** "NYG -2.5" style, from a home margin, to the nearest half point. */
    fun lineText(homeMargin: Double, homeAbbr: String, awayAbbr: String): String {
        val half = (abs(homeMargin) * 2).roundToInt() / 2.0
        if (half == 0.0) return "PK"
        return "${if (homeMargin > 0) homeAbbr else awayAbbr} -${trim(half)}"
    }

    fun trim(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

    /** Picks for the upcoming NFL games in [games]; [windMph] is the game-time forecast per ESPN id. */
    fun picks(model: Model, games: List<Sports.Game>, windMph: Map<String, Int>): List<Pick> =
        games.asSequence()
            .filter { it.league.id == "nfl" && it.state == Sports.State.UPCOMING }
            .mapNotNull { g ->
                val espnId = g.id.substringAfter(':')
                val p = model.byEspn[espnId] ?: return@mapNotNull null
                val row = p.row
                val line = g.line
                val homeLine = line?.details?.let { homeLine(it, g.home.abbr, g.away.abbr) } ?: row.spreadLine
                val total = line?.overUnder ?: row.totalLine
                val marketProb = NflModel.noVigHome(line?.homeMoneyLine, line?.awayMoneyLine)
                    ?: NflModel.noVigHome(row.homeMoneyline, row.awayMoneyline)
                val wind = windMph[espnId]
                val values = NflModel.rulesFor(row.outdoor, wind, homeLine, total).map { rule ->
                    when (rule) {
                        Rule.WIND_UNDER -> ValuePick(rule, "Under ${trim(total!!)}", total, wind)
                        Rule.HOME_DOG -> ValuePick(
                            rule, "${g.home.abbr} +${trim(-homeLine!!)}", homeLine, null,
                            modelAgrees = p.homeMargin - homeLine >= NflModel.MODEL_AGREES,
                        )
                    }
                }
                val home = NflModel.pickHomeProb(p.homeMargin, homeLine)
                val pickProb = maxOf(home, 1 - home)
                Pick(
                    game = g, espnId = espnId,
                    winner = if (home >= 0.5) g.home.abbr else g.away.abbr,
                    winProb = pickProb,
                    modelHomeMargin = p.homeMargin, marketHomeMargin = homeLine, marketHomeProb = marketProb,
                    totalLine = total, tier = NflModel.tierFor(pickProb), values = values,
                )
            }
            .sortedBy { it.game.startMs }
            .toList()

    suspend fun board(model: Model, games: List<Sports.Game>, dir: File, now: Long = System.currentTimeMillis()): Board {
        val wind = HashMap<String, Int>()
        for (g in games) {
            if (g.league.id != "nfl" || g.state != Sports.State.UPCOMING || g.startMs - now > WIND_HORIZON_MS) continue
            val id = g.id.substringAfter(':')
            if (model.byEspn[id]?.row?.outdoor != true) continue
            windAtKickoff(g, now)?.let { wind[id] = it }
        }
        val picks = picks(model, games, wind)
        val log = PickLog(File(dir, LOG_FILE))
        withContext(Dispatchers.IO) { log.lock(picks, now) }
        return Board(picks, model.rules, model.tiers, model.backtest, log.summary(model.rows))
    }

    // ---- game-time wind (Open-Meteo, keyless) -------------------------------------------------

    private data class Wind(val at: Long, val mph: Int?)
    private val windCache = ConcurrentHashMap<String, Wind>()

    private suspend fun windAtKickoff(g: Sports.Game, now: Long): Int? {
        windCache[g.id]?.takeIf { now - it.at < 3_600_000L }?.let { return it.mph }
        val city = g.venueCity ?: return null
        val mph = withContext(Dispatchers.IO) {
            runCatching {
                val places = PublicApis.searchPlaces(city)
                val place = places.firstOrNull { placeMatches(it, g.venueCountry) } ?: places.firstOrNull() ?: return@runCatching null
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                    "&hourly=wind_speed_10m&wind_speed_unit=mph&timezone=GMT&forecast_days=8"
                val j = Http.open(url) { JSONObject(it.readBytes().decodeToString()) }
                hourlyAt(j.optJSONObject("hourly"), g.startMs)
            }.getOrNull()
        }
        windCache[g.id] = Wind(now, mph)
        return mph
    }

    private fun placeMatches(p: PublicApis.Place, espnCountry: String?): Boolean = when (espnCountry) {
        null, "USA", "United States" -> p.countryCode.equals("US", ignoreCase = true)
        else -> p.country.equals(espnCountry, ignoreCase = true)
    }

    /** The forecast hour holding kickoff ([startMs], UTC) from an Open-Meteo hourly block. */
    internal fun hourlyAt(hourly: JSONObject?, startMs: Long): Int? {
        val times = hourly?.optJSONArray("time") ?: return null
        val speeds = hourly.optJSONArray("wind_speed_10m") ?: return null
        val key = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(startMs)
        val i = (0 until times.length()).firstOrNull { times.optString(it) == key } ?: return null
        return speeds.optDouble(i, Double.NaN).takeIf { !it.isNaN() }?.roundToInt()
    }

    // ---- pick log -----------------------------------------------------------------------------

    /** A value pick as it stood when first shown: the line it was taken at, before the market moved. */
    data class Locked(val espnId: String, val rule: Rule, val line: Double, val selection: String, val at: Long)

    class PickLog(private val file: File) {
        fun read(): List<Locked> = runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val rule = runCatching { Rule.valueOf(o.getString("rule")) }.getOrNull() ?: return@mapNotNull null
                Locked(o.getString("espn"), rule, o.getDouble("line"), o.optString("sel"), o.optLong("at"))
            }
        }.getOrDefault(emptyList())

        /** Adds picks not already logged; a pick keeps the line it was first shown at. */
        fun lock(picks: List<Pick>, now: Long) {
            val existing = read()
            val known = existing.mapTo(HashSet()) { it.espnId to it.rule }
            val added = picks.flatMap { p ->
                p.values.filter { (p.espnId to it.rule) !in known }.map { Locked(p.espnId, it.rule, it.line, it.selection, now) }
            }
            if (added.isEmpty()) return
            val arr = JSONArray()
            (existing + added).forEach {
                arr.put(JSONObject().put("espn", it.espnId).put("rule", it.rule.name).put("line", it.line).put("sel", it.selection).put("at", it.at))
            }
            writeAtomic(file, arr.toString())
        }

        fun summary(rows: List<NflModel.Row>): LogSummary = summarize(read(), rows)
    }

    /**
     * Grades locked picks with games.csv results; closing line value is how far the market moved
     * toward the pick after it was taken (positive = the pick beat the close).
     */
    internal fun summarize(locked: List<Locked>, rows: List<NflModel.Row>): LogSummary {
        val byEspn = rows.mapNotNull { r -> r.espnId?.let { it to r } }.toMap()
        var record = NflModel.Record.EMPTY
        var clvSum = 0.0
        var clvN = 0
        var open = 0
        for (l in locked) {
            val r = byEspn[l.espnId]
            if (r == null || !r.played) { open++; continue }
            val outcome = when (l.rule) {
                Rule.HOME_DOG -> NflModel.gradeHome(l.line, r.result!!)
                Rule.WIND_UNDER -> r.total?.let { NflModel.gradeUnder(l.line, it) }
            } ?: continue
            record += outcome
            val close = when (l.rule) { Rule.HOME_DOG -> r.spreadLine; Rule.WIND_UNDER -> r.totalLine }
            if (close != null) {
                clvSum += when (l.rule) { Rule.HOME_DOG -> close - l.line; Rule.WIND_UNDER -> l.line - close }
                clvN++
            }
        }
        return LogSummary(record, if (clvN == 0) null else clvSum / clvN, open)
    }

    private fun writeAtomic(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) temp.delete()
        }
    }
}
