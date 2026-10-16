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
) {
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
