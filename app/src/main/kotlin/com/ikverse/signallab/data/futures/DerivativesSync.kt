package com.ikverse.signallab.data.futures

import com.ikverse.signallab.data.DataConfig

/**
 * Brings funding and open interest up to date for one coin. Funding is downloaded back to [DataConfig.FUNDING_FROM]
 * once and then forward; open interest only exists for the last [DataConfig.OPEN_INTEREST_DAYS] days, so it is fetched
 * forward from what is stored, and history builds up on the phone from the first run. [available] is false on Binance.US,
 * which has no futures market, and then nothing is asked.
 */
class DerivativesSync(
    private val client: FuturesClient,
    private val store: DerivativesStore,
    private val now: () -> Long,
    private val available: () -> Boolean,
    /** Deribit's volatility index; null leaves it out. Deribit is not Binance, so it is asked even on Binance.US. */
    private val deribit: DeribitClient? = null,
    /** Exchange announcements; null leaves them out. */
    private val notices: NoticesClient? = null,
) {
    /** The oldest start Binance accepts for its 30-day series, with a day's margin. */
    private fun thirtyDaysAgo() = now() - (DataConfig.OPEN_INTEREST_DAYS - 1) * DAY_MS

    /** New basis points stored for [pair]'s [contract], accumulated from the 30 days Binance serves. */
    suspend fun syncBasis(pair: String, contract: String): Int {
        if (!available()) return 0
        val from = maxOf(store.lastBasisTime(pair, contract)?.plus(1) ?: 0, thirtyDaysAgo())
        return store.insertBasis(pair, contract, client.basisHistory(pair, contract, DataConfig.FUTURES_DATA_PERIOD, from, OI_PAGE))
    }

    /** New points of every positioning ratio of [symbol], accumulated from the 30 days Binance serves. */
    suspend fun syncPositioning(symbol: String): Int {
        if (!available()) return 0
        var added = 0
        for (kind in Positioning.entries) {
            val from = maxOf(store.lastPositioningTime(symbol, kind)?.plus(1) ?: 0, thirtyDaysAgo())
            added += store.insertPositioning(symbol, kind, client.positioningHistory(kind, symbol, DataConfig.FUTURES_DATA_PERIOD, from, OI_PAGE))
        }
        return added
    }

    /** DVOL days of [currency] back to [DataConfig.DVOL_FROM] once, then forward; the last stored day is fetched again, as it may have been still forming. */
    suspend fun syncDvol(currency: String): Int {
        val d = deribit ?: return 0
        var from = store.lastDvolTime(currency) ?: DataConfig.DVOL_FROM
        var added = 0
        val end = now()
        while (from <= end) {
            // Deribit answers at most about a thousand points per request; a year of days is well inside it.
            val to = minOf(end, from + 365 * DAY_MS)
            val page = d.dvolDaily(currency, from, to)
            added += store.insertDvol(currency, page)
            from = to + 1
        }
        return added
    }

    /** New exchange notices stored. A source that cannot be reached is skipped; the other is still read. */
    suspend fun syncNotices(): Int {
        val n = notices ?: return 0
        var added = 0
        runCatching { n.binanceDelistings() }.getOrNull()?.let { added += store.insertNotices(it) }
        runCatching { n.upbitNotices() }.getOrNull()?.let { added += store.insertNotices(it) }
        return added
    }

    /** New funding settlements stored. */
    suspend fun syncFunding(symbol: String): Int {
        if (!available()) return 0
        var next = store.lastFundingTime(symbol)?.plus(1) ?: DataConfig.FUNDING_FROM
        var added = 0
        while (true) {
            val page = client.fundingHistory(symbol, next, FUNDING_PAGE)
            if (page.isEmpty()) break
            added += store.insertFunding(symbol, page)
            val last = page.last().time
            if (page.size < FUNDING_PAGE || last + 1 <= next) break
            next = last + 1
        }
        return added
    }

    /** New open-interest points stored for [period]. */
    suspend fun syncOpenInterest(symbol: String, period: String = "1h"): Int {
        if (!available()) return 0
        // Binance refuses a start older than its 30 days; a day's margin keeps the first request inside it.
        val oldest = now() - (DataConfig.OPEN_INTEREST_DAYS - 1) * DAY_MS
        var next = maxOf(store.lastOpenInterestTime(symbol, period)?.plus(1) ?: oldest, oldest)
        var added = 0
        while (true) {
            val page = client.openInterestHistory(symbol, period, next, OI_PAGE)
            if (page.isEmpty()) break
            added += store.insertOpenInterest(symbol, period, page)
            val last = page.last().time
            if (page.size < OI_PAGE || last + 1 <= next) break
            next = last + 1
        }
        return added
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        const val FUNDING_PAGE = 1000
        const val OI_PAGE = 500
    }
}
