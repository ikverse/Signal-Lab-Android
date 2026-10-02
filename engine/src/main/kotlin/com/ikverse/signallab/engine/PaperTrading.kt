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
     * Classic and learned trades end at the first candle to touch the stop or the target (the stop wins when one
     * candle touches both, and a gap fills at the open). A trailing trade has a stop that moves: after each candle
     * closes, once the highest close has risen [EngineConfig.TRAIL_ACTIVATE_ATR] candle sizes above the entry, the
     * stop follows it at [EngineConfig.TRAIL_DISTANCE_ATR] below, and never moves down. Every trade ends at the
     * close of its [ExitSpec.limit]th candle at the latest, and a held trade has only that.
     *
     * [simulateExits] only counts a trade once its whole window exists, even if it stopped out early; this reports
     * the same exit as soon as it has happened, so a live trade closes when its candle does, not days later.
     */
    fun resolve(c: Candles, entryIdx: Int, spec: ExitSpec): Resolution? {
        val n = c.size
        if (entryIdx < 0 || entryIdx >= n) return null
        val span = minOf(spec.limit, n - entryIdx)
        if (spec.mode == ExitMode.TRAIL && spec.stop != null) {
            var stop: Double = spec.stop
            var highest = Double.NEGATIVE_INFINITY
            for (q in 0 until span) {
                val k = entryIdx + q
                if (c.low[k] <= stop) return Resolution(k, minOf(stop, c.open[k]), ExitReason.STOP)
                highest = maxOf(highest, c.close[k])
                if (highest >= spec.entry + EngineConfig.TRAIL_ACTIVATE_ATR * spec.atr) {
                    stop = maxOf(stop, highest - EngineConfig.TRAIL_DISTANCE_ATR * spec.atr)
                }
            }
        } else if (spec.target != null && spec.stop != null) {
            val target: Double = spec.target
            val stop: Double = spec.stop
            for (q in 0 until span) {
                val k = entryIdx + q
                if (c.low[k] <= stop) return Resolution(k, minOf(stop, c.open[k]), ExitReason.STOP)
                if (c.high[k] >= target) return Resolution(k, maxOf(target, c.open[k]), ExitReason.TARGET)
            }
        }
        val last = entryIdx + spec.limit - 1
        return if (last < n) Resolution(last, c.close[last], ExitReason.TIME) else null
    }

    /** The classic rule's form of [resolve]: a target and stop together, or neither (a plain hold). */
    fun resolve(c: Candles, entryIdx: Int, target: Double?, stop: Double?, limit: Int): Resolution? =
        resolve(c, entryIdx, ExitSpec(if (target != null && stop != null) ExitMode.CLASSIC else ExitMode.HELD,
            if (entryIdx in 0 until c.size) c.open[entryIdx] else Double.NaN, Double.NaN, target, stop, limit))

    /**
     * Paper trades for a batch of signals under any exit rule, ending each the way [resolve] does, so a backtest and a
     * live scan cannot disagree about an exit. The classic and held rules go through [simulateExits] unchanged.
     */
    fun simulate(c: Candles, signalIdx: IntArray, tf: Timeframe, atr: DoubleArray, rule: ExitRule): Exits {
        if (rule.mode == ExitMode.CLASSIC) return simulateExits(c, signalIdx, tf, atr, null)
        if (rule.mode == ExitMode.HELD && !rule.endOfDay) return simulateExits(c, signalIdx, tf, atr, rule.limit)
        val n = c.size
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
            if (a.isNaN() || !rule.allows(c, s)) continue
            val limit = rule.limitAt(c.t[s] + c.tf.ms, c.tf)
            if (e + limit - 1 >= n) continue
            val spec = rule.spec(c.open[e], a, limit)
            val r = resolve(c, e, spec) ?: continue
            valid[j] = true
            entryPrice[j] = c.open[e]
            exitPrice[j] = r.exitPrice
            gross[j] = r.exitPrice / c.open[e] - 1
            exitIdx[j] = r.exitIdx
            reason[j] = r.reason
        }
        return Exits(valid, entryPrice, exitPrice, gross, exitIdx, reason)
    }

    /** How far a trade that entered at candle [entryIdx] and left on [exitIdx] ran up and down, and how many candles the high took. */
    class Excursion(val maxUp: Double, val maxDown: Double, val barsToPeak: Int)

    fun excursion(c: Candles, entryIdx: Int, exitIdx: Int, entry: Double): Excursion {
        var high = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY
        var at = 0
        for (k in entryIdx..exitIdx) {
            if (c.high[k] > high) {
                high = c.high[k]
                at = k - entryIdx + 1
            }
            if (c.low[k] < low) low = c.low[k]
        }
        return Excursion(high / entry - 1, low / entry - 1, at)
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
