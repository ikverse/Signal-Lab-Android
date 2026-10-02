package com.ikverse.signallab.engine

import org.json.JSONObject
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two candle patterns against TA-Lib itself. The expected answers were produced once, by TA-Lib's
 * CDLHARAMI and CDLHIKKAKE on every real candle in the test data (1 day, 4 hour, 1 hour, 15 minute and
 * 1 minute), and frozen in the repository. A bullish flag is any positive TA-Lib answer.
 */
class CandlesticksTest {
    private val expected: JSONObject by lazy {
        val stream = CandlesticksTest::class.java.getResourceAsStream("/golden/candlesticks_expected.json.gz") ?: error("missing TA-Lib answers")
        JSONObject(GZIPInputStream(stream).bufferedReader().readText())
    }

    private fun positives(rows: org.json.JSONArray): Set<Int> =
        (0 until rows.length()).map { rows.getJSONArray(it) }.filter { it.getInt(1) > 0 }.map { it.getInt(0) }.toSet()

    @Test
    fun bothPatternsGiveTalibsAnswerOnEveryRealCandle() {
        var haramis = 0
        var hikkakes = 0
        for (label in listOf("1d", "4h", "1h", "15m", "1m")) {
            val tf = Timeframe.of(label)
            val series = expected.getJSONObject("series").getJSONObject(label)
            for ((symbol, c) in Golden.panel(tf)) {
                val want = series.getJSONObject(symbol)
                val harami = Candlesticks.bullishHarami(c)
                val hikkake = Candlesticks.bullishHikkake(c)
                assertEquals(positives(want.getJSONArray("harami")), harami.indices.filter { harami[it] }.toSet(), "$symbol $label harami")
                assertEquals(positives(want.getJSONArray("hikkake")), hikkake.indices.filter { hikkake[it] }.toSet(), "$symbol $label hikkake")
                haramis += harami.count { it }
                hikkakes += hikkake.count { it }
            }
        }
        assertTrue(haramis > 5_000, "only $haramis harami flags were compared")
        assertTrue(hikkakes > 5_000, "only $hikkakes hikkake flags were compared")
    }

    @Test
    fun theTalibAnswersIncludeTheConfirmationBarsOfTheHikkake() {
        // TA-Lib reports 100 on the pattern and 200 on the confirming bar; both must be in the data and both are flags.
        val rows = expected.getJSONObject("series").getJSONObject("1h").getJSONObject("BTCUSDT").getJSONArray("hikkake")
        val values = (0 until rows.length()).map { rows.getJSONArray(it).getInt(1) }.toSet()
        assertTrue(100 in values && 200 in values)
    }

    private fun candles(vararg bars: DoubleArray): Candles {
        val n = bars.size
        val t = LongArray(n) { Synth.START_MS + it * Timeframe.H1.ms }
        return Candles("TESTUSDT", Timeframe.H1, t, DoubleArray(n) { bars[it][0] }, DoubleArray(n) { bars[it][1] },
            DoubleArray(n) { bars[it][2] }, DoubleArray(n) { bars[it][3] }, DoubleArray(n) { 1.0 }, LongArray(n) { t[it] + Timeframe.H1.ms - 1 })
    }

    // open, high, low, close
    private fun small(i: Int) = doubleArrayOf(100.0 + i % 2 * 0.1, 101.0, 99.0, 100.0 + (i + 1) % 2 * 0.1) // twelve quiet candles to set the average body

    @Test
    fun aSmallCandleInsideALongRedOneIsABullishHarami() {
        val quiet = Array(12) { small(it) }
        val longRed = doubleArrayOf(104.0, 104.5, 95.5, 96.0)
        val inside = doubleArrayOf(98.0, 99.0, 97.5, 98.4)
        val flags = Candlesticks.bullishHarami(candles(*quiet, longRed, inside))
        assertTrue(flags[13])
        assertEquals(1, flags.count { it })
    }

    @Test
    fun aHaramiNeedsABlackFirstCandleABodyInsideItAndAShortSecond() {
        val quiet = Array(12) { small(it) }
        val longGreen = doubleArrayOf(96.0, 104.5, 95.5, 104.0)
        val longRed = doubleArrayOf(104.0, 104.5, 95.5, 96.0)
        assertFalse(Candlesticks.bullishHarami(candles(*quiet, longGreen, doubleArrayOf(98.0, 99.0, 97.5, 98.4)))[13], "a green first candle is the bearish harami")
        assertFalse(Candlesticks.bullishHarami(candles(*quiet, longRed, doubleArrayOf(95.9, 97.0, 95.5, 96.3)))[13], "a body that pokes out of the first one's is not inside it")
        assertFalse(Candlesticks.bullishHarami(candles(*quiet, longRed, doubleArrayOf(98.0, 103.0, 94.0, 102.0)))[13], "a second candle that is not short is not a harami")
        assertFalse(Candlesticks.bullishHarami(candles(*quiet, doubleArrayOf(100.0, 101.0, 99.0, 99.95), doubleArrayOf(99.96, 99.99, 99.9, 99.97)))[13], "the first candle was not long")
    }

    @Test
    fun aBodyThatOnlyTouchesAnEdgeOfTheFirstOneStillCountsAsTalibCountsIt() {
        val quiet = Array(12) { small(it) }
        val longRed = doubleArrayOf(104.0, 104.5, 95.5, 96.0)
        // Opens exactly where the first closed, as crypto candles do: TA-Lib answers 50 instead of 100, still positive.
        assertTrue(Candlesticks.bullishHarami(candles(*quiet, longRed, doubleArrayOf(96.0, 97.0, 95.5, 96.4)))[13])
    }

    @Test
    fun aFalseBreakDownOutOfAnInsideCandleIsABullishHikkakeAndItsConfirmationIsFlaggedToo() {
        // bar 6 is wide; bar 7 sits inside it; bar 8 breaks down (lower high and lower low): the pattern.
        val bars = arrayOf(
            small(0), small(1), small(2), small(3), small(4), small(5),
            doubleArrayOf(100.0, 106.0, 94.0, 101.0),
            doubleArrayOf(101.0, 104.0, 96.0, 100.0),
            doubleArrayOf(100.0, 103.0, 95.0, 96.0),
            doubleArrayOf(96.0, 103.5, 95.5, 101.0), // closes under the inside candle's high (104): not yet
            doubleArrayOf(101.0, 106.0, 100.0, 105.0), // closes above it: the confirmation, two candles after the pattern
            doubleArrayOf(105.0, 106.0, 104.0, 105.5),
        )
        val flags = Candlesticks.bullishHikkake(candles(*bars))
        assertEquals(listOf(8, 10), flags.indices.filter { flags[it] })
    }

    @Test
    fun aBreakUpOutOfAnInsideCandleIsTheBearishSideAndIsNotAFlag() {
        val bars = arrayOf(
            small(0), small(1), small(2), small(3), small(4), small(5),
            doubleArrayOf(100.0, 106.0, 94.0, 101.0),
            doubleArrayOf(101.0, 104.0, 96.0, 100.0),
            doubleArrayOf(100.0, 107.0, 97.0, 106.0),
            doubleArrayOf(106.0, 107.0, 100.0, 101.0),
        )
        assertEquals(0, Candlesticks.bullishHikkake(candles(*bars)).count { it })
    }

    @Test
    fun aConfirmationMoreThanThreeCandlesLateIsNotFlagged() {
        val bars = arrayOf(
            small(0), small(1), small(2), small(3), small(4), small(5),
            doubleArrayOf(100.0, 106.0, 94.0, 101.0),
            doubleArrayOf(101.0, 104.0, 96.0, 100.0),
            doubleArrayOf(100.0, 103.0, 95.0, 96.0),
            doubleArrayOf(96.0, 102.0, 95.5, 99.0),
            doubleArrayOf(99.0, 102.0, 95.8, 99.5),
            doubleArrayOf(99.5, 103.0, 96.0, 100.0),
            doubleArrayOf(100.0, 106.0, 99.0, 105.0), // closes above 104, but four candles after the pattern
        )
        val flags = Candlesticks.bullishHikkake(candles(*bars))
        assertEquals(listOf(8), flags.indices.filter { flags[it] })
    }

    @Test
    fun tooFewCandlesGiveNoFlagsAndNoCrash() {
        val few = candles(small(0), small(1), small(2))
        assertEquals(0, Candlesticks.bullishHarami(few).count { it })
        assertEquals(0, Candlesticks.bullishHikkake(few).count { it })
    }
}
