package com.ikverse.signallab.data.history

import com.ikverse.signallab.data.CandleSync
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.binance.BinanceException
import com.ikverse.signallab.engine.Timeframe

/** How far bringing daily history up to date got: coins done, and the ones that failed with why. */
class DailyHistoryResult(val done: Int, val failures: Map<String, String>, val blocked: Boolean)

/**
 * Keeps every daily candle of each coin back to its listing, which the daily strategies and their cross-coin rankings
 * are judged on. The first run downloads a few pages per coin; after that each coin needs one request a day. A coin's
 * days before its listing are remembered by [CandleSync] as a known gap, so they are asked for once.
 */
class DailyHistory(private val sync: CandleSync) {
    /** Brings [symbols] up to date, one coin at a time; [onCoin] reports progress. Binance refusing the network stops it. */
    suspend fun ensure(symbols: List<String>, onCoin: (done: Int, symbol: String) -> Unit = { _, _ -> }): DailyHistoryResult {
        val failures = LinkedHashMap<String, String>()
        var done = 0
        for (symbol in symbols) {
            try {
                sync.syncCoin(symbol, Timeframe.D1, DataConfig.FULL_HISTORY_FROM)
                done++
                onCoin(done, symbol)
            } catch (e: BinanceException.Blocked) {
                failures[symbol] = e.message.orEmpty()
                return DailyHistoryResult(done, failures, blocked = true)
            } catch (e: BinanceException) {
                failures[symbol] = e.message.orEmpty()
            }
        }
        return DailyHistoryResult(done, failures, blocked = false)
    }
}
