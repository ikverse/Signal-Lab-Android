package com.ikverse.signallab.data

import com.ikverse.signallab.data.binance.MarketData
import com.ikverse.signallab.engine.CoinFilter

/**
 * Every USDT pair Binance trades, with its 24-hour volume, for the watchlist picker. Refreshed at most
 * once a day. Coins that disappear from Binance's list are marked delisted (their history is kept).
 */
class UniverseRepository(
    private val market: MarketData,
    private val store: CandleStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Refreshes the pair list if it is more than a day old, or when [force]d. Returns whether it refreshed. */
    suspend fun refreshIfStale(force: Boolean = false): Boolean {
        val last = store.lastPairListRefresh()
        if (!force && last != null && clock() - last < DataConfig.UNIVERSE_REFRESH_MS) return false
        val pairs = market.spotSymbols().filter { it.quote == DataConfig.QUOTE && it.status == "TRADING" }
        val allBases = pairs.mapTo(HashSet()) { it.base }
        val tickers = market.tickers24h().associateBy { it.symbol }
        val now = clock()
        val rows = pairs.mapNotNull { p ->
            val t = tickers[p.symbol] ?: return@mapNotNull null
            CoinRow(
                symbol = p.symbol, base = p.base, status = p.status,
                offered = CoinFilter.offered(p.base, allBases, t.high, t.low), stable = false, delisted = false,
                quoteVolume = t.quoteVolume, high24 = t.high, low24 = t.low, seenAt = now,
            )
        }
        store.replaceCoins(rows)
        return true
    }

    /** What the picker shows for [query], biggest 24-hour volume first. An empty query lists the biggest. */
    suspend fun search(query: String, limit: Int = 50): List<CoinRow> = store.offeredCoins(query, limit)

    suspend fun top(n: Int): List<CoinRow> = store.offeredCoins("", n)

    /** Whether the picker offers [symbol]: a tradable pair that is not a stablecoin, wrapped or leveraged. */
    suspend fun isOffered(symbol: String): Boolean =
        store.coin(symbol)?.let { it.offered && !it.stable && !it.delisted && it.status == "TRADING" } ?: false
}
