package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WatchTrackingTest {
    private val film = WatchTracking.Watching("tt0088763", null, null)
    private val episode = WatchTracking.Watching("tt0903747", 2, 5)

    @Test fun traktBodiesNameAFilmOrAShowEpisode() {
        val f = WatchTracking.traktBody(film, 42.5)
        assertEquals("tt0088763", f.getJSONObject("movie").getJSONObject("ids").getString("imdb"))
        assertEquals(42.5, f.getDouble("progress"), 0.0)
        assertFalse(f.has("show"))
        val e = WatchTracking.traktBody(episode, 130.0)
        assertEquals("tt0903747", e.getJSONObject("show").getJSONObject("ids").getString("imdb"))
        assertEquals(2, e.getJSONObject("episode").getInt("season"))
        assertEquals(5, e.getJSONObject("episode").getInt("number"))
        assertEquals(100.0, e.getDouble("progress"), 0.0)
    }

    @Test fun simklHistoryNestsSeasonAndEpisode() {
        assertEquals("tt0088763", WatchTracking.simklBody(film).getJSONArray("movies").getJSONObject(0).getJSONObject("ids").getString("imdb"))
        val season = WatchTracking.simklBody(episode).getJSONArray("shows").getJSONObject(0).getJSONArray("seasons").getJSONObject(0)
        assertEquals(2, season.getInt("number"))
        assertEquals(5, season.getJSONArray("episodes").getJSONObject(0).getInt("number"))
    }
}
