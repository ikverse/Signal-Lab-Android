package com.ikverse.signallab.engine

/**
 * Two candlestick patterns, written to give exactly the answers of TA-Lib's `CDLHARAMI` and
 * `CDLHIKKAKE`, the library the published study of 400 coins used. A test holds them to TA-Lib's own
 * output on every real candle in the test data.
 *
 * Both look only at the candle they flag and the ones before it, so a flag never changes when more
 * candles arrive. Only the bullish side is a signal.
 */
object Candlesticks {
    /** TA-Lib averages the last ten real bodies to decide what counts as long or short. */
    private const val BODY_AVERAGE_PERIOD = 10

    private fun body(c: Candles, i: Int) = kotlin.math.abs(c.close[i] - c.open[i])

    /**
     * Bullish harami: a long black candle followed by a short candle whose body sits inside (or touching an edge of)
     * the first one's body. "Long" is a body bigger than the average of the ten before the first candle;
     * "short" is one no bigger than the average of the ten before the second. Flagged on the second candle.
     *
     * The running totals are updated the way TA-Lib updates them, so the two agree to the last digit.
     */
    fun bullishHarami(c: Candles): BooleanArray {
        val flags = BooleanArray(c.size)
        val period = BODY_AVERAGE_PERIOD
        val start = period + 1 // TA-Lib's lookback
        if (c.size <= start) return flags
        var longTotal = 0.0
        var shortTotal = 0.0
        var longTrailing = start - 1 - period
        var shortTrailing = start - period
        for (k in longTrailing until start - 1) longTotal += body(c, k)
        for (k in shortTrailing until start) shortTotal += body(c, k)
        for (i in start until c.size) {
            val firstIsLong = body(c, i - 1) > longTotal / period
            val secondIsShort = body(c, i) <= shortTotal / period
            if (firstIsLong && secondIsShort) {
                // TA-Lib answers 100 when the body is strictly inside and 50 when it only touches an edge of the first body (in
                // crypto the second candle usually opens exactly where the first closed, so touching is the common case).
                // Both are positive for a black first candle, and both are signals.
                val insideTop = maxOf(c.close[i], c.open[i]) <= maxOf(c.close[i - 1], c.open[i - 1])
                val insideBottom = minOf(c.close[i], c.open[i]) >= minOf(c.close[i - 1], c.open[i - 1])
                val firstIsBlack = c.close[i - 1] < c.open[i - 1]
                if (insideTop && insideBottom && firstIsBlack) flags[i] = true
            }
            longTotal += body(c, i - 1) - body(c, longTrailing)
            shortTotal += body(c, i) - body(c, shortTrailing)
            longTrailing++
            shortTrailing++
        }
        return flags
    }

    /**
     * Bullish hikkake: an inside candle (high lower and low higher than the one before it), followed by a
     * candle that breaks *down* out of it (lower high and lower low), a false break. It is flagged on that
     * breaking candle, and again on the first candle within three after it that closes back above the high of
     * the inside candle (TA-Lib reports 100 for the first and 200 for the confirmation; both are positive).
     */
    fun bullishHikkake(c: Candles): BooleanArray {
        val flags = BooleanArray(c.size)
        val first = 5 // TA-Lib's lookback; its state is warmed up from three candles earlier
        if (c.size <= first) return flags
        var patternIdx = 0
        var patternResult = 0
        for (i in first - 3 until c.size) {
            val inside = c.high[i - 1] < c.high[i - 2] && c.low[i - 1] > c.low[i - 2]
            val breaksDown = c.high[i] < c.high[i - 1] && c.low[i] < c.low[i - 1]
            val breaksUp = c.high[i] > c.high[i - 1] && c.low[i] > c.low[i - 1]
            if (inside && (breaksDown || breaksUp)) {
                patternResult = if (c.high[i] < c.high[i - 1]) 100 else -100
                patternIdx = i
                if (i >= first && patternResult > 0) flags[i] = true
            } else if (i <= patternIdx + 3 &&
                ((patternResult > 0 && c.close[i] > c.high[patternIdx - 1]) || (patternResult < 0 && c.close[i] < c.low[patternIdx - 1]))
            ) {
                if (i >= first && patternResult > 0) flags[i] = true
                patternIdx = 0
            }
        }
        return flags
    }
}
