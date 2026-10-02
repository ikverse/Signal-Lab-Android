package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The exits chosen per pattern: trailing, learned, end-of-day and held, and the promises they make. */
class ExitsTest {
    private val atr = 2.0

    /** Daily candles, one row each: open, high, low, close. */
    private fun daily(vararg rows: DoubleArray): Candles {
        val n = rows.size
        val t = LongArray(n) { Synth.START_MS + it * Timeframe.D1.ms }
        return Candles("TESTUSDT", Timeframe.D1, t, DoubleArray(n) { rows[it][0] }, DoubleArray(n) { rows[it][1] },
            DoubleArray(n) { rows[it][2] }, DoubleArray(n) { rows[it][3] }, DoubleArray(n) { 1.0 }, LongArray(n) { t[it] + Timeframe.D1.ms - 1 })
    }

    private fun row(open: Double, high: Double, low: Double, close: Double) = doubleArrayOf(open, high, low, close)

    private fun trail(entry: Double = 100.0, limit: Int = 30) = ExitRule(ExitMode.TRAIL, limit).spec(entry, atr, limit)

    // --- trailing ----------------------------------------------------------------------------------------

    @Test
    fun theSafetyStopStartsTwoCandleSizesBelowTheEntry() {
        assertEquals(96.0, trail().stop)
        assertNull(trail().target)
    }

    @Test
    fun theStopFollowsTheHighestCloseOnceThePriceHasRisenOneCandleSize() {
        // entry candle 0 at 100. Candle 1 closes at 103 (>= 102): the stop moves to 103 - 4 = 99. Candle 2 closes at 108: stop 104.
        // Candle 3 trades down to 100, through the stop at 104, which fills at 104.
        val c = daily(row(100.0, 101.0, 99.0, 100.0), row(100.0, 103.5, 99.5, 103.0), row(103.0, 109.0, 103.0, 108.0), row(106.0, 107.0, 100.0, 105.0))
        val r = assertNotNull(PaperTrading.resolve(c, 0, trail()))
        assertEquals(3, r.exitIdx)
        assertEquals(104.0, r.exitPrice)
        assertEquals(ExitReason.STOP, r.reason)
    }

    @Test
    fun theStopDoesNotMoveBeforeThePriceHasRisenEnough() {
        // The highest close, 101.5, is under the 102 needed to start trailing, so the stop stays at 96.
        val c = daily(row(100.0, 101.0, 99.0, 101.5), row(101.5, 102.0, 97.0, 98.0), row(98.0, 99.0, 97.0, 97.5), row(97.5, 98.0, 95.9, 96.5))
        val r = assertNotNull(PaperTrading.resolve(c, 0, trail()))
        assertEquals(3, r.exitIdx)
        assertEquals(96.0, r.exitPrice)
    }

    @Test
    fun theStopNeverMovesDownWhenThePriceFalls() {
        // Stop reaches 104 after candle 2's close of 108. Candle 3 only dips to 105 and closes at 101: the stop must stay 104, not fall to 97.
        // Candle 4 touches 103, so it leaves at 104.
        val c = daily(
            row(100.0, 101.0, 99.0, 100.0), row(100.0, 103.5, 99.5, 103.0), row(103.0, 109.0, 103.0, 108.0),
            row(107.0, 107.5, 105.0, 101.0), row(104.5, 105.0, 103.0, 104.5),
        )
        val r = assertNotNull(PaperTrading.resolve(c, 0, trail()))
        assertEquals(4, r.exitIdx)
        assertEquals(104.0, r.exitPrice)
    }

    @Test
    fun aGapThroughTheTrailingStopFillsAtTheOpen() {
        val c = daily(row(100.0, 101.0, 99.0, 100.0), row(100.0, 103.5, 99.5, 103.0), row(90.0, 91.0, 89.0, 90.5))
        val r = assertNotNull(PaperTrading.resolve(c, 0, trail()))
        assertEquals(2, r.exitIdx)
        assertEquals(90.0, r.exitPrice)
    }

    @Test
    fun theTimeCapEndsATradeThatNeverTouchesItsStop() {
        val rows = Array(10) { row(100.0, 101.0, 99.0, 100.0) }
        val c = daily(*rows)
        val r = assertNotNull(PaperTrading.resolve(c, 2, trail(limit = 4)))
        assertEquals(ExitReason.TIME, r.reason)
        assertEquals(5, r.exitIdx)
        assertNull(PaperTrading.resolve(c.head(5), 2, trail(limit = 4)), "still open one candle early")
    }

    @Test
    fun aTrailingTradeIsOnlyJudgedOnClosedCandlesSoItsExitDoesNotWaitForTheWholeCap() {
        val c = daily(row(100.0, 101.0, 99.0, 100.0), row(100.0, 100.5, 95.0, 96.0), row(96.0, 97.0, 95.0, 96.0))
        assertEquals(1, PaperTrading.resolve(c.head(2), 0, trail())!!.exitIdx)
    }

    // --- the same answer from the backtest and the live scan ------------------------------------------------

    @Test
    fun simulateAndResolveAgreeForEveryModeOnRealCandles() {
        var compared = 0
        for (tf in listOf(Timeframe.D1, Timeframe.H1)) {
            val panel = Golden.panel(tf)
            for ((key, bySymbol) in Signals.compute(panel)) {
                val rule = if (ExitPolicy.modeOf(key) == ExitMode.LEARNED) ExitRule(ExitMode.LEARNED, 6, targetPct = 0.02) else ExitPolicy.baseRule(key, tf)
                for ((symbol, flags) in bySymbol) {
                    val c = panel.getValue(symbol)
                    val idx = flags.indices.filter { flags[it] }.toIntArray()
                    if (idx.isEmpty()) continue
                    val a = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)
                    val ex = PaperTrading.simulate(c, idx, tf, a, rule)
                    for (j in idx.indices) {
                        if (!ex.valid[j]) continue
                        val s = idx[j]
                        val limit = rule.limitAt(c.t[s] + tf.ms, tf)
                        val r = assertNotNull(PaperTrading.resolve(c, s + 1, rule.spec(c.open[s + 1], a[s], limit)), "${key.name} $symbol $s")
                        assertEquals(ex.exitIdx[j], r.exitIdx, "${key.name} $symbol $s exit candle")
                        assertEquals(ex.exitPrice[j], r.exitPrice, "${key.name} $symbol $s exit price")
                        compared++
                    }
                }
            }
        }
        assertTrue(compared > 3_000, "only $compared trades compared")
    }

    @Test
    fun everyPatternGetsTheExitItsKindCallsFor() {
        fun key(name: String, family: String, vararg p: Pair<String, Double>) = SignalKey(name, family, mapOf(*p))
        for (family in listOf("trend_state", "donchian", "ts_momentum", "xs_momentum", "intraday_breakout")) {
            assertEquals(ExitMode.TRAIL, ExitPolicy.modeOf(key("x", family)), family)
        }
        assertEquals(ExitMode.LEARNED, ExitPolicy.modeOf(key("bullish_harami_1h", "candlestick")))
        assertEquals(ExitMode.LEARNED, ExitPolicy.modeOf(key("fade2h_1h", "big_move_fade", "hours" to 2.0)))
        assertEquals(ExitMode.HELD, ExitPolicy.modeOf(key("fade1h_hold24_1h", "big_move_fade", "hold_bars" to 24.0)))
        assertEquals(ExitMode.HELD, ExitPolicy.modeOf(key("intraday_mom_30m", "intraday_momentum", "hold_bars" to 1.0)))
        // and the fixed-time patterns keep the fixed time they were defined with
        assertEquals(24, ExitPolicy.baseRule(key("fade1h_hold24_1h", "big_move_fade", "hold_bars" to 24.0), Timeframe.H1).limit)
    }

    @Test
    fun theTimeCapsAreWhatWasPromised() {
        val hours = mapOf(
            Timeframe.M1 to 2, Timeframe.M5 to 6, Timeframe.M15 to 24, Timeframe.M30 to 24, Timeframe.H1 to 72, Timeframe.H4 to 360, Timeframe.D1 to 720,
        )
        for ((tf, h) in hours) assertEquals(h * 60L * 60_000, EngineConfig.trailCapBars(tf) * tf.ms, tf.label)
    }

    // --- end of the day ------------------------------------------------------------------------------------------

    @Test
    fun anEndOfDayTradeMayOnlyLastUntilTheLastCandleOfItsDay() {
        val rule = ExitRule(ExitMode.TRAIL, EngineConfig.trailCapBars(Timeframe.H1), endOfDay = true)
        val h = Timeframe.H1.ms
        val day = Synth.START_MS
        assertEquals(24, rule.limitAt(day, Timeframe.H1))
        assertEquals(3, rule.limitAt(day + 21 * h, Timeframe.H1))
        assertEquals(1, rule.limitAt(day + 23 * h, Timeframe.H1))
        assertTrue(rule.allowsSignalAt(day + 21 * h, Timeframe.H1))
        assertFalse(rule.allowsSignalAt(day + 23 * h, Timeframe.H1), "a signal on the last candle has no candle left in its day to enter on")
        assertTrue(ExitRule(ExitMode.TRAIL, 72).allowsSignalAt(day + 23 * h, Timeframe.H1))
    }

    @Test
    fun anEndOfDayTradeLeavesAtTheLastCandlesCloseAtTheLatest() {
        val perDay = Timeframe.H1.barsPerDay
        val t = LongArray(3 * perDay) { Synth.START_MS + it * Timeframe.H1.ms }
        val n = t.size
        val c = Candles("DAYUSDT", Timeframe.H1, t, DoubleArray(n) { 100.0 }, DoubleArray(n) { 100.5 }, DoubleArray(n) { 99.5 }, DoubleArray(n) { 100.0 + it % 3 * 0.01 }, DoubleArray(n) { 1.0 }, LongArray(n) { t[it] + Timeframe.H1.ms - 1 })
        val rule = ExitRule(ExitMode.TRAIL, EngineConfig.trailCapBars(Timeframe.H1), endOfDay = true)
        val a = DoubleArray(n) { 1.0 }
        val ex = PaperTrading.simulate(c, intArrayOf(perDay + 9, perDay + 23), Timeframe.H1, a, rule)
        assertTrue(ex.valid[0])
        assertEquals(2 * perDay - 1, ex.exitIdx[0], "enters at 10:00 and leaves at 23:59")
        assertEquals(ExitReason.TIME, ex.reason[0])
        assertFalse(ex.valid[1], "the last candle of the day cannot be a signal")
    }

    // --- excursions ----------------------------------------------------------------------------------------------

    @Test
    fun aTradeRemembersItsBestRiseItsWorstDipAndHowLongTheHighTook() {
        val c = daily(row(100.0, 101.0, 98.0, 100.0), row(100.0, 106.0, 99.0, 104.0), row(104.0, 105.0, 95.0, 96.0), row(96.0, 97.0, 94.0, 95.0))
        val x = PaperTrading.excursion(c, 0, 2, 100.0)
        assertEquals(0.06, x.maxUp, 1e-12)
        assertEquals(-0.05, x.maxDown, 1e-12)
        assertEquals(2, x.barsToPeak)
    }

    // --- learned exits -------------------------------------------------------------------------------------------

    private val day = Timeframe.D1.ms

    private fun outcomes(n: Int, startDay: Int, rise: Double, bars: Int) = List(n) { LearnedExit.Outcome(Synth.START_MS + (startDay + it) * day, rise, bars) }

    @Test
    fun theTargetAndTimeLimitAreTheMiddleOfWhatFollowedEarlierFinishedSignals() {
        val own = (0 until 25).map { LearnedExit.Outcome(Synth.START_MS + it * day, 0.01 * (it + 1), 1 + it % 5) }
        val rule = LearnedExit.rule(Timeframe.D1, Synth.START_MS + 100 * day, own, emptyList())
        assertEquals(ExitMode.LEARNED, rule.mode)
        assertEquals(0.13, rule.targetPct!!, 1e-12) // the median of 0.01..0.25
        assertEquals(3, rule.limit) // the median of 1,2,3,4,5 repeated is 3
    }

    @Test
    fun aSignalNeverLearnsFromOneWhoseWindowHadNotClosedYet() {
        val window = EngineConfig.learnWindowBars(Timeframe.D1)
        val now = Synth.START_MS + 100 * day
        // 20 finished signals at +1%, and 10 more whose look-ahead window ends after "now", at +50%.
        val finished = outcomes(20, 60, 0.01, 2)
        val unfinished = (0 until 10).map { LearnedExit.Outcome(now - (window - 1 - it % 3) * day, 0.50, 2) }
        val rule = LearnedExit.rule(Timeframe.D1, now, finished + unfinished, emptyList())
        assertEquals(0.01, rule.targetPct!!, 1e-12)
        val onlyUnfinished = LearnedExit.rule(Timeframe.D1, now, unfinished, emptyList())
        assertNull(onlyUnfinished.targetPct, "nothing finished means nothing learned")
    }

    @Test
    fun oldSignalsAreNotLearnedFrom() {
        val now = Synth.START_MS + 1000 * day
        val old = outcomes(30, 100, 0.40, 4) // far outside the lookback
        assertNull(LearnedExit.rule(Timeframe.D1, now, old, emptyList()).targetPct)
    }

    @Test
    fun withTooFewOfItsOwnACoinLearnsFromTheWholeListAndThenFromNothing() {
        val now = Synth.START_MS + 200 * day
        val few = outcomes(5, 100, 0.99, 6)
        val list = listOf(outcomes(12, 100, 0.03, 2), outcomes(12, 110, 0.03, 2), few)
        val rule = LearnedExit.rule(Timeframe.D1, now, few, list)
        assertEquals(0.03, rule.targetPct!!, 1e-12)
        assertEquals(2, rule.limit)
        val nothing = LearnedExit.rule(Timeframe.D1, now, few, listOf(few))
        assertNull(nothing.targetPct)
        assertEquals(EngineConfig.LEARN_FALLBACK_HOLD_BARS, nothing.limit)
        assertEquals(ExitMode.LEARNED, nothing.mode)
        assertNull(nothing.spec(100.0, 2.0, nothing.limit).stop, "a plain hold has no stop")
    }

    @Test
    fun aLearnedTradeHasATargetAndAStopThreeCandleSizesDown() {
        val rule = ExitRule(ExitMode.LEARNED, 4, targetPct = 0.02)
        val spec = rule.spec(100.0, 2.0, 4)
        assertEquals(102.0, spec.target!!, 1e-12)
        assertEquals(94.0, spec.stop!!, 1e-12)
    }

    @Test
    fun whatIsLearnedAtASignalDoesNotChangeWhenLaterCandlesArrive() {
        val tf = Timeframe.D1
        val c = Golden.panel(tf).getValue("BTCUSDT")
        val flags = Signals.candlesticks(c).entries.first { it.key.name == "bullish_hikkake_1d" }.value
        val cut = c.size - 150
        val at = c.t[cut - 1]
        val early = LearnedExit.rule(tf, at, LearnedExit.outcomes(c.head(cut), flags.copyOf(cut)), emptyList())
        val late = LearnedExit.rule(tf, at, LearnedExit.outcomes(c, flags), emptyList())
        assertNotNull(late.targetPct, "this coin has enough signals to learn from")
        assertEquals(late.targetPct, early.targetPct)
        assertEquals(late.limit, early.limit)
    }

    @Test
    fun theBacktestOfALearnedPatternGivesEachSignalItsOwnLearnedExit() {
        val tf = Timeframe.D1
        val panel = Golden.panel(tf)
        val regimes = Golden.regimes(tf)
        val flags = Signals.compute(panel)
        val key = flags.keys.first { it.name == "bullish_hikkake_1d" }
        val run = Scorecard.runVariant(key, flags.getValue(key), panel, tf, regimes, costFor = { 0.002 }, perPatternExits = true)
        assertTrue(run.trades.size > 100)
        assertTrue(run.trades.all { it.rule.mode == ExitMode.LEARNED })
        val learned = run.trades.filter { it.rule.targetPct != null }
        assertTrue(learned.size > run.trades.size / 2, "most trades should have enough history to learn from")
        assertTrue(learned.map { it.rule.targetPct }.toSet().size > 5, "each signal learns its own target, it is not one constant")
        assertTrue(run.trades.all { it.net == it.gross - 0.002 })
        // The classic backtest of the same signals is a different set of exits, so the research numbers are untouched.
        val classic = Scorecard.runVariant(key, flags.getValue(key), panel, tf, regimes)
        assertTrue(classic.trades.all { it.rule.mode == ExitMode.CLASSIC })
    }

    @Test
    fun randomEntriesAreGivenTheSameExitAsTheTradeTheyAreComparedWith() {
        val c = Synth.candles(900, Timeframe.D1, 21, "RNDUSDT")
        val a = Indicators.atr(c.high, c.low, c.close, 14)
        val regime = IntArray(c.size) { 1 }
        val trail = ExitRule(ExitMode.TRAIL, 10)
        val classic = ExitRule.classic(Timeframe.D1)
        val withTrail = Scorecard.matchedRandom("t", "RNDUSDT", c, a, regime, intArrayOf(500, 600), Timeframe.D1, 0.0, trail, IntArray(0)).mean
        val withClassic = Scorecard.matchedRandom("t", "RNDUSDT", c, a, regime, intArrayOf(500, 600), Timeframe.D1, 0.0, classic, IntArray(0)).mean
        assertTrue(withTrail.all { !it.isNaN() } && withClassic.all { !it.isNaN() })
        assertTrue(withTrail.toList() != withClassic.toList(), "a different exit has to give different comparison trades")
        // and the draws are the same entries, so with the same rule they are identical
        val again = Scorecard.matchedRandom("t", "RNDUSDT", c, a, regime, intArrayOf(500, 600), Timeframe.D1, 0.0, trail, IntArray(0)).mean
        assertEquals(withTrail.toList(), again.toList())
    }
}
