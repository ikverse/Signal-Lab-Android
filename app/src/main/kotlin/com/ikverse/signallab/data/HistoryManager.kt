package com.ikverse.signallab.data

import com.ikverse.signallab.data.binance.BinanceException
import com.ikverse.signallab.data.binance.MarketData
import com.ikverse.signallab.engine.CoinFilter
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HistoryProgress(
    val total: Int = 0,
    val ready: Int = 0,
    val current: String? = null,
    val failures: Map<String, String> = emptyMap(),
    val running: Boolean = false,
)

/**
 * Downloads the history of every coin in the active watchlists, on the charts each coin is watched on, one coin at
 * a time at a polite pace, and reports how far along it is. Each chart keeps only as much as its patterns need
 * ([DataConfig.historyDays]). It resumes after the app is killed because progress is not remembered anywhere: it is
 * read from what is stored, and a coin whose newest candles are current is ready.
 */
class HistoryManager(
    private val sync: CandleSync,
    private val store: CandleStore,
    private val market: MarketData,
) {
    private val state = MutableStateFlow(HistoryProgress())
    val progress: StateFlow<HistoryProgress> = state.asStateFlow()
    private val running = Mutex()

    /** A coin is ready when each wanted chart's newest stored candle is at most two candles behind. A stablecoin never needs history. */
    suspend fun isReady(symbol: String, timeframes: Set<Timeframe> = Timeframe.LEGACY_DEFAULT): Boolean {
        if (store.coin(symbol)?.stable == true) return true
        val now = market.nowMs()
        return timeframes.all { tf ->
            val last = store.lastOpen(symbol, tf)
            last != null && last >= (now / tf.ms) * tf.ms - tf.ms - 2 * tf.ms
        }
    }

    /** Brings every analysable coin in [symbols] up to date on [timeframes]. */
    suspend fun ensureHistory(symbols: Collection<String>, timeframes: Set<Timeframe> = Timeframe.LEGACY_DEFAULT) =
        ensureHistory(symbols.associateWith { timeframes })

    /**
     * Brings every analysable coin up to date on the charts it is watched on. Calling it again with a changed set is
     * how a list being switched on starts downloads; the caller cancels the previous call first.
     */
    suspend fun ensureHistory(needs: Map<String, Set<Timeframe>>) = running.withLock {
        val coins = store.analysable(needs.keys).toList()
        val failures = LinkedHashMap<String, String>()
        val alreadyReady = coins.filterTo(HashSet()) { isReady(it, needs.getValue(it)) }
        var ready = alreadyReady.size
        state.value = HistoryProgress(total = coins.size, ready = ready, running = true)
        try {
            for (symbol in coins) {
                if (symbol in alreadyReady) continue
                val wanted = needs.getValue(symbol)
                // Something else (the scanner, bringing a chart up to date) may have made it ready while the others downloaded.
                if (isReady(symbol, wanted)) {
                    ready++
                    continue
                }
                state.value = HistoryProgress(coins.size, ready, symbol, failures.toMap(), true)
                try {
                    downloadCoin(symbol, wanted)
                    ready++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BinanceException.Blocked) {
                    // Every other coin would fail the same way; say so once and stop.
                    for (rest in coins.dropWhile { it != symbol }) failures[rest] = e.message ?: "Binance refused the connection"
                    break
                } catch (e: Exception) {
                    failures[symbol] = e.message ?: e.javaClass.simpleName
                }
            }
        } finally {
            state.value = HistoryProgress(coins.size, ready, null, failures.toMap(), false)
        }
    }

    /**
     * The daily chart first when it is wanted, because it is the smallest and tells whether the coin is a stablecoin before the
     * heavy downloads; then the rest from the longest candles to the shortest. Last, once, when Binance listed the coin.
     */
    private suspend fun downloadCoin(symbol: String, timeframes: Set<Timeframe>) {
        for (tf in timeframes.sortedByDescending { it.minutes }) {
            sync.syncCoin(symbol, tf, market.nowMs() - DataConfig.historyDays(tf) * DAY_MS)
            if (tf == Timeframe.D1 && CoinFilter.looksStable(store.dailyCloses(symbol, CoinFilter.STABLE_DAYS))) {
                store.markStable(symbol)
                return
            }
        }
        rememberListing(symbol)
    }

    /** One request, once per coin: its first daily candle is the day it was listed. */
    private suspend fun rememberListing(symbol: String) {
        if (store.listedAt(symbol) != null) return
        val first = market.klines(symbol, Timeframe.D1, 0, 1).firstOrNull() ?: return
        store.setListedAt(symbol, first.openTime)
    }

    companion object {
        const val DAY_MS = 86_400_000L
    }
}
