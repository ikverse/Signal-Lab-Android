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
 * Downloads the history of every coin in the active watchlists, one coin at a time at a polite pace,
 * and reports how far along it is. It resumes after the app is killed because progress is not
 * remembered anywhere: it is read from what is stored, and a coin whose newest candle is current is ready.
 */
class HistoryManager(
    private val sync: CandleSync,
    private val store: CandleStore,
    private val market: MarketData,
    private val windowDays: Int = DataConfig.LIVE_HISTORY_DAYS,
) {
    private val state = MutableStateFlow(HistoryProgress())
    val progress: StateFlow<HistoryProgress> = state.asStateFlow()
    private val running = Mutex()

    /** A coin is ready when each timeframe's newest stored candle is at most two candles behind. A stablecoin never needs history. */
    suspend fun isReady(symbol: String): Boolean {
        if (store.coin(symbol)?.stable == true) return true
        val now = market.nowMs()
        return Timeframe.entries.all { tf ->
            val last = store.lastOpen(symbol, tf)
            last != null && last >= (now / tf.ms) * tf.ms - tf.ms - 2 * tf.ms
        }
    }

    /**
     * Brings every analysable coin in [symbols] up to date. Calling it again with a changed set is how
     * a list being switched on starts downloads; the caller cancels the previous call first.
     */
    suspend fun ensureHistory(symbols: Collection<String>) = running.withLock {
        val coins = store.analysable(symbols).toList()
        val failures = LinkedHashMap<String, String>()
        var ready = coins.count { isReady(it) }
        state.value = HistoryProgress(total = coins.size, ready = ready, running = true)
        try {
            for (symbol in coins) {
                if (isReady(symbol)) continue
                state.value = HistoryProgress(coins.size, ready, symbol, failures.toMap(), true)
                try {
                    downloadCoin(symbol)
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

    /** Daily first, because it is the smallest and tells whether the coin is a stablecoin before the heavy hourly download. */
    private suspend fun downloadCoin(symbol: String) {
        val since = market.nowMs() - windowDays * DAY_MS
        sync.syncCoin(symbol, Timeframe.D1, since)
        if (CoinFilter.looksStable(store.dailyCloses(symbol, CoinFilter.STABLE_DAYS))) {
            store.markStable(symbol)
            return
        }
        sync.syncCoin(symbol, Timeframe.H4, since)
        sync.syncCoin(symbol, Timeframe.H1, since)
    }

    companion object {
        const val DAY_MS = 86_400_000L
    }
}
