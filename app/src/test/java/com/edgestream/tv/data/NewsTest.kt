package com.edgestream.tv.data

import com.edgestream.tv.ui.shell.TickerPage
import com.edgestream.tv.ui.shell.tickerPages
import org.junit.Assert.assertEquals
import org.junit.Test

class NewsTest {

    @Test fun parsesRssTitlesWithCdataAndEntities() {
        val xml = """<rss><channel><title>ABC News</title>
            <item><title><![CDATA[Storm hits Brisbane]]></title></item>
            <item><title>Rates &amp; the RBA&#39;s call</title></item>
            </channel></rss>"""
        assertEquals(listOf("Storm hits Brisbane", "Rates & the RBA's call"), News.parseTitles(xml))
    }

    @Test fun parsesAtomEntries() {
        val xml = """<feed><title>Feed</title><entry><title type="html">Matildas win</title></entry></feed>"""
        assertEquals(listOf("Matildas win"), News.parseTitles(xml))
    }

    @Test fun garbageIsEmpty() = assertEquals(emptyList<String>(), News.parseTitles("<html>not a feed"))

    private val league = Sports.League("nrl", "NRL", "rugby-league/nrl")
    private fun team(a: String) = Sports.Team(a, a, null, "1", false)
    private fun game(id: String, s: Sports.State) = Sports.Game(id, league, s, "", 0, team("H$id"), team("A$id"), emptyList(), null)

    @Test fun pagesAreGamesThenHeadlines() {
        val pages = tickerPages(
            listOf(game("1", Sports.State.LIVE), game("2", Sports.State.UPCOMING), game("3", Sports.State.FINAL)),
            listOf(Headline("ABC News", "One"), Headline("ABC News", "Two")),
            perPage = 2,
        )
        assertEquals(listOf("Live", "Results", "Headlines", "Headlines"), pages.map { it.badge })
        assertEquals(true, pages[0].live)
        assertEquals(listOf("1", "2"), (pages[0] as TickerPage.Scores).games.map { it.id })
    }

    @Test fun badgesFollowTheLauncherRules() {
        assertEquals("Coming up", tickerPages(listOf(game("1", Sports.State.UPCOMING)), emptyList(), 3).single().badge)
        assertEquals("Results", tickerPages(listOf(game("1", Sports.State.FINAL)), emptyList(), 3).single().badge)
        assertEquals("Scoreboard", tickerPages(listOf(game("1", Sports.State.FINAL), game("2", Sports.State.UPCOMING)), emptyList(), 3).single().badge)
    }

    @Test fun nothingToShowIsNoPages() = assertEquals(emptyList<TickerPage>(), tickerPages(emptyList(), emptyList(), 3))
}
