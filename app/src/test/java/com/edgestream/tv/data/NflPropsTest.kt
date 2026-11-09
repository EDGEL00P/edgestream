package com.edgestream.tv.data

import com.edgestream.tv.data.NflProps.Market
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NflPropsTest {
    /** The Action's slim files (tools/sports/build_nfl.py) must keep every column read here. */
    @Test fun slimBuilderKeepsEveryColumnPropsRead() {
        val script = File("../tools/sports/build_nfl.py").readText()
        fun list(name: String) = Regex(name + """ = \[([^\]]*)]""").find(script)!!.groupValues[1]
            .let { Regex(""""([a-z_]+)"""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        val stats = list("STAT_COLS")
        Market.entries.forEach { assertTrue("${it.column} missing from STAT_COLS", it.column in stats) }
        listOf("player_id", "season", "week", "team", "game_id").forEach { assertTrue("$it missing", it in stats) }
        assertEquals(setOf("espn_id", "gsis_id", "display_name", "position", "latest_team"), list("PLAYER_COLS"))
    }

    private val players = "gsis_id,display_name,position,espn_id\n00-1,Ace Receiver,WR,111\n00-2,Back Up,WR,222.0\n"
    private fun stats(vararg rows: String) =
        "player_id,season,week,game_id,passing_yards,rushing_yards,receiving_yards,receptions\n" + rows.joinToString("\n")

    @Test fun projectionNeedsEnoughRealGames() {
        assertNull(NflProps.project(listOf(50.0, 60.0, 70.0), Market.REC_YDS))
        assertNull(NflProps.project(listOf(0.0, 0.0, 0.0, 0.0, 10.0), Market.PASS_YDS))
        val p = NflProps.project(List(8) { 80.0 }, Market.REC_YDS)!!
        assertEquals(80.0, p.mean, 1e-9)
        // A flat history still gets a floor on its spread, never a certainty.
        assertEquals(20.0, p.sd, 1e-9)
    }

    @Test fun projectionWeightsRecentGames() {
        val p = NflProps.project(listOf(20.0, 20.0, 20.0, 20.0, 100.0, 100.0), Market.REC_YDS)!!
        assertTrue(p.mean > 50.0)
    }

    @Test fun parsesOnlyKnownOverUnderMarkets() {
        val page = JSONObject(
            """{"items":[
              {"athlete":{"${'$'}ref":"http://x/athletes/111?lang=en"},"type":{"name":"Total Receiving Yards (incl. overtime)"},"current":{"target":{"value":54.5}},"open":{"target":{"value":50.5}}},
              {"athlete":{"${'$'}ref":"http://x/athletes/111?lang=en"},"type":{"name":"Anytime Touchdown Scorer"},"current":{"target":{"value":1}}},
              {"team":{},"type":{"name":"Total Receptions (incl. overtime)"},"current":{"target":{"value":4.5}}}
            ]}""",
        )
        val lines = NflProps.parseLines(page)
        assertEquals(1, lines.size)
        assertEquals(NflProps.PropLine("111", Market.REC_YDS, 54.5, 50.5), lines.single())
    }

    @Test fun leansJoinOnEspnIdAndRespectThreshold() {
        val book = NflProps.buildBook(
            players,
            listOf(stats(*(1..8).map { "00-1,2026,$it,2026_0${it}_A_B,0,0,${90 + it},6" }.toTypedArray())),
        )
        val leans = NflProps.leans(
            book, "401", "2026_10_A_B",
            listOf(
                NflProps.PropLine("111", Market.REC_YDS, 75.5, 75.5),
                NflProps.PropLine("111", Market.RECEPTIONS, 6.0, null),
                NflProps.PropLine("999", Market.REC_YDS, 10.5, null),
            ),
        )
        assertEquals(1, leans.size)
        val l = leans.single()
        assertTrue(l.over)
        assertTrue(l.confidence >= NflProps.LEAN)
        assertEquals("Ace Receiver Over 75.5 Rec Yds", l.selection)
    }

    @Test fun playerTeamComesFromLatestGameAndMapsToEspn() {
        val roster = "gsis_id,display_name,position,espn_id,latest_team\n00-1,Traded Guy,WR,111,NYJ\n00-2,Rams Guy,WR,222,LA\n"
        val csv = "player_id,season,week,game_id,team,passing_yards,rushing_yards,receiving_yards,receptions\n" +
            "00-1,2026,2,g2,WAS,0,0,50,4\n00-1,2026,1,g1,NYJ,0,0,50,4\n"
        val book = NflProps.buildBook(roster, listOf(csv))
        assertTrue(NflProps.playsFor(book.byEspn.getValue("111"), "WSH"))
        assertTrue(NflProps.playsFor(book.byEspn.getValue("222"), "LAR"))
    }

    /** A line far below the player's average is the market pricing a role change, not value. */
    @Test fun skipsLinesThatSignalARoleChange() {
        val book = NflProps.buildBook(players, listOf(stats(*(1..8).map { "00-1,2026,$it,2026_0${it}_A_B,0,0,95,6" }.toTypedArray())))
        assertTrue(NflProps.leans(book, "401", "2026_10_A_B", listOf(NflProps.PropLine("111", Market.REC_YDS, 34.5, null))).isEmpty())
    }

    @Test fun logLocksLeanTracksCloseAndGrades() {
        val dir = Files.createTempDirectory("props").toFile()
        val book = NflProps.buildBook(
            players,
            listOf(stats(*(1..8).map { "00-1,2026,$it,2026_0${it}_A_B,0,0,95,6" }.toTypedArray(), "00-1,2026,10,2026_10_A_B,0,0,90,5")),
        )
        val lean = NflProps.leans(book, "401", "2026_10_A_B", listOf(NflProps.PropLine("111", Market.REC_YDS, 80.5, null))).single()
        val log = NflProps.PropLog(File(dir, "p.json"))
        log.update(listOf(lean), mapOf("401" to listOf(NflProps.PropLine("111", Market.REC_YDS, 80.5, null))), 1)
        // The line moves to 83.5 before kickoff; the lock keeps 80.5 and tracks 83.5 as the close.
        log.update(emptyList(), mapOf("401" to listOf(NflProps.PropLine("111", Market.REC_YDS, 83.5, null))), 2)
        val entry = log.read().single()
        assertEquals(80.5, entry.line, 1e-9)
        assertEquals(83.5, entry.lastLine, 1e-9)
        val s = log.summary(book, setOf("2026_10_A_B"))
        assertEquals(1, s.record.wins)
        assertEquals(3.0, s.avgMove!!, 1e-9)
        assertEquals(0, s.open)
        File(dir, "p.json").writeText("garbage")
        assertTrue(log.read().isEmpty())
        dir.deleteRecursively()
    }
}
