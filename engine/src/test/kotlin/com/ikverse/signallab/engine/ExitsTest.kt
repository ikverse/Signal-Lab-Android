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
            // The research's saved signals, each under the classic and the trailing exit (or its fixed hold).
            for ((key, bySymbol) in Golden.researchSignals(tf)) {
                for (rule in key.holdBars?.let { listOf(ExitRule.held(it)) } ?: listOf(ExitRule.classic(tf), ExitRule(ExitMode.TRAIL, EngineConfig.trailCapBars(tf)))) {
                for ((symbol, flags) in bySymbol) {
                    val c = panel.getValue(symbol)
                    val idx = flags.indices.filter { flags[it] }.toIntArray()
                    if (idx.isEmpty()) continue
                    val a = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)
                    val ex = PaperTrading.simulate(c, idx, tf, a, rule)
                    for (j in idx.indices) {
                        if (!ex.valid[j]) continue
                        val s = idx[j]
                        val limit = rule.limit
                        val r = assertNotNull(PaperTrading.resolve(c, s + 1, rule.spec(c.open[s + 1], a[s], limit)), "${key.name} $symbol $s")
                        assertEquals(ex.exitIdx[j], r.exitIdx, "${key.name} $symbol $s exit candle")
                        assertEquals(ex.exitPrice[j], r.exitPrice, "${key.name} $symbol $s exit price")
                        compared++
                    }
                }
                }
            }
        }
        assertTrue(compared > 3_000, "only $compared trades compared")
    }

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
