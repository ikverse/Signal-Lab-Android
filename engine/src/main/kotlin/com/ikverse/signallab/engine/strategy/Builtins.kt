package com.ikverse.signallab.engine.strategy

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.DAY_MS
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.Timeframe
import kotlin.math.ceil

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
 * Trend timer (docs/strategy-research.md §1.1): on Bitcoin and the ten busiest coins, enter when a daily close crosses
 * above its [days]-day average, and leave on the first daily close back below it. The research's edge here is avoiding
 * long drawdowns, so there is no price stop; [maxDays] only keeps every trade's window finite, which the random baseline
 * needs.
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
    override val ranks = 1..10

    override fun entries(c: Candles): BooleanArray = Indicators.crossedAbove(c.close, Indicators.sma(c.close, days))

    override fun exitSignals(c: Candles): BooleanArray {
        val avg = Indicators.sma(c.close, days)
        return BooleanArray(c.size) { k -> !avg[k].isNaN() && c.close[k] < avg[k] }
    }
}

/**
 * Momentum 2w (docs/strategy-research.md §1.2): on the 30 busiest coins while Bitcoin is above its 200-day average, enter
 * when the last [weeks] weeks' return is positive and above its own median of the prior [medianDays] days; hold [weeks]
 * weeks. Momentum in crypto lives at one to four weeks and dies in bear markets.
 */
class Momentum(
    val weeks: Int = 2,
    val medianDays: Int = 60,
) : Strategy {
    override val id = "mom${weeks}w_1d"
    override val name = "Momentum ${weeks}w"
    override val timeframe = Timeframe.D1
    override val launchTier = Tier.PROMISING
    override val bullOnly = true
    override val exit = ExitPlan(maxBars = 7 * weeks, safetyStopAtr = 3.0)
    override val ranks = 1..30

    override fun entries(c: Candles): BooleanArray {
        val r = Indicators.pctReturn(c.close, 7 * weeks)
        val state = BooleanArray(c.size) { i ->
            if (i < medianDays || r[i].isNaN() || r[i] <= 0) return@BooleanArray false
            val prior = (i - medianDays until i).map { r[it] }.filter { !it.isNaN() }
            prior.size >= medianDays / 2 && r[i] > Indicators.quantile(prior.toDoubleArray(), 0.5)
        }
        return firstOfRun(state)
    }
}

/**
 * Near high (docs/strategy-research.md §1.4): on the 30 busiest coins while Bitcoin is above its 200-day average, enter
 * when a daily close is within [withinPct] of the coin's highest high of the last seven days; hold a week. Large, liquid
 * coins near their weekly high tend to keep going. One working paper: Testing.
 */
class NearHigh(
    val withinPct: Double = 0.01,
) : Strategy {
    override val id = "nearhigh7_1d"
    override val name = "Near high"
    override val timeframe = Timeframe.D1
    override val launchTier = Tier.TESTING
    override val bullOnly = true
    override val exit = ExitPlan(maxBars = 7, safetyStopAtr = 3.0)
    override val ranks = 1..30

    override fun entries(c: Candles): BooleanArray {
        val state = BooleanArray(c.size) { i ->
            if (i < 6) return@BooleanArray false
            val high = (i - 6..i).maxOf { c.high[it] }
            c.close[i] >= high * (1 - withinPct)
        }
        return firstOfRun(state)
    }
}

/**
 * Quiet dip (docs/strategy-research.md §1.5): on coins ranked 10 to 80, enter after a daily fall of more than
 * [dropPct] on volume below its median of the prior 30 days; hold five days. A drop that few traded tends to bounce, so
 * the exit is a time stop with no price stop (a stop lowers the expected return of a bounce).
 */
class QuietDip(
    val dropPct: Double = 0.05,
) : Strategy {
    override val id = "quietdip_1d"
    override val name = "Quiet dip"
    override val timeframe = Timeframe.D1
    override val launchTier = Tier.TESTING
    override val bullOnly = false
    override val exit = ExitPlan(maxBars = 5)
    override val ranks = 10..80

    override fun entries(c: Candles): BooleanArray {
        val r = Indicators.pctReturn(c.close, 1)
        return BooleanArray(c.size) { i ->
            if (i < 31 || r[i].isNaN() || r[i] >= -dropPct) return@BooleanArray false
            val prior = DoubleArray(30) { c.volume[i - 30 + it] }
            c.volume[i] < Indicators.quantile(prior, 0.5)
        }
    }
}

/**
 * Leaders (docs/strategy-research.md §1.3): every week, on the daily candle that opens on Monday, the top fifth of the
 * coins by their last [weeks] weeks' return; hold two weeks; while Bitcoin is above its 200-day average. Long-only and
 * fading since 2022: Watch.
 */
class Leaders(
    val weeks: Int = 3,
    val minCoins: Int = 8,
) : Strategy {
    override val id = "leaders${weeks}w_1d"
    override val name = "Leaders"
    override val timeframe = Timeframe.D1
    override val launchTier = Tier.WATCH
    override val bullOnly = true
    override val exit = ExitPlan(maxBars = 14)
    override val crossSectional = true

    /** A ranking needs the other coins: one coin alone never fires. */
    override fun entries(c: Candles): BooleanArray = BooleanArray(c.size)

    override fun entriesAcross(panel: Map<String, Candles>): Map<String, BooleanArray> {
        val out = panel.mapValues { BooleanArray(it.value.size) }
        val returns = panel.mapValues { Indicators.pctReturn(it.value.close, 7 * weeks) }
        val mondays = panel.values.flatMap { c -> c.t.filter { isMonday(it) } }.toSortedSet()
        for (t in mondays) {
            val ranked = panel.mapNotNull { (sym, c) ->
                val i = c.t.indexOfFirst { it == t }
                val r = if (i >= 0) returns.getValue(sym)[i] else Double.NaN
                if (r.isNaN()) null else Triple(sym, i, r)
            }
            if (ranked.size < minCoins) continue
            val top = ceil(ranked.size * EngineConfig.XS_MOMENTUM_TOP_FRACTION).toInt()
            for ((sym, i, _) in ranked.sortedWith(compareByDescending<Triple<String, Int, Double>> { it.third }.thenBy { it.first }).take(top)) {
                out.getValue(sym)[i] = true
            }
        }
        return out
    }

    private fun isMonday(t: Long) = t % DAY_MS == 0L && ((t / DAY_MS) + 3) % 7 == 0L
}

/**
 * Hourly trend (docs/strategy-research.md §1.6): an hourly close crossing above its 50-hour exponential average, taken only
 * while the daily 50-day average is rising; leave on an hourly close back below it, or after two days. The only test was
 * gross of fees, and fees decided it: over its first 121 live calls (16 to 21 October 2026) it won 10%, lost 0.74% per call
 * after fees and sat 0.76% behind random entries (t = -2.16), past the Failing rule. Retired (docs/strategy-research.md §7).
 */
class HourlyTrend(
    val hours: Int = 50,
    val days: Int = 50,
) : Strategy {
    override val id = "hourlytrend_1h"
    override val name = "Hourly trend"
    override val timeframe = Timeframe.H1
    override val launchTier = Tier.TESTING
    override val bullOnly = false
    override val exit = ExitPlan(maxBars = 48)
    override val retired = true

    override fun entries(c: Candles): BooleanArray {
        val cross = Indicators.crossedAbove(c.close, Indicators.ema(c.close, hours))
        val rising = dailyRising(c)
        return BooleanArray(c.size) { cross[it] && rising[it] }
    }

    override fun exitSignals(c: Candles): BooleanArray {
        val avg = Indicators.ema(c.close, hours)
        return BooleanArray(c.size) { k -> !avg[k].isNaN() && c.close[k] < avg[k] }
    }

    /** Whether the daily average of the last completed UTC days was rising, as known at each hourly close. */
    private fun dailyRising(c: Candles): BooleanArray {
        val closes = ArrayList<Double>()
        val out = BooleanArray(c.size)
        var rising = false
        for (i in 0 until c.size) {
            if ((c.closeTime[i] + 1) % DAY_MS == 0L) {
                closes.add(c.close[i])
                val n = closes.size
                if (n > days) {
                    val now = (n - days until n).sumOf { closes[it] } / days
                    val before = (n - days - 1 until n - 1).sumOf { closes[it] } / days
                    rising = now > before
                }
            }
            out[i] = rising
        }
        return out
    }
}

/** Every built-in strategy, in the order a person reads them. */
object Builtins {
    val all: List<Strategy> = listOf(Breakout(), TrendTimer(), Momentum(), Leaders(), NearHigh(), QuietDip(), HourlyTrend())

    fun byId(id: String): Strategy? = all.firstOrNull { it.id == id }
}
