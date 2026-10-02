package com.ikverse.signallab.data.binance

import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Binance's public REST API. No account or key is involved: it reads prices and nothing else.
 *
 * It paces itself, waits out rate limits as Binance asks, retries what is worth retrying, and says
 * plainly when Binance refuses the network. [host] is read on every call so the setting can change.
 */
class BinanceClient(
    private val host: () -> String,
    private val http: OkHttpClient = defaultHttp(),
    private val localClock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : MarketData {
    @Volatile
    private var offsetMs = 0L

    /** Binance's clock minus the phone's, in milliseconds. Positive means the phone is behind. */
    val clockSkewMs: Long get() = offsetMs

    private val paceLock = Mutex()
    private var lastCallAt = 0L

    override fun nowMs(): Long = localClock() + offsetMs

    override suspend fun serverTime(): Long {
        val before = localClock()
        val body = get("/api/v3/time")
        val after = localClock()
        val server = parse(body) { JSONObject(it).getLong("serverTime") }
        offsetMs = server - (before + after) / 2
        return server
    }

    override suspend fun spotSymbols(): List<SpotSymbol> =
        parse(get("/api/v3/exchangeInfo", mapOf("permissions" to "SPOT"))) { text ->
            val arr = JSONObject(text).getJSONArray("symbols")
            List(arr.length()) {
                val s = arr.getJSONObject(it)
                SpotSymbol(s.getString("symbol"), s.getString("baseAsset"), s.getString("quoteAsset"), s.getString("status"))
            }
        }

    override suspend fun tickers24h(): List<Ticker24h> =
        parse(get("/api/v3/ticker/24hr", mapOf("type" to "MINI"))) { text ->
            val arr = JSONArray(text)
            List(arr.length()) {
                val t = arr.getJSONObject(it)
                Ticker24h(t.getString("symbol"), t.getString("lastPrice").toDouble(), t.getString("highPrice").toDouble(),
                    t.getString("lowPrice").toDouble(), t.getString("quoteVolume").toDouble())
            }
        }

    override suspend fun klines(symbol: String, tf: Timeframe, startTime: Long, limit: Int): List<Kline> =
        parse(get("/api/v3/klines", mapOf("symbol" to symbol, "interval" to tf.label,
            "startTime" to startTime.toString(), "limit" to limit.toString()))) { text ->
            val arr = JSONArray(text)
            List(arr.length()) {
                val k = arr.getJSONArray(it)
                Kline(k.getLong(0), k.getString(1).toDouble(), k.getString(2).toDouble(), k.getString(3).toDouble(),
                    k.getString(4).toDouble(), k.getString(5).toDouble(), k.getLong(6))
            }
        }

    private inline fun <T> parse(text: String, block: (String) -> T): T =
        try {
            block(text)
        } catch (e: JSONException) {
            throw BinanceException.BadResponse(text.take(120), e)
        } catch (e: NumberFormatException) {
            throw BinanceException.BadResponse(text.take(120), e)
        }

    /** One request, paced and retried. Returns the body of the first 200 answer. */
    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): String {
        val url = host().toHttpUrl().newBuilder().encodedPath(path).apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        var attempt = 0
        var lastFailure: BinanceException = BinanceException.Network(null)
        while (attempt < DataConfig.MAX_ATTEMPTS) {
            pace()
            val response = try {
                http.newCall(request).await()
            } catch (e: IOException) {
                lastFailure = BinanceException.Network(e)
                attempt++
                sleep(backoffMs(attempt))
                continue
            }
            response.use { r ->
                val used = r.header("X-MBX-USED-WEIGHT-1M")?.toIntOrNull() ?: 0
                val body = r.body.string()
                when {
                    r.code == 200 -> {
                        if (used > DataConfig.MAX_USED_WEIGHT) sleep(30_000)
                        return body
                    }
                    r.code == 451 -> throw BinanceException.Blocked()
                    r.code == 429 || r.code == 418 -> {
                        val wait = (r.header("Retry-After")?.toLongOrNull() ?: 60L) * 1000
                        lastFailure = BinanceException.RateLimited(wait)
                        attempt++
                        sleep(wait)
                    }
                    r.code >= 500 -> {
                        lastFailure = BinanceException.Http(r.code, body.take(200))
                        attempt++
                        sleep(backoffMs(attempt))
                    }
                    else -> throw BinanceException.Http(r.code, body.take(200))
                }
            }
        }
        throw lastFailure
    }

    private fun backoffMs(attempt: Int): Long = 500L shl (attempt - 1).coerceAtMost(6)

    /** Keeps calls [DataConfig.REQUEST_PAUSE_MS] apart, whichever coroutine makes them. */
    private suspend fun pace() = paceLock.withLock {
        val wait = lastCallAt + DataConfig.REQUEST_PAUSE_MS - localClock()
        if (wait > 0) sleep(wait)
        lastCallAt = localClock()
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
