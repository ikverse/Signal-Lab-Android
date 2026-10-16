package com.ikverse.signallab.engine.strategy

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe

/**
 * How sure Signal Lab is that a strategy works, from its evidence (docs/strategy-research.md §3).
 * Only [PROVEN] and [PROMISING] strategies put calls on Today; the others run quietly into the ledger.
 */
enum class Tier(val label: String) {
    PROVEN("Proven"), PROMISING("Promising"), WATCH("Watch"), TESTING("Testing"), FAILING("Failing");

    val makesCalls: Boolean get() = this == PROVEN || this == PROMISING
}

/**
 * How a strategy's trades end. The same plan is applied to every random entry a trade is compared with, so the
 * comparison measures the entry alone.
 *
 * A trade enters at the open of the candle after its signal. It ends at the first of: a candle whose low reaches the
 * safety stop (filled at the stop, or at the open when the candle gaps through it); the strategy's own exit signal at a
 * candle's close (filled at the next candle's open, the first price anyone could act on); or the close of its
 * [maxBars]th candle.
 */
class ExitPlan(
    /** The most candles a trade lasts, counting the entry candle. */
    val maxBars: Int,
    /** A stop this many average true ranges below the entry; null for none. */
    val safetyStopAtr: Double? = null,
) {
    init {
        require(maxBars >= 1) { "a trade lasts at least one candle" }
        require(safetyStopAtr == null || safetyStopAtr > 0) { "a safety stop sits below the entry" }
    }
}

/**
 * One rule that turns candles into calls. A strategy is data plus two pure functions of one coin's closed candles:
 * where it enters and, optionally, where its own exit signal fires. Both must use only candles up to the one they flag,
 * so cutting the history at any candle gives the same flags up to it; a test holds every registered strategy to that.
 */
interface Strategy {
    /** The identifier everything is stored under, such as `breakout50_1d`. Never reused for a different rule. */
    val id: String

    /** The name a person reads, such as "Breakout". */
    val name: String

    /** The chart the strategy reads and its trades are judged on. */
    val timeframe: Timeframe

    /** Where the strategy stands before any ledger exists, from the research alone. */
    val launchTier: Tier

    /** True when it may only enter while Bitcoin is above its 200-day average. */
    val bullOnly: Boolean

    val exit: ExitPlan

    /** True at candle i when the strategy enters at the open of candle i+1. */
    fun entries(c: Candles): BooleanArray

    /** True at candle k when an open trade leaves at the open of candle k+1; null when the plan alone decides. */
    fun exitSignals(c: Candles): BooleanArray? = null
}

/** True on the first candle of each run of true values, so a condition that holds for days enters once. */
internal fun firstOfRun(state: BooleanArray): BooleanArray =
    BooleanArray(state.size) { i -> state[i] && (i == 0 || !state[i - 1]) }
