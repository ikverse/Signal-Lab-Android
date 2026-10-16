package com.ikverse.signallab.engine.strategy

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Indicators

/**
 * Reasons not to enter, whatever the strategy (docs/strategy-research.md §1.10). A pump reverses within the hour, and
 * abnormal volume predicts lower returns in crypto, so a signal on such a candle is skipped, in the backtest and live
 * alike.
 */
object Vetoes {
    /** Volume this many times its prior 30-candle median... */
    const val VOLUME_TIMES = 5.0

    /** ...on a candle whose range is this many average true ranges. */
    const val RANGE_ATR = 3.0

    /** Why a signal on candle [i] of [c] is skipped, or null when it may be taken. [atr] is the coin's ATR series. */
    fun blocked(c: Candles, i: Int, atr: DoubleArray): String? {
        if (i < 30 || atr[i].isNaN()) return null
        val usual = Indicators.quantile(DoubleArray(30) { c.volume[i - 30 + it] }, 0.5)
        if (usual > 0 && c.volume[i] > VOLUME_TIMES * usual && c.high[i] - c.low[i] > RANGE_ATR * atr[i]) {
            return "a pump: volume ${String.format(java.util.Locale.US, "%.0f", c.volume[i] / usual)}× its usual on a wide candle"
        }
        return null
    }
}
