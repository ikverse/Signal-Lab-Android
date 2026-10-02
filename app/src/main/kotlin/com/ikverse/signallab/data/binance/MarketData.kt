package com.ikverse.signallab.data.binance

import com.ikverse.signallab.engine.Timeframe

/** One candle as Binance reports it. */
data class Kline(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
)

data class SpotSymbol(val symbol: String, val base: String, val quote: String, val status: String)

data class Ticker24h(val symbol: String, val lastPrice: Double, val high: Double, val low: Double, val quoteVolume: Double)

/** Everything the app needs from an exchange. Binance implements it; tests substitute a fake. */
interface MarketData {
    /** Binance's clock in milliseconds. */
    suspend fun serverTime(): Long

    suspend fun spotSymbols(): List<SpotSymbol>

    suspend fun tickers24h(): List<Ticker24h>

    /** Up to [limit] candles starting at [startTime], oldest first. The last one may still be forming. */
    suspend fun klines(symbol: String, tf: Timeframe, startTime: Long, limit: Int = 1000): List<Kline>

    /**
     * The current time by Binance's clock, from the offset measured the last time [serverTime] was
     * called. A candle is closed when its close time is before this, not before the phone's own clock.
     */
    fun nowMs(): Long
}

sealed class BinanceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** HTTP 451: Binance refuses this network or country. Choosing another host in settings is the way out. */
    class Blocked : BinanceException("Binance is not available from this network")

    class RateLimited(val retryAfterMs: Long) : BinanceException("Binance is rate limiting this phone")

    class Http(val code: Int, val body: String) : BinanceException("Binance answered HTTP $code")

    class Network(cause: Throwable?) : BinanceException("Could not reach Binance", cause)

    class BadResponse(what: String, cause: Throwable? = null) : BinanceException("Unexpected answer from Binance: $what", cause)
}
