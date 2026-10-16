package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Golden
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.head
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.engine.strategy.ExitPlan
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FillsTest {
    private val day = 86_400_000L

    /** Daily candles from (open, high, low, close) rows, starting 1 Jan 2024. */
    private fun daily(vararg rows: DoubleArray): Candles {
        val t = LongArray(rows.size) { 1_704_067_200_000L + it * day }
        return Candles("TESTUSDT", Timeframe.D1, t, DoubleArray(rows.size) { rows[it][0] }, DoubleArray(rows.size) { rows[it][1] },
            DoubleArray(rows.size) { rows[it][2] }, DoubleArray(rows.size) { rows[it][3] }, DoubleArray(rows.size) { 1.0 },
            LongArray(rows.size) { t[it] + day - 1 })
    }

    private fun row(o: Double, h: Double, l: Double, c: Double) = doubleArrayOf(o, h, l, c)

    @Test
    fun aTradeWithNoSignalEndsAtTheCloseOfItsLastCandle() {
        val c = daily(row(100.0, 101.0, 99.0, 100.0), row(100.0, 102.0, 99.0, 101.0), row(101.0, 104.0, 100.0, 103.0))
        val f = assertNotNull(Fills.resolve(c, 1, ExitPlan(2), 1.0, null))
        assertEquals(2, f.exitIdx)
        assertEquals(103.0, f.exitPrice)
        assertEquals(Leave.TIME, f.reason)
    }

    @Test
    fun aTradeStillRunningWhenTheCandlesEndIsOpen() {
        val c = daily(row(100.0, 101.0, 99.0, 100.0), row(100.0, 102.0, 99.0, 101.0))
        assertNull(Fills.resolve(c, 1, ExitPlan(3), 1.0, null))
    }

    @Test
    fun theSafetyStopFillsAtTheStopOrAtTheOpenOfAGap() {
        val touch = daily(row(100.0, 101.0, 99.0, 100.0), row(100.0, 101.0, 96.5, 98.0), row(98.0, 99.0, 97.0, 98.0))
        val f = assertNotNull(Fills.resolve(touch, 1, ExitPlan(5, safetyStopAtr = 3.0), 1.0, null))
        assertEquals(Leave.STOP, f.reason)
        assertEquals(97.0, f.exitPrice, "the stop sits 3 candle sizes below the entry of 100")
        val gap = daily(row(100.0, 101.0, 99.0, 100.0), row(100.0, 101.0, 99.0, 100.0), row(90.0, 91.0, 89.0, 90.5))
        val g = assertNotNull(Fills.resolve(gap, 1, ExitPlan(5, safetyStopAtr = 3.0), 1.0, null))
        assertEquals(2, g.exitIdx)
        assertEquals(90.0, g.exitPrice, "a gap through the stop fills at the open")
    }

    @Test
    fun anExitSignalAtACloseFillsAtTheNextOpen() {
        val c = daily(row(100.0, 101.0, 99.0, 100.0), row(100.0, 103.0, 99.0, 102.0), row(101.5, 102.0, 100.0, 101.0))
        val exits = booleanArrayOf(false, true, false)
        val f = assertNotNull(Fills.resolve(c, 1, ExitPlan(5), 1.0, exits))
        assertEquals(2, f.exitIdx)
        assertEquals(101.5, f.exitPrice)
        assertEquals(Leave.SIGNAL, f.reason)
        assertNull(Fills.resolve(c.head(2), 1, ExitPlan(5), 1.0, exits.copyOf(2)), "the next open has not happened yet")
    }

    @Test
    fun theStopIsCheckedBeforeAnExitSignalOnTheSameCandle() {
        val c = daily(row(100.0, 101.0, 99.0, 100.0), row(100.0, 101.0, 96.0, 97.5), row(97.0, 98.0, 96.0, 97.0))
        val f = assertNotNull(Fills.resolve(c, 1, ExitPlan(5, safetyStopAtr = 3.0), 1.0, booleanArrayOf(false, true, false)))
        assertEquals(Leave.STOP, f.reason)
    }

    @Test
    fun onTheLastCandleTheTimeLimitWinsOverAnExitSignal() {
        val c = daily(row(100.0, 101.0, 99.0, 100.0), row(100.0, 101.0, 99.0, 100.5), row(100.0, 101.0, 99.0, 100.0))
        val f = assertNotNull(Fills.resolve(c, 1, ExitPlan(1), 1.0, booleanArrayOf(false, true, false)))
        assertEquals(Leave.TIME, f.reason)
        assertEquals(1, f.exitIdx)
    }

    /** Live, a trade is resolved on the candles that exist each day; it must reach the backtest's fill on the same candle. */
    @Test
    fun resolvingDayByDayReachesTheSameFillAsTheFullHistory() {
        var compared = 0
        for (s in Builtins.all) {
            for (c in Golden.panel(s.timeframe).values) {
                val atr = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)
                val entries = s.entries(c)
                val exits = s.exitSignals(c)
                for (i in entries.indices) {
                    if (!entries[i] || !Fills.judgeable(c, i, s.exit, atr)) continue
                    val full = assertNotNull(Fills.resolve(c, i + 1, s.exit, atr[i], exits))
                    // One candle before its exit candle closed, the trade is still open; at that close it has the same fill.
                    val before = c.head(full.exitIdx)
                    val beforeExits = s.exitSignals(before)
                    if (full.exitIdx > i + 1) assertNull(Fills.resolve(before, i + 1, s.exit, atr[i], beforeExits), "${s.id} ${c.symbol} $i closed early")
                    val at = c.head(full.exitIdx + 1)
                    val live = assertNotNull(Fills.resolve(at, i + 1, s.exit, atr[i], s.exitSignals(at)), "${s.id} ${c.symbol} $i")
                    assertEquals(full.exitIdx, live.exitIdx, "${s.id} ${c.symbol} $i exit candle")
                    assertEquals(full.exitPrice, live.exitPrice, "${s.id} ${c.symbol} $i exit price")
                    assertEquals(full.reason, live.reason, "${s.id} ${c.symbol} $i reason")
                    compared++
                }
            }
        }
        assertTrue(compared > 200, "only $compared trades compared")
    }
}
