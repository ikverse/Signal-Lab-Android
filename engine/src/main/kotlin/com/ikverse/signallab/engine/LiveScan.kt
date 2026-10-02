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
        private val flags: Map<SignalKey, Map<String, BooleanArray>>,
    ) {
        private val outcomes = HashMap<String, Map<String, List<LearnedExit.Outcome>>>()

        /**
         * The exit a [found] signal gets: trailing or held as its pattern says, or for a fast pattern the target and time
         * limit learned from that pattern's earlier finished signals on this coin, or on the whole list if it has too few.
         */
        fun ruleFor(f: Found, panel: Map<String, Candles>): ExitRule {
            val tf = panel.getValue(f.symbol).tf
            if (ExitPolicy.modeOf(f.key) != ExitMode.LEARNED) return ExitPolicy.baseRule(f.key, tf)
            val byCoin = outcomes.getOrPut(f.key.name) {
                flags.getValue(f.key).mapValues { (symbol, flagged) -> LearnedExit.outcomes(panel.getValue(symbol), flagged) }
            }
            return LearnedExit.rule(tf, f.barTime, byCoin[f.symbol].orEmpty(), byCoin.values)
        }
    }

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
        val flags = LinkedHashMap<SignalKey, MutableMap<String, BooleanArray>>()

        fun collect(key: SignalKey, symbol: String, listId: Long, c: Candles, flagged: BooleanArray) {
            variants.putIfAbsent(key.name, key)
            val from = if (after == null) lowerBound(c.t, newest) else maxOf(upperBound(c.closeTime, after), c.size - MAX_LOOKBACK_BARS)
            for (i in from until c.size) {
                if (c.t[i] > newest) break
                if (flagged[i]) found.add(Found(key, symbol, listId, i, c.t[i], tradeable = c.t[i] == newest))
            }
        }

        for ((symbol, c) in panel) {
            for ((key, flagged) in Signals.perCoin(c)) {
                flags.getOrPut(key) { LinkedHashMap() }[symbol] = flagged
                collect(key, symbol, 0, c, flagged)
            }
        }
        for ((listId, coins) in lists) {
            val members = panel.filterKeys { it in coins }
            for ((key, bySymbol) in Signals.xsMomentum(members)) {
                variants.putIfAbsent(key.name, key)
                for ((symbol, flagged) in bySymbol) collect(key, symbol, listId, members.getValue(symbol), flagged)
            }
        }
        return Scan(found, variants.values.toList(), flags)
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
        val mode: ExitMode,
        val target: Double?,
        val stop: Double?,
        /** The candle size at the signal, which a trailing stop is measured in. */
        val atr: Double,
        /** Candles the trade may last: its time cap or limit, or the fixed holding period of a variant that has no target and stop. */
        val limit: Int,
        /** The close time of the last candle the trade may use. */
        val exitDue: Long,
        /** What the trade costs, round trip, as a fraction; kept with the trade so changing the setting never rewrites it. */
        val cost: Double,
    )

    /**
     * The trade a [found] signal becomes under [rule], entering at [entryOpen], the open of the candle after the
     * signal's. Null where the backtest would not trade it either: the coin has too little history for a candle size
     * yet, or an end-of-day pattern fired on the last candle of its day.
     */
    fun plan(found: Found, c: Candles, atr: DoubleArray, regime: IntArray, entryOpen: Double, cost: Double, rule: ExitRule): Plan? {
        val a = atr[found.barIdx]
        if (a.isNaN() || !rule.allowsSignalAt(c.t[found.barIdx], c.tf)) return null
        val entryTime = c.t[found.barIdx] + c.tf.ms
        val limit = rule.limitAt(entryTime, c.tf)
        val spec = rule.spec(entryOpen, a, limit)
        return Plan(
            variant = found.key.name, family = found.key.family, symbol = found.symbol, tf = c.tf, listId = found.listId,
            barTime = c.t[found.barIdx], detectedAt = c.closeTime[found.barIdx], regime = regime[found.barIdx],
            entryTime = entryTime, entryPrice = entryOpen, mode = rule.mode, target = spec.target, stop = spec.stop,
            atr = a, limit = limit, exitDue = entryTime + limit * c.tf.ms - 1, cost = cost,
        )
    }

    /** A trade that is open, as the log remembers it. */
    class OpenTrade(
        val variant: String,
        val symbol: String,
        val tf: Timeframe,
        val entryTime: Long,
        val entryPrice: Double,
        val mode: ExitMode,
        val target: Double?,
        val stop: Double?,
        /** NaN for a trade opened before the candle size was kept; only a trailing trade needs it. */
        val atr: Double,
        val limit: Int,
        val cost: Double,
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
        /** The best the trade ever showed, the worst dip it sat through, and how many candles its high took. */
        val maxUp: Double,
        val maxDown: Double,
        val barsToPeak: Int,
    )

    /**
     * The exit of [t], if the closed candles in [c] show one, with the random entries it is judged
     * against. Null while the trade is still open (or its entry candle has not closed yet). A closed
     * trade's row is never edited, so the baseline is drawn here, once, from the candles there are.
     */
    fun close(t: OpenTrade, c: Candles, atr: DoubleArray, regime: IntArray): Closed? {
        val e = lowerBound(c.t, t.entryTime)
        if (e >= c.size || c.t[e] != t.entryTime) return null
        val res = PaperTrading.resolve(c, e, ExitSpec(t.mode, t.entryPrice, t.atr, t.target, t.stop, t.limit)) ?: return null
        val s = e - 1
        val gross = res.exitPrice / t.entryPrice - 1
        val net = gross - t.cost
        val mean = if (s < 0) Double.NaN else Scorecard.matchedRandom(
            t.variant, t.symbol, c, atr, regime, intArrayOf(s), c.tf, t.cost,
            ExitRule.of(t.mode, t.limit, t.entryPrice, t.target, t.variant, c.tf), horizons = IntArray(0),
        ).mean[0]
        val run = PaperTrading.excursion(c, e, res.exitIdx, t.entryPrice)
        return Closed(
            exitTime = c.closeTime[res.exitIdx], exitPrice = res.exitPrice, reason = res.reason,
            barsHeld = res.exitIdx - s, gross = gross, net = net,
            randomMean = mean, excess = if (mean.isNaN()) Double.NaN else net - mean,
            maxUp = run.maxUp, maxDown = run.maxDown, barsToPeak = run.barsToPeak,
        )
    }

    /**
     * True when BTC's daily candles run up to the one that closed most recently. The regime of a
     * trade is read from them, so a stale series would stamp it with yesterday's regime.
     */
    fun regimeIsCurrent(btcDaily: Candles?, now: Long): Boolean =
        btcDaily != null && btcDaily.size > 0 && btcDaily.t[btcDaily.size - 1] == newestClosedOpen(Timeframe.D1, now)
}
