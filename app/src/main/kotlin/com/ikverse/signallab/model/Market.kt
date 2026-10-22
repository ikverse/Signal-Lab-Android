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
    /** What the futures and options markets say; null when none of it is stored. */
    val futures: FuturesContext? = null,
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

    /** The futures and options markets in one sentence; null when nothing is stored yet. */
    val futuresSentence: String? get() = futures?.sentence

    companion object {
        /** Funding above this per 8 hours (0.03%) is unusually high. */
        const val HOT_FUNDING = 0.0003

        fun of(btc: Candles?, funding: Double?, strategies: List<Strategy>, futures: FuturesContext? = null): MarketLine {
            if (btc == null || btc.size == 0) return MarketLine(null, null, null, funding, strategies.size, strategies.size, futures)
            val n = btc.size
            val avg = Indicators.sma(btc.close, EngineConfig.REGIME_MA_DAYS)[n - 1]
            val vs = if (avg.isNaN()) null else btc.close[n - 1] / avg - 1
            val allowed = if (vs != null && vs <= 0) strategies.count { !it.bullOnly } else strategies.size
            return MarketLine(btc.close[n - 1], vs, swingsOf(btc), funding, allowed, strategies.size, futures)
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

/**
 * Bitcoin's futures and options markets as Signal Lab reads them (docs/strategy-research.md §7): funding averaged over the
 * last 30 and 7 days, the nearest quarterly contract's premium over spot, and Deribit's implied volatility with its change
 * over a week. Rates are annualised fractions (0.10 = 10% a year). Any part may be missing.
 */
data class FuturesContext(
    val funding30d: Double?,
    val funding7d: Double?,
    val quarterlyPremium: Double?,
    val impliedVol: Double?,
    val impliedVolChange7d: Double?,
) {
    /**
     * Shorts have paid longs on average for the last 30 days. Every such stretch since 2018 was followed by a strong one to
     * three months for Bitcoin (K33, VanEck), though the sample is small and the method unpublished.
     */
    val hated: Boolean get() = funding30d != null && funding30d < 0

    /**
     * Leverage is overheated: longs pay well over the usual and the quarterly premium is high, as in the week before the
     * crash of 10 October 2025; or implied volatility jumped in a week.
     */
    val hot: Boolean
        get() = (funding7d != null && funding7d > HOT_FUNDING_YEAR && quarterlyPremium != null && quarterlyPremium > HOT_PREMIUM_YEAR) ||
            (impliedVolChange7d != null && impliedVolChange7d > VOL_JUMP)

    /**
     * What the heat means for a new call: blocked when funding and the quarterly premium are both overheated, half size when
     * implied volatility jumped, otherwise nothing.
     */
    val heat: com.ikverse.signallab.engine.eval.Heat
        get() = when {
            funding7d != null && funding7d > HOT_FUNDING_YEAR && quarterlyPremium != null && quarterlyPremium > HOT_PREMIUM_YEAR ->
                com.ikverse.signallab.engine.eval.Heat.BLOCK
            impliedVolChange7d != null && impliedVolChange7d > VOL_JUMP -> com.ikverse.signallab.engine.eval.Heat.HALF
            else -> com.ikverse.signallab.engine.eval.Heat.NONE
        }

    val sentence: String?
        get() {
            val parts = ArrayList<String>()
            funding30d?.let { f ->
                val rate = pctYear(f)
                parts.add(when {
                    f < 0 -> "Over 30 days shorts have paid longs ($rate a year), a sign that has come before strong months"
                    funding7d != null && funding7d > HOT_FUNDING_YEAR -> "Longs are paying a lot to stay long ($rate a year over 30 days)"
                    else -> "Funding is ordinary ($rate a year over 30 days)"
                })
            }
            quarterlyPremium?.let { parts.add("the quarterly futures premium is ${pctYear(it)} a year") }
            impliedVol?.let { v ->
                val change = impliedVolChange7d?.let { c -> if (c >= 0) ", up ${Math.round(c)} in a week" else ", down ${Math.round(-c)} in a week" } ?: ""
                parts.add("implied volatility is ${Math.round(v)}$change")
            }
            if (parts.isEmpty()) return null
            val text = parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
            return if (hot) "$text Leverage looks overheated." else text
        }

    companion object {
        /** Seven-day funding above this, annualised, is overheated. */
        const val HOT_FUNDING_YEAR = 0.20

        /** A quarterly premium above this, annualised, is overheated. */
        const val HOT_PREMIUM_YEAR = 0.15

        /** A rise in implied volatility of more than this many points in a week. */
        const val VOL_JUMP = 15.0

        /** Binance settles funding three times a day. */
        private const val SETTLEMENTS_PER_YEAR = 3 * 365

        private fun pctYear(f: Double): String = String.format(java.util.Locale.US, "%.1f%%", f * 100).replace('-', '−')

        /** The context as of [now] from the stored series; null when nothing is stored. */
        suspend fun read(store: com.ikverse.signallab.data.futures.DerivativesStore?, now: Long): FuturesContext? {
            store ?: return null
            val day = 86_400_000L
            val btc = com.ikverse.signallab.data.DataConfig.REGIME_COIN
            val funding = runCatching { store.funding(btc, now - 30 * day) }.getOrDefault(emptyList())
            fun meanSince(t: Long) = funding.filter { it.time >= t }.map { it.rate }.takeIf { it.size >= 3 }?.average()?.times(SETTLEMENTS_PER_YEAR)
            val premium = runCatching { store.basis(btc, "CURRENT_QUARTER", now - 3 * day) }.getOrDefault(emptyList()).lastOrNull()?.annualized
                ?.takeUnless { it.isNaN() }
            val vol = runCatching { store.dvol("BTC", now - 10 * day) }.getOrDefault(emptyList())
            val latest = vol.lastOrNull()
            val weekAgo = latest?.let { l -> vol.lastOrNull { it.time <= l.time - 7 * day } }
            val ctx = FuturesContext(
                funding30d = if (funding.size >= 30) meanSince(now - 30 * day) else null,
                funding7d = meanSince(now - 7 * day),
                quarterlyPremium = premium,
                impliedVol = latest?.close,
                impliedVolChange7d = if (latest != null && weekAgo != null) latest.close - weekAgo.close else null,
            )
            return if (listOf(ctx.funding30d, ctx.funding7d, ctx.quarterlyPremium, ctx.impliedVol).all { it == null }) null else ctx
        }
    }
}
