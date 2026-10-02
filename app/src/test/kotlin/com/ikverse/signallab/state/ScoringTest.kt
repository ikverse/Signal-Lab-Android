package com.ikverse.signallab.state

import com.ikverse.signallab.data.LiveTrade
import com.ikverse.signallab.data.NewTrade
import com.ikverse.signallab.data.TradeExit
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scorecard's numbers, worked out by hand from a handful of made-up trades. */
class ScoringTest {
    private val week = 7 * 86_400_000L
    private var nextId = 1L

    private fun trade(variant: String, tf: Timeframe, weekIndex: Long, net: Double?, random: Double? = 0.0, symbol: String = "BTCUSDT"): LiveTrade {
        val entry = weekIndex * week + 3_600_000L
        val t = NewTrade(variant, "fam", symbol, tf, 0, entry - tf.ms, entry, 1, entry, 100.0, null, null, 10, entry + 10 * tf.ms)
        val exit = net?.let {
            TradeExit(entry + tf.ms, 100.0 * (1 + it), if (it > 0) ExitReason.TARGET else ExitReason.STOP, 1, it, it, random, random?.let { r -> it - r })
        }
        return LiveTrade(nextId++, t, entry, exit, exit?.exitTime)
    }

    @Test
    fun `a pattern with no trades still has its row, and says no verdict`() {
        val card = Scoring.build(emptyList(), listOf("donchian20_1h" to Timeframe.H1), trials = 10)
        assertEquals(10, card.patternsTested)
        val r = card.rows.single()
        assertEquals("donchian20_1h", r.variant)
        assertEquals("1h", r.timeframe)
        assertEquals(0, r.closed)
        assertEquals(0, r.open)
        assertNull(r.hitRate)
        assertNull(r.meanNet)
        assertNull(r.excess)
        assertEquals("No verdict", r.verdict)
        assertEquals("No verdict", r.firmness)
        assertTrue(r.label.startsWith("Breakout"))
    }

    @Test
    fun `open and closed trades are counted apart and results come only from the closed ones`() {
        val trades = listOf(
            trade("donchian20_1h", Timeframe.H1, 1, 0.02, random = 0.005),
            trade("donchian20_1h", Timeframe.H1, 2, -0.01, random = 0.001),
            trade("donchian20_1h", Timeframe.H1, 3, null),
        )
        val r = Scoring.build(trades, emptyList(), 1).rows.single()
        assertEquals(2, r.closed)
        assertEquals(1, r.open)
        assertEquals(0.5, r.hitRate!!, 1e-12)
        assertEquals(0.005, r.meanNet!!, 1e-12)
        assertEquals(0.003, r.randomMean!!, 1e-12)
        // (0.02 - 0.005 + -0.01 - 0.001) / 2
        assertEquals(0.002, r.excess!!, 1e-12)
    }

    @Test
    fun `the same pattern on two charts is two rows`() {
        val trades = listOf(trade("donchian20_1h", Timeframe.H1, 1, 0.01), trade("donchian20_4h", Timeframe.H4, 1, 0.01))
        val rows = Scoring.build(trades, emptyList(), 2).rows
        assertEquals(setOf("1h", "4h"), rows.map { it.timeframe }.toSet())
        assertEquals(2, rows.size)
    }

    @Test
    fun `a registered pattern and a pattern with trades share one row`() {
        val rows = Scoring.build(listOf(trade("donchian20_1h", Timeframe.H1, 1, 0.01)), listOf("donchian20_1h" to Timeframe.H1), 1).rows
        assertEquals(1, rows.size)
        assertEquals(1, rows.single().closed)
    }

    @Test
    fun `a trade with no random baseline counts in the average but not in the comparison`() {
        val trades = listOf(
            trade("donchian20_1h", Timeframe.H1, 1, 0.04, random = null),
            trade("donchian20_1h", Timeframe.H1, 2, 0.02, random = 0.01),
        )
        val r = Scoring.build(trades, emptyList(), 1).rows.single()
        assertEquals(0.03, r.meanNet!!, 1e-12)
        assertEquals(0.01, r.excess!!, 1e-12)
        assertEquals(0.01, r.randomMean!!, 1e-12)
    }

    private fun many(variant: String, n: Int, net: (Int) -> Double, random: Double = 0.0) =
        List(n) { i -> trade(variant, Timeframe.H1, 1L + i, net(i), random, symbol = "C$i") }

    @Test
    fun `thirty trades that clearly beat random, after counting every pattern tried, is an edge`() {
        // A steady +2% against random entries that made 0%, with a little spread so there is a variance to measure.
        val trades = many("donchian20_1h", 60, { 0.02 + (it % 5 - 2) * 0.001 })
        val r = Scoring.build(trades, emptyList(), trials = 20).rows.single()
        assertEquals("Edge", r.verdict)
        assertEquals("Early read", r.firmness)
        assertTrue(r.tCluster!! >= 3.0)
    }

    @Test
    fun `the same trades are not an edge once enough other patterns have been tried`() {
        // +1% on average with a 1.6% spread: a t of about 4. Clear on its own, but not once thousands of patterns have been tried.
        val noisy = many("donchian20_1h", 40, { 0.01 + if (it % 2 == 0) 0.0158 else -0.0158 })
        val few = Scoring.build(noisy, emptyList(), trials = 1).rows.single()
        val lots = Scoring.build(noisy, emptyList(), trials = 5_000).rows.single()
        assertTrue("t=${few.tCluster}", few.tCluster!! >= 3.0)
        assertEquals(few.tCluster, lots.tCluster)
        assertEquals("Edge", few.verdict)
        assertEquals("No edge", lots.verdict)
    }

    @Test
    fun `trades that are clearly worse than random are losing`() {
        val trades = many("donchian20_1h", 60, { -0.02 + (it % 5 - 2) * 0.001 })
        assertEquals("Losing", Scoring.build(trades, emptyList(), 5).rows.single().verdict)
    }

    @Test
    fun `under thirty trades never get a verdict however good they look`() {
        val trades = many("donchian20_1h", 29, { 0.05 + (it % 3) * 0.001 })
        val r = Scoring.build(trades, emptyList(), 1).rows.single()
        assertEquals("No verdict", r.verdict)
        assertEquals(29, r.closed)
    }

    @Test
    fun `ten trades in one week are one piece of evidence, so they cannot make an edge alone`() {
        val trades = List(60) { i -> trade("donchian20_1h", Timeframe.H1, 5L, 0.02 + (i % 5) * 0.001, symbol = "C$i") }
        val r = Scoring.build(trades, emptyList(), 1).rows.single()
        // All in one calendar week: one cluster, so no statistic can be formed.
        assertNull(r.tCluster)
        assertEquals("No verdict", r.verdict)
    }

    @Test
    fun `the live pattern defined after a backtest can be judged, because live trades are what it is judged on`() {
        val trades = many("fade1h_hold24_1h", 60, { 0.02 + (it % 5 - 2) * 0.001 })
        val r = Scoring.build(trades, emptyList(), 5).rows.single()
        assertTrue(r.forwardOnly)
        assertEquals("Edge", r.verdict)
    }

    @Test
    fun `the firmness follows the number of closed trades`() {
        fun firm(n: Int) = Scoring.build(many("donchian20_1h", n, { 0.001 * (it % 7 - 3) }), emptyList(), 1).rows.single().firmness
        assertEquals("No verdict", firm(29))
        assertEquals("Early read", firm(30))
        assertEquals("Early read", firm(99))
        assertEquals("Provisional", firm(100))
        assertEquals("Provisional", firm(299))
        assertEquals("Meaningful", firm(300))
    }

    @Test
    fun `every row of the scorecard has a plain-words label`() {
        val names = listOf("trend_ma20_1h", "bullish_harami_15m", "xsmom2w_1d", "intraday_mom_30m")
        val rows = Scoring.build(emptyList(), names.map { it to Timeframe.H1 }, 4).rows
        assertEquals(names.size, rows.size)
        for (r in rows) assertTrue(r.label, r.label != r.variant)
    }
}
