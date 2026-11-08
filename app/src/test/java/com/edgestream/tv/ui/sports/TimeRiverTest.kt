package com.edgestream.tv.ui.sports

import com.edgestream.tv.data.Sports
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeRiverTest {
    private val nfl = Sports.League("nfl", "NFL", "football/nfl")
    private val epl = Sports.League("epl", "Premier League", "soccer/eng.1")
    private fun team(a: String) = Sports.Team(a, a, null, null, false)
    private fun game(id: String, l: Sports.League, startH: Double, state: Sports.State = Sports.State.UPCOMING) =
        Sports.Game(id, l, state, "", (startH * 3_600_000).toLong(), team("A"), team("B"), emptyList(), null)

    @Test fun overlappingGamesStackAndLaterOnesReuseTracks() {
        // Two 1 pm NFL games overlap; a 7 pm game fits back on the first track.
        val lanes = riverLanes(listOf(game("1", nfl, 13.0), game("2", nfl, 13.0), game("3", nfl, 19.0)), 0, 24 * 3_600_000L)
        val tracks = lanes.single().tracks
        assertEquals(2, tracks.size)
        assertEquals(listOf("1", "3"), tracks[0].map { it.id })
        assertEquals(listOf("2"), tracks[1].map { it.id })
    }

    @Test fun liveLeaguesComeFirstAndOutOfWindowGamesDrop() {
        val lanes = riverLanes(
            listOf(game("n", nfl, 10.0), game("e", epl, 12.0, Sports.State.LIVE), game("late", nfl, 30.0)),
            9 * 3_600_000L, 21 * 3_600_000L,
        )
        assertEquals(listOf("Premier League", "NFL"), lanes.map { it.league })
        assertEquals(listOf("n"), lanes[1].tracks.flatten().map { it.id })
    }

    @Test fun durationsFollowTheSport() {
        assertEquals(195 * 60_000L, durationOf(game("x", nfl, 0.0)))
        assertEquals(115 * 60_000L, durationOf(game("y", epl, 0.0)))
    }
}
