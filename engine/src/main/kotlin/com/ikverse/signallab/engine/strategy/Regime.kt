package com.ikverse.signallab.engine.strategy

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.DAY_MS
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.Timeframe

/** A coin's funding settlements, oldest first: when each settled and the rate longs paid shorts for its interval. */
class FundingSeries(val time: LongArray, val rate: DoubleArray) {
    init {
        require(time.size == rate.size) { "one rate per settlement" }
    }

    /** The mean rate of the settlements in (from, to], or NaN when fewer than [min] settled there. */
    fun mean(from: Long, to: Long, min: Int): Double {
        var lo = time.binarySearch(from + 1).let { if (it < 0) -it - 1 else it }
        var sum = 0.0
        var n = 0
        while (lo < time.size && time[lo] <= to) {
            sum += rate[lo]
            n++
            lo++
        }
        return if (n >= min) sum / n else Double.NaN
    }
}

/**
 * The funding history the regime strategies read, kept up to date by the app before each pass (funding is not candles, so it
 * cannot travel inside [Candles]). A strategy reads only settlements at or before the close of the candle it judges, so
 * cutting history at any candle still gives the same answer up to it.
 */
class FundingBook {
    @Volatile
    private var series: Map<String, FundingSeries> = emptyMap()

    fun update(all: Map<String, FundingSeries>) {
        series = all
    }

    fun of(symbol: String): FundingSeries? = series[symbol]
}

/**
 * Hated rally (docs/strategy-research.md §7.2): buys Bitcoin, Ether and the ten busiest coins after shorts have paid longs for
 * a month. It enters when the 30-day average funding rate turns negative after a positive month before it, or when the 7-day
 * average falls below −5% a year; once per episode. It holds up to 60 days, leaves on a close below the prior 20-day low,
 * with a safety stop 3 average true ranges down. Every such stretch since 2018 was followed by strong months for Bitcoin
 * (K33, VanEck), but the evidence is gross, from seven to nine episodes, with an unpublished method: Testing.
 */
class HatedRally(
    private val funding: FundingBook,
    val maxDays: Int = 60,
    val exitLookback: Int = 20,
) : Strategy {
    override val id = "hatedrally_1d"
    override val name = "Hated rally"
    override val timeframe = Timeframe.D1
    override val launchTier = Tier.TESTING
    override val bullOnly = false
    override val exit = ExitPlan(maxBars = maxDays, safetyStopAtr = 3.0)
    override val ranks = 1..12

    override fun entries(c: Candles): BooleanArray {
        val f = funding.of(c.symbol) ?: return BooleanArray(c.size)
        val state = BooleanArray(c.size) { i ->
            val now = c.closeTime[i]
            val month = f.mean(now - 30 * DAY_MS, now, MIN_MONTH)
            val before = f.mean(now - 60 * DAY_MS, now - 30 * DAY_MS, MIN_MONTH)
            val week = f.mean(now - 7 * DAY_MS, now, MIN_WEEK)
            (month < 0 && before > 0) || week * SETTLEMENTS_PER_YEAR < WEEK_FLOOR
        }
        return firstOfRun(state)
    }

    override fun exitSignals(c: Candles): BooleanArray {
        val low = Indicators.rollingMinPrior(c.low, exitLookback)
        return BooleanArray(c.size) { k -> !low[k].isNaN() && c.close[k] < low[k] }
    }

    companion object {
        /** Binance settles funding three times a day. */
        const val SETTLEMENTS_PER_YEAR = 3 * 365.0

        /** The 7-day average below this, annualised, is deeply negative. */
        const val WEEK_FLOOR = -0.05

        /** At least two thirds of a window's settlements must be stored before it is read. */
        const val MIN_MONTH = 60
        const val MIN_WEEK = 14
    }
}
