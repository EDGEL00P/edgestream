package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandingsTest {
    private fun league(id: String) = Sports.LEAGUES.first { it.id == id }
    private fun stat(name: String, v: String) = """{"name":"$name","displayValue":"$v"}"""
    private fun entry(abbr: String, vararg stats: String) =
        """{"team":{"abbreviation":"$abbr","displayName":"$abbr FC","logos":[{"href":"https://x/$abbr.png"}]},"stats":[${stats.joinToString(",")}]}"""

    @Test fun pointsTableSortedByRank() {
        val json = """{"children":[{"name":"Premier League","standings":{"entries":[
            ${entry("MNC", stat("rank", "2"), stat("gamesPlayed", "10"), stat("wins", "7"), stat("ties", "0"), stat("losses", "3"), stat("pointDifferential", "+8"), stat("points", "21"))},
            ${entry("ARS", stat("rank", "1"), stat("gamesPlayed", "10"), stat("wins", "9"), stat("ties", "0"), stat("losses", "1"), stat("pointDifferential", "+10"), stat("points", "27"))}
        ]}}]}"""
        val t = Standings.parse(json, league("epl")).single()
        assertEquals(listOf("GP", "W", "D", "L", "DIFF", "PTS"), t.columns)
        assertEquals(listOf("ARS", "MNC"), t.rows.map { it.abbr })
        assertEquals(listOf("10", "9", "0", "1", "+10", "27"), t.rows.first().values)
        assertEquals("https://x/ARS.png", t.rows.first().logo)
    }

    @Test fun rugbyLeagueUsesItsOwnStatNames() {
        val json = """{"children":[{"name":"NRL","standings":{"entries":[
            ${entry("PEN", stat("rank", "1"), stat("gamesPlayed", "24"), stat("gamesWon", "18"), stat("gamesDrawn", "0"), stat("gamesLost", "6"), stat("pointsDifference", "+336"), stat("points", "42"))}
        ]}}]}"""
        assertEquals(listOf("24", "18", "0", "6", "+336", "42"), Standings.parse(json, league("nrl")).single().rows.single().values)
    }

    @Test fun usLeagueDropsColumnsTheFeedLacks() {
        val json = """{"children":[
            {"name":"Eastern Conference","standings":{"entries":[${entry("MIL", stat("playoffSeed", "2"), stat("wins", "1"), stat("losses", "8"), stat("winPercent", ".111"), stat("gamesBehind", "3.5"), stat("streak", "L4"))},
                                                              ${entry("CHI", stat("playoffSeed", "1"), stat("wins", "7"), stat("losses", "1"), stat("winPercent", ".875"), stat("gamesBehind", "-"), stat("streak", "W3"))}]}},
            {"name":"Western Conference","standings":{"entries":[${entry("OKC", stat("playoffSeed", "1"), stat("wins", "8"), stat("losses", "0"), stat("winPercent", "1.000"), stat("gamesBehind", "-"), stat("streak", "W8"))}]}}
        ]}"""
        val tables = Standings.parse(json, league("nba"))
        assertEquals(listOf("Eastern Conference", "Western Conference"), tables.map { it.group })
        assertEquals(listOf("W", "L", "PCT", "GB", "STRK"), tables[0].columns)
        assertEquals(listOf("CHI", "MIL"), tables[0].rows.map { it.abbr })
        assertEquals(listOf(1, 2), tables[0].rows.map { it.rank })
    }

    @Test fun hockeyHasOvertimeLosses() {
        val json = """{"children":[{"name":"East","standings":{"entries":[${entry("CAR", stat("gamesPlayed", "21"), stat("wins", "18"), stat("losses", "3"), stat("otLosses", "1"), stat("points", "37"))}]}}]}"""
        assertEquals(listOf("GP", "W", "L", "OTL", "PTS"), Standings.parse(json, league("nhl")).single().columns)
    }

    @Test fun malformedIsEmpty() = assertTrue(Standings.parse("<html>", league("nfl")).isEmpty())
}
