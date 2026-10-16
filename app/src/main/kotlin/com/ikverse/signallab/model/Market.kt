package com.ikverse.signallab.model

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.strategy.Strategy
import kotlin.math.sqrt

/** How the market stands, from Bitcoin's daily candles and the latest funding rate. */
data class MarketLine(
    val btcPrice: Double?,
    /** Bitcoin's close against its 200-day average, as a fraction; null before 200 days exist. */
    val vsAverage: Double?,
    /** "calm", "normal" or "stormy": the last 30 days' swings against the past year's; null with too little history. */
    val swings: String?,
    /** Bitcoin's latest 8-hour funding rate; null when there is none (Binance.US, or not fetched yet). */
    val funding: Double?,
    val allowed: Int,
    val total: Int,
) {
    val above: Boolean? get() = vsAverage?.let { it > 0 }

    /** The market in one sentence. */
    val sentence: String
        get() {
            val trend = when (above) {
                true -> "Bitcoin is above its 200-day average"
                false -> "Bitcoin is below its 200-day average"
                null -> "Bitcoin's 200-day average is not known yet"
            }
            val swing = swings?.let { ", swings are $it" } ?: ""
            val futures = funding?.let { f ->
                when {
                    f > HOT_FUNDING -> " and futures traders are paying up to be long"
                    f < 0 -> " and futures traders are paying to be short"
                    else -> " and futures traders are neutral"
                }
            } ?: ""
            return "$trend$swing$futures."
        }

    val allowedSentence: String
        get() = if (allowed == total) "All $total strategies may fire." else "$allowed of $total strategies may fire; bull-market ones wait."

    companion object {
        /** Funding above this per 8 hours (0.03%) is unusually high. */
        const val HOT_FUNDING = 0.0003

        fun of(btc: Candles?, funding: Double?, strategies: List<Strategy>): MarketLine {
            if (btc == null || btc.size == 0) return MarketLine(null, null, null, funding, strategies.size, strategies.size)
            val n = btc.size
            val avg = Indicators.sma(btc.close, EngineConfig.REGIME_MA_DAYS)[n - 1]
            val vs = if (avg.isNaN()) null else btc.close[n - 1] / avg - 1
            val allowed = if (vs != null && vs <= 0) strategies.count { !it.bullOnly } else strategies.size
            return MarketLine(btc.close[n - 1], vs, swingsOf(btc), funding, allowed, strategies.size)
        }

        /** The last 30 days' daily swings against the 30-day swings of every day of the past year: bottom third calm, top third stormy. */
        fun swingsOf(c: Candles): String? {
            val r = Indicators.pctReturn(c.close, 1)
            fun sd(end: Int): Double? {
                if (end < 30) return null
                val w = (end - 29..end).map { r[it] }
                if (w.any { it.isNaN() }) return null
                val m = w.average()
                return sqrt(w.sumOf { (it - m) * (it - m) } / w.size)
            }
            val now = sd(c.size - 1) ?: return null
            val year = (maxOf(30, c.size - 365) until c.size).mapNotNull { sd(it) }.sorted()
            if (year.size < 90) return null
            val low = year[year.size / 3]
            val high = year[2 * year.size / 3]
            return when {
                now <= low -> "calm"
                now >= high -> "stormy"
                else -> "normal"
            }
        }
    }
}
