package com.ikverse.signallab.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The line beside a coin's price: a day of closes, thinned to a light drawing. */
class SparkPointsTest {
    @Test
    fun `a day of hours is drawn as it is`() {
        val closes = List(25) { 100.0 + it }
        assertEquals(closes, sparkPoints(closes))
    }

    @Test
    fun `a day of 15-minute candles is thinned, keeping the first and the last`() {
        val closes = List(97) { 100.0 + it }
        val points = sparkPoints(closes)
        assertEquals(48, points.size)
        assertEquals(100.0, points.first(), 0.0)
        assertEquals(196.0, points.last(), 0.0)
        assertTrue("in order", points.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `too few closes, or broken ones, draw nothing`() {
        assertEquals(emptyList<Double>(), sparkPoints(listOf(1.0, 2.0)))
        assertEquals(emptyList<Double>(), sparkPoints(listOf(Double.NaN, 0.0, -1.0, 2.0)))
    }
}
