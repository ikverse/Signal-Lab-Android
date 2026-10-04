package com.ikverse.signallab.data

import com.ikverse.signallab.data.binance.MarketData
import com.ikverse.signallab.engine.CoinFilter
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** How the picker ranks the coins it offers. */
enum class PickerSource { VOLUME, GAINERS, LOSERS, ACTIVE, VOLATILE, NEW }

/** Over how long a gainer or loser is measured. [binance] is Binance's own name for the window. */
enum class MoveWindow(val binance: String) { H1("1h"), H24("24h"), D7("7d") }

/** A coin the picker offers, and the number it was ranked by: the change (gainers, losers), the range (volatile), the trades (active), the listing time (new). */
data class Offer(val coin: CoinRow, val value: Double?)

/** How far the lookup of listing days has got. [failed] says why it stopped early. */
data class ListingLookup(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val failed: String? = null)

/**
 * Every USDT pair Binance trades, with its 24-hour volume, for the watchlist picker. The full list is refreshed at most
 * once a day; the day's figures behind gainers, losers and trade counts when the picker opens, if they are over five minutes
 * old. Coins that disappear from Binance's list are marked delisted (their history is kept).
 */
class UniverseRepository(
    private val market: MarketData,
    private val store: CandleStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lookupLock = Mutex()
    private val lookup = MutableStateFlow(ListingLookup())

    /** Progress of [lookUpListings]; idle when none is running. */
    val listingLookup: StateFlow<ListingLookup> = lookup.asStateFlow()

    private val rolling = HashMap<MoveWindow, Pair<Long, Map<String, Double>>>()

    /** Refreshes the pair list if it is more than [maxAgeMs] old, or when [force]d. Returns whether it refreshed. */
    suspend fun refreshIfStale(force: Boolean = false, maxAgeMs: Long = DataConfig.UNIVERSE_REFRESH_MS): Boolean {
        val last = store.lastPairListRefresh()
        if (!force && last != null && clock() - last < maxAgeMs) return false
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
                change24 = if (t.open > 0) t.lastPrice / t.open - 1 else 0.0, trades24 = t.trades,
            )
        }
        store.replaceCoins(rows)
        synchronized(rolling) { rolling.clear() }
        return true
    }

    /** What the picker shows for [query], biggest 24-hour volume first. An empty query lists the biggest. */
    suspend fun search(query: String, limit: Int = 50): List<CoinRow> = store.offeredCoins(query, limit)

    suspend fun top(n: Int): List<CoinRow> = store.offeredCoins("", n)

    /**
     * The coins the picker offers for [query], ranked by [source]. Gainers and losers over an hour or a week ask Binance
     * (kept for two minutes); everything else comes from what is stored. A listing time that is not known yet keeps a coin
     * out of [PickerSource.NEW] until [lookUpListings] has found it.
     */
    suspend fun offers(query: String, source: PickerSource, window: MoveWindow = MoveWindow.H24, limit: Int = 50): List<Offer> {
        if (source == PickerSource.VOLUME) return store.offeredCoins(query, limit).map { Offer(it, null) }
        val all = store.offeredCoins(query, Int.MAX_VALUE)
        val liquid = all.filter { it.quoteVolume >= DataConfig.MIN_MOVER_VOLUME }
        val ranked = when (source) {
            PickerSource.GAINERS, PickerSource.LOSERS -> {
                val changes = if (window == MoveWindow.H24) liquid.associate { it.symbol to it.change24 } else rollingChange(window, liquid)
                val moved = liquid.mapNotNull { c -> changes[c.symbol]?.let { Offer(c, it) } }
                if (source == PickerSource.GAINERS) moved.filter { it.value!! > 0 }.sortedByDescending { it.value }
                else moved.filter { it.value!! < 0 }.sortedBy { it.value }
            }
            PickerSource.ACTIVE -> all.filter { it.trades24 > 0 }.sortedByDescending { it.trades24 }.map { Offer(it, it.trades24.toDouble()) }
            PickerSource.VOLATILE -> liquid.filter { it.low24 > 0 }.map { Offer(it, it.high24 / it.low24 - 1) }.sortedByDescending { it.value }
            PickerSource.NEW -> {
                val cutoff = clock() - DataConfig.NEW_COIN_DAYS * DAY_MS
                all.filter { c -> c.listedAt?.let { it >= cutoff } == true }.sortedByDescending { it.listedAt }.map { Offer(it, it.listedAt!!.toDouble()) }
            }
            PickerSource.VOLUME -> emptyList()
        }
        return ranked.take(limit)
    }

    private suspend fun rollingChange(window: MoveWindow, coins: List<CoinRow>): Map<String, Double> {
        synchronized(rolling) { rolling[window] }?.let { (at, map) -> if (clock() - at < DataConfig.ROLLING_CACHE_MS) return map }
        val fresh = market.rollingChange(coins.map { it.symbol }, window.binance)
        synchronized(rolling) { rolling[window] = clock() to fresh }
        return fresh
    }

    /** How many offered coins have no listing day stored yet, so [PickerSource.NEW] is still incomplete. */
    suspend fun missingListings(): Int = store.offeredWithoutListing().size

    /**
     * Finds when each offered coin was listed (its first daily candle, one request each, a few at a time) and keeps it, so it
     * is only ever asked once per coin. Does nothing if a lookup is already running. [listingLookup] shows how far it has got.
     */
    suspend fun lookUpListings() {
        if (!lookupLock.tryLock()) return
        try {
            val todo = store.offeredWithoutListing()
            if (todo.isEmpty()) {
                lookup.value = ListingLookup()
                return
            }
            lookup.value = ListingLookup(running = true, done = 0, total = todo.size)
            val done = AtomicInteger()
            val gate = Semaphore(DataConfig.LISTING_LOOKUPS_AT_ONCE)
            try {
                coroutineScope {
                    for (symbol in todo) launch {
                        gate.withPermit {
                            market.klines(symbol, Timeframe.D1, 0, 1).firstOrNull()?.let { store.setListedAt(symbol, it.openTime) }
                        }
                        lookup.update { it.copy(done = done.incrementAndGet()) }
                    }
                }
                lookup.value = ListingLookup(running = false, done = todo.size, total = todo.size)
            } catch (e: CancellationException) {
                lookup.value = ListingLookup()
                throw e
            } catch (e: Exception) {
                lookup.value = ListingLookup(running = false, done = done.get(), total = todo.size, failed = e.message ?: "The lookup did not finish.")
            }
        } finally {
            lookupLock.unlock()
        }
    }

    /** Whether the picker offers [symbol]: a tradable pair that is not a stablecoin, wrapped or leveraged. */
    suspend fun isOffered(symbol: String): Boolean =
        store.coin(symbol)?.let { it.offered && !it.stable && !it.delisted && it.status == "TRADING" } ?: false

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
