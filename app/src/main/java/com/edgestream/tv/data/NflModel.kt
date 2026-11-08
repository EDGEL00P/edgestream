package com.edgestream.tv.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * NFL game model over nflverse's schedules release (games.csv, every game since 1999 with closing
 * lines; nflverse-data, CC-BY 4.0). A points-scale team rating is walked forward one game at a
 * time, so each prediction only ever sees earlier games.
 *
 * Constants were grid-searched on 2002–2018 and frozen; 2019 onward is graded only. On that
 * holdout the rating alone does not beat the closing line, so value picks come from [Rule]s that
 * held up on both periods, never from model-vs-line disagreement.
 */
object NflModel {
    const val K = 0.06
    /** Margin error is capped before the update so one blowout can't swing a rating. */
    const val ERROR_CAP = 40.0
    const val HOME_FIELD = 2.5
    /** Points for a full week's rest edge (clamped to ±7 days). */
    const val REST = 1.0
    /** Share of each rating dropped at a new season (roster turnover). */
    const val REGRESS = 0.33
    /** A starter who isn't the team's most frequent QB this season. */
    const val BACKUP_QB = 3.0
    /** Residual SD of the model margin on the fit seasons. */
    const val SIGMA = 13.56
    /**
     * Weight of the rating against the market spread in the winner pick. The market alone is the
     * sharper forecaster; 10% rating was best on the fit seasons and level with it on the holdout.
     */
    const val BLEND = 0.1
    /** Pick confidence for the Lock and Strong tiers (heavy favourites; small payouts). */
    const val LOCK = 0.85
    const val STRONG = 0.80
    /** A home-dog pick the rating also likes by this many points (61% on the holdout). */
    const val MODEL_AGREES = 2.0
    const val HOLDOUT_FROM = 2019
    /** Moneylines are published in games.csv from 2006. */
    const val LINES_FROM = 2006

    data class Row(
        val gameId: String,
        val season: Int,
        val gameday: String,
        val gametime: String,
        val home: String,
        val away: String,
        /** Home minus away; null until played. */
        val result: Int?,
        val total: Int?,
        val neutral: Boolean,
        val homeRest: Int?,
        val awayRest: Int?,
        /** Market's expected home margin (home favoured > 0); the closing line once played. */
        val spreadLine: Double?,
        val totalLine: Double?,
        val homeMoneyline: Int?,
        val awayMoneyline: Int?,
        /** Null when the roof isn't known yet. */
        val outdoor: Boolean?,
        val windMph: Int?,
        val homeQb: String?,
        val awayQb: String?,
        val espnId: String?,
    ) {
        val played: Boolean get() = result != null
    }

    /** The model's home margin, broken into what produced it (points, home side positive). */
    data class Factors(val strength: Double, val homeField: Double, val rest: Double, val quarterback: Double) {
        val total: Double get() = strength + homeField + rest + quarterback
    }

    data class Prediction(val row: Row, val homeMargin: Double, val factors: Factors = Factors(homeMargin, 0.0, 0.0, 0.0)) {
        val homeWinProb: Double get() = winProb(homeMargin)
    }

    enum class Tier(val label: String, val floor: Double) { LOCK("Lock", NflModel.LOCK), STRONG("Strong", NflModel.STRONG) }

    /** Home win probability for the pick: the market spread, nudged by the rating. */
    fun pickHomeProb(modelMargin: Double, marketHomeMargin: Double?): Double =
        winProb(if (marketHomeMargin == null) modelMargin else BLEND * modelMargin + (1 - BLEND) * marketHomeMargin)

    fun tierFor(pickProb: Double): Tier? = Tier.entries.firstOrNull { pickProb >= it.floor }

    data class TierRecord(val tier: Tier, val all: Record, val holdout: Record)

    /** Straight-up record of each tier's favourite on closing spreads since [LINES_FROM]. */
    fun tierRecords(predictions: List<Prediction>): List<TierRecord> = Tier.entries.map { tier ->
        var all = Record.EMPTY
        var holdout = Record.EMPTY
        for (p in predictions) {
            val r = p.row
            val result = r.result ?: continue
            if (r.season < LINES_FROM || r.spreadLine == null || result == 0) continue
            val home = pickHomeProb(p.homeMargin, r.spreadLine)
            if (tierFor(maxOf(home, 1 - home)) != tier) continue
            val o = if ((home >= 0.5) == (result > 0)) Outcome.WIN else Outcome.LOSS
            all += o
            if (r.season >= HOLDOUT_FROM) holdout += o
        }
        TierRecord(tier, all, holdout)
    }

    /** Pre-registered rules: thresholds fixed before the 2019+ holdout was graded. */
    enum class Rule(val label: String, val detail: String) {
        WIND_UNDER("Wind Under", "Outdoors, wind $WIND_MPH mph or more: Under"),
        HOME_DOG("Big Home Underdog", "Home team getting ${HOME_DOG_POINTS.toInt()} or more: home + points"),
    }

    const val WIND_MPH = 15
    const val HOME_DOG_POINTS = 7.0

    enum class Outcome { WIN, LOSS, PUSH }

    data class Record(val wins: Int, val losses: Int, val pushes: Int) {
        val graded: Int get() = wins + losses
        val hitRate: Double? get() = if (graded == 0) null else wins.toDouble() / graded
        operator fun plus(o: Outcome): Record = when (o) {
            Outcome.WIN -> copy(wins = wins + 1)
            Outcome.LOSS -> copy(losses = losses + 1)
            Outcome.PUSH -> copy(pushes = pushes + 1)
        }

        companion object { val EMPTY = Record(0, 0, 0) }
    }

    data class RuleRecord(val rule: Rule, val all: Record, val holdout: Record)

    data class Backtest(
        val games: Int,
        val winnerHitRate: Double,
        val modelBrier: Double,
        val marketBrier: Double,
        val firstSeason: Int,
        val lastSeason: Int,
    )

    // ---- parsing ------------------------------------------------------------------------------

    /** Franchise moves, so a team's rating carries across its relocation. */
    private val FRANCHISE = mapOf("OAK" to "LV", "SD" to "LAC", "STL" to "LA")

    /** Parses games.csv by header name (nflverse adds columns over time). Bad rows are skipped. */
    fun parse(csv: String): List<Row> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return emptyList()
        val header = splitCsv(lines.next()).withIndex().associate { (i, h) -> h.trim() to i }
        val required = listOf("game_id", "season", "gameday", "home_team", "away_team")
        if (!required.all(header::containsKey)) return emptyList()
        val out = ArrayList<Row>(8_000)
        while (lines.hasNext()) {
            val f = splitCsv(lines.next())
            fun s(name: String): String? = header[name]?.let(f::getOrNull)?.trim()?.ifEmpty { null }
            fun i(name: String): Int? = s(name)?.toDoubleOrNull()?.toInt()
            fun d(name: String): Double? = s(name)?.toDoubleOrNull()
            val season = i("season") ?: continue
            val home = s("home_team") ?: continue
            val away = s("away_team") ?: continue
            out += Row(
                gameId = s("game_id") ?: continue,
                season = season,
                gameday = s("gameday") ?: continue,
                gametime = s("gametime").orEmpty(),
                home = FRANCHISE[home] ?: home,
                away = FRANCHISE[away] ?: away,
                result = i("result"),
                total = i("total"),
                neutral = s("location") == "Neutral",
                homeRest = i("home_rest"),
                awayRest = i("away_rest"),
                spreadLine = d("spread_line"),
                totalLine = d("total_line"),
                homeMoneyline = i("home_moneyline"),
                awayMoneyline = i("away_moneyline"),
                outdoor = when (s("roof")) { "outdoors", "open" -> true; "dome", "closed" -> false; else -> null },
                windMph = i("wind"),
                homeQb = s("home_qb_id"),
                awayQb = s("away_qb_id"),
                espnId = s("espn"),
            )
        }
        return out
    }

    /** One CSV record (RFC 4180 quoting; nflverse never wraps a record across lines). */
    internal fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(48)
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    // ---- model -------------------------------------------------------------------------------

    /**
     * Walks every game in kickoff order and returns the pre-game prediction for each one, played
     * or not. Ratings only move after a game is played, so upcoming games use the latest ratings.
     */
    fun replay(rows: List<Row>): List<Prediction> {
        val ordered = rows.sortedWith(compareBy<Row>({ it.gameday }, { it.gametime }, { it.gameId }))
        val ratings = HashMap<String, Double>()
        // Per team, this season's starts per QB, in first-start order (ties go to the earlier starter).
        var starts = HashMap<String, LinkedHashMap<String, Int>>()
        var season: Int? = null
        val out = ArrayList<Prediction>(ordered.size)
        for (r in ordered) {
            if (r.season != season) {
                season = r.season
                ratings.replaceAll { _, v -> v * (1 - REGRESS) }
                starts = HashMap()
            }
            fun qbPenalty(team: String, qb: String?): Double {
                val c = starts[team] ?: return 0.0
                if (qb == null || c.values.sum() < 2) return 0.0
                return if (c.maxByOrNull { it.value }?.key != qb) BACKUP_QB else 0.0
            }
            val rh = ratings[r.home] ?: 0.0
            val ra = ratings[r.away] ?: 0.0
            val restEdge = ((r.homeRest ?: 7) - (r.awayRest ?: 7)).coerceIn(-7, 7) / 7.0
            val factors = Factors(
                strength = rh - ra,
                homeField = if (r.neutral) 0.0 else HOME_FIELD,
                rest = REST * restEdge,
                quarterback = -qbPenalty(r.home, r.homeQb) + qbPenalty(r.away, r.awayQb),
            )
            val margin = factors.total
            out += Prediction(r, margin, factors)
            val result = r.result ?: continue
            val delta = K * (result - margin).coerceIn(-ERROR_CAP, ERROR_CAP)
            ratings[r.home] = rh + delta
            ratings[r.away] = ra - delta
            for ((team, qb) in listOf(r.home to r.homeQb, r.away to r.awayQb)) {
                if (qb == null) continue
                val c = starts.getOrPut(team) { LinkedHashMap() }
                c[qb] = (c[qb] ?: 0) + 1
            }
        }
        return out
    }

    fun winProb(homeMargin: Double): Double = phi(homeMargin / SIGMA)

    /** Standard normal CDF (Abramowitz & Stegun 7.1.26, error < 1.5e-7). */
    internal fun phi(z: Double): Double {
        val x = abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val erf = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return if (z >= 0) 0.5 * (1 + erf) else 0.5 * (1 - erf)
    }

    /** Market's no-vig home win probability from both moneylines. */
    fun noVigHome(homeMl: Int?, awayMl: Int?): Double? {
        if (homeMl == null || awayMl == null || homeMl == 0 || awayMl == 0) return null
        val h = Sports.impliedProb(homeMl)
        val a = Sports.impliedProb(awayMl)
        return h / (h + a)
    }

    // ---- rules and grading --------------------------------------------------------------------

    /** The rules that fire for a game, given its market home margin, total and game-time wind. */
    fun rulesFor(outdoor: Boolean?, windMph: Int?, homeLine: Double?, totalLine: Double?): List<Rule> = buildList {
        if (outdoor == true && windMph != null && windMph >= WIND_MPH && totalLine != null) add(Rule.WIND_UNDER)
        if (homeLine != null && homeLine <= -HOME_DOG_POINTS) add(Rule.HOME_DOG)
    }

    /** Home side against [homeLine] (the market's expected home margin). */
    fun gradeHome(homeLine: Double, result: Int): Outcome = when {
        result > homeLine -> Outcome.WIN
        result < homeLine -> Outcome.LOSS
        else -> Outcome.PUSH
    }

    fun gradeUnder(totalLine: Double, total: Int): Outcome = when {
        total < totalLine -> Outcome.WIN
        total > totalLine -> Outcome.LOSS
        else -> Outcome.PUSH
    }

    fun grade(rule: Rule, homeLine: Double?, totalLine: Double?, result: Int?, total: Int?): Outcome? = when (rule) {
        Rule.HOME_DOG -> if (homeLine != null && result != null) gradeHome(homeLine, result) else null
        Rule.WIND_UNDER -> if (totalLine != null && total != null) gradeUnder(totalLine, total) else null
    }

    /** Each rule's record on closing lines since [LINES_FROM], and on the holdout alone. */
    fun ruleRecords(rows: List<Row>): List<RuleRecord> = Rule.entries.map { rule ->
        var all = Record.EMPTY
        var holdout = Record.EMPTY
        for (r in rows) {
            if (r.season < LINES_FROM || !r.played) continue
            if (rule !in rulesFor(r.outdoor, r.windMph, r.spreadLine, r.totalLine)) continue
            val o = grade(rule, r.spreadLine, r.totalLine, r.result, r.total) ?: continue
            all += o
            if (r.season >= HOLDOUT_FROM) holdout += o
        }
        RuleRecord(rule, all, holdout)
    }

    /** Holdout grade of the rating model: winners picked, and Brier score against the market's. */
    fun backtest(predictions: List<Prediction>): Backtest? {
        var hits = 0
        var decided = 0
        var model = 0.0
        var market = 0.0
        var priced = 0
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        for (p in predictions) {
            val r = p.row
            val result = r.result ?: continue
            if (r.season < HOLDOUT_FROM || result == 0) continue
            first = minOf(first, r.season)
            last = maxOf(last, r.season)
            decided++
            if ((p.homeMargin > 0) == (result > 0)) hits++
            val m = noVigHome(r.homeMoneyline, r.awayMoneyline) ?: continue
            val y = if (result > 0) 1.0 else 0.0
            model += (p.homeWinProb - y).let { it * it }
            market += (m - y).let { it * it }
            priced++
        }
        if (decided == 0 || priced == 0) return null
        return Backtest(decided, hits.toDouble() / decided, model / priced, market / priced, first, last)
    }
}
