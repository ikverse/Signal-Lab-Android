package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Rng
import com.ikverse.signallab.engine.lowerBound
import com.ikverse.signallab.engine.upperBound
import com.ikverse.signallab.engine.strategy.ExitPlan

/**
 * The random entries a call is judged against: candles on the same coin, within [EngineConfig.randomWindowDays] of the
 * signal either side, in the same Bitcoin regime, entered and exited by exactly the same plan and exit signals, and
 * charged the same cost. A call only earns credit for what it does better than those.
 *
 * Each draw is a pure function of the strategy, the coin, the signal's candle and the draw number, so a baseline never
 * depends on the order coins or signals are processed in.
 */
object Baseline {
    const val DRAWS = EngineConfig.RANDOM_DRAWS_PER_TRADE

    /**
     * The average net result of [DRAWS] random entries for each of [signals] (candle indices in [c]); NaN where no
     * entry could be drawn. [onDraw] receives every single random result.
     */
    fun meanNet(
        strategyId: String,
        symbol: String,
        c: Candles,
        atr: DoubleArray,
        regime: IntArray,
        signals: IntArray,
        plan: ExitPlan,
        exitSignals: BooleanArray?,
        cost: Double,
        onDraw: (Double) -> Unit = {},
    ): DoubleArray {
        val windowBars = EngineConfig.randomWindowDays(c.tf) * c.tf.barsPerDay
        val usable = BooleanArray(c.size) { Fills.judgeable(c, it, plan, atr) }
        val seed = Rng.seedOf("v2", strategyId, symbol)
        val pools = HashMap<Int, IntArray>()
        return DoubleArray(signals.size) { j ->
            val s = signals[j]
            val pool = pools.getOrPut(regime[s]) { (0 until c.size).filter { usable[it] && regime[it] == regime[s] }.toIntArray() }
            val lo = lowerBound(pool, s - windowBars)
            val hi = upperBound(pool, s + windowBars)
            if (hi <= lo) return@DoubleArray Double.NaN
            var total = 0.0
            var count = 0
            for (d in 0 until DRAWS) {
                val r = pool[Rng.intIn(seed, s.toLong(), d.toLong(), lo, hi)]
                val fill = Fills.resolve(c, r + 1, plan, atr[r], exitSignals) ?: continue
                val net = fill.exitPrice / c.open[r + 1] - 1 - cost
                total += net
                count++
                onDraw(net)
            }
            if (count > 0) total / count else Double.NaN
        }
    }
}
