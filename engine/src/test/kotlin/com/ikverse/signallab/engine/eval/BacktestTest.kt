package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Golden
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.engine.strategy.ExitPlan
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.Tier
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BacktestTest {
    private val panel = Golden.panel(Timeframe.D1)
    private val btc = panel.getValue("BTCUSDT")

    /** Enters on every [every]th candle and holds [hold] candles: no skill at all, so it should match random entries. */
    private fun noSkill(every: Int, hold: Int, bull: Boolean = false) = object : Strategy {
        override val id = "noskill${every}_1d"
        override val name = "No skill"
        override val timeframe = Timeframe.D1
        override val launchTier = Tier.TESTING
        override val bullOnly = bull
        override val exit = ExitPlan(hold, safetyStopAtr = 3.0)
        override fun entries(c: Candles) = BooleanArray(c.size) { it % every == 0 }
    }

    @Test
    fun aStrategyWithNoSkillMatchesItsRandomEntries() {
        val trades = Backtest.run(noSkill(3, 10), panel, btc)
        val ex = trades.map { it.excess }.filter { !it.isNaN() }
        assertTrue(ex.size > 1000, "only ${ex.size} trades")
        val mean = ex.average()
        val sd = sqrt(ex.sumOf { (it - mean) * (it - mean) } / (ex.size - 1))
        // Overlapping holds make neighbouring trades alike, so allow a generous margin around zero.
        assertTrue(abs(mean) < 4 * sd / sqrt(ex.size / 10.0), "mean excess $mean is too far from zero (sd $sd)")
    }

    @Test
    fun theBacktestIsReproducibleAndDoesNotDependOnCoinOrder() {
        val s = Builtins.all.first()
        val a = Backtest.run(s, panel, btc)
        val b = Backtest.run(s, panel.entries.reversed().associate { it.toPair() }, btc)
        fun key(t: BacktestTrade) = "${t.symbol}@${t.signalTime}"
        assertEquals(a.associate { key(it) to it.excess }, b.associate { key(it) to it.excess })
    }

    @Test
    fun aCoinNeverHoldsTwoTradesOfOneStrategyAtOnce() {
        for (s in Builtins.all) {
            for ((symbol, list) in Backtest.run(s, Golden.panel(s.timeframe), btc).groupBy { it.symbol }) {
                val sorted = list.sortedBy { it.entryTime }
                for (i in 1 until sorted.size) {
                    assertTrue(sorted[i].entryTime > sorted[i - 1].exitTime - 1, "${s.id} $symbol overlaps at ${sorted[i].entryTime}")
                }
            }
        }
    }

    @Test
    fun aBullOnlyStrategyNeverEntersWhileBitcoinIsBelowItsAverage() {
        val trades = Backtest.run(noSkill(5, 5, bull = true), panel, btc)
        assertTrue(trades.isNotEmpty())
        assertTrue(trades.all { it.regime == 1 })
    }

    @Test
    fun weeksStartOnMondayInUtc() {
        val monday = 1_704_067_200_000L // 1 Jan 2024 00:00 UTC, a Monday
        val day = 86_400_000L
        assertEquals(Backtest.weekOf(monday), Backtest.weekOf(monday + 7 * day - 1))
        assertEquals(Backtest.weekOf(monday) - 1, Backtest.weekOf(monday - 1))
    }

    /** The table a person would read, on the twelve golden coins. Printed, and checked only for sanity. */
    @Test
    fun theBuiltInsOnTheGoldenCoins() {
        val lines = ArrayList<String>()
        lines.add("%-14s %6s %6s %8s %8s %7s %6s  %s".format("strategy", "calls", "up", "net", "excess", "t", "weeks", "excess 95% CI"))
        for (s in Builtins.all) {
            val trades = Backtest.run(s, Golden.panel(s.timeframe), btc)
            val sum = Backtest.summarize(trades, s.id)
            assertTrue(sum.n > 10, "${s.id}: only ${sum.n} calls on the golden coins")
            assertTrue(trades.all { it.barsHeld in 1..s.exit.maxBars }, "${s.id}: a hold outside its plan")
            val ci = sum.excessCi95?.let { "%+.2f%% to %+.2f%%".format(it.first * 100, it.second * 100) } ?: "-"
            lines.add("%-14s %6d %5.0f%% %+7.2f%% %+7.2f%% %7.2f %6d  %s".format(
                s.id, sum.n, sum.hit * 100, sum.meanNet * 100, sum.meanExcess * 100, sum.tCluster, sum.weeks, ci))
        }
        println(lines.joinToString("\n"))
    }
}
