package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Freezes what the random baselines produce on the real candles. The live scan draws its baselines
 * through the same function the backtest uses, so pulling that function out of the scorecard must
 * not move a single draw; if anyone changes the generator or the matching rules on purpose, this is
 * the number to update, knowing every stored baseline becomes incomparable with new ones.
 */
class RandomFingerprintTest {
    @Test
    fun theBaselinesOnTheRealCandlesAreFrozen() {
        var trades = 0
        var withBaseline = 0
        var sumMean = 0.0
        var sumExcess = 0.0
        var pooled = 0
        var pooledSum = 0.0
        val tf = Timeframe.D1
        val panel = Golden.panel(tf)
        val regimes = Golden.regimes(tf)
        // The research's saved signals: the fingerprint was taken on exactly those, before any new pattern existed.
        for ((key, flags) in Golden.researchSignals(tf)) {
            val run = Scorecard.runVariant(key, flags, panel, tf, regimes)
            for (t in run.trades) {
                trades++
                if (!t.randomMean.isNaN()) {
                    withBaseline++
                    sumMean += t.randomMean
                    sumExcess += t.excess
                }
            }
            pooled += run.pooled.exit.size
            pooledSum += run.pooled.exit.sum()
            for (h in run.pooled.horizons.values) {
                pooled += h.size
                pooledSum += h.sum()
            }
        }
        // Taken from the scorecard as it stood before the baseline was made reusable (M3's engine). The counts must match
        // exactly. The sums are compared to one part in a trillion: adding the same numbers in another order (the saved
        // signals come in name order) moves the last digit, while a single changed draw would move a sum by 0.001 or more.
        assertEquals(11633, trades)
        assertEquals(11633, withBaseline)
        assertClose(18.34023686426625, sumMean, "sum of baseline means", tol = 1e-12)
        assertClose(-25.48984363573965, sumExcess, "sum of excess", tol = 1e-12)
        assertEquals(1159073, pooled)
        assertClose(42448.966080903294, pooledSum, "sum of pooled draws", tol = 1e-12)
    }
}
