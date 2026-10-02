package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatisticsTest {
    @Test
    fun erfcMatchesKnownValues() {
        // Reference values to 15 digits.
        val known = listOf(
            0.0 to 1.0,
            0.5 to 0.4795001221869535,
            1.0 to 0.15729920705028513,
            2.0 to 0.004677734981047266,
            3.0 to 2.209049699858544e-05,
            4.0 to 1.541725790028002e-08,
            5.0 to 1.5374597944280347e-12,
            6.0 to 2.1519736712498913e-17,
            10.0 to 2.088487583762545e-45,
        )
        for ((x, want) in known) assertClose(want, Statistics.erfc(x), "erfc($x)", tol = 1e-10)
        assertClose(2.0 - 0.15729920705028513, Statistics.erfc(-1.0), "erfc(-1)")
    }

    @Test
    fun normalSurvivalHasTheFamiliarValues() {
        assertClose(0.5, Statistics.normalSurvival(0.0), "z=0")
        assertClose(0.15865525393145707, Statistics.normalSurvival(1.0), "z=1", tol = 1e-12)
        assertClose(0.0013498980316301, Statistics.normalSurvival(3.0), "z=3", tol = 1e-12)
        assertClose(0.9772498680518208, Statistics.normalSurvival(-2.0), "z=-2", tol = 1e-12)
    }

    @Test
    fun clusterTIsNaNWithoutTwoClustersOrWithoutVariance() {
        assertTrue(Statistics.clusterT(doubleArrayOf(1.0, 2.0, 3.0), longArrayOf(1, 1, 1)).isNaN())
        assertTrue(Statistics.clusterT(doubleArrayOf(1.0), longArrayOf(1)).isNaN())
        assertTrue(Statistics.clusterT(doubleArrayOf(2.0, 2.0, 2.0, 2.0), longArrayOf(1, 1, 2, 2)).isNaN())
    }

    @Test
    fun clusterTCountsTenCoinsOnOneDayAsOneObservation() {
        // The same ten values, spread over ten weeks versus all in one week plus one other.
        val x = DoubleArray(10) { 0.01 + 0.001 * it }
        val spread = Statistics.clusterT(x, LongArray(10) { it.toLong() })
        val lumped = Statistics.clusterT(DoubleArray(11) { if (it < 10) x[it] else -0.02 }, LongArray(11) { if (it < 10) 0L else 1L })
        assertTrue(spread > 3.0, "spread-out evidence should be strong, was $spread")
        assertTrue(lumped.isNaN() || lumped < 2.5, "ten coins in one week should not look like ten observations, was $lumped")
    }

    @Test
    fun clusterTIsTheOrdinaryTWhenEveryObservationIsItsOwnCluster() {
        val x = doubleArrayOf(0.5, -0.1, 0.3, 0.9, 0.2, 0.4, -0.3, 0.6)
        val n = x.size
        val mean = x.sum() / n
        val sd = sqrt(x.sumOf { (it - mean) * (it - mean) } / (n - 1))
        assertClose(mean / (sd / sqrt(n.toDouble())), Statistics.clusterT(x, LongArray(n) { it.toLong() }), "t", tol = 1e-12)
    }

    @Test
    fun bootstrapIntervalContainsTheMeanAndIsReproducible() {
        val x = DoubleArray(120) { 0.004 * kotlin.math.sin(it * 1.7) + 0.003 }
        val clusters = LongArray(120) { (it / 3).toLong() }
        val a = assertNotNull(Statistics.clusterBootstrapCi(x, clusters, seed = 5, resamples = 800))
        val b = assertNotNull(Statistics.clusterBootstrapCi(x, clusters, seed = 5, resamples = 800))
        assertEquals(a, b)
        val mean = x.sum() / x.size
        assertTrue(a.first < mean && mean < a.second, "interval $a does not contain the mean $mean")
        assertNull(Statistics.clusterBootstrapCi(x, LongArray(120) { 0 }, seed = 5, resamples = 10))
    }

    @Test
    fun verdictsFollowTheRules() {
        fun j(variant: String, n: Int, t: Double) = Statistics.Judged(variant, n, t, if (t.isNaN()) Double.NaN else Statistics.normalSurvival(t))
        val out = Statistics.judge(listOf(
            j("strong", 400, 6.0), j("strongButFew", 20, 6.0), j("losing", 400, -3.0), j("middling", 400, 1.0),
            j("fadeLike", 400, 6.0).let { Statistics.Judged("fade1h_hold24_1h", it.n, it.tCluster, it.p) }, j("nothing", 0, Double.NaN),
        ), trials = 6)
        assertEquals(listOf("Edge", "No verdict", "Losing", "No edge", EngineConfig.FORWARD_ONLY_VERDICT, "No verdict"), out.map { it.verdict })
    }

    @Test
    fun manyTrialsMakeAnEdgeHarderToClaim() {
        val one = Statistics.Judged("v", 400, 3.2, Statistics.normalSurvival(3.2))
        assertEquals("Edge", Statistics.judge(listOf(one), trials = 1)[0].verdict)
        assertEquals("No edge", Statistics.judge(listOf(one), trials = 5000)[0].verdict)
    }
}
