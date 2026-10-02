package com.ikverse.signallab.engine

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Indicators, each a single pass over primitive arrays.
 *
 * Every function returns an array the same length as its input, with NaN where there is not yet
 * enough history. Value i only ever uses data up to and including bar i.
 */
object Indicators {
    private fun nans(n: Int) = DoubleArray(n) { Double.NaN }

    /**
     * Simple moving average of the last [n] values, including the current one. Built from a running
     * total (not a sliding sum) so the arithmetic is the same operations in the same order as the
     * research version, which keeps threshold comparisons identical.
     */
    fun sma(x: DoubleArray, n: Int): DoubleArray {
        val out = nans(x.size)
        if (x.size < n) return out
        val c = DoubleArray(x.size + 1)
        for (i in x.indices) c[i + 1] = c[i] + x[i]
        for (i in n - 1 until x.size) out[i] = (c[i + 1] - c[i + 1 - n]) / n
        return out
    }

    /** Highest value of the [n] bars before bar i; bar i itself is excluded. */
    fun rollingMaxPrior(x: DoubleArray, n: Int): DoubleArray {
        val out = nans(x.size)
        for (i in n until x.size) {
            var m = x[i - n]
            for (j in i - n + 1 until i) m = maxOf(m, x[j])
            out[i] = m
        }
        return out
    }

    /** Standard deviation (divisor n) of the last [n] values, including the current one. */
    fun rollingStd(x: DoubleArray, n: Int): DoubleArray {
        val out = nans(x.size)
        for (i in n - 1 until x.size) {
            var s = 0.0
            for (j in i - n + 1..i) s += x[j]
            val mean = s / n
            var ss = 0.0
            for (j in i - n + 1..i) {
                val d = x[j] - mean
                ss += d * d
            }
            out[i] = sqrt(ss / n)
        }
        return out
    }

    fun trueRange(high: DoubleArray, low: DoubleArray, close: DoubleArray): DoubleArray {
        val out = DoubleArray(close.size)
        for (i in close.indices) {
            val prev = if (i == 0) close[0] else close[i - 1]
            out[i] = maxOf(high[i] - low[i], maxOf(kotlin.math.abs(high[i] - prev), kotlin.math.abs(low[i] - prev)))
        }
        return out
    }

    /** Average true range: simple mean of the last [n] true ranges. */
    fun atr(high: DoubleArray, low: DoubleArray, close: DoubleArray, n: Int): DoubleArray =
        sma(trueRange(high, low, close), n)

    /** Return over the last [lag] bars: close[i] / close[i - lag] - 1. */
    fun pctReturn(close: DoubleArray, lag: Int): DoubleArray {
        val out = nans(close.size)
        for (i in lag until close.size) out[i] = close[i] / close[i - lag] - 1
        return out
    }

    /** Wilder's RSI, seeded with the simple average of the first [n] changes. */
    fun rsi(close: DoubleArray, n: Int = 14): DoubleArray {
        val out = nans(close.size)
        if (close.size <= n) return out
        val up = DoubleArray(close.size - 1)
        val dn = DoubleArray(close.size - 1)
        for (i in 1 until close.size) {
            val ch = close[i] - close[i - 1]
            up[i - 1] = maxOf(ch, 0.0)
            dn[i - 1] = maxOf(-ch, 0.0)
        }
        var g = 0.0
        var l = 0.0
        for (i in 0 until n) {
            g += up[i]
            l += dn[i]
        }
        g /= n
        l /= n
        out[n] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
        for (i in n until up.size) {
            g = (g * (n - 1) + up[i]) / n
            l = (l * (n - 1) + dn[i]) / n
            out[i + 1] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
        }
        return out
    }

    /** True on the bar where a moves from <= b to > b. NaN compares false, so it never fires early. */
    fun crossedAbove(a: DoubleArray, b: DoubleArray): BooleanArray {
        val out = BooleanArray(a.size)
        for (i in 1 until a.size) out[i] = a[i] > b[i] && a[i - 1] <= b[i - 1]
        return out
    }

    /**
     * Quantile with linear interpolation between the two nearest values: numpy's default, including
     * its choice of interpolating from the nearer end, so thresholds agree to the last digit.
     */
    fun quantile(values: DoubleArray, q: Double): Double {
        require(values.isNotEmpty()) { "quantile of nothing" }
        val w = values.copyOf()
        w.sort()
        val virtual = (w.size - 1) * q
        val prev = floor(virtual).toInt()
        val gamma = virtual - prev
        val a = w[prev]
        val b = w[minOf(prev + 1, w.size - 1)]
        val diff = b - a
        return if (gamma >= 0.5) b - diff * (1 - gamma) else a + diff * gamma
    }
}
