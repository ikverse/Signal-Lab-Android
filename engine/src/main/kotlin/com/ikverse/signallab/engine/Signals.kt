package com.ikverse.signallab.engine

import kotlin.math.ceil

/**
 * One signal variant: a name like `donchian20_1d`, the family it belongs to, and the settings that
 * distinguish it from its siblings. Equal keys are the same variant.
 */
data class SignalKey(val name: String, val family: String, val params: Map<String, Double>) {
    fun param(key: String): Double? = params[key]
    /** Candles to hold a trade for, when the variant has no target and stop. */
    val holdBars: Int? get() = params["hold_bars"]?.toInt()
}

/**
 * The signals. A flag on bar i means "detected when bar i closed", and each rule looks only at bars
 * up to i, so running it on history cut off at bar i gives the same answer for bar i as running it
 * on all of it. The tests check that for every signal on every timeframe.
 */
object Signals {
    fun barsPerWeek(tf: Timeframe): Int = 7 * 24 / tf.hours

    private fun isUtcMidnight(t: Long) = t % DAY_MS == 0L

    // 1 Jan 1970 was a Thursday, so (days + 3) % 7 == 0 on Mondays.
    private fun isMondayMidnight(t: Long) = isUtcMidnight(t) && ((t / DAY_MS) + 3) % 7 == 0L

    /** True only on the first bar of each run of true values. */
    fun firstBarOf(state: BooleanArray): BooleanArray =
        BooleanArray(state.size) { i -> state[i] && (i == 0 || !state[i - 1]) }

    private fun key(name: String, family: String, vararg p: Pair<String, Double>) =
        SignalKey(name, family, linkedMapOf(*p))

    // --- per-coin signals ---------------------------------------------------------

    fun trendState(c: Candles): Map<SignalKey, BooleanArray> =
        EngineConfig.TREND_MA_WINDOWS.associate { n ->
            key("trend_ma${n}_${c.tf.label}", "trend_state", "ma" to n.toDouble()) to
                Indicators.crossedAbove(c.close, Indicators.sma(c.close, n))
        }

    fun donchian(c: Candles): Map<SignalKey, BooleanArray> =
        EngineConfig.DONCHIAN_LOOKBACKS.associate { n ->
            val prior = Indicators.rollingMaxPrior(c.high, n)
            val above = BooleanArray(c.size) { i -> c.close[i] > prior[i] }
            key("donchian${n}_${c.tf.label}", "donchian", "lookback" to n.toDouble()) to firstBarOf(above)
        }

    /**
     * Quantile of [values] over the trailing window, recomputed at each UTC midnight bar and held
     * until the next one. The recompute points depend on timestamps, not on how much history was
     * loaded, so the threshold for a bar never changes when more data arrives.
     */
    internal fun rollingQuantileDaily(
        values: DoubleArray, t: LongArray, historyDays: Int, q: Double, tf: Timeframe,
    ): DoubleArray {
        val thr = DoubleArray(values.size) { Double.NaN }
        val windowMs = historyDays * DAY_MS
        // At least half a window of history before the quantile is trusted.
        val minCount = historyDays * 24 / tf.hours / 2
        val lastMidnight = IntArray(t.size) { -1 }
        var last = -1
        for (j in t.indices) {
            if (isUtcMidnight(t[j])) {
                last = j
                val lo = upperBound(t, t[j] - windowMs)
                var count = 0
                for (i in lo..j) if (!values[i].isNaN()) count++
                if (count >= minCount) {
                    val w = DoubleArray(count)
                    var k = 0
                    for (i in lo..j) if (!values[i].isNaN()) w[k++] = values[i]
                    thr[j] = Indicators.quantile(w, q)
                }
            }
            lastMidnight[j] = last
        }
        return DoubleArray(values.size) { i -> if (lastMidnight[i] >= 0) thr[lastMidnight[i]] else Double.NaN }
    }

    fun tsMomentum(c: Candles): Map<SignalKey, BooleanArray> =
        EngineConfig.TS_MOMENTUM_WEEKS.associate { w ->
            val ret = Indicators.pctReturn(c.close, w * barsPerWeek(c.tf))
            val thr = rollingQuantileDaily(ret, c.t, EngineConfig.TS_MOMENTUM_HISTORY_DAYS,
                EngineConfig.TS_MOMENTUM_TOP_QUANTILE, c.tf)
            val strong = BooleanArray(c.size) { i -> ret[i] >= thr[i] }
            key("tsmom${w}w_${c.tf.label}", "ts_momentum", "weeks" to w.toDouble()) to firstBarOf(strong)
        }

    // --- intraday experiments (1h only) -------------------------------------------

    fun bigMoveFade(c: Candles): Map<SignalKey, BooleanArray> {
        val out = LinkedHashMap<SignalKey, BooleanArray>()
        val volBars = EngineConfig.FADE_VOL_DAYS * 24
        for (k in EngineConfig.FADE_HOURS) {
            val r = Indicators.pctReturn(c.close, k)
            val sd = Indicators.rollingStd(r, volBars)
            // The move itself must not inflate its own yardstick, so the deviation is the previous bar's.
            val bigDrop = BooleanArray(c.size) { i ->
                val before = if (i == 0) Double.NaN else sd[i - 1]
                r[i] < -EngineConfig.FADE_SIGMA * before
            }
            val flags = firstBarOf(bigDrop)
            out[key("fade${k}h_${c.tf.label}", "big_move_fade", "hours" to k.toDouble(),
                "sigma" to EngineConfig.FADE_SIGMA)] = flags
            if (k == 1) {
                // Declared after the first backtest hinted at a 24-hour bounce: same entry, held 24
                // candles with no target or stop. Judged on live paper trades only.
                out[key("fade1h_hold24_${c.tf.label}", "big_move_fade", "hours" to 1.0,
                    "sigma" to EngineConfig.FADE_SIGMA, "hold_bars" to 24.0)] = flags
            }
        }
        return out
    }

    /**
     * Signal on the 22:00 bar (detected when it closes at 23:00), so the paper trade enters at the
     * 23:00 open and is held for the last hour of the UTC day.
     */
    fun intradayMomentum(c: Candles): Map<SignalKey, BooleanArray> {
        val flags = BooleanArray(c.size)
        val mid = c.t.indices.filter { isUtcMidnight(c.t[it]) }
        val firstHour = DoubleArray(mid.size) { c.close[mid[it]] / c.open[mid[it]] - 1 }
        val midDays = LongArray(mid.size) { c.t[mid[it]] / DAY_MS }
        val minCount = EngineConfig.INTRADAY_MOM_HISTORY_DAYS / 2
        for (i in c.t.indices) {
            if (c.t[i] % DAY_MS != 22 * HOUR_MS) continue
            val day = c.t[i] / DAY_MS
            val k = lowerBound(midDays, day)
            if (k >= midDays.size || midDays[k] != day) continue
            val past = ArrayList<Double>()
            for (m in midDays.indices) {
                if (midDays[m] >= day - EngineConfig.INTRADAY_MOM_HISTORY_DAYS && midDays[m] < day) past.add(firstHour[m])
            }
            if (past.size < minCount) continue
            val thr = Indicators.quantile(past.toDoubleArray(), EngineConfig.INTRADAY_MOM_TOP_QUANTILE)
            flags[i] = firstHour[k] > 0 && firstHour[k] >= thr
        }
        return mapOf(key("intraday_mom_1h", "intraday_momentum",
            "top_quantile" to EngineConfig.INTRADAY_MOM_TOP_QUANTILE, "hold_bars" to 1.0) to flags)
    }

    // --- cross-sectional momentum -------------------------------------------------

    /**
     * Every Monday 00:00 UTC bar, the coins in the top fifth by w-week return get a signal. A week
     * with fewer than [EngineConfig.XS_MOMENTUM_MIN_COINS] coins trading is skipped. The ranking is
     * among whatever coins are in [panel], so each watchlist is its own universe.
     */
    fun xsMomentum(panel: Map<String, Candles>): Map<SignalKey, Map<String, BooleanArray>> {
        val out = LinkedHashMap<SignalKey, Map<String, BooleanArray>>()
        if (panel.isEmpty()) return out
        val tf = panel.values.first().tf
        for (w in EngineConfig.XS_MOMENTUM_WEEKS) {
            val lag = w * barsPerWeek(tf)
            val rets = panel.mapValues { Indicators.pctReturn(it.value.close, lag) }
            val flags = panel.mapValues { BooleanArray(it.value.size) }
            val mondays = sortedSetOf<Long>()
            for (c in panel.values) for (t in c.t) if (isMondayMidnight(t)) mondays.add(t)
            for (monday in mondays) {
                val ranked = ArrayList<Triple<Double, String, Int>>()
                for ((sym, c) in panel) {
                    val i = lowerBound(c.t, monday)
                    if (i < c.size && c.t[i] == monday && !rets.getValue(sym)[i].isNaN()) {
                        ranked.add(Triple(rets.getValue(sym)[i], sym, i))
                    }
                }
                if (ranked.size < EngineConfig.XS_MOMENTUM_MIN_COINS) continue
                // Strongest first; ties broken by symbol, descending, as the research version did.
                ranked.sortWith(compareByDescending<Triple<Double, String, Int>> { it.first }
                    .thenByDescending { it.second }.thenByDescending { it.third })
                val k = ceil(EngineConfig.XS_MOMENTUM_TOP_FRACTION * ranked.size).toInt()
                for (r in ranked.take(k)) flags.getValue(r.second)[r.third] = true
            }
            out[key("xsmom${w}w_${tf.label}", "xs_momentum", "weeks" to w.toDouble())] = flags
        }
        return out
    }

    // --- everything -----------------------------------------------------------------

    fun perCoin(c: Candles): Map<SignalKey, BooleanArray> {
        val out = LinkedHashMap<SignalKey, BooleanArray>()
        out.putAll(trendState(c))
        out.putAll(donchian(c))
        out.putAll(tsMomentum(c))
        if (c.tf == Timeframe.H1) {
            out.putAll(bigMoveFade(c))
            out.putAll(intradayMomentum(c))
        }
        return out
    }

    /** Every signal on one timeframe: {variant: {symbol: flags}}. All candles must share [tf]. */
    fun compute(panel: Map<String, Candles>): Map<SignalKey, Map<String, BooleanArray>> {
        val out = LinkedHashMap<SignalKey, MutableMap<String, BooleanArray>>()
        for ((sym, c) in panel) {
            for ((k, f) in perCoin(c)) out.getOrPut(k) { LinkedHashMap() }[sym] = f
        }
        val result = LinkedHashMap<SignalKey, Map<String, BooleanArray>>(out)
        result.putAll(xsMomentum(panel))
        return result
    }
}
