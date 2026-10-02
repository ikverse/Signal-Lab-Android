package com.ikverse.signallab.engine

/**
 * The rules for running the signals live, as pure functions of candles and a clock, so a replay of
 * old candles through them can be compared trade for trade with the backtest.
 *
 * A signal found when candle i closes becomes a paper trade that enters at the open of candle i+1,
 * which has only just begun. The trade may be opened while that candle is the current one, and not
 * after: a signal noticed later than that could not have been acted on, so it is reported as
 * missed and never traded. Whether it was noticed in time depends on the clock alone, never on how
 * the trade would have ended, so skipping them cannot flatter the scorecard.
 */
object LiveScan {
    /** How far back a scan looks for signals it never evaluated, in candles. Older ones are not reported. */
    const val MAX_LOOKBACK_BARS = 48

    /** A signal on a closed candle. [tradeable] is false when it was noticed after the entry candle ended. */
    class Found(
        val key: SignalKey,
        val symbol: String,
        /** 0 for a signal that belongs to the coin; a list's id for one that ranks within a list. */
        val listId: Long,
        val barIdx: Int,
        val barTime: Long,
        val tradeable: Boolean,
    )

    class Scan(
        val found: List<Found>,
        /** Every variant that ran, whether or not it fired. The count of these is the multiple-testing divisor. */
        val variants: List<SignalKey>,
    )

    /** The open time of the candle that closed most recently, by the clock [now]. */
    fun newestClosedOpen(tf: Timeframe, now: Long): Long = CandleClock.lastClose(tf, now) - tf.ms

    /**
     * Runs every signal on [panel] (closed candles of every watched coin, all of timeframe [tf]) and
     * returns those that fired on a candle that closed after [after] (a close time; null on a first
     * run, when only the newest candle counts). [lists] maps each active list to its coins, because
     * cross-sectional momentum ranks within a list.
     */
    fun scan(
        tf: Timeframe,
        panel: Map<String, Candles>,
        lists: Map<Long, Collection<String>>,
        after: Long?,
        now: Long,
    ): Scan {
        val newest = newestClosedOpen(tf, now)
        val found = ArrayList<Found>()
        val variants = LinkedHashMap<String, SignalKey>()

        fun collect(key: SignalKey, symbol: String, listId: Long, c: Candles, flags: BooleanArray) {
            variants.putIfAbsent(key.name, key)
            val from = if (after == null) lowerBound(c.t, newest) else maxOf(upperBound(c.closeTime, after), c.size - MAX_LOOKBACK_BARS)
            for (i in from until c.size) {
                if (c.t[i] > newest) break
                if (flags[i]) found.add(Found(key, symbol, listId, i, c.t[i], tradeable = c.t[i] == newest))
            }
        }

        for ((symbol, c) in panel) {
            for ((key, flags) in Signals.perCoin(c)) collect(key, symbol, 0, c, flags)
        }
        for ((listId, coins) in lists) {
            val members = panel.filterKeys { it in coins }
            for ((key, bySymbol) in Signals.xsMomentum(members)) {
                variants.putIfAbsent(key.name, key)
                for ((symbol, flags) in bySymbol) collect(key, symbol, listId, members.getValue(symbol), flags)
            }
        }
        return Scan(found, variants.values.toList())
    }

    /** A paper trade ready to be logged. Everything the exit will need is in it, so the trade can be judged later from the log alone. */
    class Plan(
        val variant: String,
        val family: String,
        val symbol: String,
        val tf: Timeframe,
        val listId: Long,
        val barTime: Long,
        val detectedAt: Long,
        val regime: Int,
        val entryTime: Long,
        val entryPrice: Double,
        val target: Double?,
        val stop: Double?,
        /** Candles the trade may last: the time limit, or the fixed holding period of a variant that has no target and stop. */
        val limit: Int,
        /** The close time of the last candle the trade may use. */
        val exitDue: Long,
    )

    /**
     * The trade a [found] signal becomes, entering at [entryOpen], the open of the candle after the
     * signal's. Null where the backtest would not trade it either: the coin has too little history
     * for an average true range yet.
     */
    fun plan(found: Found, c: Candles, atr: DoubleArray, regime: IntArray, entryOpen: Double): Plan? {
        val a = atr[found.barIdx]
        if (a.isNaN()) return null
        val hold = found.key.holdBars
        val limit = hold ?: EngineConfig.timeLimitBars(c.tf)
        val entryTime = c.t[found.barIdx] + c.tf.ms
        return Plan(
            variant = found.key.name, family = found.key.family, symbol = found.symbol, tf = c.tf, listId = found.listId,
            barTime = c.t[found.barIdx], detectedAt = c.closeTime[found.barIdx], regime = regime[found.barIdx],
            entryTime = entryTime, entryPrice = entryOpen,
            target = if (hold == null) PaperTrading.targetPrice(entryOpen, a) else null,
            stop = if (hold == null) PaperTrading.stopPrice(entryOpen, a) else null,
            limit = limit, exitDue = entryTime + limit * c.tf.ms - 1,
        )
    }

    /** A trade that is open, as the log remembers it. */
    class OpenTrade(
        val variant: String,
        val symbol: String,
        val tf: Timeframe,
        val entryTime: Long,
        val entryPrice: Double,
        val target: Double?,
        val stop: Double?,
        val limit: Int,
    )

    /** How a trade ended. [randomMean] and [excess] are NaN when no random entries could be drawn. */
    class Closed(
        val exitTime: Long,
        val exitPrice: Double,
        val reason: ExitReason,
        val barsHeld: Int,
        val gross: Double,
        val net: Double,
        val randomMean: Double,
        val excess: Double,
    )

    /**
     * The exit of [t], if the closed candles in [c] show one, with the random entries it is judged
     * against. Null while the trade is still open (or its entry candle has not closed yet). A closed
     * trade's row is never edited, so the baseline is drawn here, once, from the candles there are.
     */
    fun close(t: OpenTrade, c: Candles, atr: DoubleArray, regime: IntArray): Closed? {
        val e = lowerBound(c.t, t.entryTime)
        if (e >= c.size || c.t[e] != t.entryTime) return null
        val res = PaperTrading.resolve(c, e, t.target, t.stop, t.limit) ?: return null
        val s = e - 1
        val gross = res.exitPrice / t.entryPrice - 1
        val cost = EngineConfig.costFor(t.symbol)
        val net = gross - cost
        val mean = if (s < 0) Double.NaN else Scorecard.matchedRandom(
            t.variant, t.symbol, c, atr, regime, intArrayOf(s), c.tf, cost,
            hold = if (t.target == null) t.limit else null, horizons = IntArray(0),
        ).mean[0]
        return Closed(
            exitTime = c.closeTime[res.exitIdx], exitPrice = res.exitPrice, reason = res.reason,
            barsHeld = res.exitIdx - s, gross = gross, net = net,
            randomMean = mean, excess = if (mean.isNaN()) Double.NaN else net - mean,
        )
    }

    /**
     * True when BTC's daily candles run up to the one that closed most recently. The regime of a
     * trade is read from them, so a stale series would stamp it with yesterday's regime.
     */
    fun regimeIsCurrent(btcDaily: Candles?, now: Long): Boolean =
        btcDaily != null && btcDaily.size > 0 && btcDaily.t[btcDaily.size - 1] == newestClosedOpen(Timeframe.D1, now)
}
