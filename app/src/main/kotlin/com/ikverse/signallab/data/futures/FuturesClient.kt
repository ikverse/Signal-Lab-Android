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
 * The gap between a futures contract and the index at [time]: [basisRate] as a fraction of the index, [annualized] the same
 * over a year for a dated contract (NaN where Binance gives none).
 */
data class BasisPoint(val time: Long, val futuresPrice: Double, val indexPrice: Double, val basisRate: Double, val annualized: Double)

/** Which positioning ratio Binance publishes. */
enum class Positioning(val path: String, val label: String) {
    /** Long against short positions of the biggest accounts, by position size. */
    TOP_POSITIONS("/futures/data/topLongShortPositionRatio", "top_positions"),

    /** Accounts long against accounts short, every account counted once. */
    ALL_ACCOUNTS("/futures/data/globalLongShortAccountRatio", "all_accounts"),

    /** Taker buy volume against taker sell volume. */
    TAKER_VOLUME("/futures/data/takerlongshortRatio", "taker_volume"),
}

/** A positioning ratio at [time]: [longSide] over [shortSide] (shares of accounts, or volumes), and their [ratio]. */
data class PositioningPoint(val time: Long, val longSide: Double, val shortSide: Double, val ratio: Double)

/**
 * Binance's USDⓈ-M futures market data, which needs no key. Used for the market line, context and vetoes, never to enter a
 * call on its own (docs/strategy-research.md §1.10 and §7). Requests go through [binance], so they share its pacing and retries.
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

    /** The basis of [pair]'s [contractType] (PERPETUAL, CURRENT_QUARTER or NEXT_QUARTER) every [period] from [startTime]; Binance keeps 30 days. */
    suspend fun basisHistory(pair: String, contractType: String, period: String, startTime: Long, limit: Int = 500): List<BasisPoint> = orEmptyIfNoPerpetual {
        binance.fetchFrom(host, "/futures/data/basis",
            mapOf("pair" to pair, "contractType" to contractType, "period" to period, "startTime" to startTime.toString(), "limit" to limit.toString())) { text ->
            val arr = JSONArray(text)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                BasisPoint(o.getLong("timestamp"), o.getString("futuresPrice").toDouble(), o.getString("indexPrice").toDouble(),
                    o.getString("basisRate").toDouble(), o.optString("annualizedBasisRate").toDoubleOrNull() ?: Double.NaN)
            }
        }
    }

    /** One positioning ratio of [symbol] every [period] from [startTime], oldest first; Binance keeps 30 days. */
    suspend fun positioningHistory(kind: Positioning, symbol: String, period: String, startTime: Long, limit: Int = 500): List<PositioningPoint> = orEmptyIfNoPerpetual {
        binance.fetchFrom(host, kind.path, mapOf("symbol" to symbol, "period" to period, "startTime" to startTime.toString(), "limit" to limit.toString())) { text ->
            val arr = JSONArray(text)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                if (kind == Positioning.TAKER_VOLUME) {
                    PositioningPoint(o.getLong("timestamp"), o.getString("buyVol").toDouble(), o.getString("sellVol").toDouble(), o.getString("buySellRatio").toDouble())
                } else {
                    PositioningPoint(o.getLong("timestamp"), o.getString("longAccount").toDouble(), o.getString("shortAccount").toDouble(), o.getString("longShortRatio").toDouble())
                }
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
