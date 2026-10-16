package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Rng
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvidenceTest {
    private fun normal(n: Int, seed: Long, mean: Double = 0.0, sd: Double = 1.0) = DoubleArray(n) { i ->
        val u1 = maxOf(Rng.unit(seed, i.toLong(), 1), 1e-12)
        val u2 = Rng.unit(seed, i.toLong(), 2)
        mean + sd * sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    @Test
    fun theNormalQuantileInvertsTheCdf() {
        assertEquals(1.959963985, Evidence.normalQuantile(0.975), 1e-8)
        assertEquals(1.644853627, Evidence.normalQuantile(0.95), 1e-8)
        assertEquals(0.0, Evidence.normalQuantile(0.5), 1e-12)
        assertEquals(-Evidence.normalQuantile(0.01), Evidence.normalQuantile(0.99), 1e-9)
        for (p in listOf(1e-6, 0.01, 0.3, 0.7, 0.99, 1 - 1e-6)) assertEquals(p, Evidence.normalCdf(Evidence.normalQuantile(p)), 1e-7 * maxOf(1.0, 1 / p))
    }

    @Test
    fun theProbabilisticSharpeMatchesItsFormula() {
        // A per-call Sharpe of 0.1 over 101 normally distributed results: z = 0.1 · √100 / √(1 + 0.1²/2).
        val m = Moments(101, 0.1, 1.0, 0.0, 3.0)
        val z = 0.1 * 10 / sqrt(1 + 0.5 * 0.01)
        assertEquals(Evidence.normalCdf(z), Evidence.probabilisticSharpe(m), 1e-12)
        assertEquals(0.5, Evidence.probabilisticSharpe(m, benchmark = 0.1), 1e-12)
        assertTrue(Evidence.probabilisticSharpe(Moments(401, 0.1, 1.0, 0.0, 3.0)) > Evidence.probabilisticSharpe(m), "more calls, more certainty")
        assertTrue(Evidence.probabilisticSharpe(Moments(101, 0.1, 1.0, -1.0, 9.0)) < Evidence.probabilisticSharpe(m), "left skew and fat tails, less certainty")
    }

    @Test
    fun aPerCallSharpeOfOneTenthNeedsAboutTwoHundredAndSeventyCalls() {
        assertEquals(273, Evidence.minTrackRecord(Moments(500, 0.1, 1.0, 0.0, 3.0)))
        assertNull(Evidence.minTrackRecord(Moments(500, -0.1, 1.0, 0.0, 3.0)), "a strategy behind cannot earn a verdict by waiting")
        assertTrue(Evidence.minTrackRecord(Moments(500, 0.1, 1.0, 0.0, 12.0))!! > 273, "fat tails need more calls")
    }

    @Test
    fun moreStrategiesTriedRaiseTheBar() {
        assertEquals(0.0, Evidence.expectedMaxSharpe(1, 100))
        val few = Evidence.expectedMaxSharpe(10, 100)
        val many = Evidence.expectedMaxSharpe(100, 100)
        assertTrue(few > 0 && many > few)
        val m = Moments(200, 0.15, 1.0, 0.0, 3.0)
        assertTrue(Evidence.deflatedSharpe(m, 100) < Evidence.deflatedSharpe(m, 10))
        assertTrue(Evidence.deflatedSharpe(m, 10) < Evidence.probabilisticSharpe(m))
    }

    @Test
    fun momentsReadMeanSpreadSkewAndTails() {
        val x = normal(20_000, 7, mean = 0.5, sd = 2.0)
        val m = Moments.of(x)
        assertEquals(0.5, m.mean, 0.05)
        assertEquals(2.0, m.sd, 0.05)
        assertEquals(0.0, m.skew, 0.1)
        assertEquals(3.0, m.kurtosis, 0.15)
        assertEquals(0.25, m.sharpe, 0.03)
    }

    @Test
    fun theBlockBootstrapIsReproducibleAndCoversTheMean() {
        val x = normal(300, 3, mean = 0.02, sd = 0.1)
        val a = Evidence.bootstrapMeanCi(x, 11)!!
        assertEquals(a, Evidence.bootstrapMeanCi(x, 11))
        val mean = x.average()
        assertTrue(a.first < mean && mean < a.second)
        val width = a.second - a.first
        val se = 0.1 / sqrt(300.0)
        assertTrue(abs(width - 2 * 1.96 * se) < 0.4 * 2 * 1.96 * se, "width $width is far from the textbook ${2 * 1.96 * se}")
        assertNull(Evidence.bootstrapMeanCi(doubleArrayOf(1.0), 1))
    }

    @Test
    fun aGenuinelyBetterVariantIsRarelyOverfitButPureNoiseOftenIs() {
        val noise = List(10) { normal(400, 100L + it) }
        val pboNoise = Evidence.overfitProbability(noise)
        // With complementary halves, noise that looks best in sample tends to look worse out of sample: pure noise reads as overfit.
        assertTrue(pboNoise > 0.5, "noise gave $pboNoise")
        val withEdge = noise.drop(1) + listOf(normal(400, 99, mean = 0.5))
        assertTrue(Evidence.overfitProbability(withEdge) < 0.1)
        assertTrue(Evidence.overfitProbability(noise.take(1)).isNaN())
    }
}
