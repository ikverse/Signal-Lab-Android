package com.ikverse.signallab.engine

/**
 * How a research-era paper trade ends, for the golden backtest that reproduces the research.
 *
 * - [CLASSIC]: the research's rule, a target 2 candle sizes up and a stop 1 down, with a time limit.
 * - [TRAIL]: a safety stop, then a trailing stop that follows the highest close, and a time cap.
 * - [HELD]: held a fixed number of candles with no target or stop.
 *
 * Signal Lab 2's strategies end by their own [com.ikverse.signallab.engine.strategy.ExitPlan] instead.
 */
enum class ExitMode(val label: String) {
    CLASSIC("classic"), TRAIL("trail"), HELD("held");

    companion object {
        /** What a stored trade used. A label no longer known reads by whether it had a target: classic, else held. */
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
    /** Candles the trade may last. */
    val limit: Int,
) {
    /** The levels for an entry at [entry] when the candle size is [atr], over [limit] candles. */
    fun spec(entry: Double, atr: Double, limit: Int): ExitSpec = when (mode) {
        ExitMode.CLASSIC -> ExitSpec(mode, entry, atr, PaperTrading.targetPrice(entry, atr), PaperTrading.stopPrice(entry, atr), limit)
        ExitMode.HELD -> ExitSpec(mode, entry, atr, null, null, limit)
        ExitMode.TRAIL -> ExitSpec(mode, entry, atr, null, entry - EngineConfig.TRAIL_STOP_ATR * atr, limit)
    }

    companion object {
        fun classic(tf: Timeframe) = ExitRule(ExitMode.CLASSIC, EngineConfig.timeLimitBars(tf))

        fun held(bars: Int) = ExitRule(ExitMode.HELD, bars)
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
