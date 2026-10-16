package com.ikverse.signallab.engine.strategy

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.PaperTrading
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.eval.Live
import com.ikverse.signallab.engine.eval.NewCall

/**
 * The analyst's own judgment as a strategy, so it gets a ledger and a verdict like any rule. It never fires by itself: a
 * call starts when the owner records one the analyst suggested, and enters at the next daily open like every other call.
 * Each call carries its own stop, target and time limit, set as distances from the entry when it was suggested; its random
 * entries use the same distances, measured in average true ranges, so the comparison still measures the entry alone.
 */
object AnalystCall : Strategy {
    override val id = "analyst"
    override val name = "Analyst"
    override val timeframe = Timeframe.D1
    override val launchTier = Tier.TESTING
    override val bullOnly = false

    /** What a call of the analyst may ask for: up to 60 days, a stop up to 30% down and a target up to 100% up. */
    const val MAX_DAYS = 60
    const val MAX_STOP = 0.30
    const val MAX_TARGET = 1.0

    /** The plan of the registry entry; every call has its own (see [forCall]). */
    override val exit = ExitPlan(maxBars = MAX_DAYS)

    override fun entries(c: Candles): BooleanArray = BooleanArray(c.size)

    /** The strategy as one stored call ran it: the same id, with that call's stop, target and time limit. */
    fun forCall(entry: Double, atr: Double, stop: Double?, target: Double?, maxBars: Int): Strategy {
        val plan = ExitPlan(
            maxBars = maxBars,
            safetyStopAtr = stop?.takeIf { atr > 0 && it < entry }?.let { (entry - it) / atr },
            targetAtr = target?.takeIf { atr > 0 && it > entry }?.let { (it - entry) / atr },
        )
        return object : Strategy by AnalystCall {
            override val exit = plan
        }
    }

    /**
     * The call for [symbol] entering at [formingOpen], the open of the candle after the newest closed one in [c], with a
     * stop [stopPct] below the entry and a target [targetPct] above it (fractions, 0.05 = 5%), held at most [days] days.
     * Null when the coin's average true range is not known yet.
     */
    fun open(c: Candles, btcDaily: Candles, formingOpen: Double, stopPct: Double?, targetPct: Double?, days: Int, cost: Double): NewCall? {
        require(c.tf == Timeframe.D1) { "the analyst's calls are daily" }
        require(days in 1..MAX_DAYS) { "a call lasts 1 to $MAX_DAYS days" }
        require(stopPct == null || stopPct > 0 && stopPct <= MAX_STOP) { "the stop is 0 to 30% below the entry" }
        require(targetPct == null || targetPct > 0 && targetPct <= MAX_TARGET) { "the target is 0 to 100% above the entry" }
        val s = c.size - 1
        if (s < 0 || !(formingOpen > 0)) return null
        val atr = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)[s]
        if (atr.isNaN()) return null
        val regime = PaperTrading.regimeSeries(btcDaily, longArrayOf(c.closeTime[s]))[0]
        return NewCall(
            strategyId = id, symbol = c.symbol, signalTime = c.t[s], entryTime = c.t[s] + c.tf.ms, entryPrice = formingOpen,
            atr = atr, stop = stopPct?.let { formingOpen * (1 - it) }, maxBars = days, regime = regime,
            sizeFraction = Live.sizeFor(formingOpen, atr), cost = cost, target = targetPct?.let { formingOpen * (1 + it) },
        )
    }
}
