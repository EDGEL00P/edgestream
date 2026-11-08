package com.edgestream.tv.remote

import com.edgestream.tv.data.Sports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAnswersTest {
    private val nrl = Sports.League("nrl", "NRL", "rugby-league/3")
    private fun t(name: String, abbr: String, score: String? = null) = Sports.Team(abbr, name, null, score, false)
    private fun g(id: String, state: Sports.State, home: Sports.Team, away: Sports.Team, start: Long, detail: String = "") =
        Sports.Game(id = id, league = nrl, state = state, detail = detail, startMs = start, home = home, away = away, broadcasts = emptyList(), venue = null)

    private val final = g("1", Sports.State.FINAL, t("Brisbane Broncos", "BRI", "24"), t("Melbourne Storm", "MEL", "18"), 1_000)
    private val next = g("2", Sports.State.UPCOMING, t("Penrith Panthers", "PEN"), t("Brisbane Broncos", "BRI"), 9_000_000)
    private val live = g("3", Sports.State.LIVE, t("Sydney Roosters", "SYD", "6"), t("Melbourne Storm", "MEL", "12"), 5_000, "2nd Half 52'")

    @Test fun findsATeamByNicknameOrCodePreferringLiveThenNext() {
        assertEquals("3", VoiceAnswers.findTeam(listOf(final, next, live), "Storm")?.id)
        assertEquals("2", VoiceAnswers.findTeam(listOf(final, next, live), "the Broncos")?.id)
        assertEquals("2", VoiceAnswers.findTeam(listOf(final, next), "BRI")?.id)
        assertEquals("1", VoiceAnswers.findTeam(listOf(final), "Broncos")?.id)
        assertEquals(null, VoiceAnswers.findTeam(listOf(final, next), "Eels"))
    }

    @Test fun liveScoresHideWhenSpoilersAreOff() {
        val shown = VoiceAnswers.team("Storm", live, "Fox League (502)", hideScores = false, use24h = true)
        assertTrue(shown, "12" in shown && "Fox League (502)" in shown)
        val hidden = VoiceAnswers.team("Storm", live, null, hideScores = true, use24h = true)
        assertFalse(hidden, hidden.any(Char::isDigit))
        assertFalse(VoiceAnswers.team("Broncos", final, null, hideScores = true, use24h = true).contains("24"))
    }

    @Test fun todaysGamesNameWhatsLiveAndWhatsNext() {
        val text = VoiceAnswers.games(listOf(final, next, live), hideScores = false, use24h = true, now = 6_000)
        assertTrue(text, text.startsWith("1 live: Melbourne Storm 12, Sydney Roosters 6"))
        assertTrue(text, "Next: Brisbane Broncos v Penrith Panthers" in text)
        assertEquals("No games are live right now.", VoiceAnswers.games(listOf(final), false, true, 6_000))
    }

    @Test fun unknownTeamSaysSo() {
        assertEquals("I couldn't find a game for Eels this week.", VoiceAnswers.team("Eels", null, null, false, true))
    }
}
