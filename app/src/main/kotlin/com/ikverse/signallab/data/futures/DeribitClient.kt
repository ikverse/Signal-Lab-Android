package com.ikverse.signallab.data.futures

import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.binance.BinanceClient
import org.json.JSONObject

/** One day of Deribit's implied-volatility index for a coin: open, high, low and close of the index, in volatility points. */
data class DvolPoint(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double)

/**
 * Deribit's public market data, which needs no key: the DVOL index, the options market's 30-day implied volatility for
 * Bitcoin and Ether (docs/strategy-research.md §7). Requests go through [binance] so they share one polite pace.
 */
class DeribitClient(private val binance: BinanceClient, private val host: String = DataConfig.DERIBIT_HOST) {
    /** Daily DVOL candles of [currency] ("BTC" or "ETH") from [startTime] to [endTime], oldest first. */
    suspend fun dvolDaily(currency: String, startTime: Long, endTime: Long): List<DvolPoint> =
        binance.fetchFrom(host, "/api/v2/public/get_volatility_index_data",
            mapOf("currency" to currency, "resolution" to "1D", "start_timestamp" to startTime.toString(), "end_timestamp" to endTime.toString())) { text ->
            val data = JSONObject(text).getJSONObject("result").getJSONArray("data")
            List(data.length()) {
                val row = data.getJSONArray(it)
                DvolPoint(row.getLong(0), row.getDouble(1), row.getDouble(2), row.getDouble(3), row.getDouble(4))
            }.sortedBy { it.time }
        }
}
