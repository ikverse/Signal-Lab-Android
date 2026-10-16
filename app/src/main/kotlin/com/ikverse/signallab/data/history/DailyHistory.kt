package com.ikverse.signallab.data.history

import com.ikverse.signallab.data.CandleSync
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.binance.BinanceException
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.engine.Timeframe

/**
 * How far bringing daily history up to date got: coins done, the ones that failed with why, and for each coin the daily
 * candle that was still forming when it was asked, which is never stored but whose open is where a call enters or leaves.
 */
class DailyHistoryResult(
    val done: Int,
    val failures: Map<String, String>,
    val blocked: Boolean,
    val forming: Map<String, Kline> = emptyMap(),
)

/**
 * Keeps every daily candle of each coin back to its listing, which the daily strategies and their cross-coin rankings
 * are judged on. The first run downloads a few pages per coin; after that each coin needs one request a day. A coin's
 * days before its listing are remembered by [CandleSync] as a known gap, so they are asked for once.
 */
class DailyHistory(private val sync: CandleSync) {
    /** Brings [symbols] up to date, one coin at a time; [onCoin] reports progress. Binance refusing the network stops it. */
    suspend fun ensure(symbols: List<String>, onCoin: (done: Int, symbol: String) -> Unit = { _, _ -> }): DailyHistoryResult {
        val failures = LinkedHashMap<String, String>()
        val forming = HashMap<String, Kline>()
        var done = 0
        for (symbol in symbols) {
            try {
                sync.syncCoin(symbol, Timeframe.D1, DataConfig.FULL_HISTORY_FROM).forming?.let { forming[symbol] = it }
                done++
                onCoin(done, symbol)
            } catch (e: BinanceException.Blocked) {
                failures[symbol] = e.message.orEmpty()
                return DailyHistoryResult(done, failures, blocked = true, forming = forming)
            } catch (e: BinanceException) {
                failures[symbol] = e.message.orEmpty()
            }
        }
        return DailyHistoryResult(done, failures, blocked = false, forming = forming)
    }
}
