package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.DAY_MS
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.PaperTrading
import com.ikverse.signallab.engine.Rng
import com.ikverse.signallab.engine.Statistics
import com.ikverse.signallab.engine.strategy.Strategy

/** One paper trade a strategy would have made, with what the matched random entries did. */
class BacktestTrade(
    val symbol: String,
    /** Open time of the candle the signal fired on. */
    val signalTime: Long,
    val entryTime: Long,
    val entryPrice: Double,
    /** When the trade left: a fill at a candle's open is timed at that open, any other at the candle's close. */
    val exitTime: Long,
    val exitPrice: Double,
    val reason: Leave,
    val barsHeld: Int,
    val gross: Double,
    val net: Double,
    /** Average net result of the random entries; NaN when none could be drawn. */
    val randomMean: Double,
    val excess: Double,
    /** 1 when Bitcoin was above its 200-day average at the signal, 0 when below, -1 before that is known. */
    val regime: Int,
)

/** A strategy's backtest in numbers. NaN means "not available", as with no trades. */
class BacktestSummary(
    val n: Int,
    /** Share of trades that ended up after costs. */
    val hit: Double,
    val meanNet: Double,
    val meanExcess: Double,
    /** Cluster-robust t-statistic of the excess, clustered by calendar week. */
    val tCluster: Double,
    /** 95% interval of the mean excess from a week-cluster bootstrap; null with fewer than two trades. */
    val excessCi95: Pair<Double, Double>?,
    val weeks: Int,
    /** Mean excess and count by regime: 1 Bitcoin above its 200-day average, 0 below. */
    val byRegime: Map<Int, Pair<Int, Double>>,
)

/**
 * Runs a strategy over stored history the way the live runner will run it: one open trade per coin at a time, entry at
 * the next open, the strategy's own exit plan, the cost for each coin, and a matched random baseline for every trade.
 */
object Backtest {
    /** Calendar weeks start on Monday 00:00 UTC; 1 Jan 1970 was a Thursday, three days later in the week. */
    private const val MONDAY_SHIFT_MS = 3 * DAY_MS
    private const val WEEK_MS = 7 * DAY_MS

    fun weekOf(time: Long): Long = Math.floorDiv(time + MONDAY_SHIFT_MS, WEEK_MS)

    /**
     * [panel] holds each coin's candles on the strategy's chart; [btcDaily] gives the regime. A bull-only strategy's
     * signals while Bitcoin is not above its 200-day average are skipped, as they would be live.
     */
    fun run(
        strategy: Strategy,
        panel: Map<String, Candles>,
        btcDaily: Candles,
        costFor: (String) -> Double = EngineConfig::costFor,
    ): List<BacktestTrade> {
        val plan = strategy.exit
        val out = ArrayList<BacktestTrade>()
        for ((symbol, c) in panel) {
            require(c.tf == strategy.timeframe) { "${strategy.id} reads ${strategy.timeframe.label} candles, $symbol has ${c.tf.label}" }
            val entries = strategy.entries(c)
            val exits = strategy.exitSignals(c)
            val atr = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)
            val regime = PaperTrading.regimeSeries(btcDaily, c.closeTime)
            val cost = costFor(symbol)

            val kept = ArrayList<Int>()
            val fills = ArrayList<Fill>()
            var busyUntil = -1
            for (s in entries.indices) {
                if (!entries[s] || s + 1 <= busyUntil || !Fills.judgeable(c, s, plan, atr)) continue
                if (strategy.bullOnly && regime[s] != 1) continue
                val fill = Fills.resolve(c, s + 1, plan, atr[s], exits) ?: continue
                kept.add(s)
                fills.add(fill)
                busyUntil = fill.exitIdx
            }
            if (kept.isEmpty()) continue
            val signals = kept.toIntArray()
            val random = Baseline.meanNet(strategy.id, symbol, c, atr, regime, signals, plan, exits, cost)
            for (j in signals.indices) {
                val s = signals[j]
                val f = fills[j]
                val entry = c.open[s + 1]
                val gross = f.exitPrice / entry - 1
                val net = gross - cost
                out.add(BacktestTrade(
                    symbol = symbol, signalTime = c.t[s], entryTime = c.t[s + 1], entryPrice = entry,
                    exitTime = Fills.exitTime(c, f),
                    exitPrice = f.exitPrice, reason = f.reason, barsHeld = f.exitIdx - s, gross = gross, net = net,
                    randomMean = random[j], excess = if (random[j].isNaN()) Double.NaN else net - random[j], regime = regime[s],
                ))
            }
        }
        return out
    }

    /** The numbers behind a strategy's backtest. [seedName] makes the bootstrap reproducible. */
    fun summarize(trades: List<BacktestTrade>, seedName: String): BacktestSummary {
        val n = trades.size
        if (n == 0) return BacktestSummary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, 0, emptyMap())
        val paired = trades.filter { !it.excess.isNaN() }
        val excess = DoubleArray(paired.size) { paired[it].excess }
        val weeks = LongArray(paired.size) { weekOf(paired[it].entryTime) }
        val byRegime = paired.groupBy { it.regime }.filterKeys { it >= 0 }
            .mapValues { (_, list) -> list.size to list.sumOf { it.excess } / list.size }
        return BacktestSummary(
            n = n,
            hit = trades.count { it.net > 0 }.toDouble() / n,
            meanNet = trades.sumOf { it.net } / n,
            meanExcess = if (excess.isEmpty()) Double.NaN else excess.sum() / excess.size,
            tCluster = if (excess.size > 1) Statistics.clusterT(excess, weeks) else Double.NaN,
            excessCi95 = if (excess.size > 1) Statistics.clusterBootstrapCi(excess, weeks, Rng.seedOf(seedName, "bootstrap"), EngineConfig.BOOTSTRAP_RESAMPLES) else null,
            weeks = weeks.distinct().size,
            byRegime = byRegime.toSortedMap(),
        )
    }
}
