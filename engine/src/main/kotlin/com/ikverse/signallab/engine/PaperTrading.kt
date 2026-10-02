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

/** Where an open trade ended, found in candles that have closed. */
class Resolution(val exitIdx: Int, val exitPrice: Double, val reason: ExitReason)

/**
 * The paper-trading rules. A signal on bar s enters at the open of bar s+1 and exits at the first of
 * a target (2x ATR above), a stop (1x ATR below) or a time limit. If one candle touches both, the
 * stop is assumed to have come first. A gap through a level fills at the open, not at the level.
 */
object PaperTrading {
    /** The target for an entry at [entry] when the coin's average true range is [atr]. */
    fun targetPrice(entry: Double, atr: Double): Double = entry + EngineConfig.TARGET_ATR * atr

    /** The stop for an entry at [entry] when the coin's average true range is [atr]. */
    fun stopPrice(entry: Double, atr: Double): Double = entry - EngineConfig.STOP_ATR * atr

    /**
     * Where a trade that entered at the open of candle [entryIdx] stands, judged on the candles in [c],
     * which are all closed. Returns its exit if it has one, and null while it is still open.
     *
     * The rules are [simulateExits]'s, applied one candle at a time: the first candle to touch the
     * [stop] or the [target] ends the trade (the stop wins when one candle touches both, and a gap
     * fills at the open), and otherwise it ends at the close of the [limit]th candle. With no target
     * and stop (a fixed holding period) only the time limit applies. [simulateExits] only counts a
     * trade once its whole window exists, even if it stopped out early; this reports the same exit as
     * soon as it has happened, so a live trade closes when its candle does, not days later.
     */
    fun resolve(c: Candles, entryIdx: Int, target: Double?, stop: Double?, limit: Int): Resolution? {
        val n = c.size
        if (entryIdx < 0 || entryIdx >= n) return null
        if (target != null && stop != null) {
            for (q in 0 until minOf(limit, n - entryIdx)) {
                val k = entryIdx + q
                if (c.low[k] <= stop) return Resolution(k, minOf(stop, c.open[k]), ExitReason.STOP)
                if (c.high[k] >= target) return Resolution(k, maxOf(target, c.open[k]), ExitReason.TARGET)
            }
        }
        val last = entryIdx + limit - 1
        return if (last < n) Resolution(last, c.close[last], ExitReason.TIME) else null
    }

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
                val target = targetPrice(entry, a)
                val stop = stopPrice(entry, a)
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
