package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Golden
import com.ikverse.signallab.engine.Timeframe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiskTest {
    /** Bitcoin's closes from daily returns that alternate around a drift, so its variance is known. */
    private fun closes(returns: (Int) -> Double, n: Int = 120): DoubleArray {
        val out = DoubleArray(n + 1)
        out[0] = 100.0
        for (i in 1..n) out[i] = out[i - 1] * (1 + returns(i))
        return out
    }

    private val btcR = { i: Int -> if (i % 2 == 0) 0.02 else -0.015 }
    private val btc = closes(btcR)

    @Test
    fun aCoinMovingTwiceAsMuchAsBitcoinHasBetaTwoAndNoOwnRisk() {
        val coin = closes({ i -> 2 * btcR(i) })
        val e = assertNotNull(Risk.exposure(coin, btc))
        assertEquals(2.0, e.beta, 1e-9)
        assertEquals(0.0, e.idioVar, 1e-12)
        assertNull(Risk.exposure(coin.copyOf(10), btc), "too little history")
    }

    @Test
    fun aQuietBookTakesTheCallAtItsPlainSize() {
        val e = Exposure(1.0, 0.0)
        val s = Risk.size(0.10, 1.0, Heat.NONE, e, emptyList(), btcVar = 1e-5)
        assertEquals(0.10, s.size, 1e-12)
        assertEquals(0.10, s.plain)
        assertTrue(s.reasons.isEmpty())
    }

    @Test
    fun aSwingingBookShrinksTheNewCallToTheTarget() {
        // Bitcoin at 4% a day swings about 76% a year; a book already 30% in it is near 23%.
        val btcVar = 0.04 * 0.04
        val e = Exposure(1.0, 0.0)
        val book = listOf(Holding(0.30, e))
        val s = Risk.size(0.20, 1.0, Heat.NONE, e, book, btcVar)
        val vol = Risk.bookVol(book + Holding(s.size, e), btcVar)
        assertTrue(s.size < 0.20)
        assertTrue(abs(vol - Risk.TARGET_VOL) < 1e-6, "the book lands on the target: $vol")
        assertTrue("the book is already swinging a lot" in s.reasons)
        val full = Risk.size(0.20, 1.0, Heat.NONE, e, listOf(Holding(0.40, e)), btcVar)
        assertEquals(0.0, full.size, 1e-9, "a book already past the target takes nothing more")
    }

    @Test
    fun exposureToBitcoinIsCapped() {
        val e = Exposure(1.5, 0.0)
        val book = List(6) { Holding(0.15, Exposure(1.5, 0.0)) } // 1.35 of beta exposure already
        val s = Risk.size(0.20, 1.0, Heat.NONE, e, book, btcVar = 1e-8)
        assertEquals((Risk.MAX_BETA_EXPOSURE - 1.35) / 1.5, s.size, 1e-9)
    }

    @Test
    fun heatHalvesOrBlocksAndTrendScales() {
        val e = Exposure(1.0, 0.0)
        assertEquals(0.05, Risk.size(0.10, 1.0, Heat.HALF, e, emptyList(), 1e-8).size, 1e-12)
        assertEquals(0.0, Risk.size(0.10, 1.0, Heat.BLOCK, e, emptyList(), 1e-8).size)
        assertEquals(0.15, Risk.size(0.10, 1.5, Heat.NONE, e, emptyList(), 1e-8).size, 1e-12)
        assertEquals(Live.MAX_SIZE, Risk.size(0.19, 1.5, Heat.NONE, e, emptyList(), 1e-8).size, "never above the cap per call")
        assertEquals(0.0, Risk.size(0.0, 1.0, Heat.NONE, e, emptyList(), 1e-8).size)
    }

    @Test
    fun theTrendFactorReadsDistanceAboveTheFiftyDayAverage() {
        val c = Golden.panel(Timeframe.D1).getValue("BTCUSDT")
        for (i in 60 until c.size step 97) {
            val f = Risk.trendFactor(c, i)
            assertTrue(f in 0.5..1.5, "factor $f at $i")
        }
        assertEquals(1.0, Risk.trendFactor(c, 10), "before the average exists it changes nothing")
    }

    @Test
    fun theLiveRecordCapsTheBookOnlyWhenItIsLongAndWinning() {
        assertNull(Risk.kellyCap(DoubleArray(50) { 0.01 }), "too few calls")
        assertNull(Risk.kellyCap(DoubleArray(200) { if (it % 2 == 0) 0.05 else -0.06 }), "a losing record is the tiers' job")
        val cap = assertNotNull(Risk.kellyCap(DoubleArray(200) { if (it % 2 == 0) 0.06 else -0.04 }))
        // mean 0.01, sample variance 0.0025125...: half of mean / variance.
        assertEquals(0.5 * 0.01 / (0.05 * 0.05 * 200 / 199), cap, 1e-9)
        val s = Risk.size(0.20, 1.0, Heat.NONE, Exposure(0.0, 0.0), listOf(Holding(cap - 0.05, Exposure(0.0, 0.0))), 1e-8, kelly = cap)
        assertEquals(0.05, s.size, 1e-9)
    }
}
