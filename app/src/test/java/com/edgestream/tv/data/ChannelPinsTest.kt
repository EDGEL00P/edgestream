package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelPinsTest {
    private val nrl = Sports.LEAGUES.first { it.id == "nrl" }
    private fun team(abbr: String) = Sports.Team(abbr, abbr, null, null, false)
    private val game = Sports.Game("g1", nrl, Sports.State.UPCOMING, "", 0, team("BRI"), team("MEL"), emptyList(), null)

    @Test fun gamePinBeatsTeamPins() = assertEquals(
        "ch-game",
        ChannelPins.pinFor(game, mapOf("game:g1" to "ch-game", "team:nrl:BRI" to "ch-home", "team:nrl:MEL" to "ch-away")),
    )

    @Test fun homeTeamPinBeatsAway() =
        assertEquals("ch-home", ChannelPins.pinFor(game, mapOf("team:nrl:BRI" to "ch-home", "team:nrl:MEL" to "ch-away")))

    @Test fun awayTeamPinUsedAlone() = assertEquals("ch-away", ChannelPins.pinFor(game, mapOf("team:nrl:MEL" to "ch-away")))

    @Test fun otherLeaguesPinIgnored() = assertNull(ChannelPins.pinFor(game, mapOf("team:nfl:BRI" to "x")))

    @Test fun spoilerFreeHidesResult() {
        val final = game.copy(state = Sports.State.FINAL, home = game.home.copy(score = "24", winner = true), away = game.away.copy(score = "12"))
        val hidden = Sports.spoilerFree(final)
        assertEquals("–" to "–", hidden.home.score to hidden.away.score)
        assertEquals(false, hidden.home.winner)
        assertEquals("upcoming untouched", game, Sports.spoilerFree(game))
    }
}
