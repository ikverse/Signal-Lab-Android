package com.ikverse.signallab.scan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.Alert
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.CandleSync
import com.ikverse.signallab.data.FakeMarket
import com.ikverse.signallab.data.RecordDatabase
import com.ikverse.signallab.data.SettingsStore
import com.ikverse.signallab.data.TradeLog
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.engine.CandleClock
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.Watchlist
import kotlinx.coroutines.Dispatchers
import java.util.zip.GZIPInputStream

val appContext: Context get() = ApplicationProvider.getApplicationContext()

/** The research candles (real Binance data), as the exchange would serve them. */
object GoldenData {
    private val cache = HashMap<Timeframe, Map<String, List<Kline>>>()

    @Synchronized
    fun klines(tf: Timeframe): Map<String, List<Kline>> = cache.getOrPut(tf) {
        val out = LinkedHashMap<String, MutableList<Kline>>()
        val stream = GoldenData::class.java.getResourceAsStream("/golden/candles_${tf.label}.csv.gz") ?: error("missing golden candles")
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val f = line.split(',')
                out.getOrPut(f[0]) { ArrayList() }.add(Kline(f[1].toLong(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[6].toDouble(), f[7].toLong()))
            }
        }
        out
    }

    fun candles(symbol: String, tf: Timeframe): Candles {
        val k = klines(tf).getValue(symbol)
        return Candles(symbol, tf, LongArray(k.size) { k[it].openTime }, DoubleArray(k.size) { k[it].open }, DoubleArray(k.size) { k[it].high },
            DoubleArray(k.size) { k[it].low }, DoubleArray(k.size) { k[it].close }, DoubleArray(k.size) { k[it].volume }, LongArray(k.size) { k[it].closeTime })
    }

    fun panel(tf: Timeframe): Map<String, Candles> = klines(tf).keys.associateWith { candles(it, tf) }
}

class RecordingSink : AlertSink {
    val delivered = ArrayList<Alert>()
    var calls = 0

    override suspend fun deliver(alerts: List<Alert>) {
        calls++
        delivered.addAll(alerts)
    }
}

/**
 * A scanner on empty in-memory stores, with a fake exchange that serves a source of candles as of a
 * chosen moment: everything that had closed, plus the candle that was forming, and nothing later.
 */
class ScanEnv(
    coins: List<String>,
    val tf: Timeframe,
    private val source: Map<Pair<String, Timeframe>, List<Kline>>,
) {
    val market = FakeMarket(0)
    val candles = CandleStore(appContext, name = null, io = Dispatchers.Unconfined)
    private val db = RecordDatabase(appContext, name = null)
    var logNow = 0L
    val log = TradeLog(db, clock = { logNow }, io = Dispatchers.Unconfined)
    val settings = SettingsStore(db, io = Dispatchers.Unconfined)
    val sink = RecordingSink()
    var lists = listOf(Watchlist(1, "L", coins, true))
    val sync = CandleSync(market, candles)
    val scanner = Scanner(market, sync, candles, log, settings, { lists }, sink)

    /** Series the exchange serves nothing for, however far the clock moves. */
    val hidden = HashSet<Pair<String, Timeframe>>()

    /** Moves the world to [now]: the exchange serves what existed then. */
    fun at(now: Long) {
        market.now = now
        logNow = now
        for ((key, all) in source) {
            val newest = CandleClock.lastClose(key.second, now) // the open time of the forming candle
            market.series[key] = if (key in hidden) emptyList() else all.filter { it.openTime <= newest }
        }
    }

    /** A moment [afterMs] after the close that ended the candle opening at [openTime]. */
    fun justAfterCloseOf(openTime: Long, afterMs: Long = 5_000) = openTime + tf.ms + afterMs
}
