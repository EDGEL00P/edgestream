package com.edgestream.tv.data

import com.edgestream.tv.data.db.ChannelEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsTest {

    private fun team(abbr: String, score: String? = null, winner: Boolean = false) = Sports.Team(abbr, abbr, null, score, winner)

    private fun namedTeam(name: String, abbr: String, score: String? = null, winner: Boolean = false) =
        Sports.Team(abbr, name, null, score, winner)

    private fun game(
        id: String,
        state: Sports.State,
        home: Sports.Team,
        away: Sports.Team,
        league: Sports.League = Sports.League("nfl", "NFL", "football/nfl"),
        broadcasts: List<String> = listOf("ESPN"),
    ) = Sports.Game(
        id = id, league = league, state = state,
        detail = "Q3 4:12", startMs = 0L, home = home, away = away, broadcasts = broadcasts, venue = "Anywhere",
    )

    private fun nba() = Sports.League("nba", "NBA", "basketball/nba")

    private fun channel(
        key: String,
        name: String,
        group: String = "Sports",
        number: Int = 1,
    ) = ChannelEntity(
        id = 0, key = key, sourceId = 0, name = name, groupName = group,
        logo = null, streamUrl = "http://local/$key", epgId = null, number = number,
    )

    @Test fun alertsOnFollowedGameStart() {
        val followed = setOf("nfl:1")
        val first = game("nfl:1", Sports.State.UPCOMING, team("KC"), team("BUF"))
        val second = game("nfl:1", Sports.State.LIVE, team("KC", "7", true), team("BUF", "3"))
        // The user followed before kickoff: the jump into live is the alert.
        assertEquals(listOf(second), Sports.newlyLiveOrFinal(listOf(first), listOf(second), followed))
    }

    @Test
    fun alertsOnFollowedGameFinal() {
        val followed = setOf("nfl:1")
        val first = game("nfl:1", Sports.State.LIVE, team("KC", "7", true), team("BUF", "3"))
        val second = game("nfl:1", Sports.State.FINAL, team("KC", "27", true), team("BUF", "24"))
        assertEquals(listOf(second), Sports.newlyLiveOrFinal(listOf(first), listOf(second), followed))
    }

    @Test
    fun ignoresScoreOnlyUpdatesAndUnfollowedGames() {
        val followed = setOf("nfl:1")
        val first = game("nfl:1", Sports.State.LIVE, team("KC", "7", true), team("BUF", "3"))
        // Score tick: live -> live, must not alert.
        assertTrue(Sports.newlyLiveOrFinal(listOf(first), listOf(first), followed).isEmpty())
        // Same game, nobody following: no alert.
        assertTrue(Sports.newlyLiveOrFinal(listOf(first), listOf(first), emptySet()).isEmpty())
    }

    @Test
    fun ignoresGamesSeenForTheFirstTime() {
        val followed = setOf("nfl:1")
        val kickoff = game("nfl:1", Sports.State.UPCOMING, team("KC"), team("BUF"))
        assertTrue(Sports.newlyLiveOrFinal(emptyList(), listOf(kickoff), followed).isEmpty())
    }

    // ---- channel resolution ------------------------------------------------------------------

    @Test
    fun teamEventChannelBeatsNetworkChannel() {
        val event = channel("evt", "NBA: Suns at Lakers", group = "NBA")
        val espn = channel("espn", "ESPN", group = "Sports")
        val g = game(
            "nba:1", Sports.State.LIVE,
            namedTeam("Los Angeles Lakers", "LAL", "104", true),
            namedTeam("Phoenix Suns", "PHX", "98"),
            league = nba(), broadcasts = listOf("ESPN"),
        )
        assertEquals(event, Sports.ChannelLookup(listOf(event, espn)).find(g))
    }

    @Test
    fun eventChannelMustBeThisGameNotTheWomensOrAnotherDay() {
        // Lazio v Napoli (men, Serie A) kicks off 2026-11-08 21:30 Australia/Brisbane = 06:30 US Eastern.
        val kickoff = java.time.ZonedDateTime.of(2026, 11, 8, 6, 30, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val g = Sports.Game(
            id = "seriea:1", league = Sports.League("ita.1", "Serie A", "soccer/ita.1"), state = Sports.State.UPCOMING, detail = "",
            startMs = kickoff, home = namedTeam("Napoli", "NAP"), away = namedTeam("Lazio", "LAZ"), broadcasts = emptyList(), venue = null,
        )
        val womens = channel("w", "DAZN US 020", group = "Sports")
        val men = channel("m", "PARAMOUNT PPV 025", group = "Sports")
        val later = channel("l", "PARAMOUNT PPV 031", group = "Sports")
        val raw = mapOf(
            "w" to "US| DAZN US 020 [Lazio vs. Napoli | Serie A Women (2026-11-09 17:00:00)]",
            "m" to "US| PARAMOUNT PPV 025 [Lazio vs. Napoli (2026-11-08 06:30:00)]",
            "l" to "US| PARAMOUNT PPV 031 [Napoli vs. Lazio (2026-12-20 12:00:00)]",
        )
        assertEquals(men, Sports.ChannelLookup(listOf(womens, later, men), raw).find(g))
        assertNull(Sports.ChannelLookup(listOf(womens, later), raw).find(g))
        // Same kick-off time, but the women's sides: still not this game.
        val sameTime = mapOf("w" to "US| DAZN US 020 [Lazio vs. Napoli | Serie A Women (2026-11-08 06:30:00)]")
        assertNull(Sports.ChannelLookup(listOf(womens), sameTime).find(g))
    }

    @Test
    fun espnPlusGameNeverLandsOnLinearEspn() {
        val espn = channel("espn", "ESPN", group = "Sports")
        val g = game("nhl:1", Sports.State.LIVE, team("BOS"), team("NYR"), league = nba(), broadcasts = listOf("ESPN+"))
        assertNull(Sports.ChannelLookup(listOf(espn)).find(g))
        val plus = channel("plus", "ESPN+", group = "Sports")
        assertEquals(plus, Sports.ChannelLookup(listOf(espn, plus)).find(g))
    }

    @Test
    fun rawEventNameFindsTheMatchupChannel() {
        // The cleaned name drops the bracketed matchup; only the raw provider name carries it.
        val event = channel("evt", "ESPN+ 004", group = "Sports")
        val espn = channel("espn", "ESPN", group = "Sports")
        val g = game(
            "laliga:1", Sports.State.LIVE, namedTeam("Granada", "GRA"), namedTeam("Eldense", "ELD"),
            league = nba(), broadcasts = listOf("ESPN+"),
        ).copy(startMs = java.time.ZonedDateTime.of(2026, 11, 1, 7, 50, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli())
        val raw = mapOf("evt" to "US| ESPN+ 004 [Soccer: Eldense vs. Granada (2026-11-01 07:50:00)]")
        assertEquals(event, Sports.ChannelLookup(listOf(espn, event), raw).find(g))
    }

    @Test
    fun guideEntriesRecogniseTheGame() {
        val g = game(
            "nrl:1", Sports.State.UPCOMING, namedTeam("Brisbane Broncos", "BRI"), namedTeam("Melbourne Storm", "MEL"), league = nba(),
        )
        assertTrue(Sports.airsGame("NRL: Broncos v Storm", null, g))
        assertTrue(Sports.airsGame("Rugby League", "Brisbane Broncos vs Melbourne Storm", g))
        // One team, or the 3-letter codes inside ordinary words, is not the game.
        assertFalse(Sports.airsGame("NRL: Broncos v Eels", null, g))
        assertFalse(Sports.airsGame("Melbourne Weekly: Brisbane", "Bridge of Melody", g))
    }

    @Test
    fun guideMatchBeatsListedNetworkAndHomeCountryWins() {
        val espn = channel("espn", "ESPN", group = "Sports")
        val us = channel("us", "Fox Sports 1", group = "Sports").copy(epgId = "fs1.us")
        val au = channel("au", "Fox League", group = "Sports").copy(epgId = "foxleague.au")
        val g = game(
            "nrl:1", Sports.State.LIVE, namedTeam("Brisbane Broncos", "BRI"), namedTeam("Melbourne Storm", "MEL"),
            league = nba(), broadcasts = listOf("ESPN"),
        )
        val raw = mapOf("us" to "US| Fox Sports 1", "au" to "AU| Fox League")
        val guide = mapOf("nrl:1" to listOf("fs1.us", "foxleague.au"))
        assertEquals(au, Sports.ChannelLookup(listOf(espn, us, au), raw, guide = guide, home = "AU").find(g))
        assertEquals(us, Sports.ChannelLookup(listOf(espn, us, au), raw, guide = guide, home = "US").find(g))
        assertEquals(espn, Sports.ChannelLookup(listOf(espn, us, au), raw, home = "AU").find(g))
    }

    @Test
    fun exactBroadcastMatchBeatsPrefixMatch() {
        val espn2 = channel("espn2", "ESPN2")
        val g = game("nba:1", Sports.State.LIVE, team("LAL"), team("PHX"), league = nba(), broadcasts = listOf("ESPN"))
        // Only ESPN2 exists: "ESPN" should not claim it (different network), so no match.
        assertNull(Sports.ChannelLookup(listOf(espn2)).find(g))
        // The exact broadcast wins when it exists.
        val espn = channel("espn", "ESPN")
        assertEquals(espn, Sports.ChannelLookup(listOf(espn2, espn)).find(g))
    }

    @Test
    fun localRsnResolvesThroughSharedWords() {
        val family = channel("rsn", "Arizona Family Sports", group = "RSN")
        val g = game("nba:1", Sports.State.LIVE, team("PHX"), team("LAL"), league = nba(), broadcasts = listOf("Arizona Family TV"))
        // "Arizona Family TV" vs "Arizona Family Sports": two shared meaningful words.
        assertEquals(family, Sports.ChannelLookup(listOf(family)).find(g))
        assertTrue(Sports.ChannelLookup(listOf(family)).missing(g).isEmpty())
    }

    @Test
    fun unverifiedGameGetsNoChannelButLeagueChannelsStayOffered() {
        // NBA TV / League Pass show one game of many: guessing sends the viewer to the wrong game.
        val pass = channel("pass", "NBA League Pass", group = "NBA")
        val g = game("nba:1", Sports.State.LIVE, team("PHX"), team("LAL"), league = nba(), broadcasts = listOf("Arizona Family TV"))
        val lookup = Sports.ChannelLookup(listOf(pass))
        assertNull(lookup.find(g))
        assertEquals(listOf("Arizona Family TV"), lookup.missing(g))
        assertEquals(listOf(pass), lookup.candidates(g))
    }

    @Test
    fun numberedEventSlotsNeedTheirEventToMatch() {
        val rack = (1..3).map { channel("nba$it", "NBA 0$it", group = "NBA") }
        val g = game("nba:1", Sports.State.LIVE, team("PHX"), team("LAL"), league = nba(), broadcasts = listOf("Arizona Family TV"))
        val lookup = Sports.ChannelLookup(rack)
        assertNull(lookup.find(g))
        assertFalse(lookup.isDirectMatch(g))
        assertEquals(3, lookup.candidates(g).size)
    }

    @Test
    fun espnPlusSlotsAreNotTheEspnPlusNetwork() {
        // Seen on the Shield: four NHL games all sent to "ESPN+ 001" via the ESPN+ group.
        val slots = (1..2).map { channel("e$it", "ESPN+ 00$it", group = "ESPN+") }
        val g = game("nhl:1", Sports.State.LIVE, team("CGY"), team("PHI"), broadcasts = listOf("ESPN+"))
        assertNull(Sports.ChannelLookup(slots).find(g))
    }

    @Test
    fun numberedSeriesFeedsAreSlotsButNumberedNetworksAreNot() {
        val prime = channel("p1", "Prime Video Serie 1 HD")
        val jax = game("nfl:9", Sports.State.UPCOMING, team("BAL"), team("JAX"), broadcasts = listOf("Prime Video"))
        assertNull(Sports.ChannelLookup(listOf(prime)).find(jax))
        val foxSports = channel("fs", "Fox Sports 503", group = "Fox Sports")
        val epl = game("epl:1", Sports.State.LIVE, team("ARS"), team("CHE"), broadcasts = listOf("Fox Sports"))
        assertEquals(foxSports, Sports.ChannelLookup(listOf(foxSports)).find(epl))
    }

    @Test
    fun aNetworkIsNotItsSportsSpinOff() {
        // CBS is not CBS Sports Network; Fox is not Fox Sports. ESPN HD is still ESPN.
        val cbssn = channel("cbssn", "CBS Sports Network")
        val nflGame = game("nfl:1", Sports.State.UPCOMING, team("NO"), team("CLE"), broadcasts = listOf("CBS"))
        assertNull(Sports.ChannelLookup(listOf(cbssn)).find(nflGame))
        val espnHd = channel("espnhd", "ESPN HD")
        val espnGame = game("nba:3", Sports.State.LIVE, team("DAL"), team("BKN"), league = nba(), broadcasts = listOf("ESPN"))
        assertEquals(espnHd, Sports.ChannelLookup(listOf(espnHd)).find(espnGame))
    }

    @Test
    fun gameForChannelPrefersTitleMatch() {
        val channel = channel("nbatv", "NBA TV", group = "NBA")
        val titled = game(
            "nba:2", Sports.State.LIVE,
            namedTeam("Los Angeles Lakers", "LAL", "91", true),
            namedTeam("Phoenix Suns", "PHX", "88"),
            league = nba(), broadcasts = listOf("NBA TV"),
        )
        val other = game(
            "nba:1", Sports.State.LIVE,
            namedTeam("New York Knicks", "NYK", "97"),
            namedTeam("Boston Celtics", "BOS", "99", true),
            league = nba(), broadcasts = listOf("NBA TV"),
        )
        val found = Sports.gameForChannel(listOf(other, titled), channel, "Phoenix Suns vs Los Angeles Lakers")
        assertEquals("nba:2", found?.id)
    }

    @Test
    fun gameForChannelSkipsFinalGames() {
        val channel = channel("espn", "ESPN")
        val final = game("nba:1", Sports.State.FINAL, team("PHX"), team("LAL"), league = nba(), broadcasts = listOf("ESPN"))
        val live = game("nba:2", Sports.State.LIVE, team("BOS"), team("NYK"), league = nba(), broadcasts = listOf("ESPN"))
        assertEquals("nba:2", Sports.gameForChannel(listOf(final, live), channel)?.id)
    }

    @Test
    fun genericNetworkDoesNotGuessBetweenTwoLiveGames() {
        val channel = channel("espn", "ESPN")
        val knicks = game(
            "nba:1", Sports.State.LIVE,
            namedTeam("New York Knicks", "NYK", "97"),
            namedTeam("Boston Celtics", "BOS", "99", true),
            league = nba(), broadcasts = listOf("ESPN"),
        )
        val lakers = game(
            "nba:2", Sports.State.LIVE,
            namedTeam("Los Angeles Lakers", "LAL", "91", true),
            namedTeam("Phoenix Suns", "PHX", "88"),
            league = nba(), broadcasts = listOf("ESPN"),
        )
        // A generic studio title must not pick a game from another arena.
        assertNull(Sports.gameForChannel(listOf(knicks, lakers), channel, "NBA Today"))
        // The title is the tie-breaker when it names both teams.
        assertEquals("nba:2", Sports.gameForChannel(listOf(knicks, lakers), channel, "Lakers vs Suns")?.id)
    }

    @Test
    fun eventChannelUsesItsOwnTeamNames() {
        val channel = channel("evt", "NBA: Suns at Lakers", group = "NBA")
        val g = game(
            "nba:1", Sports.State.LIVE,
            namedTeam("Los Angeles Lakers", "LAL", "104", true),
            namedTeam("Phoenix Suns", "PHX", "98"),
            league = nba(), broadcasts = listOf("ESPN"),
        )
        assertEquals("nba:1", Sports.gameForChannel(listOf(g), channel)?.id)
    }

    // ---- countries ---------------------------------------------------------------------------

    @Test
    fun countriesGroupTheLeagues() {
        val au = Sports.leaguesFor(Sports.Country.AU).map { it.id }
        assertTrue(au.containsAll(listOf("afl", "aleague", "nrl", "nbl", "superrugby", "bbl")))
        assertTrue("nfl" !in au && "epl" !in au)
        // Global competitions follow every market.
        assertTrue(Sports.leaguesFor(Sports.Country.AU).any { it.id == "f1" })
        assertTrue(Sports.leaguesFor(Sports.Country.US).any { it.id == "ufc" })
        // Shared leagues appear under each market that watches them.
        assertTrue(Sports.leaguesFor(Sports.Country.CA).any { it.id == "nhl" })
        assertTrue(Sports.leaguesFor(Sports.Country.NZ).any { it.id == "superrugby" })
        assertEquals(Sports.LEAGUES, Sports.leaguesFor(null))
    }

    // ---- snapshot codec ----------------------------------------------------------------------

    @Test
    fun snapshotRoundTripKeepsEverything() {
        val original = game(
            "nba:1", Sports.State.FINAL,
            namedTeam("Los Angeles Lakers", "LAL", "112", true),
            namedTeam("Phoenix Suns", "PHX", "104"),
            league = nba(), broadcasts = listOf("ESPN", "TNT"),
        )
        val decoded = Sports.decodeGames(Sports.encodeGames(listOf(original)))
        assertEquals(1, decoded.size)
        val g = decoded.first()
        assertEquals("nba:1", g.id)
        assertEquals(Sports.State.FINAL, g.state)
        assertEquals("NBA", g.league.name)
        // The decoded league is the real LEAGUES entry, so country grouping survives a cold start.
        assertEquals(Sports.LEAGUES.first { it.id == "nba" }.countries, g.league.countries)
        assertEquals(listOf("ESPN", "TNT"), g.broadcasts)
        assertEquals("112", g.home.score)
        assertEquals("Anywhere", g.venue)
    }

    @Test
    fun snapshotIgnoresUnknownLeagues() {
        val doomed = Sports.League("none", "Gone", "ghost/league")
        val g = game("none:1", Sports.State.LIVE, team("A"), team("B"), league = doomed)
        assertTrue(Sports.decodeGames(Sports.encodeGames(listOf(g))).isEmpty())
    }

    // ---- advanced parsers --------------------------------------------------------------------

    @Test
    fun parsesTeamStatsFromBoxscore() {
        val root = JSONObject(
            """{"boxscore":{"teams":[
                {"homeAway":"away","statistics":[{"name":"totalYards","displayValue":"301"}]},
                {"homeAway":"home","statistics":[{"name":"totalYards","label":"Total Yards","displayValue":"289"}]}
            ]}}""",
        )
        val stats = Sports.parseTeamStats(root)
        assertEquals(1, stats.size)
        assertEquals(Sports.TeamStat("totalYards", "Total Yards", "301", "289"), stats.first())
    }

    @Test
    fun parsesPlayByPlayNewestFirst() {
        val root = JSONObject(
            """{"drives":{
                "previous":[{"team":{"abbreviation":"KC"},"plays":[
                    {"id":"p2","text":"Field Goal","period":{"number":2},"clock":{"displayValue":"0:03"},"awayScore":3,"homeScore":7}
                ]}],
                "current":{"team":{"abbreviation":"BUF"},"plays":[
                    {"id":"p1","text":"Kickoff","period":{"number":3},"clock":{"displayValue":"15:00"}}
                ]}
            }}""",
        )
        val plays = Sports.parsePlays(root)
        assertEquals(listOf("p1", "p2"), plays.map { it.id })
        assertEquals("Kickoff", plays.first().text)
        assertEquals("BUF", plays.first().team)
    }

    @Test
    fun parsesScoringPlaysNewestFirst() {
        val root = JSONObject(
            """{"scoringPlays":[
                {"id":"s1","text":"TD Suns","team":{"abbreviation":"PHX"}},
                {"id":"s2","text":"3PT Lakers","team":{"abbreviation":"LAL"}}
            ]}""",
        )
        val plays = Sports.parseScoringPlays(root)
        assertEquals(listOf("s2", "s1"), plays.map { it.id })
        assertTrue(plays.all { it.scoring })
    }

    @Test
    fun parsesLiveWinProbabilityAsHomePercent() {
        val root = JSONObject(
            """{"winprobability":[{"homeWinPercentage":0.63},{"homeWinPercentage":0.714}]}""",
        )
        assertEquals(71, Sports.parseLiveHomeWinChance(root))
    }

    @Test
    fun parsesTeamLeaders() {
        val root = JSONObject(
            """{"leaders":[{"team":{"abbreviation":"LAL"},"leaders":[
                {"displayName":"Points","leaders":[{"athlete":{"displayName":"LeBron James"},"displayValue":"28"}]}
            ]}]}""",
        )
        assertEquals(listOf(Sports.LeaderStat("LAL", "Points", "LeBron James", "28")), Sports.parseLeaders(root))
    }
}
