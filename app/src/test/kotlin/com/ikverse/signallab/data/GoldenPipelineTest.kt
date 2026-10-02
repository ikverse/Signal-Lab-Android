package com.ikverse.signallab.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.binance.BinanceClient
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Signals
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole path a candle takes, on real data: Binance's JSON (served by a fake server, as text with
 * the number formatting Binance uses) → the real HTTP client → paging and the closed-candle rule →
 * SQLite → the engine's candle type → the signals. The signals that come out must equal, bar for bar,
 * what the research version produced from the same candles. If anything between Binance and the
 * engine loses a digit, drops a candle or reorders one, this fails.
 */
@RunWith(RobolectricTestRunner::class)
class GoldenPipelineTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun resource(name: String) = GoldenPipelineTest::class.java.getResourceAsStream("/golden/$name") ?: error("missing golden file $name")

    private class Row(val symbol: String, val open: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double, val close: Long)

    private fun golden(tf: Timeframe): Map<String, List<Row>> {
        val out = LinkedHashMap<String, MutableList<Row>>()
        GZIPInputStream(resource("candles_${tf.label}.csv.gz")).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val f = line.split(',')
                out.getOrPut(f[0]) { ArrayList() }.add(Row(f[0], f[1].toLong(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[6].toDouble(), f[7].toLong()))
            }
        }
        return out
    }

    /** A server that answers /klines exactly as Binance does: text numbers, a start time, a page limit. */
    private fun exchange(rows: Map<Pair<String, String>, List<Row>>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.url
            val series = rows[url.queryParameter("symbol")!! to url.queryParameter("interval")!!] ?: return MockResponse.Builder().body("[]").build()
            val start = url.queryParameter("startTime")!!.toLong()
            val limit = url.queryParameter("limit")!!.toInt()
            val body = series.filter { it.open >= start }.take(limit).joinToString(",", "[", "]") {
                """[${it.open},"${it.o}","${it.h}","${it.l}","${it.c}","${it.v}",${it.close},"0",0,"0","0","0"]"""
            }
            return MockResponse.Builder().code(200).body(body).build()
        }
    }

    @Test
    fun candlesAndSignalsSurviveTheWholePathUnchanged() = runTest {
        val expected = JSONObject(GZIPInputStream(resource("expected.json.gz")).bufferedReader().readText())
        val farFuture = 4_000_000_000_000L
        val store = CandleStore(context, name = null, io = Dispatchers.Unconfined)
        var checkedSignals = 0

        for (tf in listOf(Timeframe.D1, Timeframe.H4, Timeframe.H1)) {
            val series = golden(tf)
            MockWebServer().use { server ->
                server.dispatcher = exchange(series.entries.associate { (sym, r) -> (sym to tf.label) to r })
                server.start()
                val client = BinanceClient(host = { server.url("/").toString().trimEnd('/') }, localClock = { farFuture }, sleep = {})
                val sync = CandleSync(client, store)
                for ((sym, rows) in series) sync.syncCoin(sym, tf, since = rows.first().open)
            }

            // 1. Every candle comes back with exactly the digits it went in with.
            val panel = LinkedHashMap<String, Candles>()
            for ((sym, rows) in series) {
                val c = assertNotNull(store.window(sym, tf), "$sym ${tf.label}: nothing stored")
                assertEquals(rows.size, c.size, "$sym ${tf.label}: candle count")
                for ((i, r) in rows.withIndex()) {
                    assertEquals(r.open, c.t[i], "$sym ${tf.label}[$i] open time")
                    assertEquals(r.close, c.closeTime[i], "$sym ${tf.label}[$i] close time")
                    assertEquals(r.o.toRawBits(), c.open[i].toRawBits(), "$sym ${tf.label}[$i] open")
                    assertEquals(r.h.toRawBits(), c.high[i].toRawBits(), "$sym ${tf.label}[$i] high")
                    assertEquals(r.l.toRawBits(), c.low[i].toRawBits(), "$sym ${tf.label}[$i] low")
                    assertEquals(r.c.toRawBits(), c.close[i].toRawBits(), "$sym ${tf.label}[$i] close")
                    assertEquals(r.v.toRawBits(), c.volume[i].toRawBits(), "$sym ${tf.label}[$i] volume")
                }
                panel[sym] = c
            }

            // 2. The engine, fed from the database, fires on exactly the bars the research version did.
            val want = expected.getJSONObject("signals").getJSONObject(tf.label)
            val got = Signals.compute(panel).entries.associate { it.key.name to it.value }
            // The app also runs patterns the research did not have, and no longer runs 1-4 week momentum on 1h charts.
            val gone = want.keys().asSequence().toSet() - got.keys
            assertEquals(if (tf == Timeframe.H1) want.keys().asSequence().filter { it.startsWith("tsmom") }.toSet() else emptySet(), gone, "${tf.label}: research variants missing")
            for (name in want.keys()) {
                if (name in gone) continue
                val flags = want.getJSONObject(name).getJSONObject("flags")
                for (sym in flags.keys()) {
                    val arr = flags.getJSONArray(sym)
                    val expectedBars = IntArray(arr.length()) { arr.getInt(it) }
                    val have = got.getValue(name).getValue(sym).let { f -> f.indices.filter { f[it] }.toIntArray() }
                    assertTrue(expectedBars.contentEquals(have), "${tf.label} $name $sym: signals differ after the database round trip")
                    checkedSignals += expectedBars.size
                }
            }
        }
        assertTrue(checkedSignals > 10_000, "only $checkedSignals signals were compared")
    }
}
