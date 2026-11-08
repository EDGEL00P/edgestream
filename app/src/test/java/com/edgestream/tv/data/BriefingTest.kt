package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefingTest {
    private val nfl = Sports.League("nfl", "NFL", "football/nfl")
    private val epl = Sports.League("epl", "Premier League", "soccer/eng.1")
    private fun team(abbr: String, name: String, score: String? = null) = Sports.Team(abbr, name, null, score, false)
    private fun text(parts: List<Briefing.Part>) = parts.joinToString("") { it.text }

    private val live = Sports.Game("nfl:1", nfl, Sports.State.LIVE, "Q4", 0, team("ATL", "Falcons", "17"), team("CIN", "Bengals", "31"), emptyList(), null)
    private val soccer = Sports.Game("epl:1", epl, Sports.State.LIVE, "60'", 0, team("CHE", "Chelsea", "1"), team("ARS", "Arsenal", "1"), emptyList(), null)
    private val next = Sports.Game("nfl:2", nfl, Sports.State.UPCOMING, "", 5_000, team("KC", "Chiefs"), team("NYJ", "Jets"), emptyList(), null)

    private fun pick(g: Sports.Game, tier: NflModel.Tier?) = NflPicks.Pick(
        g, g.id.substringAfter(':'), g.home.abbr, 0.86, 10.0, NflModel.Factors(7.5, 2.5, 0.0, 0.0), 9.5, 0.8, 47.5, tier, emptyList(),
    )

    @Test fun livePriorityNextPickLocksAndWeather() {
        val parts = Briefing.compose(listOf(soccer, live, next), listOf(pick(next, NflModel.Tier.LOCK)), 19.4, "Childers", null, { "4:00 am" }, now = 1_000)
        assertEquals(
            "2 live. Bengals lead Falcons 31–17  ·  Next: Jets at Chiefs 4:00 am, KC 86% Lock  ·  1 NFL Lock on the slate  ·  19° in Childers",
            text(parts),
        )
        assertTrue(parts.first().key)
    }

    @Test fun levelScoresAndQuietDays() {
        assertEquals("1 live. Arsenal and Chelsea level at 1–1", text(Briefing.compose(listOf(soccer), emptyList(), null, null, null, { "" }, 0)))
        assertTrue(Briefing.compose(emptyList(), emptyList(), null, null, null, { "" }, 0).isEmpty())
    }

    @Test fun warningsAreCalledOut() {
        val parts = Briefing.compose(emptyList(), emptyList(), 20.0, "Childers", "Severe thunderstorm", { "" }, 0)
        assertEquals("Warning: Severe thunderstorm  ·  20° in Childers", text(parts))
    }
}
