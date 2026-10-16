package com.ikverse.signallab.data.futures

import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.binance.BinanceClient
import com.ikverse.signallab.data.binance.BinanceException
import org.json.JSONArray

/** One funding settlement: the rate longs paid shorts (negative: shorts paid longs) for the interval ending at [time]. */
data class FundingPoint(val time: Long, val rate: Double, val markPrice: Double)

/** Open interest at [time]: contracts outstanding and their value in USDT. */
data class OpenInterestPoint(val time: Long, val contracts: Double, val value: Double)

/** A perpetual's state right now. */
data class PremiumNow(val symbol: String, val markPrice: Double, val indexPrice: Double, val lastFundingRate: Double, val nextFundingTime: Long)

/**
 * Binance's USDⓈ-M futures market data, which needs no key. Used only for warnings and the market line, never to enter a
 * call (docs/strategy-research.md §1.10). Requests go through [binance], so they share its pacing and retries.
 */
class FuturesClient(private val binance: BinanceClient, private val host: String = DataConfig.FUTURES_HOST) {
    /** Up to [limit] funding settlements from [startTime], oldest first. A symbol with no perpetual gives an empty list. */
    suspend fun fundingHistory(symbol: String, startTime: Long, limit: Int = 1000): List<FundingPoint> = orEmptyIfNoPerpetual {
        binance.fetchFrom(host, "/fapi/v1/fundingRate", mapOf("symbol" to symbol, "startTime" to startTime.toString(), "limit" to limit.toString())) { text ->
            val arr = JSONArray(text)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                FundingPoint(o.getLong("fundingTime"), o.getString("fundingRate").toDouble(), o.optString("markPrice").toDoubleOrNull() ?: Double.NaN)
            }
        }
    }

    /** Open interest every [period] ("5m" to "1d") from [startTime], oldest first; Binance keeps 30 days of it. */
    suspend fun openInterestHistory(symbol: String, period: String, startTime: Long, limit: Int = 500): List<OpenInterestPoint> = orEmptyIfNoPerpetual {
        binance.fetchFrom(host, "/futures/data/openInterestHist",
            mapOf("symbol" to symbol, "period" to period, "startTime" to startTime.toString(), "limit" to limit.toString())) { text ->
            val arr = JSONArray(text)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                OpenInterestPoint(o.getLong("timestamp"), o.getString("sumOpenInterest").toDouble(), o.getString("sumOpenInterestValue").toDouble())
            }
        }
    }

    /** Every perpetual's mark price and funding right now. */
    suspend fun premiumIndex(): List<PremiumNow> =
        binance.fetchFrom(host, "/fapi/v1/premiumIndex", emptyMap()) { text ->
            val arr = JSONArray(text)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                PremiumNow(o.getString("symbol"), o.getString("markPrice").toDouble(), o.getString("indexPrice").toDouble(),
                    o.optString("lastFundingRate").toDoubleOrNull() ?: 0.0, o.optLong("nextFundingTime", 0))
            }
        }

    /** Binance answers HTTP 400 for a symbol with no perpetual; that is no data, not a failure. */
    private suspend fun <T> orEmptyIfNoPerpetual(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: BinanceException.Http) {
            if (e.code == 400) emptyList() else throw e
        }
}
