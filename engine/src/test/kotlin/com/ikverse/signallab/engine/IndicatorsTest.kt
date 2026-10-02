package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Indicator values worked out by hand. */
class IndicatorsTest {
    private val nan = Double.NaN

    private fun check(expected: DoubleArray, actual: DoubleArray, tol: Double = 1e-6) = assertSeries(expected, actual, "series", tol)

    @Test
    fun sma() = check(doubleArrayOf(nan, nan, 2.0, 3.0, 4.0), Indicators.sma(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3))

    @Test
    fun smaOfTooFewValuesIsAllNaN() = check(doubleArrayOf(nan, nan), Indicators.sma(doubleArrayOf(1.0, 2.0), 3))

    @Test
    fun rollingMaxPriorExcludesTheCurrentBar() =
        check(doubleArrayOf(nan, nan, 3.0, 3.0, 5.0), Indicators.rollingMaxPrior(doubleArrayOf(1.0, 3.0, 2.0, 5.0, 4.0), 2))

    @Test
    fun trueRangeAndAtr() {
        val high = doubleArrayOf(10.0, 12.0, 11.0)
        val low = doubleArrayOf(9.0, 10.0, 8.0)
        val close = doubleArrayOf(9.5, 11.0, 9.0)
        // bar 1: max(12-10, |12-9.5|, |10-9.5|) = 2.5; bar 2: max(3, |11-11|, |8-11|) = 3
        check(doubleArrayOf(1.0, 2.5, 3.0), Indicators.trueRange(high, low, close))
        check(doubleArrayOf(nan, 1.75, 2.75), Indicators.atr(high, low, close, 2))
    }

    @Test
    fun pctReturn() {
        check(doubleArrayOf(nan, 0.1, 0.1), Indicators.pctReturn(doubleArrayOf(100.0, 110.0, 121.0), 1))
        check(doubleArrayOf(nan, nan, 0.21), Indicators.pctReturn(doubleArrayOf(100.0, 110.0, 121.0), 2))
    }

    @Test
    fun rsiByHand() {
        // n = 2: seed gain 0.17 loss 0.125 -> 57.627; then Wilder smoothing step by step:
        // (0.115, 0.0625) -> 64.789; (0.0575, 0.30125) -> 16.028; (0.38875, 0.150625) -> 72.074
        val close = doubleArrayOf(44.0, 44.34, 44.09, 44.15, 43.61, 44.33)
        check(doubleArrayOf(nan, nan, 57.6271, 64.7887, 16.0279, 72.0740), Indicators.rsi(close, 2), tol = 1e-4)
    }

    @Test
    fun rsiOfAnUnbrokenRiseIs100() {
        assertEquals(100.0, Indicators.rsi(DoubleArray(29) { it + 1.0 }, 14).last())
    }

    @Test
    fun crossedAbove() {
        val got = Indicators.crossedAbove(doubleArrayOf(1.0, 2.0, 3.0, 2.0, 4.0), doubleArrayOf(2.0, 2.0, 2.0, 2.0, 2.0))
        assertTrue(got.contentEquals(booleanArrayOf(false, false, true, false, true)))
    }

    @Test
    fun crossedAboveNeverFiresOnNaN() {
        val got = Indicators.crossedAbove(doubleArrayOf(1.0, 5.0, 6.0), doubleArrayOf(nan, nan, 2.0))
        assertFalse(got.any { it })
    }

    @Test
    fun rollingStd() = check(doubleArrayOf(nan, 1.0, 1.0), Indicators.rollingStd(doubleArrayOf(1.0, 3.0, 5.0), 2))

    @Test
    fun quantileInterpolatesLinearly() {
        val v = doubleArrayOf(40.0, 10.0, 30.0, 20.0, 50.0)
        assertClose(10.0, Indicators.quantile(v, 0.0), "q0")
        assertClose(30.0, Indicators.quantile(v, 0.5), "q50")
        assertClose(42.0, Indicators.quantile(v, 0.8), "q80") // position 3.2: a fifth of the way from 40 to 50
        assertClose(50.0, Indicators.quantile(v, 1.0), "q100")
        assertClose(15.0, Indicators.quantile(v, 0.125), "q12.5")
    }
}
