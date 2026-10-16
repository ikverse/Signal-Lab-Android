package com.ikverse.signallab.engine.strategy

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.Timeframe

/**
 * Breakout (docs/strategy-research.md §1.1): enter when a daily close rises above the highest high of the prior
 * [lookback] days, and leave on a daily close below the lowest low of the prior [exitLookback] days, after [maxDays] at
 * the latest. A safety stop 3 average true ranges down keeps a gap from wiping out a call.
 */
class Breakout(
    val lookback: Int = 50,
    val exitLookback: Int = 20,
    maxDays: Int = 60,
) : Strategy {
    override val id = "breakout${lookback}_1d"
    override val name = "Breakout"
    override val timeframe = Timeframe.D1
    override val launchTier = Tier.PROMISING
    override val bullOnly = false
    override val exit = ExitPlan(maxBars = maxDays, safetyStopAtr = 3.0)

    override fun entries(c: Candles): BooleanArray {
        val high = Indicators.rollingMaxPrior(c.high, lookback)
        return firstOfRun(BooleanArray(c.size) { i -> !high[i].isNaN() && c.close[i] > high[i] })
    }

    override fun exitSignals(c: Candles): BooleanArray {
        val low = Indicators.rollingMinPrior(c.low, exitLookback)
        return BooleanArray(c.size) { k -> !low[k].isNaN() && c.close[k] < low[k] }
    }
}

/**
 * Trend timer (docs/strategy-research.md §1.1): enter when a daily close crosses above its [days]-day average, and
 * leave on the first daily close back below it. The research's edge here is avoiding long drawdowns, so there is no
 * price stop; [maxDays] only keeps every trade's window finite, which the random baseline needs.
 */
class TrendTimer(
    val days: Int = 50,
    maxDays: Int = 200,
) : Strategy {
    override val id = "trend${days}_1d"
    override val name = "Trend timer"
    override val timeframe = Timeframe.D1
    override val launchTier = Tier.PROMISING
    override val bullOnly = false
    override val exit = ExitPlan(maxBars = maxDays)

    override fun entries(c: Candles): BooleanArray = Indicators.crossedAbove(c.close, Indicators.sma(c.close, days))

    override fun exitSignals(c: Candles): BooleanArray {
        val avg = Indicators.sma(c.close, days)
        return BooleanArray(c.size) { k -> !avg[k].isNaN() && c.close[k] < avg[k] }
    }
}

/** Every built-in strategy, in the order a person reads them. */
object Builtins {
    val all: List<Strategy> = listOf(Breakout(), TrendTimer())

    fun byId(id: String): Strategy? = all.firstOrNull { it.id == id }
}
