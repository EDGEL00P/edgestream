package com.edgestream.tv.ui.shell

import com.edgestream.tv.data.Sports
import com.edgestream.tv.data.Trackers
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.ProgressEntity
import com.edgestream.tv.data.db.VodEntity
import com.edgestream.tv.data.db.VodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandRowsTest {
    private val league = Sports.League("nba", "NBA", "basketball/nba")
    private fun team(a: String) = Sports.Team(a, a, null, null, false)
    private fun game(id: String, state: Sports.State, start: Long) =
        Sports.Game(id, league, state, "", start, team("H$id"), team("A$id"), emptyList(), null)
    private fun ch(k: String) = ChannelEntity(key = k, sourceId = 1, name = k, groupName = "g", logo = null, streamUrl = "u", epgId = null, number = 1)
    private fun vod(k: String, kind: VodKind) = VodEntity(key = k, sourceId = 1, kind = kind, name = k, category = "c", poster = null, streamUrl = "u")
    private fun prog(k: String) = ProgressEntity(k, k, null, "u", 10, 100, 0)

    private val empty = BandInputs(
        games = emptyList(), favourites = emptyList(), recent = emptyList(), nowTitle = { null },
        resume = emptyList(), latestMovies = emptyList(), latestSeries = emptyList(), alert = null, weather = null,
    )

    @Test fun sportsLiveThenUpcomingSorted() {
        val rows = bandRows(Tile.SPORTS, empty.copy(games = listOf(
            game("u2", Sports.State.UPCOMING, 300), game("l1", Sports.State.LIVE, 0),
            game("f1", Sports.State.FINAL, 0), game("u1", Sports.State.UPCOMING, 200),
        )))
        assertEquals(listOf("On Now", "Upcoming"), rows.map { it.title })
        assertEquals(listOf("game:l1"), rows[0].items.map { it.id })
        assertEquals(listOf("game:u1", "game:u2"), rows[1].items.map { it.id })
    }

    @Test fun emptyRowsOmitted() {
        Tile.entries.forEach { assertEquals("$it", emptyList<BandRow>(), bandRows(it, empty)) }
    }

    @Test fun moviesAndTvShareOneResumeRow() {
        val i = empty.copy(resume = listOf(prog("m1"), prog("s:E:2")))
        assertEquals(listOf("resume:m1", "resume:s:E:2"), bandRows(Tile.MOVIES, i).first().items.map { it.id })
        assertEquals("Continue watching", bandRows(Tile.MOVIES, i).first().title)
    }

    @Test fun newShowsFollowNewMovies() {
        val rows = bandRows(Tile.MOVIES, empty.copy(latestMovies = listOf(vod("v1", VodKind.MOVIE)), latestSeries = listOf(vod("s1", VodKind.SERIES))))
        assertEquals(listOf("New movies", "New shows"), rows.map { it.title })
    }

    @Test fun moviesNewestAfterResume() {
        val rows = bandRows(Tile.MOVIES, empty.copy(resume = listOf(prog("m1")), latestMovies = listOf(vod("v1", VodKind.MOVIE))))
        assertEquals(listOf("Continue watching", "New movies"), rows.map { it.title })
    }

    @Test fun liveFallsBackToRecent() {
        assertEquals("Favourites", bandRows(Tile.LIVE, empty.copy(favourites = listOf(ch("a")), recent = listOf(ch("b")))).single().title)
        assertEquals("Recently watched", bandRows(Tile.LIVE, empty.copy(recent = listOf(ch("b")))).single().title)
    }

    @Test fun rowsAreCapped() {
        val many = (1..40).map { game("l$it", Sports.State.LIVE, 0) }
        assertTrue(bandRows(Tile.SPORTS, empty.copy(games = many)).single().items.size <= 10)
    }

    @Test fun weatherShowsWarningFirst() {
        val w = Trackers.Warning("Severe storm", "detail", "Severe", "BOM")
        assertEquals("Warnings", bandRows(Tile.WEATHER, empty.copy(alert = w)).first().title)
    }

    @Test fun utilityHasNoRows() =
        assertEquals(emptyList<BandRow>(), bandRows(Tile.SEARCH, empty.copy(games = listOf(game("l", Sports.State.LIVE, 0)))))

    @Test fun weatherBandIsWarningsThenForecastDays() {
        val days = listOf(com.edgestream.tv.data.PublicApis.DayPoint("2026-11-08", 3, 30.0, 19.0), com.edgestream.tv.data.PublicApis.DayPoint("2026-11-09", 61, 25.0, 18.0))
        val w = Trackers.Warning("Severe storm", "detail", "Severe", "BOM")
        val rows = bandRows(Tile.WEATHER, empty.copy(alert = w, forecast = days))
        assertEquals(listOf("Warnings", "Forecast"), rows.map { it.title })
        assertEquals(listOf("day:2026-11-08", "day:2026-11-09"), rows[1].items.map { it.id })
    }

    @Test fun eachRemovableRowRemovesTheRightThing() {
        // Favourites un-favourite; they must never delete watch history.
        assertEquals(Removal.FAVOURITE, removalFor("Favourites"))
        assertEquals(Removal.RECENT, removalFor("Recently watched"))
        assertEquals(Removal.CONTINUE, removalFor("Continue watching"))
        assertEquals(null, removalFor("On Now"))
    }
}
