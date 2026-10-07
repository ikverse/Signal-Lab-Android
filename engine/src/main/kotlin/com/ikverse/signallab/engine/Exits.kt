package com.ikverse.signallab.engine

import kotlin.math.ceil

/**
 * How a paper trade ends.
 *
 * - [CLASSIC]: the research's rule, a target 2 candle sizes up and a stop 1 down, with a time limit. Kept for the
 *   backtest that matches the research and for trades opened before exits were chosen per pattern.
 * - [TRAIL]: trend patterns. A safety stop, then a trailing stop that follows the highest close, and a time cap.
 * - [LEARNED]: fast patterns. A target and a time limit learned from the same pattern's earlier, finished signals.
 * - [HELD]: held a fixed number of candles with no target or stop.
 */
enum class ExitMode(val label: String) {
    CLASSIC("classic"), TRAIL("trail"), LEARNED("learned"), HELD("held");

    companion object {
        /** What a stored trade used. Trades from before exits were chosen have no label: a target means classic, none means held. */
        fun fromStored(label: String?, hasTarget: Boolean): ExitMode =
            entries.firstOrNull { it.label == label } ?: if (hasTarget) CLASSIC else HELD
    }
}

/**
 * The exit for one trade, in candle sizes (ATR) rather than prices, so the same rule can be applied to the trade and to
 * each random entry it is compared with.
 */
class ExitRule(
    val mode: ExitMode,
    /** Candles the trade may last (for [endOfDay] rules, the most it may last). */
    val limit: Int,
    /** The trade is closed at the end of the UTC day it entered on. */
    val endOfDay: Boolean = false,
    /** [ExitMode.LEARNED]: the target as a fraction above the entry; null means no target and no stop (a plain hold). */
    val targetPct: Double? = null,
) {
    /** Candles a trade entering at [entryTime] may last. */
    fun limitAt(entryTime: Long, tf: Timeframe): Int {
        if (!endOfDay) return limit
        val slot = ((entryTime % DAY_MS) / tf.ms).toInt()
        return minOf(limit, tf.barsPerDay - slot)
    }

    /** Candles the trade entering at candle [entryIdx] may last. */
    fun limitFor(c: Candles, entryIdx: Int): Int = limitAt(c.t[entryIdx], c.tf)

    /** Whether a signal on the candle opening at [signalTime] may be traded: an end-of-day rule needs the entry candle on the same UTC day. */
    fun allowsSignalAt(signalTime: Long, tf: Timeframe): Boolean = !endOfDay || (signalTime + tf.ms) / DAY_MS == signalTime / DAY_MS

    fun allows(c: Candles, signalIdx: Int): Boolean = allowsSignalAt(c.t[signalIdx], c.tf)

    /** The levels for an entry at [entry] when the candle size is [atr], over [limit] candles. */
    fun spec(entry: Double, atr: Double, limit: Int): ExitSpec = when (mode) {
        ExitMode.CLASSIC -> ExitSpec(mode, entry, atr, PaperTrading.targetPrice(entry, atr), PaperTrading.stopPrice(entry, atr), limit)
        ExitMode.HELD -> ExitSpec(mode, entry, atr, null, null, limit)
        ExitMode.TRAIL -> ExitSpec(mode, entry, atr, null, entry - EngineConfig.TRAIL_STOP_ATR * atr, limit)
        ExitMode.LEARNED ->
            if (targetPct != null) ExitSpec(mode, entry, atr, entry * (1 + targetPct), entry - EngineConfig.LEARNED_STOP_ATR * atr, limit)
            else ExitSpec(mode, entry, atr, null, null, limit)
    }

    companion object {
        fun classic(tf: Timeframe) = ExitRule(ExitMode.CLASSIC, EngineConfig.timeLimitBars(tf))

        fun held(bars: Int) = ExitRule(ExitMode.HELD, bars)

        /** What a stored trade used, so its random entries can be given the same exit. */
        fun of(mode: ExitMode, limit: Int, entry: Double, target: Double?, variant: String, tf: Timeframe): ExitRule {
            val endOfDay = ExitPolicy.endsTheDay(variant)
            return ExitRule(
                mode, if (endOfDay) EngineConfig.trailCapBars(tf) else limit, endOfDay = endOfDay,
                targetPct = if (mode == ExitMode.LEARNED && target != null) target / entry - 1 else null,
            )
        }
    }
}

/** An exit as it applies to one open trade. */
class ExitSpec(
    val mode: ExitMode,
    val entry: Double,
    /** The candle size at the signal, which a trailing stop is measured in. */
    val atr: Double,
    val target: Double?,
    /** The safety stop; for a trailing trade, where the trailing stop starts. */
    val stop: Double?,
    val limit: Int,
)

/** Which exit each family of patterns gets. */
object ExitPolicy {
    private val learnedFamilies = setOf("candlestick", "big_move_fade")

    fun modeOf(key: SignalKey): ExitMode = when {
        key.holdBars != null -> ExitMode.HELD
        key.family in learnedFamilies -> ExitMode.LEARNED
        key.family == LabPatterns.FAMILY && key.param(LabPatterns.LEARNED) == 1.0 -> ExitMode.LEARNED
        else -> ExitMode.TRAIL
    }

    /** True for the pattern whose trades close at the end of the UTC day. */
    fun endsTheDay(variant: String): Boolean = variant.startsWith("intraday_breakout_")

    /** The rule for a pattern that does not learn its own: trailing, or held. A learned pattern starts from a plain hold. */
    fun baseRule(key: SignalKey, tf: Timeframe): ExitRule = when (val mode = modeOf(key)) {
        ExitMode.HELD -> ExitRule.held(key.holdBars!!)
        ExitMode.TRAIL -> ExitRule(mode, EngineConfig.trailCapBars(tf), endOfDay = endsTheDay(key.name))
        else -> ExitRule(ExitMode.LEARNED, EngineConfig.LEARN_FALLBACK_HOLD_BARS)
    }
}

/**
 * What the same pattern did after its earlier signals, turned into an exit for the next one. Only signals
 * whose whole look-ahead window had already closed count, so a signal never learns from candles it could not
 * have seen, and only recent ones do, so a trade in the live scan and its backtest learn from the same signals.
 */
object LearnedExit {
    /** What followed one signal: its best rise and how many candles it took. */
    class Outcome(val time: Long, val rise: Double, val bars: Int)

    /** The outcome of every flagged candle whose look-ahead window is complete, oldest first. */
    fun outcomes(c: Candles, flags: BooleanArray): List<Outcome> {
        val window = EngineConfig.learnWindowBars(c.tf)
        val out = ArrayList<Outcome>()
        for (s in flags.indices) {
            if (!flags[s] || s + window >= c.size) continue
            val e = s + 1
            var best = Double.NEGATIVE_INFINITY
            var at = 0
            for (k in 0 until window) {
                if (c.high[e + k] > best) {
                    best = c.high[e + k]
                    at = k + 1
                }
            }
            out.add(Outcome(c.t[s], best / c.open[e] - 1, at))
        }
        return out
    }

    private fun median(sorted: DoubleArray): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /**
     * The exit for a signal at time [signalTime] on [tf]: from [own] coin's finished recent signals if there
     * are enough, else the whole list's ([all], every coin's outcomes), else a plain hold.
     */
    fun rule(tf: Timeframe, signalTime: Long, own: List<Outcome>, all: Collection<List<Outcome>>): ExitRule {
        val window = EngineConfig.learnWindowBars(tf)
        val oldest = signalTime - EngineConfig.learnLookbackDays(tf) * DAY_MS
        fun usable(list: List<Outcome>) = list.filter { it.time >= oldest && it.time + window * tf.ms <= signalTime }
        var picked = usable(own)
        if (picked.size < EngineConfig.LEARN_MIN_SIGNALS) picked = all.flatMap { usable(it) }
        if (picked.size < EngineConfig.LEARN_MIN_SIGNALS) return ExitRule(ExitMode.LEARNED, EngineConfig.LEARN_FALLBACK_HOLD_BARS)
        val rise = median(picked.map { it.rise }.sorted().toDoubleArray())
        val bars = ceil(median(picked.map { it.bars.toDouble() }.sorted().toDoubleArray())).toInt().coerceIn(1, window)
        if (!(rise > 0)) return ExitRule(ExitMode.LEARNED, EngineConfig.LEARN_FALLBACK_HOLD_BARS)
        return ExitRule(ExitMode.LEARNED, bars, targetPct = rise)
    }
}
