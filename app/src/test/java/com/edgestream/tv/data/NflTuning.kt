package com.edgestream.tv.data

import kotlin.math.sqrt

/**
 * The weekly re-tune (run by .github/workflows/nfl-tune.yml through [NflTuningTest]). It uses
 * the app's own [NflModel], so tuned numbers can't drift from what the app runs.
 *
 * Seasons roll forward: the last three complete seasons are the holdout; everything from 2002
 * to the season before them is the fit. New numbers ship only when their pick probabilities
 * score a better Brier on the holdout than the numbers already in use.
 */
object NflTuning {
    data class Result(
        val params: NflModel.Params,
        val holdout: IntRange,
        val fitThrough: Int,
        val brier: Double,
        val currentBrier: Double,
        val holdoutGames: Int,
    ) {
        /** A real improvement, not noise: churn on tiny gains would only move picks around. */
        val ships: Boolean get() = brier < currentBrier - 0.0002
    }

    private const val FIT_FROM = 2002

    fun tune(
        rows: List<NflModel.Row>,
        current: NflModel.Params,
        ks: List<Double> = listOf(0.04, 0.05, 0.06, 0.07, 0.08),
        homeFields: List<Double> = listOf(1.5, 2.0, 2.5, 3.0),
        rests: List<Double> = listOf(0.0, 0.5, 1.0, 1.5),
        regresses: List<Double> = listOf(0.25, 0.33, 0.4, 0.5),
        qbs: List<Double> = listOf(0.0, 2.0, 3.0, 4.0),
        caps: List<Double> = listOf(28.0, 40.0),
    ): Result {
        val current_season = rows.maxOf { it.season }
        val holdout = (current_season - 3)..(current_season - 1)
        val fitThrough = holdout.first - 1

        var best: NflModel.Params? = null
        var bestRmse = Double.MAX_VALUE
        for (k in ks) for (hf in homeFields) for (rest in rests) for (reg in regresses) for (qb in qbs) for (cap in caps) {
            val p = NflModel.Params(k = k, errorCap = cap, homeField = hf, rest = rest, regress = reg, backupQb = qb)
            val rmse = rmse(NflModel.replay(rows, p), FIT_FROM..fitThrough)
            if (rmse < bestRmse) { bestRmse = rmse; best = p }
        }
        val withSigma = best!!.copy(sigma = bestRmse)
        val fitPreds = NflModel.replay(rows, withSigma)
        val blend = (0..10).map { it * 0.05 }.minBy { b -> brier(fitPreds, NflModel.LINES_FROM..fitThrough, withSigma.copy(blend = b)) }
        val tuned = withSigma.copy(blend = blend, source = "re-tune: fit $FIT_FROM–$fitThrough, holdout ${holdout.first}–${holdout.last}")

        val tunedPreds = NflModel.replay(rows, tuned)
        val currentPreds = NflModel.replay(rows, current)
        return Result(
            params = tuned,
            holdout = holdout,
            fitThrough = fitThrough,
            brier = brier(tunedPreds, holdout, tuned),
            currentBrier = brier(currentPreds, holdout, current),
            holdoutGames = tunedPreds.count { it.row.season in holdout && it.row.result != null && it.row.result != 0 && it.row.spreadLine != null },
        )
    }

    private fun rmse(preds: List<NflModel.Prediction>, seasons: IntRange): Double {
        var se = 0.0
        var n = 0
        for (pr in preds) {
            val r = pr.row.result ?: continue
            if (pr.row.season !in seasons) continue
            se += (r - pr.homeMargin).let { it * it }
            n++
        }
        return if (n == 0) Double.MAX_VALUE else sqrt(se / n)
    }

    /** Brier score of the pick probability (rating blended with the closing spread). */
    private fun brier(preds: List<NflModel.Prediction>, seasons: IntRange, p: NflModel.Params): Double {
        var sum = 0.0
        var n = 0
        for (pr in preds) {
            val r = pr.row.result ?: continue
            val spread = pr.row.spreadLine ?: continue
            if (pr.row.season !in seasons || r == 0) continue
            val prob = NflModel.pickHomeProb(pr.homeMargin, spread, p)
            sum += (prob - if (r > 0) 1.0 else 0.0).let { it * it }
            n++
        }
        return if (n == 0) Double.MAX_VALUE else sum / n
    }
}
