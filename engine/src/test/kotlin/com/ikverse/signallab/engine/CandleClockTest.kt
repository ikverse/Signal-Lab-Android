package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.test.assertEquals

class CandleClockTest {
    private val midnight = 1_704_067_200_000L // 1 Jan 2024 00:00 UTC
    private val hour = 3_600_000L

    @Test
    fun aCloseIsStrictlyAfterNowEvenOnTheBoundary() {
        assertEquals(midnight + hour, CandleClock.nextClose(Timeframe.H1, midnight))
        assertEquals(midnight + hour, CandleClock.nextClose(Timeframe.H1, midnight + hour - 1))
        assertEquals(midnight + 2 * hour, CandleClock.nextClose(Timeframe.H1, midnight + hour))
    }

    @Test
    fun fourHourCandlesCloseAtZeroFourEightTwelveSixteenTwenty() {
        val closes = generateSequence(midnight) { CandleClock.nextClose(Timeframe.H4, it) }.take(7).toList()
        assertEquals(listOf(0L, 4, 8, 12, 16, 20, 24).map { midnight + it * hour }, closes)
        assertEquals(midnight + 4 * hour, CandleClock.nextClose(Timeframe.H4, midnight + 3 * hour + 59 * 60_000))
    }

    @Test
    fun dailyCandlesCloseAtUtcMidnight() {
        assertEquals(midnight + 24 * hour, CandleClock.nextClose(Timeframe.D1, midnight + 23 * hour))
        assertEquals(midnight + 48 * hour, CandleClock.nextClose(Timeframe.D1, midnight + 24 * hour))
    }

    @Test
    fun theNextCloseOfAnyTimeframeIsTheSoonest() {
        assertEquals(midnight + 5 * hour, CandleClock.nextClose(midnight + 4 * hour + 1))
        assertEquals(midnight + 24 * hour, CandleClock.nextClose(midnight + 23 * hour))
    }

    @Test
    fun theLastCloseIsAtOrBeforeNow() {
        assertEquals(midnight + 4 * hour, CandleClock.lastClose(Timeframe.H4, midnight + 7 * hour + 59 * 60_000))
        assertEquals(midnight + 8 * hour, CandleClock.lastClose(Timeframe.H4, midnight + 8 * hour))
        assertEquals(midnight, CandleClock.lastClose(Timeframe.D1, midnight + 23 * hour))
    }

    @Test
    fun midnightClosesEveryTimeframeAndOtherHoursOnlyTheShorterOnes() {
        assertEquals(listOf(Timeframe.H1, Timeframe.H4, Timeframe.D1), CandleClock.closingAt(midnight + 24 * hour))
        assertEquals(listOf(Timeframe.H1, Timeframe.H4), CandleClock.closingAt(midnight + 4 * hour))
        assertEquals(listOf(Timeframe.H1), CandleClock.closingAt(midnight + 5 * hour))
        assertEquals(emptyList(), CandleClock.closingAt(midnight + 5 * hour + 1))
    }

    @Test
    fun closesBetweenListsEveryCloseInAnOpenClosedRange() {
        val got = CandleClock.closesBetween(Timeframe.H4, midnight + 4 * hour, midnight + 16 * hour)
        assertEquals(listOf(8L, 12, 16).map { midnight + it * hour }, got)
        assertEquals(emptyList(), CandleClock.closesBetween(Timeframe.D1, midnight, midnight + 23 * hour))
    }

    @Test
    fun theScheduleAgreesWithTheCandlesBinanceServes() {
        // Every golden candle opens on its timeframe's grid and closes one millisecond before the next boundary.
        for (tf in Timeframe.entries) {
            for (c in Golden.panel(tf).values.take(2)) {
                for (i in c.t.indices step 7) {
                    assertEquals(c.closeTime[i] + 1, CandleClock.nextClose(tf, c.t[i]), "${c.symbol} ${tf.label} candle $i")
                }
            }
        }
    }
}
