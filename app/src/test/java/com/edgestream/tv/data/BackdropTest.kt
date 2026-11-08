package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropTest {

    private fun img(url: String) = """{"url":"$url","width":1296}"""
    private fun news(vararg urls: String) = """{"articles":[{"images":[${urls.joinToString(",") { img(it) }}]}]}"""

    @Test fun keepsOnlyFullWidthActionPhotos() = assertEquals(
        listOf(
            "https://a.espncdn.com/photo/2026/1107/r1742411_1296x729_16-9.jpg",
            "https://a.espncdn.com/photo/2026/1107/r1742522_2_1296x864_3-2.jpg",
        ),
        Backdrop.parseEspnPhotos(news(
            "https://a.espncdn.com/photo/2026/1107/r1742411_1296x729_16-9.jpg",
            "https://a.espncdn.com/photo/2026/1107/r1742411_1296x729_16-9.jpg", // duplicate
            "https://a.espncdn.com/photo/2026/1107/r1742522_2_1296x864_3-2.jpg",
            "https://a.espncdn.com/photo/2026/1107/r1742610_600x400_3-2.jpg", // too small for 4K
            "https://a.espncdn.com/photo/2026/1104/nfl-week-9-unis-16x9.jpg", // graphic, not a photo
            "https://a.espncdn.com/i/headshots/nba/players/full/1966.png",     // headshot
            "https://evil.example/photo/2026/1107/r1_1296x729_16-9.jpg",       // other host
        )),
    )

    @Test fun malformedNewsIsEmpty() {
        assertEquals(emptyList<String>(), Backdrop.parseEspnPhotos("<html>"))
        assertEquals(emptyList<String>(), Backdrop.parseEspnPhotos("{}"))
        assertEquals(emptyList<String>(), Backdrop.parseEspnPhotos("""{"articles":[{"images":[{}]}]}"""))
    }

    @Test fun leaguesTakeTurns() = assertEquals(
        listOf("nfl1", "nrl1", "nhl1", "nfl2", "nhl2"),
        Backdrop.interleave(listOf(listOf("nfl1", "nfl2", "nfl3"), listOf("nrl1"), listOf("nhl1", "nhl2")), perLeague = 2),
    )

    @Test fun freshWithin6h() {
        assertTrue(Backdrop.isFresh(0, 21_599_999))
        assertFalse(Backdrop.isFresh(0, 21_600_000))
        assertFalse("clock went backwards", Backdrop.isFresh(10_000, 0))
    }

}
