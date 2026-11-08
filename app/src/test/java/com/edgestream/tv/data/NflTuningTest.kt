package com.edgestream.tv.data

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NflTuningTest {
    private val rows by lazy { NflModel.parse(javaClass.getResource("/nfl/games.csv")!!.readText()) }

    /** A small grid on the real fixture: the tuner runs, stays sane, and grades fairly. */
    @Test fun smallGridTunesSanelyOnRealData() {
        val r = NflTuning.tune(
            rows, NflModel.Params(),
            ks = listOf(0.05, 0.06), homeFields = listOf(2.5), rests = listOf(1.0),
            regresses = listOf(0.33), qbs = listOf(3.0), caps = listOf(40.0),
        )
        assertTrue(r.params.sane())
        assertTrue(r.holdoutGames > 500)
        assertTrue(r.brier < 0.25 && r.currentBrier < 0.25)
        assertTrue(r.fitThrough < r.holdout.first)
    }

    @Test fun paramsRoundTripAndRejectNonsense() {
        val p = NflModel.Params(k = 0.07, blend = 0.2, source = "test")
        assertTrue(NflModel.Params.fromJson(p.toJson()) == p)
        assertTrue(NflModel.Params.fromJson(p.copy(sigma = 2.0).toJson()) == null)
        assertTrue(NflModel.Params.fromJson("not json") == null)
    }

    /**
     * The weekly job (NFL_TUNE=1): re-tune on fresh games.csv and write model/nfl-params.json
     * only when the new numbers beat the current ones on the holdout.
     */
    @Test fun weeklyRetune() {
        assumeTrue(System.getenv("NFL_TUNE") == "1")
        val games = File(System.getenv("NFL_GAMES") ?: error("NFL_GAMES not set"))
        val out = File(System.getenv("NFL_PARAMS") ?: error("NFL_PARAMS not set"))
        val fresh = NflModel.parse(games.readText())
        val current = out.takeIf(File::isFile)?.readText()?.let(NflModel.Params::fromJson) ?: NflModel.Params()
        val r = NflTuning.tune(fresh, current)
        println(
            "NFL re-tune: holdout ${r.holdout.first}-${r.holdout.last} (${r.holdoutGames} games) " +
                "brier new ${"%.5f".format(r.brier)} vs current ${"%.5f".format(r.currentBrier)} -> ${if (r.ships) "SHIP" else "keep"}",
        )
        if (!r.ships) return
        out.parentFile?.mkdirs()
        out.writeText(
            r.params.toJson(
                mapOf(
                    "holdout" to "${r.holdout.first}-${r.holdout.last}",
                    "holdoutGames" to r.holdoutGames,
                    "holdoutBrier" to r.brier,
                    "previousBrier" to r.currentBrier,
                    "tunedOn" to java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(),
                ),
            ) + "\n",
        )
    }
}
