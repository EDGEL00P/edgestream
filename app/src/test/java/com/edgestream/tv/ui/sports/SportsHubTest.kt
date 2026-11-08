package com.edgestream.tv.ui.sports

import com.edgestream.tv.data.Sports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsHubTest {
    private val nba = Sports.League("nba", "NBA", "basketball/nba")
    private val nrl = Sports.League("nrl", "NRL", "rugby-league/nrl")
    private fun team(a: String) = Sports.Team(a, a, null, "0", false)
    private fun g(id: String, l: Sports.League, s: Sports.State, start: Long = 0) =
        Sports.Game(id, l, s, "", start, team("H$id"), team("A$id"), emptyList(), null)

    private val games = listOf(
        g("1", nrl, Sports.State.UPCOMING, 500), g("2", nba, Sports.State.LIVE), g("3", nba, Sports.State.FINAL), g("4", nrl, Sports.State.UPCOMING, 300),
    )

    @Test fun chipsAreAllThenLiveLeaguesFirstThenRacing() =
        assertEquals(listOf(ALL_SPORTS, "NBA", "NRL", RACING), sportChips(games, hasRaces = true))

    @Test fun noRacingChipWithoutRaces() = assertEquals(listOf(ALL_SPORTS, "NBA", "NRL"), sportChips(games, hasRaces = false))

    @Test fun filterByLeagueKeepsLiveFirstThenSoonest() {
        assertEquals(listOf("2", "4", "1", "3"), gamesFor(games, ALL_SPORTS).map { it.id })
        assertEquals(listOf("4", "1"), gamesFor(games, "NRL").map { it.id })
    }

    @Test fun featuredIsLiveElseNext() {
        assertEquals("2", featuredGame(games)?.id)
        assertEquals("4", featuredGame(games.filter { it.state != Sports.State.LIVE })?.id)
        assertNull(featuredGame(emptyList()))
    }
}
