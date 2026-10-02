package com.ikverse.signallab.scan

import com.ikverse.signallab.data.DataConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class FeedState { IDLE, CONNECTING, LIVE, RETRYING }

/**
 * Live prices over Binance's WebSocket, only while something on screen wants them. A mini-ticker
 * stream costs about 90 MB an hour for 150 coins, so it is never left running in the background:
 * scanning works from candles and does not need it.
 *
 * Whoever wants prices calls [watch] and closes what it returns when it stops caring. The stream opens
 * for the union of what is wanted, reopens when that changes or after 23 hours (Binance cuts a
 * connection at 24), waits longer after each failure, and closes a short while after the last
 * watcher leaves, so a screen rotation does not cost a reconnect.
 */
class PriceFeed(
    private val client: OkHttpClient,
    /** The stream host, such as `wss://stream.binance.com:9443`. */
    private val streamBase: () -> String,
    private val scope: CoroutineScope,
    private val graceMs: Long = DataConfig.PRICE_FEED_GRACE_MS,
    private val maxAgeMs: Long = DataConfig.PRICE_FEED_MAX_AGE_MS,
    private val backoffBaseMs: Long = 1_000,
    private val backoffMaxMs: Long = 60_000,
) {
    private val priceMap = MutableStateFlow<Map<String, Double>>(emptyMap())
    private val feedState = MutableStateFlow(FeedState.IDLE)
    private val wanted = MutableStateFlow<Set<String>>(emptySet())

    private val lock = Any()
    private val watchers = HashMap<Long, Set<String>>()
    private var nextId = 0L
    private var supervisor: Job? = null

    /** The last price of every coin seen, as Binance quotes it. */
    val prices: StateFlow<Map<String, Double>> = priceMap.asStateFlow()

    val state: StateFlow<FeedState> = feedState.asStateFlow()

    private val streams = client.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()

    /** Asks for live prices of [symbols] until the returned handle is closed. */
    fun watch(symbols: Set<String>): AutoCloseable {
        val id: Long
        synchronized(lock) {
            id = nextId++
            watchers[id] = symbols
            wanted.value = watchers.values.flatten().toSet()
            if (supervisor?.isActive != true) supervisor = scope.launch { supervise() }
        }
        return AutoCloseable {
            synchronized(lock) {
                watchers.remove(id)
                wanted.value = watchers.values.flatten().toSet()
            }
        }
    }

    private sealed interface Event {
        class Message(val text: String) : Event
        class Closed(val clean: Boolean) : Event
        data object Changed : Event
        data object Abandoned : Event
        data object Aged : Event
    }

    private suspend fun supervise() {
        var failures = 0
        while (true) {
            val symbols = wanted.value
            if (symbols.isEmpty()) {
                // Nobody is looking. Give a screen rotation time to come back before letting go.
                val someoneCame = withTimeoutOrNull(graceMs) { wanted.first { it.isNotEmpty() } } != null
                if (!someoneCame && retire()) return
                continue
            }
            val outcome = hold(symbols)
            when (outcome) {
                Outcome.ABANDONED -> if (retire()) return
                Outcome.CHANGED -> {
                    failures = 0
                    delay(DEBOUNCE_MS)
                }
                Outcome.AGED -> failures = 0
                Outcome.DROPPED_AFTER_DATA -> {
                    failures = 0
                    feedState.value = FeedState.RETRYING
                    delay(backoff(failures))
                }
                Outcome.FAILED -> {
                    feedState.value = FeedState.RETRYING
                    delay(backoff(failures++))
                }
            }
        }
    }

    private enum class Outcome { CHANGED, ABANDONED, AGED, DROPPED_AFTER_DATA, FAILED }

    /**
     * Ends the supervisor if nobody wants prices. Decided under the lock that [watch] takes, so a watcher
     * arriving at this instant either is seen here or starts a new supervisor; it is never left unserved.
     */
    private fun retire(): Boolean = synchronized(lock) {
        if (wanted.value.isNotEmpty()) return false
        feedState.value = FeedState.IDLE
        supervisor = null
        true
    }

    private fun backoff(failures: Int): Long = minOf(backoffMaxMs, backoffBaseMs shl failures.coerceAtMost(10))

    /** Opens the stream for [symbols] and reads it until it ends, the wanted set changes, or it is old. */
    private suspend fun hold(symbols: Set<String>): Outcome {
        val events = Channel<Event>(Channel.UNLIMITED)
        val url = streamBase() + "/stream?streams=" + symbols.sorted().joinToString("/") { it.lowercase() + "@miniTicker" }
        feedState.value = FeedState.CONNECTING
        val socket: WebSocket = streams.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(Event.Message(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                events.trySend(Event.Closed(clean = true))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(Event.Closed(clean = true))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(Event.Closed(clean = false))
            }
        })
        val watcher = scope.launch {
            while (true) {
                val now = wanted.first { it != symbols }
                if (now.isNotEmpty()) {
                    events.trySend(Event.Changed)
                    return@launch
                }
                // Everyone left. Hold the connection for the grace period: a rotation will be back before it ends.
                val back = withTimeoutOrNull(graceMs) { wanted.first { it.isNotEmpty() } }
                if (back == null) {
                    events.trySend(Event.Abandoned)
                    return@launch
                }
                if (back != symbols) {
                    events.trySend(Event.Changed)
                    return@launch
                }
            }
        }
        val timer = scope.launch { delay(maxAgeMs); events.trySend(Event.Aged) }
        var gotData = false
        try {
            for (e in events) {
                when (e) {
                    is Event.Message -> {
                        if (!gotData) {
                            gotData = true
                            feedState.value = FeedState.LIVE
                        }
                        read(e.text)
                    }
                    is Event.Closed -> return if (gotData) Outcome.DROPPED_AFTER_DATA else Outcome.FAILED
                    Event.Changed -> return Outcome.CHANGED
                    Event.Abandoned -> return Outcome.ABANDONED
                    Event.Aged -> return Outcome.AGED
                }
            }
            return Outcome.FAILED
        } catch (e: CancellationException) {
            throw e
        } finally {
            watcher.cancel()
            timer.cancel()
            socket.cancel()
            events.close()
        }
    }

    /** One frame of the combined stream. A frame that is not a ticker, or is malformed, is ignored. */
    private fun read(text: String) {
        try {
            val data = JSONObject(text).getJSONObject("data")
            val symbol = data.getString("s")
            val price = data.getString("c").toDouble()
            if (price > 0 && price.isFinite()) priceMap.value = priceMap.value + (symbol to price)
        } catch (e: Exception) {
            // Ignore it: one bad frame must not take the feed down.
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
