package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.strategy.ExitPlan

/** Why a trade ended. */
enum class Leave(val label: String) {
    STOP("stop"), TARGET("target"), SIGNAL("signal"), TIME("time"),

    /** Live only: the exchange announced it will stop trading the coin, so the call leaves at the next open. */
    NOTICE("notice");

    companion object {
        fun of(label: String): Leave = entries.first { it.label == label }
    }
}

/** Where a trade ended: the candle it left on, the price, and why. */
class Fill(val exitIdx: Int, val exitPrice: Double, val reason: Leave)

/**
 * The fill rules, shared by the backtest, the random baseline and the live runner, so they cannot disagree about an
 * exit. See [ExitPlan] for the rules themselves.
 */
object Fills {
    /**
     * Where a trade that entered at the open of candle [entryIdx] stands, judged on the closed candles in [c]: its fill
     * once it has one, null while it is still open. [atr] is the average true range at the signal candle, which the
     * safety stop is measured in. [exitSignals], when given, is the strategy's own exit at each candle's close.
     */
    fun resolve(c: Candles, entryIdx: Int, plan: ExitPlan, atr: Double, exitSignals: BooleanArray?): Fill? {
        if (entryIdx < 0 || entryIdx >= c.size) return null
        val entry = c.open[entryIdx]
        val stop = plan.safetyStopAtr?.let { entry - it * atr }
        val target = plan.targetAtr?.let { entry + it * atr }
        for (q in 0 until plan.maxBars) {
            val k = entryIdx + q
            if (k >= c.size) return null
            if (stop != null && c.low[k] <= stop) return Fill(k, minOf(stop, c.open[k]), Leave.STOP)
            if (target != null && c.high[k] >= target) return Fill(k, maxOf(target, c.open[k]), Leave.TARGET)
            // At the last candle the time limit closes the trade at its close, before any signal could act at the next open.
            if (q == plan.maxBars - 1) return Fill(k, c.close[k], Leave.TIME)
            if (exitSignals != null && exitSignals[k]) {
                return if (k + 1 < c.size) Fill(k + 1, c.open[k + 1], Leave.SIGNAL) else null
            }
        }
        return null
    }

    /**
     * When a fill happened: a fill at a candle's open (an exit signal, or a gap through the stop) at that candle's open
     * time, any other at its close. Shared by the backtest and the live runner, so both record the same time.
     */
    fun exitTime(c: Candles, fill: Fill): Long =
        if (fill.reason != Leave.TIME && fill.exitPrice == c.open[fill.exitIdx]) c.t[fill.exitIdx] else c.closeTime[fill.exitIdx]

    /**
     * Whether a signal on candle [signalIdx] can be judged in a backtest: its average true range exists and its whole
     * window, up to the last candle the plan allows, lies inside [c]. Counting only such trades means a trade that
     * happens to end early is not favoured over one still running when the data ends.
     */
    fun judgeable(c: Candles, signalIdx: Int, plan: ExitPlan, atr: DoubleArray): Boolean =
        signalIdx >= 0 && signalIdx + plan.maxBars < c.size && !atr[signalIdx].isNaN()
}
