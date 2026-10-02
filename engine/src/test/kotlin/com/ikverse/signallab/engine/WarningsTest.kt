package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two market warnings: what counts as a pump and as a volume spike, and what does not. */
class WarningsTest {
    private fun candles(tf: Timeframe, n: Int, price: (Int) -> Double, volume: (Int) -> Double): Candles {
        val t = LongArray(n) { Synth.START_MS + it * tf.ms }
        val open = DoubleArray(n) { if (it == 0) price(0) else price(it - 1) }
        val close = DoubleArray(n) { price(it) }
        return Candles("PUMPUSDT", tf, t, open, DoubleArray(n) { maxOf(open[it], close[it]) }, DoubleArray(n) { minOf(open[it], close[it]) },
            close, DoubleArray(n) { volume(it) }, LongArray(n) { t[it] + tf.ms - 1 })
    }

    /** A flat minute chart for 300 candles, then the last five rise [rise] in all on [times] the usual volume. */
    private fun pumpOnMinutes(rise: Double, times: Double): Candles {
        val n = 305
        val step = (1 + rise).let { Math.pow(it, 1.0 / 5) }
        return candles(Timeframe.M1, n, { i -> if (i < 300) 100.0 else 100.0 * Math.pow(step, (i - 299).toDouble()) },
            { i -> if (i < 300) 10.0 else 10.0 * times })
    }

    @Test
    fun aSixPercentRiseInFiveMinutesOnFifteenTimesTheVolumeIsAPump() {
        val p = assertNotNull(Warnings.pump(pumpOnMinutes(0.06, 15.0)))
        assertEquals(0.06, p.rise, 1e-9)
        assertEquals(15.0, p.volumeMultiple, 1e-9)
        assertEquals(5, p.minutes)
    }

    @Test
    fun aRiseUnderFivePercentIsNotAPump() {
        assertNull(Warnings.pump(pumpOnMinutes(0.04, 15.0)))
    }

    @Test
    fun aBigRiseOnOrdinaryVolumeIsNotAPump() {
        assertNull(Warnings.pump(pumpOnMinutes(0.06, 5.0)))
        assertNotNull(Warnings.pump(pumpOnMinutes(0.06, 10.0)), "ten times is enough")
    }

    @Test
    fun aFiveMinuteChartLooksAtOneCandle() {
        val c = candles(Timeframe.M5, 60, { i -> if (i < 59) 100.0 else 106.0 }, { i -> if (i < 59) 10.0 else 130.0 })
        val p = assertNotNull(Warnings.pump(c))
        assertEquals(0.06, p.rise, 1e-9)
        assertEquals(13.0, p.volumeMultiple, 1e-9)
    }

    @Test
    fun onlyMinuteAndFiveMinuteChartsCanShowAPump() {
        for (tf in listOf(Timeframe.M15, Timeframe.M30, Timeframe.H1, Timeframe.H4, Timeframe.D1)) {
            assertNull(Warnings.pump(candles(tf, 400, { i -> if (i < 399) 100.0 else 120.0 }, { i -> if (i < 399) 1.0 else 100.0 })), tf.label)
        }
    }

    @Test
    fun withTooLittleHistoryOrNoUsualVolumeThereIsNoPump() {
        assertNull(Warnings.pump(candles(Timeframe.M1, 100, { i -> if (i < 95) 100.0 else 110.0 }, { 10.0 })), "under four hours of history")
        assertNull(Warnings.pump(candles(Timeframe.M1, 305, { i -> if (i < 300) 100.0 else 110.0 }, { i -> if (i < 300) 0.0 else 50.0 })), "no usual volume to compare with")
    }

    @Test
    fun aPumpIsOnlyFoundOnTheCandleItEndsOn() {
        val c = pumpOnMinutes(0.06, 15.0)
        assertNull(Warnings.pump(c, 300), "one candle in, the rise is not there yet")
        assertNotNull(Warnings.pump(c, 304))
    }

    @Test
    fun aDayOfMoreThanThreeTimesTheUsualVolumeIsASpike() {
        val c = candles(Timeframe.D1, 41, { 100.0 }, { i -> if (i < 40) 10.0 else 41.0 })
        assertEquals(4.1, Warnings.volumeSpike(c)!!, 1e-9)
    }

    @Test
    fun exactlyThreeTimesIsNotAndOtherChartsNeverAre() {
        assertNull(Warnings.volumeSpike(candles(Timeframe.D1, 41, { 100.0 }, { i -> if (i < 40) 10.0 else 30.0 })))
        assertNull(Warnings.volumeSpike(candles(Timeframe.H1, 41, { 100.0 }, { i -> if (i < 40) 10.0 else 400.0 })))
        assertNull(Warnings.volumeSpike(candles(Timeframe.D1, 20, { 100.0 }, { 100.0 })), "fewer than thirty days to average")
    }

    @Test
    fun theThresholdsAreWhatWasPromised() {
        assertEquals(0.05, EngineConfig.PUMP_RISE)
        assertEquals(5, EngineConfig.PUMP_MINUTES)
        assertEquals(10.0, EngineConfig.PUMP_VOLUME_MULTIPLE)
        assertEquals(3.0, EngineConfig.VOLUME_SPIKE_MULTIPLE)
        assertEquals(30, EngineConfig.VOLUME_SPIKE_DAYS)
        assertTrue(EngineConfig.randomWindowDays(Timeframe.M1) < EngineConfig.randomWindowDays(Timeframe.M5) &&
            EngineConfig.randomWindowDays(Timeframe.M30) < EngineConfig.randomWindowDays(Timeframe.H1) &&
            EngineConfig.randomWindowDays(Timeframe.H1) < EngineConfig.randomWindowDays(Timeframe.H4))
        assertEquals(listOf(3, 10, 10, 10, 30, 90, 90), Timeframe.entries.map { EngineConfig.randomWindowDays(it) })
    }
}
