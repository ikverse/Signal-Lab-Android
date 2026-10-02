package com.ikverse.signallab.engine

enum class ExitReason(val label: String) {
    STOP("stop"), TARGET("target"), TIME("time");

    companion object {
        fun of(label: String): ExitReason = entries.first { it.label == label }
    }
}

/** Exits for a batch of signals on one coin. Arrays line up with the signals; [valid] says which are real. */
class Exits(
    val valid: BooleanArray,
    val entryPrice: DoubleArray,
    val exitPrice: DoubleArray,
    val gross: DoubleArray,
    val exitIdx: IntArray,
    val reason: Array<ExitReason?>,
)

/**
 * The paper-trading rules. A signal on bar s enters at the open of bar s+1 and exits at the first of
 * a target (2x ATR above), a stop (1x ATR below) or a time limit. If one candle touches both, the
 * stop is assumed to have come first. A gap through a level fills at the open, not at the level.
 */
object PaperTrading {
    /**
     * @param holdBars when set, the trade has no target or stop and is held that many candles.
     */
    fun simulateExits(c: Candles, signalIdx: IntArray, tf: Timeframe, atr: DoubleArray, holdBars: Int? = null): Exits {
        val n = c.size
        val limit = holdBars ?: EngineConfig.timeLimitBars(tf)
        val m = signalIdx.size
        val valid = BooleanArray(m)
        val entryPrice = DoubleArray(m) { Double.NaN }
        val exitPrice = DoubleArray(m) { Double.NaN }
        val gross = DoubleArray(m) { Double.NaN }
        val exitIdx = IntArray(m) { -1 }
        val reason = arrayOfNulls<ExitReason>(m)
        for (j in 0 until m) {
            val s = signalIdx[j]
            val e = s + 1
            val a = atr[minOf(s, n - 1)]
            if (!(e + limit - 1 < n && !a.isNaN())) continue
            valid[j] = true
            val entry = c.open[e]
            val k: Int
            val price: Double
            val why: ExitReason
            if (holdBars != null) {
                k = limit - 1
                price = c.close[e + limit - 1]
                why = ExitReason.TIME
            } else {
                val target = entry + EngineConfig.TARGET_ATR * a
                val stop = entry - EngineConfig.STOP_ATR * a
                var firstStop = limit
                var firstTarget = limit
                for (q in 0 until limit) {
                    if (firstStop == limit && c.low[e + q] <= stop) firstStop = q
                    if (firstTarget == limit && c.high[e + q] >= target) firstTarget = q
                    if (firstStop < limit && firstTarget < limit) break
                }
                val isStop = firstStop <= firstTarget && firstStop < limit
                val isTarget = !isStop && firstTarget < limit
                k = if (isStop) firstStop else if (isTarget) firstTarget else limit - 1
                val openK = c.open[e + k]
                price = when {
                    isStop -> minOf(stop, openK)
                    isTarget -> maxOf(target, openK)
                    else -> c.close[e + limit - 1]
                }
                why = if (isStop) ExitReason.STOP else if (isTarget) ExitReason.TARGET else ExitReason.TIME
            }
            entryPrice[j] = entry
            exitPrice[j] = price
            gross[j] = price / entry - 1
            exitIdx[j] = e + k
            reason[j] = why
        }
        return Exits(valid, entryPrice, exitPrice, gross, exitIdx, reason)
    }

    /** Net return from the next open to the close [h] candles later, after costs; NaN where there is no such candle. */
    fun horizonReturns(c: Candles, signalIdx: IntArray, horizons: IntArray, cost: Double): Map<Int, DoubleArray> {
        val n = c.size
        return horizons.associateWith { h ->
            DoubleArray(signalIdx.size) { j ->
                val e = signalIdx[j] + 1
                val end = e + h - 1
                if (end < n) c.close[end] / c.open[e] - 1 - cost else Double.NaN
            }
        }
    }

    /**
     * 1 if BTC's last completed daily close was above its 200-day average, 0 if below, -1 before
     * there is enough history. Only daily candles that had closed by then are used.
     */
    fun regimeSeries(btcDaily: Candles, closeTimes: LongArray): IntArray {
        val ma = Indicators.sma(btcDaily.close, EngineConfig.REGIME_MA_DAYS)
        val above = IntArray(btcDaily.size) { i ->
            if (ma[i].isNaN()) -1 else if (btcDaily.close[i] > ma[i]) 1 else 0
        }
        return IntArray(closeTimes.size) { j ->
            val k = upperBound(btcDaily.closeTime, closeTimes[j]) - 1
            if (k >= 0) above[k] else -1
        }
    }
}
