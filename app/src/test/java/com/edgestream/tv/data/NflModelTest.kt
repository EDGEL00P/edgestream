package com.edgestream.tv.data

import com.edgestream.tv.data.NflModel.Outcome
import com.edgestream.tv.data.NflModel.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NflModelTest {
    private val header = "game_id,season,game_type,week,gameday,gametime,away_team,away_score,home_team,home_score,location,result,total,home_rest,away_rest,spread_line,total_line,home_moneyline,away_moneyline,roof,wind,home_qb_id,away_qb_id,espn"

    private fun row(id: String, season: Int, day: String, away: String, home: String, result: Int?, qbHome: String = "", qbAway: String = "") =
        "$id,$season,REG,1,$day,13:00,$away,,$home,,Home,${result ?: ""},,7,7,,,,,outdoors,,$qbHome,$qbAway,"

    private val real: List<NflModel.Row> by lazy {
        val text = javaClass.getResource("/nfl/games.csv")!!.readText()
        NflModel.parse(text)
    }

    @Test fun splitsQuotedFields() {
        assertEquals(listOf("a", "b,c", "say \"hi\"", ""), NflModel.splitCsv("a,\"b,c\",\"say \"\"hi\"\"\","))
    }

    @Test fun parsesByHeaderNameAndMapsRelocatedFranchises() {
        val csv = "extra,$header\nx,${row("g1", 2010, "2010-09-12", "SD", "OAK", 3)}\nx,${row("g2", 2026, "2026-11-15", "KC", "ATL", null)}"
        val rows = NflModel.parse(csv)
        assertEquals(2, rows.size)
        assertEquals("LV", rows[0].home)
        assertEquals("LAC", rows[0].away)
        assertEquals(3, rows[0].result)
        assertTrue(rows[0].outdoor == true)
        assertNull(rows[1].result)
    }

    @Test fun missingRequiredColumnsYieldNothing() {
        assertTrue(NflModel.parse("foo,bar\n1,2").isEmpty())
        assertTrue(NflModel.parse("").isEmpty())
    }

    @Test fun ratingUpdateMatchesHandMath() {
        val rows = NflModel.parse("$header\n${row("g1", 2020, "2020-09-13", "B", "A", 10)}\n${row("g2", 2020, "2020-09-20", "B", "A", null)}")
        val p = NflModel.replay(rows)
        assertEquals(NflModel.HOME_FIELD, p[0].homeMargin, 1e-9)
        val delta = NflModel.K * (10 - NflModel.HOME_FIELD)
        assertEquals(2 * delta + NflModel.HOME_FIELD, p[1].homeMargin, 1e-9)
    }

    @Test fun newSeasonRegressesRatings() {
        val rows = NflModel.parse("$header\n${row("g1", 2020, "2020-09-13", "B", "A", 30)}\n${row("g2", 2021, "2021-09-12", "B", "A", null)}")
        val p = NflModel.replay(rows)
        val delta = NflModel.K * (30 - NflModel.HOME_FIELD)
        assertEquals(2 * delta * (1 - NflModel.REGRESS) + NflModel.HOME_FIELD, p[1].homeMargin, 1e-9)
    }

    @Test fun backupQuarterbackIsPenalised() {
        val rows = NflModel.parse(
            "$header\n" +
                "${row("g1", 2020, "2020-09-13", "B", "A", 0, qbHome = "q1")}\n" +
                "${row("g2", 2020, "2020-09-20", "B", "A", 0, qbHome = "q1")}\n" +
                row("g3", 2020, "2020-09-27", "B", "A", null, qbHome = "q2"),
        )
        val p = NflModel.replay(rows)
        // Both played games end level, so only home field, the residual updates and the QB term remain.
        val expected = p[1].homeMargin + 2 * NflModel.K * (0 - p[1].homeMargin) - NflModel.BACKUP_QB
        assertEquals(expected, p[2].homeMargin, 1e-9)
    }

    @Test fun predictionsNeverSeeLaterGames() {
        val cut = real.filter { it.season <= 2015 }
        val full = NflModel.replay(real).associateBy { it.row.gameId }
        NflModel.replay(cut).forEach { assertEquals(it.homeMargin, full.getValue(it.row.gameId).homeMargin, 1e-9) }
    }

    @Test fun normalCdf() {
        assertEquals(0.5, NflModel.phi(0.0), 1e-7)
        assertEquals(0.975, NflModel.phi(1.959964), 1e-6)
        assertEquals(0.025, NflModel.phi(-1.959964), 1e-6)
    }

    @Test fun pickLeansOnTheMarket() {
        assertEquals(NflModel.winProb(0.1 * 10 + 0.9 * 3), NflModel.pickHomeProb(10.0, 3.0), 1e-12)
        assertEquals(NflModel.winProb(10.0), NflModel.pickHomeProb(10.0, null), 1e-12)
        assertEquals(NflModel.Tier.LOCK, NflModel.tierFor(0.9))
        assertEquals(NflModel.Tier.STRONG, NflModel.tierFor(0.82))
        assertNull(NflModel.tierFor(0.79))
    }

    @Test fun rulesAndGrading() {
        assertEquals(listOf(Rule.WIND_UNDER, Rule.HOME_DOG), NflModel.rulesFor(true, 15, -7.0, 41.5))
        assertTrue(NflModel.rulesFor(false, 25, -6.5, 41.5).isEmpty())
        assertTrue(NflModel.rulesFor(null, 25, null, 41.5).isEmpty())
        assertEquals(Outcome.WIN, NflModel.gradeHome(-7.0, -6))
        assertEquals(Outcome.PUSH, NflModel.gradeHome(-7.0, -7))
        assertEquals(Outcome.LOSS, NflModel.gradeHome(-7.0, -8))
        assertEquals(Outcome.WIN, NflModel.gradeUnder(44.5, 44))
        assertEquals(Outcome.LOSS, NflModel.gradeUnder(44.5, 45))
    }

    @Test fun noVigNormalises() {
        assertEquals(0.5, NflModel.noVigHome(-110, -110)!!, 1e-12)
        assertNull(NflModel.noVigHome(null, -110))
    }

    /** The shipped rules and tiers must still clear break-even on the real holdout. */
    @Test fun realHoldoutStillHolds() {
        val predictions = NflModel.replay(real)
        val bt = NflModel.backtest(predictions)!!
        val rules = NflModel.ruleRecords(real)
        val tiers = NflModel.tierRecords(predictions)
        println("NFL holdout ${bt.firstSeason}-${bt.lastSeason}: winners ${"%.3f".format(bt.winnerHitRate)} n${bt.games} brier ${"%.4f".format(bt.modelBrier)} vs market ${"%.4f".format(bt.marketBrier)}")
        rules.forEach { println("  ${it.rule}: all ${it.all} holdout ${it.holdout}") }
        tiers.forEach { println("  ${it.tier}: all ${it.all} holdout ${it.holdout}") }
        assertTrue(bt.winnerHitRate in 0.6..0.7)
        assertTrue(bt.modelBrier < 0.25)
        rules.forEach { assertTrue("${it.rule} holdout ${it.holdout}", it.holdout.hitRate!! > 0.524 && it.holdout.graded >= 100) }
        assertTrue(tiers.first { it.tier == NflModel.Tier.LOCK }.holdout.hitRate!! >= 0.9)
    }
}
