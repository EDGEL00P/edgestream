package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskTest {
    private val nfl = Sports.League("nfl", "NFL", "football/nfl")
    private val epl = Sports.League("epl", "Premier League", "soccer/eng.1")
    private fun team(abbr: String, name: String) = Sports.Team(abbr, name, null, null, false)
    private val kc = Sports.Game("nfl:1", nfl, Sports.State.UPCOMING, "", 0, team("KC", "Kansas City Chiefs"), team("NYJ", "New York Jets"), emptyList(), null)
    private val ars = Sports.Game("epl:2", epl, Sports.State.LIVE, "60'", 0, team("ARS", "Arsenal"), team("CHE", "Chelsea"), emptyList(), null)

    private fun pick(g: Sports.Game, tier: NflModel.Tier?) = NflPicks.Pick(
        game = g, espnId = g.id.substringAfter(':'), winner = g.home.abbr, winProb = 0.76, modelHomeMargin = 10.0,
        factors = NflModel.Factors(7.5, 2.5, 0.0, 0.0), marketHomeMargin = 9.5, marketHomeProb = 0.8, totalLine = 47.5,
        tier = tier, values = emptyList(),
    )

    private val board = NflPicks.Board(
        picks = listOf(pick(kc, NflModel.Tier.LOCK)), rules = emptyList(), tiers = emptyList(), backtest = null,
        log = NflPicks.LogSummary(NflModel.Record.EMPTY, null, 0),
    )

    @Test fun keywordsListTheirKind() {
        assertEquals(listOf("epl:2"), Ask.answer("live", listOf(kc, ars), board).map { (it as Ask.Answer.Live).game.id })
        assertTrue(Ask.answer("locks", listOf(kc, ars), board).single() is Ask.Answer.Pick)
    }

    @Test fun teamNamesAndAbbreviationsFindGamesAndPicks() {
        assertEquals("New York Jets at Kansas City Chiefs", Ask.answer("chiefs", listOf(kc, ars), board).single().title)
        assertTrue(Ask.answer("KC", listOf(kc, ars), board).single() is Ask.Answer.Pick)
        assertTrue(Ask.answer("arsenal", listOf(kc, ars), board).single() is Ask.Answer.Live)
        assertTrue(Ask.answer("premier", listOf(kc, ars), board).single() is Ask.Answer.Live)
    }

    @Test fun shortOrUnknownQueriesAnswerNothing() {
        assertTrue(Ask.answer("k", listOf(kc), board).isEmpty())
        assertTrue(Ask.answer("zzz", listOf(kc, ars), board).isEmpty())
        assertTrue(Ask.answer("locks", listOf(kc), null).isEmpty())
    }
}
