package com.edgestream.tv.data

import com.edgestream.tv.data.NflModel.Rule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NflPicksTest {
    private val nfl = Sports.League("nfl", "NFL", "football/nfl")
    private fun team(abbr: String) = Sports.Team(abbr, abbr, null, null, false)

    private fun row(espn: String, result: Int?, total: Int?, spread: Double?, totalLine: Double?) = NflModel.Row(
        gameId = "g$espn", season = 2026, gameday = "2026-11-15", gametime = "13:00", home = "NYG", away = "WAS",
        result = result, total = total, neutral = false, homeRest = 7, awayRest = 7, spreadLine = spread, totalLine = totalLine,
        homeMoneyline = null, awayMoneyline = null, outdoor = true, windMph = null, homeQb = null, awayQb = null, espnId = espn,
    )

    @Test fun parsesEspnLineAsHomeMargin() {
        assertEquals(3.5, NflPicks.homeLine("KC -3.5", "KC", "ATL")!!, 1e-9)
        assertEquals(-3.5, NflPicks.homeLine("KC -3.5", "ATL", "KC")!!, 1e-9)
        assertEquals(0.0, NflPicks.homeLine("EVEN", "KC", "ATL")!!, 1e-9)
        assertEquals(0.0, NflPicks.homeLine("PK", "KC", "ATL")!!, 1e-9)
        assertNull(NflPicks.homeLine("DAL -3", "KC", "ATL"))
        assertNull(NflPicks.homeLine("", "KC", "ATL"))
    }

    @Test fun formatsLines() {
        assertEquals("NYG -2.5", NflPicks.lineText(2.4, "NYG", "WAS"))
        assertEquals("WAS -7", NflPicks.lineText(-7.1, "NYG", "WAS"))
        assertEquals("PK", NflPicks.lineText(0.1, "NYG", "WAS"))
    }

    @Test fun picksUseEspnLineAndFlagRules() {
        val r = row("401", null, null, -1.0, 40.0)
        val model = NflPicks.build(listOf(r))
        val game = Sports.Game(
            "nfl:401", nfl, Sports.State.UPCOMING, "", 0, team("NYG"), team("WAS"), emptyList(), null,
            line = Sports.Line("ESPN BET", "WAS -8.5", 44.5, 300, -400),
        )
        val pick = NflPicks.picks(model, listOf(game), mapOf("401" to 18)).single()
        assertEquals(-8.5, pick.marketHomeMargin!!, 1e-9)
        assertEquals("WAS", pick.winner)
        assertEquals(setOf(Rule.WIND_UNDER, Rule.HOME_DOG), pick.values.map { it.rule }.toSet())
        assertEquals("NYG +8.5", pick.values.first { it.rule == Rule.HOME_DOG }.selection)
        assertEquals("Under 44.5", pick.values.first { it.rule == Rule.WIND_UNDER }.selection)
        // Finished and non-NFL games are never picked.
        assertTrue(NflPicks.picks(model, listOf(game.copy(state = Sports.State.FINAL)), emptyMap()).isEmpty())
    }

    @Test fun gradesLockedPicksAndClosingLineValue() {
        val locked = listOf(
            NflPicks.Locked("1", Rule.HOME_DOG, -8.0, "NYG +8", 0),
            NflPicks.Locked("2", Rule.WIND_UNDER, 45.0, "Under 45", 0),
            NflPicks.Locked("3", Rule.HOME_DOG, -7.0, "NYG +7", 0),
        )
        val rows = listOf(row("1", -3, 40, -6.5, 44.0), row("2", 7, 47, 3.0, 43.0), row("3", null, null, -7.0, 44.0))
        val s = NflPicks.summarize(locked, rows)
        assertEquals(1, s.record.wins)
        assertEquals(1, s.record.losses)
        assertEquals(1, s.open)
        assertEquals((1.5 + 2.0) / 2, s.avgClv!!, 1e-9)
    }

    @Test fun pickLogLocksOnceAndSurvivesCorruption() {
        val dir = Files.createTempDirectory("nfl").toFile()
        val file = File(dir, "log.json")
        val log = NflPicks.PickLog(file)
        val model = NflPicks.build(listOf(row("9", null, null, -7.5, 41.0)))
        val game = Sports.Game("nfl:9", nfl, Sports.State.UPCOMING, "", 0, team("NYG"), team("WAS"), emptyList(), null)
        log.lock(NflPicks.picks(model, listOf(game), emptyMap()), 100)
        val moved = NflPicks.build(listOf(row("9", null, null, -9.5, 41.0)))
        log.lock(NflPicks.picks(moved, listOf(game), emptyMap()), 200)
        val entries = log.read()
        assertEquals(1, entries.size)
        assertEquals(-7.5, entries.single().line, 1e-9)
        file.writeText("{not json")
        assertTrue(log.read().isEmpty())
        dir.deleteRecursively()
    }

    @Test fun windAtKickoffHour() {
        val hourly = JSONObject("""{"time":["2026-11-15T17:00","2026-11-15T18:00"],"wind_speed_10m":[9.6,16.4]}""")
        val kickoff = java.time.Instant.parse("2026-11-15T18:25:00Z").toEpochMilli()
        assertEquals(16, NflPicks.hourlyAt(hourly, kickoff))
        assertNull(NflPicks.hourlyAt(hourly, java.time.Instant.parse("2026-11-16T18:00:00Z").toEpochMilli()))
        assertNull(NflPicks.hourlyAt(null, kickoff))
    }
}
