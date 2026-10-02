package com.ikverse.signallab.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** The golden data: real Binance candles and what the research version produced from them. */
object Golden {
    private fun resource(name: String): InputStream =
        Golden::class.java.getResourceAsStream("/golden/$name") ?: error("missing golden file $name")

    private val panels = HashMap<Timeframe, Map<String, Candles>>()

    @Synchronized
    fun panel(tf: Timeframe): Map<String, Candles> = panels.getOrPut(tf) {
        val rows = LinkedHashMap<String, MutableList<List<String>>>()
        GZIPInputStream(resource("candles_${tf.label}.csv.gz")).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val f = line.split(',')
                rows.getOrPut(f[0]) { ArrayList() }.add(f)
            }
        }
        rows.mapValues { (sym, r) ->
            Candles(sym, tf, LongArray(r.size) { r[it][1].toLong() }, DoubleArray(r.size) { r[it][2].toDouble() },
                DoubleArray(r.size) { r[it][3].toDouble() }, DoubleArray(r.size) { r[it][4].toDouble() },
                DoubleArray(r.size) { r[it][5].toDouble() }, DoubleArray(r.size) { r[it][6].toDouble() },
                LongArray(r.size) { r[it][7].toLong() })
        }
    }

    val expected: JSONObject by lazy {
        JSONObject(GZIPInputStream(resource("expected.json.gz")).bufferedReader().readText())
    }

    /** Each coin's BTC-regime series on [tf], computed the way the scorecard does. */
    fun regimes(tf: Timeframe): Map<String, IntArray> {
        val btc = panel(Timeframe.D1).getValue("BTCUSDT")
        return panel(tf).mapValues { PaperTrading.regimeSeries(btc, it.value.closeTime) }
    }
}

fun JSONArray.doubles(): DoubleArray = DoubleArray(length()) { if (isNull(it)) Double.NaN else getDouble(it) }

fun JSONArray.ints(): IntArray = IntArray(length()) { getInt(it) }

fun JSONArray.longs(): LongArray = LongArray(length()) { getLong(it) }

fun JSONObject.keyList(): List<String> = keys().asSequence().toList()

/** Agreement to a relative tolerance; two NaNs agree. */
fun closeTo(expected: Double, actual: Double, tol: Double = 1e-9): Boolean =
    (expected.isNaN() && actual.isNaN()) || expected == actual ||
        abs(expected - actual) <= tol * maxOf(1.0, abs(expected), abs(actual))

fun assertClose(expected: Double, actual: Double, what: String, tol: Double = 1e-9) {
    check(closeTo(expected, actual, tol)) { "$what: expected $expected, got $actual" }
}

fun assertSeries(expected: DoubleArray, actual: DoubleArray, what: String, tol: Double = 1e-9) {
    check(expected.size == actual.size) { "$what: expected ${expected.size} values, got ${actual.size}" }
    for (i in expected.indices) {
        check(closeTo(expected[i], actual[i], tol)) { "$what[$i]: expected ${expected[i]}, got ${actual[i]}" }
    }
}

/** Synthetic candles for invariant tests: a random walk with occasional jumps from a fixed start. */
object Synth {
    const val START_MS = 1_704_067_200_000L // 1 Jan 2024 00:00 UTC, a Monday

    private fun gaussian(seed: Long, i: Long): Double {
        val u1 = maxOf(Rng.unit(seed, i, 1), 1e-12)
        val u2 = Rng.unit(seed, i, 2)
        return sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    fun candles(n: Int, tf: Timeframe, seed: Long, symbol: String = "TESTUSDT"): Candles {
        val t = LongArray(n) { START_MS + it * tf.ms }
        val close = DoubleArray(n)
        var level = 100.0
        for (i in 0 until n) {
            var r = 0.0005 + 0.02 * gaussian(seed, i.toLong())
            if (Rng.unit(seed, i.toLong(), 3) < 0.02) r *= 5 // a few big moves, so signals fire
            level *= exp(r)
            close[i] = level
        }
        val open = DoubleArray(n) { if (it == 0) 100.0 else close[it - 1] }
        val high = DoubleArray(n) { maxOf(open[it], close[it]) * (1 + abs(0.006 * gaussian(seed, it.toLong() + 10_000))) }
        val low = DoubleArray(n) { minOf(open[it], close[it]) * (1 - abs(0.006 * gaussian(seed, it.toLong() + 20_000))) }
        val volume = DoubleArray(n) { 1 + Rng.unit(seed, it.toLong(), 4) }
        return Candles(symbol, tf, t, open, high, low, close, volume, LongArray(n) { t[it] + tf.ms - 1 })
    }

    fun panel(coins: Int, n: Int, tf: Timeframe): Map<String, Candles> =
        (0 until coins).associate { i -> "C${i}USDT" to candles(n, tf, seed = i.toLong() + 1, symbol = "C${i}USDT") }
}

/** Candles cut at the first [k] bars, as a live run would have had them. */
fun Candles.head(k: Int): Candles = slice(0, k)
