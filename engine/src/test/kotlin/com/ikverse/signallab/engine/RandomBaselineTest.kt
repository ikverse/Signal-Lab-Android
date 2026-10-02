package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The random baselines use their own generator, so they cannot be compared with the research
 * version draw for draw. These tests check what matters instead: draws are reproducible and do not
 * depend on order, they are uniform, and the baseline is unbiased - a "signal" that fires
 * everywhere must score no better than random entries.
 */
class RandomBaselineTest {
    @Test
    fun drawsAreReproducibleAndSeedSensitive() {
        assertEquals(Rng.unit(1, 2, 3), Rng.unit(1, 2, 3))
        assertTrue(Rng.unit(1, 2, 3) != Rng.unit(2, 2, 3))
        assertTrue(Rng.unit(1, 2, 3) != Rng.unit(1, 3, 3))
        assertTrue(Rng.seedOf("ab", "c") != Rng.seedOf("a", "bc"))
    }

    @Test
    fun drawsAreUniformAndStayInRange() {
        val buckets = IntArray(10)
        val draws = 100_000
        for (i in 0 until draws) {
            val v = Rng.intIn(Rng.seedOf("uniform"), i.toLong(), 7, 5, 15)
            assertTrue(v in 5 until 15)
            buckets[v - 5]++
        }
        val expected = draws / 10.0
        val sd = sqrt(draws * 0.1 * 0.9)
        for ((i, b) in buckets.withIndex()) assertTrue(abs(b - expected) < 4.5 * sd, "bucket $i has $b of about $expected")
        val mean = (0 until draws).sumOf { Rng.unit(3, it.toLong(), 0) } / draws
        assertTrue(abs(mean - 0.5) < 0.005, "mean of unit draws was $mean")
    }

    private fun everywhere(c: Candles) = BooleanArray(c.size) { it > 30 }

    @Test
    fun baselineDoesNotDependOnTheOrderCoinsAreProcessed() {
        val panel = Synth.panel(5, 700, Timeframe.D1)
        val regimes = panel.mapValues { IntArray(it.value.size) { 1 } }
        val key = SignalKey("everywhere_1d", "test", emptyMap())
        val forward = panel.mapValues { everywhere(it.value) }
        val backward = LinkedHashMap<String, BooleanArray>().also { m -> forward.keys.reversed().forEach { m[it] = forward.getValue(it) } }
        val a = Scorecard.runVariant(key, forward, panel, Timeframe.D1, regimes).trades.associateBy { it.symbol to it.barTime }
        val b = Scorecard.runVariant(key, backward, panel, Timeframe.D1, regimes).trades.associateBy { it.symbol to it.barTime }
        assertEquals(a.keys, b.keys)
        for ((k, t) in a) assertEquals(t.randomMean, b.getValue(k).randomMean, "random mean for $k")
    }

    @Test
    fun aSignalThatFiresEverywhereIsNoBetterThanRandomEntries() {
        // On a market with no structure, trading everywhere minus a busy-until rule should land on the
        // baseline: mean excess within a few standard errors of zero, for several independent series.
        for (seed in 1L..6L) {
            val c = Synth.candles(3000, Timeframe.D1, seed, "T${seed}USDT")
            val panel = mapOf(c.symbol to c)
            val regimes = mapOf(c.symbol to IntArray(c.size) { 1 })
            val run = Scorecard.runVariant(SignalKey("everywhere_1d", "test", emptyMap()),
                mapOf(c.symbol to everywhere(c)), panel, Timeframe.D1, regimes)
            val ex = run.trades.map { it.excess }.filter { !it.isNaN() }
            assertTrue(ex.size > 200, "only ${ex.size} trades")
            val mean = ex.average()
            val se = sqrt(ex.sumOf { (it - mean) * (it - mean) } / (ex.size - 1)) / sqrt(ex.size.toDouble())
            assertTrue(abs(mean) < 4 * se, "seed $seed: mean excess $mean is ${mean / se} standard errors from zero")
        }
    }

    @Test
    fun baselineIsUnbiasedOnRealBitcoinToo() {
        val c = Golden.panel(Timeframe.D1).getValue("BTCUSDT")
        val run = Scorecard.runVariant(SignalKey("everywhere_1d", "test", emptyMap()),
            mapOf(c.symbol to everywhere(c)), mapOf(c.symbol to c), Timeframe.D1, Golden.regimes(Timeframe.D1).filterKeys { it == "BTCUSDT" })
        val ex = run.trades.map { it.excess }.filter { !it.isNaN() }
        val mean = ex.average()
        val se = sqrt(ex.sumOf { (it - mean) * (it - mean) } / (ex.size - 1)) / sqrt(ex.size.toDouble())
        assertTrue(abs(mean) < 4 * se, "mean excess $mean is ${mean / se} standard errors from zero")
    }

    @Test
    fun scoringIsReproducible() {
        val panel = Golden.panel(Timeframe.D1)
        val regimes = Golden.regimes(Timeframe.D1)
        val (key, flags) = Signals.compute(panel).entries.first { it.key.name == "donchian20_1d" }.toPair()
        val run1 = Scorecard.runVariant(key, flags, panel, Timeframe.D1, regimes)
        val run2 = Scorecard.runVariant(key, flags, panel, Timeframe.D1, regimes)
        val s1 = Scorecard.score(key, key.family, Timeframe.D1, run1)
        val s2 = Scorecard.score(key, key.family, Timeframe.D1, run2)
        assertEquals(s1.excess, s2.excess)
        assertEquals(s1.tCluster, s2.tCluster)
        assertEquals(s1.details.excessCi95, s2.details.excessCi95)
        assertTrue(s1.n > 500)
        assertTrue(!s1.excess.isNaN() && !s1.tCluster.isNaN() && !s1.p.isNaN())
    }
}
