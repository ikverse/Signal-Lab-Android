package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Rng
import com.ikverse.signallab.engine.strategy.Breakout
import com.ikverse.signallab.engine.strategy.NearHigh
import com.ikverse.signallab.engine.strategy.Tier
import org.junit.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals

class TiersTest {
    private fun series(n: Int, mean: Double, sd: Double, seed: Long) = DoubleArray(n) { i ->
        val u1 = maxOf(Rng.unit(seed, i.toLong(), 1), 1e-12)
        val u2 = Rng.unit(seed, i.toLong(), 2)
        mean + sd * sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    private val none = DoubleArray(0)

    @Test
    fun aClearEdgeAtDoubleTheFeesIsProven() {
        val strong = series(600, 0.03, 0.1, 1)
        assertEquals(Tier.PROVEN, Tiers.judge(Breakout(), strong, strong, none, trials = 100).tier)
    }

    @Test
    fun anEdgeThatOnlyHoldsAtTheUsualFeesIsPromising() {
        val strong = series(600, 0.03, 0.1, 1)
        val gone = series(600, -0.005, 0.1, 2)
        assertEquals(Tier.PROMISING, Tiers.judge(Breakout(), strong, gone, none, trials = 100).tier)
    }

    @Test
    fun withoutEnoughEvidenceTheResearchAloneGivesWatch() {
        val noise = series(600, 0.0, 0.1, 3)
        assertEquals(Tier.WATCH, Tiers.judge(Breakout(), noise, noise, none, 100).tier)
        assertEquals(Tier.WATCH, Tiers.judge(Breakout(), series(50, 0.05, 0.1, 4), series(50, 0.05, 0.1, 4), none, 100).tier)
    }

    @Test
    fun aSingleSourceStrategyStaysTestingUntilItsLiveCallsShowIt() {
        val strong = series(600, 0.03, 0.1, 1)
        assertEquals(Tier.TESTING, Tiers.judge(NearHigh(), strong, strong, none, 100).tier)
        assertEquals(Tier.PROMISING, Tiers.judge(NearHigh(), none, none, series(150, 0.03, 0.1, 5), 100).tier)
    }

    @Test
    fun liveCallsClearlyBehindMakeItFailingWhateverThePastSays() {
        val strong = series(600, 0.03, 0.1, 1)
        val behind = series(40, -0.05, 0.1, 6)
        assertEquals(Tier.FAILING, Tiers.judge(Breakout(), strong, strong, behind, 100).tier)
    }
}
