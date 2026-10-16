package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.PaperTrading
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.Vetoes

/** A call the live runner opens: everything its exit will later need, so it can be judged from the ledger alone. */
class NewCall(
    val strategyId: String,
    val symbol: String,
    /** Open time of the candle the signal fired on. */
    val signalTime: Long,
    /** Open time of the candle it enters at, the one after the signal. */
    val entryTime: Long,
    val entryPrice: Double,
    /** Average true range at the signal candle, which the safety stop and the size are measured in. */
    val atr: Double,
    /** The safety stop price; null when the plan has none. */
    val stop: Double?,
    val maxBars: Int,
    val regime: Int,
    /** Share of the paper account the call holds: one average true range equals [Live.RISK_PER_ATR] of it, capped. */
    val sizeFraction: Double,
    val cost: Double,
)

/** How an open call ended, with what its random entries did when it closed. */
class ClosedCall(
    val exitIdx: Int,
    /** Close time of the exit candle, or the open time of the candle whose open filled a signal exit. */
    val exitTime: Long,
    val exitPrice: Double,
    val reason: Leave,
    val barsHeld: Int,
    val gross: Double,
    val net: Double,
    val randomMean: Double,
    val excess: Double,
    val maxUp: Double,
    val maxDown: Double,
)

/**
 * The live runner's rules, as pure functions of closed candles, so a replay of old candles day by day can be compared
 * call for call with the backtest.
 *
 * A strategy's signal on the newest closed candle becomes a call that enters at the open of the candle now forming,
 * which has only just begun. A signal on an older candle was not seen in time and is never traded.
 */
object Live {
    /** One average true range move is this share of the paper account. */
    const val RISK_PER_ATR = 0.01

    /** No call holds more than this share of the account. */
    const val MAX_SIZE = 0.20

    /** Paper account share for an entry at [entry] when the average true range is [atr]. */
    fun sizeFor(entry: Double, atr: Double): Double =
        if (!(atr > 0) || !(entry > 0)) 0.0 else minOf(RISK_PER_ATR / (atr / entry), MAX_SIZE)

    /**
     * The call [strategy] makes on [c] (one coin's closed candles) when its newest candle has just closed, entering at
     * [formingOpen], the first price of the candle after it. Null when there is no signal there, the strategy is
     * bull-only and Bitcoin is not above its average, its average true range is not known yet, a veto applies, or [busy]
     * (the coin already holds a call of this strategy).
     */
    fun signal(
        strategy: Strategy,
        c: Candles,
        btcDaily: Candles,
        formingOpen: Double,
        busy: Boolean,
        cost: Double,
        /** For a ranking strategy, whether it fired on this coin's newest candle (from [Strategy.entriesAcross]). */
        fired: Boolean? = null,
    ): NewCall? {
        val s = c.size - 1
        if (s < 0 || busy || !(formingOpen > 0)) return null
        if (!(fired ?: strategy.entries(c)[s])) return null
        val regime = PaperTrading.regimeSeries(btcDaily, longArrayOf(c.closeTime[s]))[0]
        if (strategy.bullOnly && regime != 1) return null
        val atrs = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)
        val atr = atrs[s]
        if (atr.isNaN() || Vetoes.blocked(c, s, atrs) != null) return null
        val stop = strategy.exit.safetyStopAtr?.let { formingOpen - it * atr }
        return NewCall(strategy.id, c.symbol, c.t[s], c.t[s] + c.tf.ms, formingOpen, atr, stop, strategy.exit.maxBars, regime,
            sizeFor(formingOpen, atr), cost)
    }

    /** [c] with the candle now forming added after it, known only by its first price: enough to fill an exit at that open. */
    private fun withForming(c: Candles, open: Double): Candles {
        val n = c.size
        fun plus(a: DoubleArray) = a.copyOf(n + 1).also { it[n] = open }
        val t = c.t.copyOf(n + 1).also { it[n] = c.t[n - 1] + c.tf.ms }
        val ct = c.closeTime.copyOf(n + 1).also { it[n] = t[n] + c.tf.ms - 1 }
        return Candles(c.symbol, c.tf, t, plus(c.open), plus(c.high), plus(c.low), plus(c.close), c.volume.copyOf(n + 1), ct)
    }

    /**
     * Whether [call] has ended on the closed candles in [c], and how; null while it is still open, or while the candle it
     * entered at has not closed yet. With [formingOpen], the first price of the candle now forming, an exit that fills at
     * that open (the strategy's own exit signal at the last close, or a gap through the stop) is reported at once rather
     * than when that candle closes. Its random entries are drawn when it closes, from the closed candles that exist then.
     */
    fun close(strategy: Strategy, call: NewCall, c: Candles, btcDaily: Candles, formingOpen: Double? = null): ClosedCall? {
        val e = c.t.indexOfFirst { it == call.entryTime }
        if (e < 1) return null
        val exits = strategy.exitSignals(c)
        var fill = Fills.resolve(c, e, strategy.exit, call.atr, exits)
        if (fill == null && formingOpen != null && formingOpen > 0) {
            val next = withForming(c, formingOpen)
            // Only what the open alone decides counts: a signal exit, or a gap through the stop. A time exit at the forming
            // candle's close is not known yet.
            fill = Fills.resolve(next, e, strategy.exit, call.atr, exits?.copyOf(next.size))
                ?.takeIf { it.exitIdx == c.size && it.reason != Leave.TIME && it.exitPrice == formingOpen }
                ?.let { Fill(it.exitIdx, it.exitPrice, it.reason) }
        }
        if (fill == null) return null
        // As the backtest records it ([Fills.exitTime]); a fill at the forming candle's open is timed at that open.
        val exitTime = if (fill.exitIdx >= c.size) c.t[c.size - 1] + c.tf.ms else Fills.exitTime(c, fill)
        val gross = fill.exitPrice / call.entryPrice - 1
        val net = gross - call.cost
        val atr = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)
        val regime = PaperTrading.regimeSeries(btcDaily, c.closeTime)
        val random = Baseline.meanNet(strategy.id, call.symbol, c, atr, regime, intArrayOf(e - 1), strategy.exit, exits, call.cost)[0]
        // How far it ran up and down: every candle it was held through, which stops before the candle whose open it left at.
        val lastHeld = minOf(if (fill.reason == Leave.SIGNAL || fill.exitIdx >= c.size) fill.exitIdx - 1 else fill.exitIdx, c.size - 1)
        var high = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY
        for (k in e..lastHeld) {
            high = maxOf(high, c.high[k])
            low = minOf(low, c.low[k])
        }
        if (lastHeld < e) {
            high = call.entryPrice
            low = minOf(call.entryPrice, fill.exitPrice)
        }
        return ClosedCall(
            exitIdx = fill.exitIdx,
            exitTime = exitTime,
            exitPrice = fill.exitPrice, reason = fill.reason, barsHeld = fill.exitIdx - (e - 1), gross = gross, net = net,
            randomMean = random, excess = if (random.isNaN()) Double.NaN else net - random,
            maxUp = high / call.entryPrice - 1, maxDown = low / call.entryPrice - 1,
        )
    }
}
