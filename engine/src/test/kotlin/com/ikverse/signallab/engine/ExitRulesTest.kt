package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The paper-trading rules, on a flat market so every outcome is one thing the test caused. */
class ExitRulesTest {
    private fun flat(n: Int = 12): Candles {
        val t = LongArray(n) { Synth.START_MS + it * Timeframe.D1.ms }
        return Candles("FLATUSDT", Timeframe.D1, t, DoubleArray(n) { 100.0 }, DoubleArray(n) { 101.0 },
            DoubleArray(n) { 99.0 }, DoubleArray(n) { 100.0 }, DoubleArray(n) { 1.0 },
            LongArray(n) { t[it] + Timeframe.D1.ms - 1 })
    }

    private fun exits(c: Candles, signal: Int, atr: Double = 2.0, hold: Int? = null) =
        PaperTrading.simulateExits(c, intArrayOf(signal), Timeframe.D1, DoubleArray(c.size) { atr }, hold)

    @Test
    fun entryIsTheNextCandlesOpen() {
        val c = flat()
        c.open[4] = 123.0
        assertEquals(123.0, exits(c, 3).entryPrice[0])
    }

    @Test
    fun targetIsTwoAtrAboveAndStopOneBelow() {
        val c = flat()
        c.high[6] = 104.5 // target is 100 + 2*2 = 104
        val e = exits(c, 3)
        assertEquals(ExitReason.TARGET, e.reason[0])
        assertEquals(104.0, e.exitPrice[0])
        assertEquals(6, e.exitIdx[0])
        val s = flat()
        s.low[5] = 97.5 // stop is 100 - 2 = 98
        assertEquals(ExitReason.STOP, exits(s, 3).reason[0])
        assertEquals(98.0, exits(s, 3).exitPrice[0])
    }

    @Test
    fun theStopIsAssumedFirstWhenOneCandleTouchesBoth() {
        val c = flat()
        c.high[5] = 110.0
        c.low[5] = 90.0
        val e = exits(c, 3)
        assertEquals(ExitReason.STOP, e.reason[0])
        assertEquals(98.0, e.exitPrice[0])
    }

    @Test
    fun aGapBelowTheStopFillsAtTheOpenNotAtTheStop() {
        val c = flat()
        c.open[6] = 90.0; c.low[6] = 89.0; c.high[6] = 91.0
        val e = exits(c, 3)
        assertEquals(ExitReason.STOP, e.reason[0])
        assertEquals(90.0, e.exitPrice[0])
    }

    @Test
    fun aGapAboveTheTargetFillsAtTheOpen() {
        val c = flat()
        c.open[6] = 110.0; c.low[6] = 109.0; c.high[6] = 112.0
        val e = exits(c, 3)
        assertEquals(ExitReason.TARGET, e.reason[0])
        assertEquals(110.0, e.exitPrice[0])
    }

    @Test
    fun withNeitherLevelTouchedTheTradeClosesAtTheTimeLimit() {
        val c = flat()
        val e = exits(c, 2)
        assertEquals(ExitReason.TIME, e.reason[0])
        assertEquals(2 + 1 + EngineConfig.timeLimitBars(Timeframe.D1) - 1, e.exitIdx[0])
        assertEquals(100.0, e.exitPrice[0])
    }

    @Test
    fun aHeldTradeHasNoTargetOrStop() {
        val c = flat()
        c.low[5] = 50.0 // would be a stop, but a held trade ignores it
        c.close[6] = 103.0
        val e = exits(c, 3, hold = 3)
        assertEquals(ExitReason.TIME, e.reason[0])
        assertEquals(103.0, e.exitPrice[0]) // the close of candle 4 + 3 - 1 = 6
        assertEquals(6, e.exitIdx[0])
    }

    @Test
    fun aSignalWithoutEnoughFutureOrWithoutAnAtrIsNotATrade() {
        val c = flat()
        assertFalse(exits(c, 9).valid[0])
        val noAtr = PaperTrading.simulateExits(c, intArrayOf(3), Timeframe.D1, DoubleArray(c.size) { Double.NaN })
        assertFalse(noAtr.valid[0])
        assertNull(noAtr.reason[0])
    }

    @Test
    fun costsAreHigherForEverythingButBtcAndEth() {
        assertEquals(0.0025, EngineConfig.costFor("BTCUSDT"))
        assertEquals(0.0025, EngineConfig.costFor("ETHUSDT"))
        assertEquals(0.0040, EngineConfig.costFor("SOLUSDT"))
    }

    @Test
    fun horizonReturnsAreNetOfCostsAndMissingBeyondTheData() {
        val c = flat(8)
        c.close[6] = 110.0
        val r = PaperTrading.horizonReturns(c, intArrayOf(2), intArrayOf(4, 6), 0.004)
        assertEquals(110.0 / 100.0 - 1 - 0.004, r.getValue(4)[0], 1e-12) // close of candle 3 + 4 - 1 = 6
        assertEquals(true, r.getValue(6)[0].isNaN())
    }

    @Test
    fun theRegimeLooksOnlyAtDailyCandlesThatHadClosed() {
        val btc = Synth.candles(300, Timeframe.D1, seed = 5, symbol = "BTCUSDT")
        val early = longArrayOf(btc.closeTime[10], btc.closeTime[250] - 1, btc.closeTime[250])
        val regime = PaperTrading.regimeSeries(btc, early)
        assertEquals(-1, regime[0]) // not enough history for a 200-day average yet
        val ma = Indicators.sma(btc.close, EngineConfig.REGIME_MA_DAYS)
        assertEquals(if (btc.close[249] > ma[249]) 1 else 0, regime[1]) // candle 250 had not closed at its own close time - 1
        assertEquals(if (btc.close[250] > ma[250]) 1 else 0, regime[2])
    }
}
