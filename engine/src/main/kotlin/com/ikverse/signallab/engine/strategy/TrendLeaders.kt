package com.ikverse.signallab.engine.strategy

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.DAY_MS
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.Timeframe
import kotlin.math.ceil

/**
 * The 28 trend indicators of CTREND (Fieberg, Liedtke, Poddig, Walker and Zaremba, *JFQA* 60(7), 2025, §III.B), each a
 * series over a coin's daily candles, scaled the paper's way so coins at different prices compare.
 */
object TrendIndicators {
    val NAMES = listOf(
        "rsi", "stochRSI", "stochK", "stochD", "cci",
        "sma_3d", "sma_5d", "sma_10d", "sma_20d", "sma_50d", "sma_100d", "sma_200d", "macd", "macd_diff_signal",
        "volsma_3d", "volsma_5d", "volsma_10d", "volsma_20d", "volsma_50d", "volsma_100d", "volsma_200d",
        "volmacd", "volmacd_diff_signal", "chaikin",
        "boll_low", "boll_mid", "boll_high", "boll_width",
    )

    private val WINDOWS = intArrayOf(3, 5, 10, 20, 50, 100, 200)

    /** Every indicator of [c], in the order of [NAMES]; NaN where its window is not full. */
    fun of(c: Candles): List<DoubleArray> {
        val n = c.size
        val close = c.close
        val dollarVolume = DoubleArray(n) { c.volume[it] * close[it] }
        val rsi = Indicators.rsi(close, 14)
        val stochRsi = DoubleArray(n) { i ->
            if (i < 13) return@DoubleArray Double.NaN
            val w = (i - 13..i).map { rsi[it] }
            if (w.any { it.isNaN() }) return@DoubleArray Double.NaN
            val lo = w.min()
            val hi = w.max()
            if (hi > lo) (rsi[i] - lo) / (hi - lo) else 0.5
        }
        val stochK = DoubleArray(n) { i ->
            if (i < 13) return@DoubleArray Double.NaN
            val lo = (i - 13..i).minOf { c.low[it] }
            val hi = (i - 13..i).maxOf { c.high[it] }
            if (hi > lo) (close[i] - lo) / (hi - lo) else 0.5
        }
        // Not Indicators.sma: its running total would carry the leading gaps of stochK through the whole series.
        val stochD = DoubleArray(n) { i -> if (i < 2) Double.NaN else (stochK[i] + stochK[i - 1] + stochK[i - 2]) / 3 }
        val typical = DoubleArray(n) { (c.high[it] + c.low[it] + close[it]) / 3 }
        val typicalAvg = Indicators.sma(typical, 20)
        val cci = DoubleArray(n) { i ->
            if (i < 19 || typicalAvg[i].isNaN()) return@DoubleArray Double.NaN
            val dev = (i - 19..i).sumOf { kotlin.math.abs(typical[it] - typicalAvg[i]) } / 20
            if (dev > 0) (typical[i] - typicalAvg[i]) / (0.015 * dev) else 0.0
        }
        val smas = WINDOWS.map { w -> Indicators.sma(close, w).let { s -> DoubleArray(n) { s[it] / close[it] } } }
        val (macd, macdSignal) = oscillator(close)
        val volSmas = WINDOWS.map { w ->
            Indicators.sma(dollarVolume, w).let { s -> DoubleArray(n) { if (dollarVolume[it] > 0) s[it] / dollarVolume[it] else Double.NaN } }
        }
        val (volMacd, volMacdSignal) = oscillator(dollarVolume)
        val flow = DoubleArray(n) { i ->
            val range = c.high[i] - c.low[i]
            if (range > 0) ((close[i] - c.low[i]) - (c.high[i] - close[i])) / range * c.volume[i] else 0.0
        }
        val chaikin = DoubleArray(n) { i ->
            if (i < 19) return@DoubleArray Double.NaN
            val v = (i - 19..i).sumOf { c.volume[it] }
            if (v > 0) (i - 19..i).sumOf { flow[it] } / v else 0.0
        }
        val mid = Indicators.sma(close, 20)
        val sd = Indicators.rollingStd(close, 20)
        val bollLow = DoubleArray(n) { (mid[it] - 2 * sd[it]) / close[it] }
        val bollMid = DoubleArray(n) { mid[it] / close[it] }
        val bollHigh = DoubleArray(n) { (mid[it] + 2 * sd[it]) / close[it] }
        val bollWidth = DoubleArray(n) { if (mid[it] > 0) 4 * sd[it] / mid[it] else Double.NaN }
        return listOf(rsi, stochRsi, stochK, stochD, cci) + smas + listOf(macd, macdSignal) + volSmas +
            listOf(volMacd, volMacdSignal, chaikin, bollLow, bollMid, bollHigh, bollWidth)
    }

    /** The percentage oscillator (fast EMA 12 minus slow EMA 26, over the fast) and its distance from a 9-day EMA of itself. */
    private fun oscillator(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val fast = Indicators.ema(x, 12)
        val slow = Indicators.ema(x, 26)
        val osc = DoubleArray(x.size) { if (fast[it] > 0) (fast[it] - slow[it]) / fast[it] else Double.NaN }
        val firstValid = osc.indexOfFirst { !it.isNaN() }
        val signal = DoubleArray(x.size) { Double.NaN }
        if (firstValid >= 0) {
            val tail = Indicators.ema(osc.copyOfRange(firstValid, osc.size), 9)
            for (k in tail.indices) signal[firstValid + k] = tail[k]
        }
        return osc to DoubleArray(x.size) { osc[it] - signal[it] }
    }
}

/**
 * Trend leaders (docs/strategy-research.md §7.3): every Monday close, the watched coins ranked by a forecast built from the
 * 28 CTREND indicators, the top fifth bought for a week.
 *
 * The forecast follows the paper's combined Fama–MacBeth construction. Each week, for each indicator, the coins' next-week
 * returns (less their average) are regressed on the indicator's values; each indicator's forecast for a coin uses the
 * average intercept and slope of every past week whose return is already known. One simplification: the paper keeps the
 * forecasts an elastic net selects on the last 12 weeks; here a forecast is kept when it was positively related to the
 * coins' returns over those 12 weeks (the paper's own restriction, θ > 0, without the shrinkage). The kept forecasts are
 * averaged, and the coins ranked by the average.
 *
 * Peer-reviewed, with a long leg that earned 3.1 to 3.4% a week after costs on the 100 largest coins in 2015 to 2022, but
 * its later half was not significant alone and nothing covers the years after the ETFs: Watch.
 */
class TrendLeaders(
    val minCoins: Int = 8,
    val minWeeks: Int = 26,
    val selectionWeeks: Int = 12,
) : Strategy {
    override val id = "trendleaders_1d"
    override val name = "Trend leaders"
    override val timeframe = Timeframe.D1
    override val launchTier = Tier.WATCH
    override val bullOnly = false
    override val trend = true
    override val exit = ExitPlan(maxBars = 7)
    override val crossSectional = true

    /** A ranking needs the other coins: one coin alone never fires. */
    override fun entries(c: Candles): BooleanArray = BooleanArray(c.size)

    override fun entriesAcross(panel: Map<String, Candles>): Map<String, BooleanArray> {
        val out = panel.mapValues { BooleanArray(it.value.size) }
        val symbols = panel.keys.sorted()
        val indicators = symbols.associateWith { TrendIndicators.of(panel.getValue(it)) }
        val mondays = panel.values.flatMap { c -> c.t.filter(::isMonday) }.toSortedSet().toList()
        val j = TrendIndicators.NAMES.size
        // Per week: each coin's index, its indicator values, and its return to the next Monday's close (less the week's average).
        class Week(val idx: Map<String, Int>, val z: Map<String, DoubleArray>, val r: Map<String, Double>)
        val weeks = mondays.mapIndexed { w, t ->
            val idx = HashMap<String, Int>()
            val z = HashMap<String, DoubleArray>()
            val raw = HashMap<String, Double>()
            for (s in symbols) {
                val c = panel.getValue(s)
                val i = c.t.binarySearch(t).takeIf { it >= 0 } ?: continue
                val values = DoubleArray(j) { k -> indicators.getValue(s)[k][i] }
                if (values.any { !it.isFinite() }) continue
                idx[s] = i
                z[s] = values
                val next = mondays.getOrNull(w + 1)?.let { c.t.binarySearch(it) }?.takeIf { it >= 0 }
                if (next != null) raw[s] = c.close[next] / c.close[i] - 1
            }
            val mean = raw.values.takeIf { it.size >= minCoins }?.average()
            Week(idx, z, if (mean == null) emptyMap() else raw.mapValues { it.value - mean })
        }
        // Each week's intercept and slope per indicator, from the coins with both a value and a known return.
        val fits = weeks.map { wk ->
            val coins = wk.r.keys.filter { it in wk.z }
            if (coins.size < minCoins) null
            else Array(j) { k -> ols(coins.map { wk.z.getValue(it)[k] }, coins.map { wk.r.getValue(it) }) }
        }
        val pastForecasts = ArrayList<Array<Map<String, Double>>?>()
        for ((w, wk) in weeks.withIndex()) {
            // Weeks whose return is known at this Monday's close: those before it.
            val known = fits.subList(0, w).filterNotNull()
            if (known.size < minWeeks || wk.z.size < minCoins) {
                pastForecasts.add(null)
                continue
            }
            val alpha = DoubleArray(j) { k -> known.map { it[k].first }.average() }
            val beta = DoubleArray(j) { k -> known.map { it[k].second }.average() }
            val forecasts = Array(j) { k -> wk.z.mapValues { (_, v) -> alpha[k] + beta[k] * v[k] } }
            pastForecasts.add(forecasts)
            // Keep the forecasts that moved with returns over the last weeks whose returns are known.
            val recent = (maxOf(0, w - selectionWeeks) until w).filter { pastForecasts[it] != null && weeks[it].r.isNotEmpty() }
            val kept = (0 until j).filter { k ->
                recent.isNotEmpty() && recent.sumOf { pw ->
                    val f = pastForecasts[pw]!![k]
                    val r = weeks[pw].r
                    val common = f.keys.filter { it in r }
                    if (common.size < 2) 0.0 else {
                        val mf = common.map { f.getValue(it) }.average()
                        common.sumOf { (f.getValue(it) - mf) * r.getValue(it) }
                    }
                } > 0
            }.ifEmpty { (0 until j).toList() }
            val score = wk.z.keys.associateWith { s -> kept.sumOf { forecasts[it].getValue(s) } / kept.size }
            val top = ceil(score.size * TOP_FRACTION).toInt()
            for ((s, _) in score.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).take(top)) {
                out.getValue(s)[wk.idx.getValue(s)] = true
            }
        }
        return out
    }

    private fun isMonday(t: Long) = t % DAY_MS == 0L && ((t / DAY_MS) + 3) % 7 == 0L

    /** Intercept and slope of y on x; a flat x gives the mean and no slope. */
    private fun ols(x: List<Double>, y: List<Double>): Pair<Double, Double> {
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in x.indices) {
            sxy += (x[i] - mx) * (y[i] - my)
            sxx += (x[i] - mx) * (x[i] - mx)
        }
        val slope = if (sxx > 0) sxy / sxx else 0.0
        return (my - slope * mx) to slope
    }

    companion object {
        /** The top fifth, as in the paper's quintiles. */
        const val TOP_FRACTION = 0.20
    }
}
