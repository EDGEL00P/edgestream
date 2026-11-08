package com.edgestream.tv.data

import android.content.Context
import com.edgestream.tv.data.db.ChannelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Live scores and fixtures for the Sports hub, from ESPN's public scoreboard (no key): real team
 * logos, scores, game state, and the networks showing each game, so the hub can tune straight
 * to the channel.
 *
 * Speed notes:
 *  - every league is fetched in parallel, but each request has a hard OkHttp call timeout; the
 *    old `withTimeout` could not interrupt a blocking socket read, so one slow feed held the hub.
 *  - a successful board is kept in memory and in a small snapshot file. A cold start paints the
 *    last known scores immediately while fresh scores stream in behind it.
 *  - the summary endpoint is parsed once per game and carries the advanced box score, live win
 *    probability, team leaders, scoring plays and the full play-by-play.
 */
object Sports {

    /** The five provider markets the Sports hub follows. Flags are real images, not emoji. */
    enum class Country(val code: String, val label: String, val short: String, val flag: String) {
        US("us", "United States", "US", "https://flagcdn.com/w80/us.png"),
        UK("gb", "United Kingdom", "UK", "https://flagcdn.com/w80/gb.png"),
        CA("ca", "Canada", "CA", "https://flagcdn.com/w80/ca.png"),
        AU("au", "Australia", "AU", "https://flagcdn.com/w80/au.png"),
        NZ("nz", "New Zealand", "NZ", "https://flagcdn.com/w80/nz.png");
    }

    /**
     * [countries] is the "genre" the league belongs to; shared competitions (NHL, MLS, Super Rugby)
     * appear under every market that watches them. Global sports carry all five.
     */
    data class League(
        val id: String,
        val name: String,
        val path: String,
        val logo: String? = null,
        val countries: Set<Country> = emptySet(),
    )

    data class Team(
        val abbr: String,
        val name: String,
        val logo: String?,
        val score: String?,
        val winner: Boolean,
        val record: String? = null,
        /** Primary uniform colour from the league feed, hex without '#'. */
        val color: String? = null,
        /** Secondary uniform colour, hex without '#'. */
        val altColor: String? = null,
    )

    enum class State { LIVE, UPCOMING, FINAL }

    data class Game(
        val id: String,
        val league: League,
        val state: State,
        /** "Q3 4:12", "Halftime", "Final", or the start time for upcoming games. */
        val detail: String,
        val startMs: Long,
        val home: Team,
        val away: Team,
        /** Networks carrying it, as ESPN names them ("ESPN", "TNT", "FOX"). */
        val broadcasts: List<String>,
        val venue: String?,
        /** Bookmaker line from the scoreboard feed, when ESPN publishes one. */
        val line: Line? = null,
    )

    /** One team's result in a recent game, e.g. "W 24–18 vs TEN". */
    data class FormGame(val result: String, val score: String, val atVs: String, val opponent: String)

    /** A bookmaker's line. Money lines are American odds; overUnder is the points total. */
    data class Line(
        val provider: String,
        val details: String,
        val overUnder: Double?,
        val homeMoneyLine: Int?,
        val awayMoneyLine: Int?,
    )

    data class Injury(val team: String, val player: String, val status: String, val detail: String?)

    /** A head-to-head box-score stat. [away] and [home] are already ESPN display strings. */
    data class TeamStat(val name: String, val label: String, val away: String, val home: String)

    /** One snap / possession in the play-by-play. */
    data class Play(
        val id: String,
        val text: String,
        val period: Int,
        val clock: String,
        val awayScore: Int?,
        val homeScore: Int?,
        val scoring: Boolean,
        /** Offensive team abbreviation, when ESPN publishes one. */
        val team: String?,
    )

    /** The leading player in one statistical category for a team. */
    data class LeaderStat(val team: String, val category: String, val player: String, val value: String)

    /** A starter, from the league feed's roster: enough to put a real number on the pitch. */
    data class Starter(val team: String, val name: String, val jersey: String, val position: String)

    /**
     * The "analyst" layer for a game, all from ESPN's public summary: their matchup predictor,
     * the market line, each side's last five results, injuries, recent reporting, plus the
     * advanced box score, live win probability, leaders and play-by-play. Nothing is invented;
     * anything ESPN doesn't publish simply stays null/empty.
     */
    data class Insight(
        val homeWinChance: Int?,
        val awayWinChance: Int?,
        val line: Line?,
        val homeForm: List<FormGame>,
        val awayForm: List<FormGame>,
        val injuries: List<Injury>,
        val headlines: List<String>,
        val stats: List<TeamStat> = emptyList(),
        val leaders: List<LeaderStat> = emptyList(),
        val scoringPlays: List<Play> = emptyList(),
        val plays: List<Play> = emptyList(),
        /** In-game win probability from ESPN's win-probability feed, when the game is live. */
        val liveHomeWinChance: Int? = null,
        /** No-vig market probability implied by ESPN's bookmaker moneyline, when one is published. */
        val marketHomeWinChance: Int? = null,
        /** Both teams' starters, with real jersey numbers, for the match view. */
        val starters: List<Starter> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = homeWinChance == null && line == null && homeForm.isEmpty() &&
                awayForm.isEmpty() && injuries.isEmpty() && headlines.isEmpty() &&
                stats.isEmpty() && leaders.isEmpty() && scoringPlays.isEmpty() && plays.isEmpty() &&
                liveHomeWinChance == null

        companion object { val EMPTY = Insight(null, null, null, emptyList(), emptyList(), emptyList(), emptyList()) }
    }

    private fun leagueLogo(file: String) = "https://a.espncdn.com/i/teamlogos/leagues/500/$file.png"
    private fun soccerLogo(id: Int) = "https://a.espncdn.com/i/leaguelogos/soccer/500/$id.png"
    private fun espnIcon(name: String) = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-$name.png"
    private fun espnPath(path: String) = "https://a.espncdn.com/combiner/i?img=$path"

    private val US = setOf(Country.US)
    private val CA = setOf(Country.CA)
    private val UK = setOf(Country.UK)
    private val AU = setOf(Country.AU)
    private val NZ = setOf(Country.NZ)
    private val NORTH_AMERICA = setOf(Country.US, Country.CA)
    private val OCEANIA = setOf(Country.AU, Country.NZ)
    private val ALL = Country.entries.toSet()

    /**
     * Every league the hub follows, grouped into the country it belongs to. Shared competitions
     * (NHL, NBA, MLB, MLS, Super Rugby, NRL) appear under each market that watches them; global
     * series (F1, tennis, golf, UFC) appear everywhere. A country tab only fetches its own
     * leagues, so an AU/NZ/UK/CA/US board loads in a fraction of the time of one giant board.
     */
    val LEAGUES = listOf(
        // United States
        League("nfl", "NFL", "football/nfl", leagueLogo("nfl"), US),
        League("nba", "NBA", "basketball/nba", leagueLogo("nba"), NORTH_AMERICA),
        League("mlb", "MLB", "baseball/mlb", leagueLogo("mlb"), NORTH_AMERICA),
        League("nhl", "NHL", "hockey/nhl", leagueLogo("nhl"), NORTH_AMERICA),
        League("wnba", "WNBA", "basketball/wnba", leagueLogo("wnba"), US),
        League("ncaaf", "College Football", "football/college-football", "https://a.espncdn.com/redesign/assets/img/icons/NCAASports_Football.png", US),
        League("ncaab", "College Basketball", "basketball/mens-college-basketball", "https://a.espncdn.com/redesign/assets/img/icons/NCAASports_MensBasketball.png", US),
        League("mls", "MLS", "soccer/usa.1", soccerLogo(19), NORTH_AMERICA),
        League("nwsl", "NWSL", "soccer/usa.nwsl", soccerLogo(2323), US),
        League("usl", "USL", "soccer/usa.usl.1", soccerLogo(2292), US),
        // Marquee European football: the ticker should not look only Anglo-American.
        League("laliga", "La Liga", "soccer/esp.1", espnIcon("soccer"), ALL),
        League("seriea", "Serie A", "soccer/ita.1", espnIcon("soccer"), ALL),
        League("bundesliga", "Bundesliga", "soccer/ger.1", espnIcon("soccer"), ALL),
        League("ligue1", "Ligue 1", "soccer/fra.1", espnIcon("soccer"), ALL),
        League("ligamx", "Liga MX", "soccer/mex.1", espnIcon("soccer"), ALL),
        League("nascar", "NASCAR", "racing/nascar-premier", espnPath("/redesign/assets/img/icons/ESPN-icon-NASCAR.png"), US),
        League("indycar", "IndyCar", "racing/irl", espnPath("/i/espn/teamlogos/500/indycar_series.png"), US),
        // Canada
        League("cfl", "CFL", "football/cfl", espnIcon("football"), CA),
        // United Kingdom
        League("epl", "Premier League", "soccer/eng.1", soccerLogo(23), UK),
        League("eng2", "Championship", "soccer/eng.2", soccerLogo(24), UK),
        League("facup", "FA Cup", "soccer/eng.fa", soccerLogo(40), UK),
        League("carabao", "Carabao Cup", "soccer/eng.league_cup", soccerLogo(41), UK),
        League("scottish", "Scottish Premiership", "soccer/sco.1", soccerLogo(45), UK),
        League("ucl", "Champions League", "soccer/uefa.champions", soccerLogo(2), ALL),
        League("europa", "Europa League", "soccer/uefa.europa", soccerLogo(2310), UK),
        League("urc", "United Rugby", "rugby/270557", espnIcon("rugby"), UK),
        League("county", "County Cricket", "cricket/8052", espnIcon("cricket"), UK),
        League("t20", "T20 Blast", "cricket/8053", espnIcon("cricket"), UK),
        // Australia
        League("afl", "AFL", "australian-football/afl", leagueLogo("afl"), AU),
        League("aleague", "A-League", "soccer/aus.1", soccerLogo(1308), AU),
        League("nrl", "NRL", "rugby-league/3", leagueLogo("nrl"), OCEANIA),
        League("nbl", "NBL", "basketball/nbl", leagueLogo("nbl"), AU),
        League("superrugby", "Super Rugby", "rugby/242041", espnIcon("rugby"), OCEANIA),
        League("bbl", "Big Bash League", "cricket/8044", "https://a.espncdn.com/i/leaguelogos/cricket/500/8044.png", AU),
        League("sheffield", "Sheffield Shield", "cricket/8043", espnIcon("cricket"), AU),
        // New Zealand
        League("rugbychamp", "Rugby Championship", "rugby/244293", espnIcon("rugby"), OCEANIA),
        // Global
        League("worldcup", "Cricket World Cup", "cricket/8039", espnIcon("cricket"), ALL),
        // Global
        League("f1", "Formula 1", "racing/f1", espnPath("/i/teamlogos/leagues/500/f1.png"), ALL),
        League("atp", "ATP Tennis", "tennis/atp", espnIcon("tennis"), ALL),
        League("wta", "WTA Tennis", "tennis/wta", espnIcon("tennis"), ALL),
        League("pga", "PGA Tour", "golf/pga", espnPath("/i/teamlogos/leagues/500/pgatour.png"), ALL),
        League("lpga", "LPGA", "golf/lpga", espnPath("/i/teamlogos/leagues/500/lpga.png"), ALL),
        League("ufc", "UFC", "mma/ufc", leagueLogo("ufc"), ALL),
    )

    // ---- caching --------------------------------------------------------------------------

    private const val SCORE_TTL_MS = 20_000L
    private const val LIVE_INSIGHT_TTL_MS = 15_000L
    private const val INSIGHT_TTL_MS = 60_000L
    private const val HTTP_TIMEOUT_MS = 5_000L
    private const val INSIGHT_TIMEOUT_MS = 7_000L
    private const val SNAPSHOT_FILE = "sports_scores.json"
    private const val SNAPSHOT_EVERY_MS = 60_000L

    /** One league's last successful board. A per-league cache means a country tab only pays for its own feeds. */
    private class LeagueBoard(val at: Long, val games: List<Game>)

    private val boards = java.util.concurrent.ConcurrentHashMap<String, LeagueBoard>()
    @Volatile private var appContext: Context? = null
    @Volatile private var snapshotStarted = false
    @Volatile private var lastSnapshotWrite = 0L

    /** OkHttp's call timeout is the only one that actually stops a blocking socket read. */
    private val scoreClient by lazy {
        Http.client.newBuilder().callTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }
    private val insightClient by lazy {
        Http.client.newBuilder().callTimeout(INSIGHT_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    /** Called once from Application.onCreate; safe to omit in unit tests. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (snapshotStarted) return
        snapshotStarted = true
        CoroutineScope(Dispatchers.IO).launch {
            val saved = runCatching { readSnapshot(context.applicationContext) }.getOrNull() ?: return@launch
            // The snapshot is only for an instant first paint; mark it stale so the selected
            // country refreshes immediately, and never overwrite a board a live fetch won.
            val staleAt = System.currentTimeMillis() - SCORE_TTL_MS
            saved.groupBy { it.league.id }.forEach { (id, games) ->
                boards.putIfAbsent(id, LeagueBoard(staleAt, games))
            }
        }
    }

    /** The last successful board, even if older than the TTL. Lets the hub paint instantly. */
    fun cachedGames(): List<Game>? {
        val ctx = appContext
        if (boards.isEmpty() && ctx != null && !snapshotStarted) init(ctx)
        return boards.values.flatMap { it.games }.distinctBy { it.id }.takeIf { it.isNotEmpty() }
    }

    /** The leagues in one country tab, plus the global competitions everyone follows. */
    fun leaguesFor(country: Country?): List<League> =
        if (country == null) LEAGUES else LEAGUES.filter { country in it.countries }

    /**
     * The scoreboard for a country tab (or every league when [country] is null). Each league is
     * cached independently and fetched in parallel; only stale leagues are re-requested. If every
     * feed fails the last good boards are kept rather than blanking the screen.
     */
    suspend fun games(country: Country? = null, force: Boolean = false): List<Game> =
        refresh(leaguesFor(country), force)

    private suspend fun refresh(leagues: List<League>, force: Boolean): List<Game> = coroutineScope {
        val started = System.currentTimeMillis()
        val stale = leagues.filter { league ->
            force || boards[league.id]?.let { started - it.at >= SCORE_TTL_MS } ?: true
        }
        if (stale.isNotEmpty()) {
            val results: List<Pair<League, List<Game>?>> = stale.map { league ->
                league to async(Dispatchers.IO) { runCatching { fetch(league) }.getOrNull() }
            }.map { (league, pending) -> league to pending.await() }

            if (results.any { it.second != null }) {
                // A league that failed (not one that is legitimately empty) keeps its last board.
                for ((league, fresh) in results) if (fresh != null) boards[league.id] = LeagueBoard(started, fresh)
                maybeSnapshot(started)
            }
        }
        leagues.flatMap { boards[it.id]?.games.orEmpty() }
    }

    private suspend fun maybeSnapshot(now: Long) {
        val ctx = appContext ?: return
        if (lastSnapshotWrite != 0L && now - lastSnapshotWrite < SNAPSHOT_EVERY_MS) return
        lastSnapshotWrite = now
        val all = boards.values.flatMap { it.games }.distinctBy { it.id }
        withContext(Dispatchers.IO) { runCatching { writeSnapshot(ctx, all) } }
    }

    /**
     * The leagues worth asking about for one channel. A dedicated network gives a precise list
     * (Sky Sports F1 -> F1, NBA League Pass -> NBA); anything else gets the marquee board rather
     * than every league on earth, so zapping to a sports channel doesn't trigger 30 feeds.
     */
    fun leaguesForChannel(channel: ChannelEntity): List<League> {
        val text = norm(channel.name) + norm(NameCleaner.tidyGroup(channel.groupName))
        val matched = LEAGUES.filter { league -> leagueWords(league).any { text.contains(it) } }
        return matched.ifEmpty { MARQUEE }
    }

    /** Every league whose board could contain the game on [channel], merged with the cached board. */
    suspend fun gamesForChannel(channel: ChannelEntity, programTitle: String? = null, force: Boolean = false): List<Game> {
        val cached = cachedGames().orEmpty()
        // Once the game is known, keep only its league fresh instead of re-fetching a whole
        // marquee board every 30 seconds while the viewer watches.
        val known = gameForChannel(cached, channel, programTitle)?.league
        val fresh = refresh(if (known != null) listOf(known) else leaguesForChannel(channel), force)
        return (fresh + cached).distinctBy { it.id }
    }

    private fun readSnapshot(context: Context): List<Game>? {
        val file = File(context.filesDir, SNAPSHOT_FILE)
        if (!file.isFile) return null
        return decodeGames(file.readText()).takeIf { it.isNotEmpty() }
    }

    private fun writeSnapshot(context: Context, games: List<Game>) {
        val target = File(context.filesDir, SNAPSHOT_FILE)
        val temp = File(context.filesDir, "$SNAPSHOT_FILE.tmp")
        temp.writeText(encodeGames(games))
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    // ---- game follows and alerts ---------------------------------------------------------

    /** Ids of the games the user has chosen to be alerted about; start and final alerts fire for these. */
    private val followed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isFollowing(id: String): Boolean = followed.contains(id)

    fun toggleFollow(id: String) { if (!followed.add(id)) followed.remove(id) }

    fun followedIds(): Set<String> = followed

    /**
     * Followed games that have newly entered the live or final state since [previous] was sampled
     * (a game that just kicked off, or just finished). Live score updates within a game don't count;
     * a game seen for the first time is treated as still upcoming, so it can't alert on load.
     */
    fun newlyLiveOrFinal(previous: List<Game>, current: List<Game>, followedIds: Set<String>): List<Game> {
        val prev = previous.associateBy { it.id }
        return current.filter { followedIds.contains(it.id) && it.state != State.UPCOMING && prev[it.id]?.state != null && it.state != prev[it.id]?.state }
    }

    // ---- scoreboard fetch -----------------------------------------------------------------

    /** Win-loss line from ESPN's team record list, e.g. "12–5"; null when the payload carries none. */
    private fun recordFor(t: JSONObject): String? {
        val arr = t.optJSONArray("records") ?: return null
        var out: String? = null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val s = r.optString("summary").ifBlank { r.optString("wins").ifBlank { r.optString("losses").ifBlank { "" } } }.trim()
            if (s.isNotEmpty()) out = s
        }
        return out
    }

    private fun fetch(l: League): List<Game> {
        val body = scoreClient.newCall(Request.Builder().url("https://site.api.espn.com/apis/site/v2/sports/${l.path}/scoreboard").build())
            .execute().use { r ->
                if (!r.isSuccessful) throw IOException("ESPN scoreboard ${r.code} for ${l.id}")
                r.body.string()
            }
        // The whole board is wrapped defensively: a malformed event is skipped, not fatal.
        val events = runCatching { JSONObject(body).optJSONArray("events") }.getOrNull() ?: return emptyList()
        return (0 until events.length()).mapNotNull { i ->
            runCatching { parseEvent(events.optJSONObject(i) ?: return@mapNotNull null, l) }
                .getOrNull() // one bad game never blanks the board
        }
    }

    /** One event from the scoreboard, isolated so a parse failure only skips that game. */
    private fun parseEvent(e: JSONObject, l: League): Game? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val teams = comp.optJSONArray("competitors") ?: return null
        fun team(side: String): Team? = (0 until teams.length()).map { teams.getJSONObject(it) }
            .firstOrNull { it.optString("homeAway") == side }?.let { c ->
                val t = c.optJSONObject("team") ?: c.optJSONObject("athlete") ?: JSONObject()
                Team(
                    abbr = t.optString("abbreviation").ifBlank { t.optString("shortDisplayName") },
                    name = t.optString("shortDisplayName").ifBlank { t.optString("displayName") },
                    logo = t.optString("logo").ifBlank { null } ?: t.optJSONObject("flag")?.optString("href"),
                    score = c.optString("score").ifBlank { null },
                    winner = c.optBoolean("winner"),
                    record = recordFor(t),
                    color = t.optString("color").ifBlank { null },
                    altColor = t.optString("alternateColor").ifBlank { null },
                )
            }
        // F1 and UFC events aren't home/away; they appear as single cards without teams.
        val home = team("home") ?: return null
        val away = team("away") ?: return null
        val status = e.optJSONObject("status")?.optJSONObject("type")
        val state = when (status?.optString("state")) { "in" -> State.LIVE; "post" -> State.FINAL; else -> State.UPCOMING }
        val broadcasts = comp.optJSONArray("broadcasts")?.let { b ->
            (0 until b.length()).flatMap { j -> b.optJSONObject(j)?.optJSONArray("names")?.let { n -> (0 until n.length()).map { n.optString(it) } }.orEmpty() }
        }.orEmpty().filter { it.isNotBlank() }.distinct()
        return Game(
            id = "${l.id}:${e.optString("id")}", league = l, state = state,
            detail = status?.optString("shortDetail").orEmpty(),
            startMs = parseDate(e.optString("date")),
            home = home, away = away, broadcasts = broadcasts,
            venue = comp.optJSONObject("venue")?.optString("fullName")?.ifBlank { null },
            line = lineFor(comp),
        )
    }

    /**
     * ESPN puts the current bookmaker line on the scoreboard itself, so the ticker can show odds
     * without fetching a summary per game. Shape: competitions[0].odds[0] with details,
     * overUnder, and home/away money lines.
     */
    private fun lineFor(comp: JSONObject): Line? {
        val o = comp.optJSONArray("odds")?.optJSONObject(0) ?: return null
        val details = o.optString("details").trim()
        val overUnder = o.optDouble("overUnder", Double.NaN).takeIf { !it.isNaN() }
        val home = o.optJSONObject("homeTeamOdds")?.let { if (it.has("moneyLine")) it.optInt("moneyLine") else null }
        val away = o.optJSONObject("awayTeamOdds")?.let { if (it.has("moneyLine")) it.optInt("moneyLine") else null }
        val provider = o.optJSONObject("provider")?.optString("name").orEmpty()
        return Line(provider, details, overUnder, home, away)
            .takeIf { it.details.isNotBlank() || it.overUnder != null || it.homeMoneyLine != null || it.awayMoneyLine != null }
    }

    // ---- analyst / pro layer --------------------------------------------------------------

    private val insightCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Insight>>()

    /** Analyst data for one game; live games refresh every 15 s, everything else every 60 s. */
    suspend fun insight(game: Game, force: Boolean = false): Insight? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val ttl = if (game.state == State.LIVE) LIVE_INSIGHT_TTL_MS else INSIGHT_TTL_MS
        if (!force) insightCache[game.id]?.takeIf { now - it.first < ttl }?.let { return@withContext it.second }
        val fresh = runCatching { fetchInsight(game) }.getOrNull() ?: return@withContext null
        insightCache[game.id] = now to fresh
        fresh
    }

    private fun fetchInsight(g: Game): Insight {
        val eventId = g.id.substringAfter(':')
        val url = "https://site.api.espn.com/apis/site/v2/sports/${g.league.path}/summary?event=$eventId"
        val body = insightClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("ESPN summary ${r.code} for ${g.id}")
            r.body.string()
        }
        val root = JSONObject(body)

        val predictor = root.optJSONObject("predictor")
        val homeProjection = predictor?.optJSONObject("homeTeam")?.optString("gameProjection")?.toDoubleOrNull()
        val awayProjection = predictor?.optJSONObject("awayTeam")?.optString("gameProjection")?.toDoubleOrNull()
        val homeChance: Int?
        val awayChance: Int?
        if (homeProjection != null && awayProjection != null && homeProjection + awayProjection > 0.0) {
            homeChance = (homeProjection / (homeProjection + awayProjection) * 100).roundToInt()
            awayChance = 100 - homeChance
        } else {
            homeChance = null
            awayChance = null
        }

        val lineJson = root.optJSONArray("pickcenter")?.optJSONObject(0)
            ?: root.optJSONArray("odds")?.optJSONObject(0)
        val line = lineJson?.let { o ->
            val homeOdds = o.optJSONObject("homeTeamOdds")
            val awayOdds = o.optJSONObject("awayTeamOdds")
            Line(
                provider = o.optJSONObject("provider")?.optString("name").orEmpty().ifBlank { "Market" },
                details = o.optString("details"),
                overUnder = o.optDouble("overUnder").takeIf { !it.isNaN() },
                homeMoneyLine = homeOdds?.let { if (it.has("moneyLine")) it.optInt("moneyLine") else null },
                awayMoneyLine = awayOdds?.let { if (it.has("moneyLine")) it.optInt("moneyLine") else null },
            )
        }?.takeIf { it.details.isNotBlank() || it.overUnder != null }

        // The market's own prediction: the no-vig probability implied by both money lines.
        // Same math the books themselves use to remove the vig, so the two percentages sum to 100.
        val marketHomeChance: Int?
        val homeMl = line?.homeMoneyLine
        val awayMl = line?.awayMoneyLine
        if (homeMl != null && awayMl != null) {
            val h = impliedProb(homeMl)
            val a = impliedProb(awayMl)
            marketHomeChance = if (h + a > 0.0) (h / (h + a) * 100).roundToInt().coerceIn(0, 100) else null
        } else marketHomeChance = null

        val injuries = buildList {
            val arr = root.optJSONArray("injuries") ?: return@buildList
            for (i in 0 until arr.length()) {
                val team = arr.optJSONObject(i) ?: continue
                val teamName = team.optJSONObject("team")?.optString("displayName").orEmpty()
                val list = team.optJSONArray("injuries") ?: continue
                for (j in 0 until minOf(list.length(), 2)) {
                    val injury = list.optJSONObject(j) ?: continue
                    val player = injury.optJSONObject("athlete")?.optString("displayName").orEmpty()
                    if (player.isBlank()) continue
                    add(
                        Injury(
                            team = teamName,
                            player = player,
                            status = injury.optString("status"),
                            detail = injury.optJSONObject("details")?.optString("type")?.ifBlank { null },
                        ),
                    )
                }
            }
        }

        val headlines = buildList {
            val articles = root.optJSONObject("news")?.optJSONArray("articles") ?: return@buildList
            for (i in 0 until minOf(articles.length(), 3)) {
                val headline = cleanText(articles.optJSONObject(i)?.optString("headline").orEmpty())
                if (headline.isNotBlank()) add(headline)
            }
        }

        return Insight(
            homeWinChance = homeChance,
            awayWinChance = awayChance,
            line = line,
            homeForm = runCatching { formFor(root, g.home.abbr) }.getOrDefault(emptyList()),
            awayForm = runCatching { formFor(root, g.away.abbr) }.getOrDefault(emptyList()),
            injuries = injuries,
            headlines = headlines,
            // Each section is independently fault-tolerant: a malformed box score never takes
            // down the play-by-play, and a bad leaders payload never kills the predictor.
            stats = runCatching { parseTeamStats(root) }.getOrDefault(emptyList()),
            leaders = runCatching { parseLeaders(root) }.getOrDefault(emptyList()),
            scoringPlays = runCatching { parseScoringPlays(root) }.getOrDefault(emptyList()),
            plays = runCatching { parsePlays(root) }.getOrDefault(emptyList()),
            liveHomeWinChance = runCatching { parseLiveHomeWinChance(root) }.getOrNull(),
            marketHomeWinChance = marketHomeChance,
            starters = runCatching { parseStarters(root) }.getOrDefault(emptyList()),
        )
    }

    /** Both teams' starters with real jersey numbers, from the summary roster payload. */
    internal fun parseStarters(root: JSONObject): List<Starter> {
        val rosters = root.optJSONArray("rosters") ?: return emptyList()
        val out = ArrayList<Starter>(16)
        for (i in 0 until rosters.length()) {
            val team = rosters.optJSONObject(i) ?: continue
            val abbr = team.optJSONObject("team")?.optString("abbreviation").orEmpty()
            val athletes = team.optJSONArray("athletes") ?: team.optJSONArray("roster") ?: continue
            for (j in 0 until athletes.length()) {
                val a = athletes.optJSONObject(j) ?: continue
                if (!a.optBoolean("starter", false) && out.any { it.team == abbr }) continue
                val ath = a.optJSONObject("athlete") ?: a
                val name = ath.optString("displayName").ifBlank { ath.optString("shortName") }
                if (name.isBlank()) continue
                val jersey = a.optString("jersey").ifBlank { ath.optString("jersey") }
                val pos = a.optJSONObject("position")?.let { p ->
                    p.optString("abbreviation").ifBlank { p.optString("name") }
                } ?: a.optString("position").ifBlank { "" }
                out += Starter(abbr, name, jersey, pos)
            }
        }
        return out
    }

    /** Implied probability of an American money line: -150 -> 60 %, +150 -> 40 %. */
    private fun impliedProb(american: Int): Double = if (american < 0) {
        -american.toDouble() / (-american + 100)
    } else {
        100.0 / (american + 100)
    }

    /** Last five results for the team whose abbreviation (or display name) is [teamKey], newest first. */
    private fun formFor(root: JSONObject, teamKey: String): List<FormGame> {
        if (teamKey.isBlank()) return emptyList()
        val teams = root.optJSONArray("lastFiveGames") ?: return emptyList()
        for (i in 0 until teams.length()) {
            val entry = teams.optJSONObject(i) ?: continue
            val team = entry.optJSONObject("team") ?: continue
            val abbr = team.optString("abbreviation")
            val name = team.optString("displayName")
            if (!abbr.equals(teamKey, true) && !name.equals(teamKey, true)) continue
            val events = entry.optJSONArray("events") ?: return emptyList()
            return (events.length() - 1 downTo 0).mapNotNull { j ->
                val e = events.optJSONObject(j) ?: return@mapNotNull null
                val opponent = e.optJSONObject("opponent")
                FormGame(
                    result = e.optString("gameResult"),
                    score = e.optString("score"),
                    atVs = e.optString("atVs"),
                    opponent = opponent?.optString("abbreviation").orEmpty()
                        .ifBlank { opponent?.optString("displayName").orEmpty() },
                )
            }.filter { it.result.isNotBlank() }.take(5)
        }
        return emptyList()
    }

    // ---- advanced parsing (internal so the JVM tests can exercise real payloads) ----------

    private val SKIP_STATS = setOf(
        "firstDownsPassing", "firstDownsRushing", "firstDownsPenalty", "totalDrives",
        "averageGain", "longGain", "longRushing", "longPassing", "longReception",
    )

    /** ESPN's box-score comparison, in ESPN's own order, capped to the stats people actually read. */
    internal fun parseTeamStats(root: JSONObject): List<TeamStat> {
        val teams = root.optJSONObject("boxscore")?.optJSONArray("teams") ?: return emptyList()
        var awayTeam: JSONObject? = null
        var homeTeam: JSONObject? = null
        for (i in 0 until teams.length()) {
            val team = teams.optJSONObject(i) ?: continue
            when (team.optString("homeAway")) {
                "home" -> homeTeam = team
                "away" -> awayTeam = team
            }
        }
        val homeStats = statsMap(homeTeam ?: return emptyList())
        val awayStats = statsMap(awayTeam ?: return emptyList())
        val ordered = homeTeam.optJSONArray("statistics") ?: return emptyList()
        val out = ArrayList<TeamStat>(14)
        for (i in 0 until ordered.length()) {
            val stat = ordered.optJSONObject(i) ?: continue
            val name = stat.optString("name")
            if (name.isBlank() || name in SKIP_STATS) continue
            val away = cleanStat(awayStats[name]) ?: continue
            val home = cleanStat(homeStats[name]) ?: continue
            out += TeamStat(name, stat.optString("label").ifBlank { name }, away, home)
            if (out.size == 14) break
        }
        return out
    }

    private fun statsMap(team: JSONObject): Map<String, String> {
        val arr = team.optJSONArray("statistics") ?: return emptyMap()
        val out = HashMap<String, String>(arr.length())
        for (i in 0 until arr.length()) {
            val stat = arr.optJSONObject(i) ?: continue
            val name = stat.optString("name")
            if (name.isNotBlank()) out[name] = stat.optString("displayValue")
        }
        return out
    }

    private fun cleanStat(value: String?): String? {
        val text = value?.trim().orEmpty()
        return text.takeIf { it.isNotEmpty() && it != "-" }
    }

    /** Full play-by-play from ESPN's drive list. Newest first, deduped, capped for the UI. */
    internal fun parsePlays(root: JSONObject): List<Play> {
        val drives = root.optJSONObject("drives")
        val out = ArrayList<Play>()
        fun addDrive(drive: JSONObject?) {
            if (drive == null) return
            val team = drive.optJSONObject("team")?.optString("abbreviation")?.ifBlank { null }
            val plays = drive.optJSONArray("plays") ?: return
            for (i in 0 until plays.length()) {
                plays.optJSONObject(i)?.let { out += play(it, team) }
            }
        }
        drives?.optJSONArray("previous")?.let { arr ->
            for (i in 0 until arr.length()) addDrive(arr.optJSONObject(i))
        }
        addDrive(drives?.optJSONObject("current"))
        if (out.isEmpty()) {
            val plays = root.optJSONArray("plays") ?: return emptyList()
            for (i in 0 until plays.length()) plays.optJSONObject(i)?.let { out += play(it, null) }
        }
        return out.asReversed()
            .filter { it.text.isNotBlank() }
            .distinctBy { it.id.ifBlank { "${it.period}:${it.clock}:${it.text}" } }
            .take(120)
    }

    /** Scoring summary, newest first. */
    internal fun parseScoringPlays(root: JSONObject): List<Play> {
        val arr = root.optJSONArray("scoringPlays") ?: return emptyList()
        return (arr.length() - 1 downTo 0).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            play(o, o.optJSONObject("team")?.optString("abbreviation")?.ifBlank { null }).copy(scoring = true)
        }.filter { it.text.isNotBlank() }.take(40)
    }

    /** The current in-game win probability, as a home-team percentage. */
    internal fun parseLiveHomeWinChance(root: JSONObject): Int? {
        val arr = root.optJSONArray("winprobability") ?: return null
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("homeWinPercentage")) continue
            val chance = o.optDouble("homeWinPercentage", Double.NaN)
            if (!chance.isNaN()) return (chance * 100).roundToInt().coerceIn(0, 100)
        }
        return null
    }

    /** The leading player in each category for both teams, capped so the panel stays readable. */
    internal fun parseLeaders(root: JSONObject): List<LeaderStat> {
        val arr = root.optJSONArray("leaders") ?: return emptyList()
        val out = ArrayList<LeaderStat>()
        for (i in 0 until arr.length()) {
            val team = arr.optJSONObject(i) ?: continue
            val abbr = team.optJSONObject("team")?.optString("abbreviation").orEmpty()
            val categories = team.optJSONArray("leaders") ?: continue
            for (j in 0 until categories.length()) {
                val category = categories.optJSONObject(j) ?: continue
                val leaders = category.optJSONArray("leaders") ?: continue
                val top = leaders.optJSONObject(0) ?: continue
                val player = top.optJSONObject("athlete")?.optString("displayName").orEmpty()
                val value = top.optString("displayValue").ifBlank {
                    val main = top.optJSONObject("mainStat")
                    listOf(main?.optString("value").orEmpty(), main?.optString("label").orEmpty())
                        .filter { it.isNotBlank() }.joinToString(" ")
                }
                if (player.isBlank() || value.isBlank()) continue
                out += LeaderStat(abbr, category.optString("displayName").ifBlank { category.optString("name") }, player, value)
            }
        }
        return out.take(10)
    }

    private fun play(o: JSONObject, team: String?): Play {
        val period = o.optJSONObject("period")
        return Play(
            id = o.optString("id"),
            text = cleanText(o.optString("text")),
            period = period?.optInt("number") ?: period?.optInt("value") ?: 0,
            clock = o.optJSONObject("clock")?.optString("displayValue").orEmpty(),
            awayScore = if (o.has("awayScore")) o.optInt("awayScore") else null,
            homeScore = if (o.has("homeScore")) o.optInt("homeScore") else null,
            scoring = o.optBoolean("scoringPlay"),
            team = team,
        )
    }

    private val HTML_TAG = Regex("<[^>]+>")

    private fun cleanText(value: String): String = HTML_TAG.replace(value, "").trim()

    // ---- helpers --------------------------------------------------------------------------

    /** ESPN writes "2026-11-05T00:00Z" (no seconds), full ISO times, and millisecond variants. */
    private val MINUTES = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")
    internal fun parseDate(s: String): Long =
        runCatching { java.time.OffsetDateTime.parse(s, MINUTES).toInstant().toEpochMilli() }
            .recoverCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .recoverCatching { java.time.OffsetDateTime.parse(s, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
            .getOrDefault(0L)

    /** The same game with nothing that gives the result away: scores read "–", nobody has won. */
    fun spoilerFree(g: Game): Game = if (g.state == State.UPCOMING) g else g.copy(
        home = g.home.copy(score = "–", winner = false),
        away = g.away.copy(score = "–", winner = false),
    )

    fun statusText(g: Game): String = when (g.state) {
        State.LIVE -> "Live"
        State.FINAL -> "Final"
        State.UPCOMING -> "Upcoming"
    }

    /** Name tokens a provider channel or EPG title might use for [team]: full name, abbreviation, short name. */
    private fun teamTokens(team: Team): List<String> = buildList {
        norm(team.name).takeIf { it.length > 2 }?.let(::add)
        norm(team.abbr).takeIf { it.length >= 3 }?.let(::add)
        team.name.split(Regex("[^A-Za-z0-9]+")).lastOrNull()
            ?.let { norm(it) }?.takeIf { it.length >= 3 }?.let(::add)
    }.distinct()

    // "+" is spelled out: "ESPN+" and "ESPN" are different services.
    private fun norm(s: String) = s.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }

    /** "gb" and "uk" are the same viewer; everything else is the ISO code as written. */
    private fun countryCode(cc: String) = cc.lowercase().let { if (it == "gb") "uk" else it }

    /**
     * Names a guide entry would use for [team]: the full name and the nickname ("Broncos"), never
     * the 3-letter code, which shows up inside ordinary words in programme titles.
     */
    private fun guideTokens(team: Team): List<String> = buildList {
        norm(team.name).takeIf { it.length >= 4 }?.let(::add)
        team.name.split(Regex("[^A-Za-z0-9]+")).lastOrNull()?.let(::norm)?.takeIf { it.length >= 4 }?.let(::add)
    }.distinct()

    /**
     * True when a guide entry ("NRL: Broncos v Storm", or "NHL Hockey" with the episode title
     * "Boston Bruins at New York Rangers") names both teams of [g].
     */
    fun airsGame(title: String, subtitle: String?, g: Game): Boolean {
        val home = guideTokens(g.home)
        val away = guideTokens(g.away)
        if (home.isEmpty() || away.isEmpty()) return false
        val text = norm(title + " " + subtitle.orEmpty())
        return home.any(text::contains) && away.any(text::contains)
    }

    /**
     * Provider channel names are not ESPN's names ("Sky Sports F1", "NBA League Pass", "Fox
     * Footy"). These tokens map a channel to the few boards that could be showing on it, so the
     * player never downloads every league just to identify one game.
     */
    private val LEAGUE_ALIASES: Map<String, List<String>> = mapOf(
        "nfl" to listOf("nfl", "redzone", "sundayticket"),
        "nba" to listOf("nba", "leaguepass", "tsn", "sportsnet"),
        "mlb" to listOf("mlb", "extrainnings", "tsn", "sportsnet"),
        "nhl" to listOf("nhl", "centerice", "tsn", "sportsnet", "rds"),
        "wnba" to listOf("wnba"),
        "ncaaf" to listOf("ncaa", "collegefootball", "secnetwork", "accnetwork", "bigten", "pac12"),
        "ncaab" to listOf("ncaa", "collegebasketball", "marchmadness", "secnetwork", "accnetwork", "bigten", "pac12"),
        "mls" to listOf("mls", "soccer", "beinsports", "tsn", "sportsnet"),
        "nwsl" to listOf("nwsl", "soccer"),
        "usl" to listOf("usl", "soccer"),
        "nascar" to listOf("nascar"),
        "indycar" to listOf("indycar", "irl"),
        "cfl" to listOf("cfl", "tsn", "sportsnet"),
        "epl" to listOf("premierleague", "epl", "btsport", "tntsports", "optussport"),
        "eng2" to listOf("championship", "efl", "skybet"),
        "facup" to listOf("facup", "btsport", "tntsports"),
        "carabao" to listOf("carabao", "leaguecup"),
        "scottish" to listOf("scottishpremiership", "spfl", "scotland"),
        "ucl" to listOf("championsleague", "ucl", "btsport", "tntsports", "optussport", "stansport"),
        "europa" to listOf("europaleague", "uel", "btsport", "tntsports"),
        "urc" to listOf("unitedrugby", "urc", "btsport", "stansport", "rugby"),
        "county" to listOf("countycricket", "county", "cricket", "willow"),
        "t20" to listOf("t20", "blast", "cricket", "willow"),
        "afl" to listOf("afl", "foxfooty", "kayo"),
        "aleague" to listOf("aleague", "soccer", "kayo"),
        "nrl" to listOf("nrl", "rugbyleague", "foxleague", "kayo"),
        "nbl" to listOf("nbl", "kayo"),
        "superrugby" to listOf("superrugby", "rugby", "stansport", "kayo"),
        "bbl" to listOf("bigbash", "bbl", "cricket", "kayo", "willow"),
        "rugbychamp" to listOf("rugbychampionship", "rugby", "stansport"),
        "f1" to listOf("f1", "formula1", "motorsport", "skysportsf1"),
        "atp" to listOf("atp", "tennis", "tennischannel"),
        "wta" to listOf("wta", "tennis", "tennischannel"),
        "pga" to listOf("pga", "golf", "golfchannel"),
        "lpga" to listOf("lpga", "golf", "golfchannel"),
        "ufc" to listOf("ufc", "mma", "fightpass"),
    )

    private val MARQUEE = LEAGUES.filter {
        it.id in setOf("nfl", "nba", "nhl", "mlb", "ncaaf", "ncaab", "mls", "epl", "ucl", "afl", "nrl", "f1", "ufc")
    }

    private fun leagueWords(league: League): List<String> =
        buildList {
            add(norm(league.name))
            add(norm(league.id))
            addAll(LEAGUE_ALIASES[league.id].orEmpty())
        }.filter { it.length >= 2 }.distinct()

    /**
     * ESPN's network names versus the provider's channel names. A game can list a local RSN
     * ("Arizona Family TV") that the lineup doesn't carry; the alias and league fallbacks below
     * find the national feed, League Pass, or another channel that actually is carried.
     */
    private val NETWORK_ALIASES: Map<String, List<String>> = mapOf(
        "abc" to listOf("abc"),
        "espn" to listOf("espn"),
        "espn2" to listOf("espn2"),
        "espnu" to listOf("espnu"),
        // ESPN+ is a streaming service, not the ESPN channel: most of its games never air on ESPN.
        "espn+" to listOf("espnplus"),
        "espn plus" to listOf("espnplus"),
        "tnt" to listOf("tnt"),
        "tbs" to listOf("tbs"),
        "nba tv" to listOf("nbatv", "nba"),
        "nba league pass" to listOf("nbaleaguepass", "leaguepass", "nba"),
        "nfl network" to listOf("nflnetwork", "nfl"),
        "nfl sunday ticket" to listOf("sundayticket", "nfl"),
        "redzone" to listOf("redzone", "nfl"),
        "mlb network" to listOf("mlbnetwork", "mlb"),
        "mlb extra innings" to listOf("extrainnings", "mlb"),
        "nhl network" to listOf("nhlnetwork", "nhl"),
        "nhl center ice" to listOf("centerice", "nhl"),
        "cbs" to listOf("cbs"),
        "cbs sports network" to listOf("cbssportsnetwork", "cbssports", "cbs"),
        "nbc" to listOf("nbc"),
        "nbc sports" to listOf("nbcsports", "nbc"),
        "fox" to listOf("fox"),
        "fox sports" to listOf("foxsports", "fox"),
        "fs1" to listOf("fs1", "foxsports1"),
        "fs2" to listOf("fs2", "foxsports2"),
        "usa" to listOf("usanetwork", "usa"),
        "usa network" to listOf("usanetwork", "usa"),
        "peacock" to listOf("peacock"),
        "paramount+" to listOf("paramount"),
        "prime video" to listOf("primevideo", "amazonprime", "prime"),
        "amazon prime video" to listOf("primevideo", "amazonprime", "prime"),
        "apple tv+" to listOf("appletv"),
        "sky sports" to listOf("skysports"),
        "tnt sports" to listOf("tntsports", "tnt"),
        "dazn" to listOf("dazn"),
        "kayo" to listOf("kayo"),
        "stan sport" to listOf("stansport", "stan"),
        "optus sport" to listOf("optussport", "optus"),
        "tsn" to listOf("tsn"),
        "sportsnet" to listOf("sportsnet"),
        "rds" to listOf("rds"),
        "bally sports" to listOf("ballysports", "bally"),
        "fanduel sports network" to listOf("fanduelsports", "fanduel"),
        "root sports" to listOf("rootsports", "root"),
        "sec network" to listOf("secnetwork", "sec"),
        "acc network" to listOf("accnetwork", "acc"),
        "big ten network" to listOf("bigtennetwork", "bigten"),
        "golf channel" to listOf("golfchannel", "golf"),
        "tennis channel" to listOf("tennischannel", "tennis"),
        "willow" to listOf("willow"),
    )

    /**
     * A reusable normalized channel index. Building it once per channel refresh avoids
     * re-normalizing the whole lineup for every game card (the old hub did that thousands of
     * times per frame on large playlists).
     */
    class ChannelLookup(
        private val channels: List<ChannelEntity>,
        rawNames: Map<String, String> = emptyMap(),
        /** The viewer's "always watch on" choices (ChannelPins); a pinned channel always comes first. */
        private val pins: Map<String, String> = emptyMap(),
        /** Game id -> guide ids whose programme at game time names both teams (see [airsGame]). */
        private val guide: Map<String, List<String>> = emptyMap(),
        /** The viewer's country ("au"): a game their own channels show beats an overseas feed. */
        home: String? = null,
    ) {
        private val byKey: Map<String, ChannelEntity> by lazy { channels.associateBy { it.key } }
        private val byEpg: Map<String, List<ChannelEntity>> by lazy { channels.filter { it.epgId != null }.groupBy { it.epgId!! } }
        private val local: Set<String> = home?.let(::countryCode)?.let { want ->
            channels.filter { c ->
                (EpgMatcher.countryOf(rawNames[c.key] ?: c.name) ?: EpgMatcher.countryOf(c.tvgId))?.let(::countryCode) == want
            }.mapTo(HashSet()) { it.key }
        }.orEmpty()
        private fun localFirst(list: List<ChannelEntity>) = if (local.isEmpty()) list else list.sortedBy { if (it.key in local) 0 else 1 }
        private fun pinned(g: Game): ChannelEntity? = ChannelPins.pinFor(g, pins)?.let { byKey[it] }

        private val entries: List<Pair<ChannelEntity, String>> = channels.map { it to norm(it.name) }
        /** Provider names keep the event description ("NBA LIVE 01 [Hawks vs Wizards]"). */
        private val rawEntries: List<Pair<ChannelEntity, String>> =
            channels.map { it to norm(rawNames[it.key] ?: it.name) }
        /** An event channel's listed start time and whether it names a women's event, by channel key. */
        private val events: Map<String, Pair<Long?, Boolean>> = channels.associate { c ->
            val raw = rawNames[c.key] ?: c.name
            c.key to (NameListings.eventStart(raw) to WOMEN.containsMatchIn(raw))
        }
        private val byName: Map<String, ChannelEntity> = buildMap {
            for (channel in channels) putIfAbsent(norm(channel.name), channel)
        }
        private val groupEntries: List<Pair<ChannelEntity, String>> =
            channels.map { it to norm(NameCleaner.tidyGroup(it.groupName)) }
        /** Every channel that could be showing [g], best first. Used for "also in your lineup". */
        fun candidates(g: Game): List<ChannelEntity> {
            val out = LinkedHashSet<ChannelEntity>()
            pinned(g)?.let(out::add)
            out += teamMatches(g)
            out += guideMatches(g)
            for (broadcast in g.broadcasts) out += networkMatches(broadcast)
            out += localFirst(leagueMatches(g))
            // One choice per channel name: providers often carry the same feed several times.
            return out.distinctBy { norm(it.name) }
        }

        /** The best single channel for [g], or null when the lineup genuinely doesn't carry it. */
        fun find(g: Game): ChannelEntity? {
            pinned(g)?.let { return it }
            teamMatches(g).firstOrNull()?.let { return it }
            // The guide says what a channel is actually airing, so it finds the viewer's own
            // broadcaster (Fox Sports, Sky Sports) that ESPN's US network list never names.
            guideMatches(g).firstOrNull()?.let { return it }
            for (broadcast in g.broadcasts) networkMatches(broadcast).firstOrNull()?.let { return it }
            // The exact local RSN may be missing. Prefer a league-wide channel (League Pass,
            // RedZone, Sunday Ticket); otherwise take the first league-branded channel the
            // lineup actually carries. A 10k-channel provider almost always has one, and a
            // real NBA channel is more useful than a false "not in your lineup".
            val league = localFirst(leagueMatches(g))
            if (league.isEmpty()) return null
            val wide = league.filter(::isLeagueWide)
            if (wide.isNotEmpty()) return wide.first()
            val branded = league.filter { isLeagueBranded(g, it) }
            return branded.firstOrNull() ?: league.first()
        }

        /** True when a channel's name or its guide entry names both teams. */
        fun isTeamMatch(g: Game): Boolean = teamMatches(g).isNotEmpty() || guideMatches(g).isNotEmpty()

        /** True when the guess is grounded in the teams or a real broadcaster, not just the league. */
        fun isDirectMatch(g: Game): Boolean =
            isTeamMatch(g) || g.broadcasts.any { broadcast -> networkMatches(broadcast).isNotEmpty() }

        /** ESPN's broadcast names that the lineup does not carry, for an honest "not in lineup" line. */
        fun missing(g: Game): List<String> =
            g.broadcasts.filter { broadcast -> networkMatches(broadcast).isEmpty() }.distinct()

        private fun teamMatches(g: Game): List<ChannelEntity> {
            val home = teamTokens(g.home)
            val away = teamTokens(g.away)
            if (home.isEmpty() || away.isEmpty()) return emptyList()
            val womensGame = WOMEN.containsMatchIn(g.league.name) || g.league.id in WOMENS_LEAGUES
            return localFirst(rawEntries.filter { (channel, name) ->
                if (!(home.any { name.contains(it) } && away.any { name.contains(it) })) return@filter false
                // The same two clubs meet in other competitions: the women's sides, a cup, next
                // month's fixture. An event channel must be this game: its listed start within
                // three hours of kick-off, and women's events only for women's competitions.
                val (start, womens) = events[channel.key] ?: (null to false)
                (start == null || kotlin.math.abs(start - g.startMs) <= 3 * 3_600_000L) && womens == womensGame
            }.map { it.first })
        }

        private fun guideMatches(g: Game): List<ChannelEntity> =
            localFirst(guide[g.id].orEmpty().flatMap { byEpg[it].orEmpty() }.distinct())

        private fun networkMatches(broadcast: String): List<ChannelEntity> {
            val wanted = NETWORK_ALIASES[broadcast.trim().lowercase()] ?: listOf(norm(broadcast))
            val found = LinkedHashSet<ChannelEntity>()
            for (alias in wanted) {
                if (alias.length < 2) continue
                byName[alias]?.let { channel ->
                    if (isSportLike(norm(channel.name), norm(NameCleaner.tidyGroup(channel.groupName)))) found.add(channel)
                }
                for ((i, entry) in entries.withIndex()) {
                    val (channel, name) = entry
                    val group = groupEntries[i].second
                    if (!isSportLike(name, group)) continue
                    when {
                        name == alias -> found.add(channel)
                        // "ESPN" reaches "ESPN HD"/"ESPN Plus", but a word-qualified suffix only:
                        // "ESPN2"/"ESPNU" are different networks and must match their own alias.
                        // Short call signs (FOX, CBS) only prefix-match inside a sports context, so
                        // "Fox News" and "CBS Reality" can never stand in for a sports broadcast.
                        name.startsWith(alias) && name.length > alias.length &&
                            name.substring(alias.length).all { it.isLetter() } &&
                            (alias.length >= 4 || SPORT_WORD.containsMatchIn(name) || SPORT_WORD.containsMatchIn(group)) ->
                            found.add(channel)
                    }
                }
                for ((i, entry) in groupEntries.withIndex()) {
                    if (entry.second == alias && isSportLike(entries[i].second, entry.second)) found.add(entry.first)
                }
            }
            // Local RSNs often differ by one word ("Arizona Family TV" vs "Arizona Family
            // Sports"). Two meaningful shared words are a safe enough match.
            val tokens = broadcast.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 4 && it !in NETWORK_STOP_WORDS }
            if (tokens.size >= 2) {
                val fuzzy = entries.withIndex().firstOrNull { (i, entry) ->
                    isSportLike(entry.second, groupEntries[i].second) &&
                        tokens.count { entry.second.contains(it) } >= 2
                }
                fuzzy?.let { found.add(it.value.first) }
            }
            return found.toList()
        }

        private fun isSportLike(name: String, group: String): Boolean {
            val text = "$name $group"
            return !NON_SPORT.containsMatchIn(text) || SPORT_WORD.containsMatchIn(text)
        }

        private fun leagueMatches(g: Game): List<ChannelEntity> {
            val words = leagueWords(g.league)
            if (words.isEmpty()) return emptyList()
            // The channel list is normalized once at construction; matching never re-normalizes,
            // so this stays cheap even on 10k-channel playlists.
            return channels.filterIndexed { i, channel ->
                val name = entries[i].second
                val group = groupEntries[i].second
                isSportLike(name, group) && words.any { word ->
                    // Short league codes (NBA, NFL, NHL) must be whole words. Without this,
                    // "Atn Bangla" matches NBA and lands in a basketball channel list.
                    if (word.length <= 4) {
                        hasWord(channel.name, word) || hasWord(NameCleaner.tidyGroup(channel.groupName), word)
                    } else {
                        name.contains(word) || group == word || group.contains(word)
                    }
                }
            }
        }

        private fun hasWord(text: String, word: String): Boolean =
            Regex("(^|[^A-Za-z0-9])${Regex.escape(word)}([^A-Za-z0-9]|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)

        private fun isLeagueWide(c: ChannelEntity): Boolean = LEAGUE_WIDE.containsMatchIn(norm(c.name))

        /** "NBA TV", "NBA 01", a group called "NBA": branded by the league itself. */
        private fun isLeagueBranded(g: Game, c: ChannelEntity): Boolean {
            val words = listOf(norm(g.league.id), norm(g.league.name))
                .filter { it.length >= 2 }
                .distinct()
            if (words.isEmpty()) return false
            val name = norm(c.name)
            val group = norm(NameCleaner.tidyGroup(c.groupName))
            return words.any { name.contains(it) || group.contains(it) }
        }

        private companion object {
            /** "Serie A Women", "NCAA Women's Basketball", "(W)", "Femminile", "Ladies". */
            val WOMEN = Regex("""\bwom[ae]n'?s?\b|\(w\)|\bfemminile\b|\bféminine\b|\bladies\b|\bwsl\b|\bnwsl\b|\bwnba\b""", RegexOption.IGNORE_CASE)
            val WOMENS_LEAGUES = setOf("wnba", "nwsl", "wsl", "ncaaw", "wncaab")
            val LEAGUE_WIDE = Regex(
                "leaguepass|redzone|sundayticket|extrainnings|centerice|gamepass|" +
                    "nbatv|nflnetwork|mlbnetwork|nhlnetwork|fightpass",
            )
            /** Channels that are clearly not a sports broadcast unless their group says otherwise. */
            val NON_SPORT = Regex(
                "news|reality|movies?|cinema|kids|cartoon|shopping|religion|adult|porn|weather|music",
                RegexOption.IGNORE_CASE,
            )
            val SPORT_WORD = Regex(
                "sport|espn|nfl|nba|nhl|mlb|ncaa|football|soccer|racing|golf|tennis|cricket|" +
                    "boxing|ufc|fight|league|arena|stadium|game",
                RegexOption.IGNORE_CASE,
            )
            val NETWORK_STOP_WORDS = setOf(
                "sports", "sport", "network", "channel", "television", "live", "plus", "hd", "tv",
            )
        }
    }

    /**
     * The channel in the user's lineup most likely to be showing [g]: an event channel naming
     * both teams beats a network channel ("ESPN") from its broadcast list.
     */
    fun channelFor(g: Game, channels: List<ChannelEntity>): ChannelEntity? = ChannelLookup(channels).find(g)

    /** Every plausible channel for [g], for the sheet's "also in your lineup" choices. */
    fun channelsFor(g: Game, channels: List<ChannelEntity>): List<ChannelEntity> =
        ChannelLookup(channels).candidates(g)

    /** Broadcast names ESPN lists that the provider lineup does not carry. */
    fun missingBroadcasts(g: Game, channels: List<ChannelEntity>): List<String> =
        ChannelLookup(channels).missing(g)

    /**
     * The game currently on [channel], if any. [programTitle] (the EPG programme) breaks ties when
     * one network carries several games at once. Provider event channels that only say "NBA 01"
     * are resolved through their programme title, or through their league when there is exactly
     * one live game in that league.
     */
    fun gameForChannel(games: List<Game>, channel: ChannelEntity, programTitle: String? = null): Game? {
        val lookup = ChannelLookup(listOf(channel))
        val title = norm(programTitle.orEmpty())
        fun namesBothTeams(g: Game): Boolean {
            if (title.isBlank()) return false
            val home = teamTokens(g.home)
            val away = teamTokens(g.away)
            return home.isNotEmpty() && away.isNotEmpty() &&
                home.any { title.contains(it) } && away.any { title.contains(it) }
        }
        fun rank(g: Game): Int {
            var rank = when (g.state) { State.LIVE -> 0; State.UPCOMING -> 1; State.FINAL -> 2 }
            if (namesBothTeams(g)) rank -= 10
            return rank
        }

        val candidates = games.filter { it.state != State.FINAL }

        // 1. The programme title names both teams: unambiguous.
        candidates.filter(::namesBothTeams).minByOrNull(::rank)?.let { return it }

        // 2. The channel itself names both teams (an event channel, e.g. "NBA: Suns at Lakers").
        candidates.filter { lookup.isTeamMatch(it) }.minByOrNull(::rank)?.let { return it }

        // 3. A network/league channel is only safe when exactly one live game matches it. With
        // two live games on ESPN, a generic "NBA Today" title must not pick a game from another
        // arena; the Gamecast stays hidden until the title identifies one.
        val matches = candidates.filter { it.state == State.LIVE && lookup.find(it) != null }
        if (matches.size == 1) return matches.first()

        // 4. A dedicated league/event channel with exactly one live game in that league.
        val channelText = norm(channel.name) + norm(NameCleaner.tidyGroup(channel.groupName))
        if (channelText.isNotBlank()) {
            val leagueMatches = candidates.filter {
                it.state == State.LIVE && channelText.contains(norm(it.league.name))
            }
            if (leagueMatches.size == 1) return leagueMatches.first()
        }
        return null
    }

    /**
     * Sports channels that actually exist in the provider lineup, ranked for the hub:
     *  3 = a channel currently matched to a game in the scoreboard
     *  2 = a dedicated sports network / event group ("Sky Sports", "NBA", "PPV")
     *  0 = not sport
     * The game match lets generic networks (CBS, NBC) surface only while they are carrying a game.
     */
    fun sportsChannelRank(c: ChannelEntity, hasGame: Boolean): Int {
        if (hasGame) return 3
        val group = NameCleaner.tidyGroup(c.groupName)
        return if (SPORT_WORDS.containsMatchIn(c.name) || SPORT_WORDS.containsMatchIn(group)) 2 else 0
    }

    /** True when a programme title is clearly sport, or names a team from [games]. */
    fun isSportsTitle(title: String?, games: List<Game> = emptyList()): Boolean {
        if (title.isNullOrBlank()) return false
        if (SPORT_WORDS.containsMatchIn(title)) return true
        val text = norm(title)
        return games.any { g -> (teamTokens(g.home) + teamTokens(g.away)).any { text.contains(it) } }
    }

    /** Channels that are about sport: by group, provider network name or league/event name. */
    private val SPORT_WORDS = Regex(
        """sport|espn|fox sports|fs1|fs2|sky sports|bt sport|tnt sports|sportsnet|tsn|rds|eurosport|""" +
            """nfl|nba|nhl|mlb|wnba|ncaa|college football|college basketball|ufc|mma|boxing|fight|""" +
            """dazn|bein|golf|tennis|f1|formula 1|motorsport|racing|premier|epl|champions league|ucl|mls|""" +
            """afl|a-league|soccer|football|cricket|rugby|nrl|kayo|optus sport|stan sport|""" +
            """acc network|sec network|big ten|redzone|league pass|sunday ticket|ppv|pay per view|""" +
            """matchroom|top rank|wrestling|wwe|tnt|bally|fanduel sports|root sports|nesn|yes network|""" +
            """msg|altitude|spectrum sports|cox sports|marquee sports|nbc sports|regional sports|rsn|""" +
            """golf channel|tennis channel|willow|fox footy|fox league""",
        RegexOption.IGNORE_CASE,
    )
    fun isSportsChannel(c: ChannelEntity) = SPORT_WORDS.containsMatchIn(c.name) || SPORT_WORDS.containsMatchIn(NameCleaner.tidyGroup(c.groupName))

    // ---- snapshot codec -------------------------------------------------------------------

    private fun encodeLine(line: Line): JSONObject = JSONObject().apply {
        put("provider", line.provider)
        put("details", line.details)
        line.overUnder?.let { put("overUnder", it) }
        line.homeMoneyLine?.let { put("homeMoneyLine", it) }
        line.awayMoneyLine?.let { put("awayMoneyLine", it) }
    }

    private fun decodeLine(o: JSONObject?): Line? {
        if (o == null) return null
        return Line(
            provider = o.optString("provider"),
            details = o.optString("details"),
            overUnder = o.optDouble("overUnder", Double.NaN).takeIf { !it.isNaN() },
            homeMoneyLine = o.optInt("homeMoneyLine", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            awayMoneyLine = o.optInt("awayMoneyLine", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
        )
    }

    internal fun encodeGames(games: List<Game>): String {
        val arr = JSONArray()
        for (g in games) {
            val broadcasts = JSONArray()
            g.broadcasts.forEach { broadcasts.put(it) }
            arr.put(
                JSONObject().apply {
                    put("id", g.id)
                    put("league", g.league.id)
                    put("state", g.state.name)
                    put("detail", g.detail)
                    put("start", g.startMs)
                    put("home", encodeTeam(g.home))
                    put("away", encodeTeam(g.away))
                    put("broadcasts", broadcasts)
                    g.venue?.let { put("venue", it) }
                    g.line?.let { put("line", encodeLine(it)) }
                },
            )
        }
        return JSONObject().put("version", 1).put("games", arr).toString()
    }

    private fun encodeTeam(team: Team): JSONObject = JSONObject().apply {
        put("abbr", team.abbr)
        put("name", team.name)
        team.logo?.let { put("logo", it) }
        team.score?.let { put("score", it) }
        put("winner", team.winner)
        team.record?.let { put("record", it) }
    }

    internal fun decodeGames(raw: String): List<Game> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        if (root.optInt("version") != 1) return emptyList()
        val arr = root.optJSONArray("games") ?: return emptyList()
        val leagues = LEAGUES.associateBy { it.id }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val league = leagues[o.optString("league")] ?: return@mapNotNull null
            val state = runCatching { State.valueOf(o.optString("state")) }.getOrNull() ?: return@mapNotNull null
            val home = decodeTeam(o.optJSONObject("home")) ?: return@mapNotNull null
            val away = decodeTeam(o.optJSONObject("away")) ?: return@mapNotNull null
            val broadcasts = mutableListOf<String>()
            o.optJSONArray("broadcasts")?.let { b ->
                for (j in 0 until b.length()) b.optString(j).takeIf { it.isNotBlank() }?.let(broadcasts::add)
            }
            Game(
                id = o.optString("id"), league = league, state = state, detail = o.optString("detail"),
                startMs = o.optLong("start"), home = home, away = away, broadcasts = broadcasts,
                venue = o.optString("venue").ifBlank { null },
                line = decodeLine(o.optJSONObject("line")),
            )
        }
    }

    private fun decodeTeam(o: JSONObject?): Team? {
        if (o == null) return null
        return Team(
            abbr = o.optString("abbr"), name = o.optString("name"),
            logo = o.optString("logo").ifBlank { null }, score = o.optString("score").ifBlank { null },
            winner = o.optBoolean("winner"), record = o.optString("record").ifBlank { null },
        )
    }
}
