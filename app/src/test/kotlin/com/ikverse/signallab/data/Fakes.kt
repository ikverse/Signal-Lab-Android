package com.ikverse.signallab.data

import com.ikverse.signallab.data.binance.BinanceException
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.data.binance.MarketData
import com.ikverse.signallab.data.binance.SpotSymbol
import com.ikverse.signallab.data.binance.Ticker24h
import com.ikverse.signallab.engine.Timeframe

/** A stand-in exchange: serves candles it was given, one page at a time, exactly as Binance pages them. */
class FakeMarket(var now: Long) : MarketData {
    val series = HashMap<Pair<String, Timeframe>, List<Kline>>()
    var symbols: List<SpotSymbol> = emptyList()
    var tickers: List<Ticker24h> = emptyList()
    val klineCalls = ArrayList<Triple<String, Timeframe, Long>>()
    var symbolCalls = 0

    /** Called at the start of every kline request, before it is answered: tests use it to hold a request or to act while it is in flight. */
    var onKlines: (suspend (String, Timeframe) -> Unit)? = null

    /** When set, the Nth kline request and every one after it fails. */
    var failKlinesFrom: Int? = null
    var failWith: BinanceException = BinanceException.Network(null)

    override fun nowMs() = now

    override suspend fun serverTime() = now

    override suspend fun spotSymbols(): List<SpotSymbol> {
        symbolCalls++
        return symbols
    }

    override suspend fun tickers24h() = tickers

    override suspend fun klines(symbol: String, tf: Timeframe, startTime: Long, limit: Int): List<Kline> {
        klineCalls.add(Triple(symbol, tf, startTime))
        onKlines?.invoke(symbol, tf)
        if (failKlinesFrom != null && klineCalls.size >= failKlinesFrom!!) throw failWith
        val all = series[symbol to tf] ?: return emptyList()
        return all.filter { it.openTime >= startTime }.take(limit)
    }

    fun requestsFor(symbol: String, tf: Timeframe) = klineCalls.count { it.first == symbol && it.second == tf }
}

/**
 * [count] consecutive candles of [tf] starting at [firstOpen]; the price drifts by [drift] a candle so no
 * two candles are alike, and each candle spans [spread] either side of its open and closes half a spread above it.
 */
fun candlesOf(tf: Timeframe, firstOpen: Long, count: Int, base: Double = 100.0, drift: Double = 0.01, spread: Double = 1.0): List<Kline> =
    List(count) { i ->
        val o = firstOpen + i * tf.ms
        val p = base + i * drift
        Kline(o, p, p + spread, p - spread, p + spread / 2, 10.0 + i, o + tf.ms - 1)
    }

const val DAY = 86_400_000L

/** A start time that falls on a day boundary, so every timeframe lines up. */
const val EPOCH_START = 1_704_067_200_000L // 1 Jan 2024 00:00 UTC

fun pair(base: String, volume: Double, high: Double = 2.0, low: Double = 1.0, status: String = "TRADING") =
    Triple(SpotSymbol("${base}USDT", base, "USDT", status), Ticker24h("${base}USDT", (high + low) / 2, high, low, volume), base)
