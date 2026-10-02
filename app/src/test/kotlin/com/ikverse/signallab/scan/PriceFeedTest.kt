package com.ikverse.signallab.scan

import com.ikverse.signallab.data.DataConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The price stream, against a real WebSocket server on this machine, in real time with short waits. */
class PriceFeedTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun frame(symbol: String, price: String) =
        """{"stream":"${symbol.lowercase()}@miniTicker","data":{"e":"24hrMiniTicker","s":"$symbol","c":"$price","o":"1","h":"2","l":"0.5","v":"10","q":"10"}}"""

    private fun feed(grace: Long = 60_000, maxAge: Long = 60_000, backoff: Long = 20) =
        PriceFeed(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, scope, grace, maxAge, backoff, 200)

    /** An upgrade that sends [frames] and then, if [hangUp], closes the connection from the server's side. */
    private fun serve(vararg frames: String, hangUp: Boolean = false) {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                for (f in frames) webSocket.send(f)
                if (hangUp) webSocket.close(1001, "going away")
            }
        }).build())
    }

    private fun <T : Any> eventually(timeoutMs: Long = 8_000, check: () -> T?): T = runBlocking {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            check()?.let { return@runBlocking it }
            if (System.currentTimeMillis() > end) error("did not happen within $timeoutMs ms")
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    @Test
    fun itOpensTheStreamForTheWantedCoinsAndReadsTheirPrices() {
        serve(frame("BTCUSDT", "67000.5"), frame("ETHUSDT", "3500.25"))
        val feed = feed()
        feed.watch(setOf("ETHUSDT", "BTCUSDT"))
        eventually { feed.prices.value.takeIf { it.size == 2 } }
        assertEquals(67000.5, feed.prices.value["BTCUSDT"])
        assertEquals(3500.25, feed.prices.value["ETHUSDT"])
        assertEquals(FeedState.LIVE, feed.state.value)
        val url = server.takeRequest().url
        assertEquals("/stream", url.encodedPath)
        assertEquals("btcusdt@miniTicker/ethusdt@miniTicker", url.queryParameter("streams"))
    }

    @Test
    fun aFrameThatIsNotATickerOrIsBrokenIsIgnored() {
        serve("garbage", "{}", """{"data":{}}""", """{"data":{"s":"BTCUSDT","c":"not a number"}}""", """{"data":{"s":"BTCUSDT","c":"-5"}}""", frame("BTCUSDT", "42"))
        val feed = feed()
        feed.watch(setOf("BTCUSDT"))
        eventually { feed.prices.value["BTCUSDT"] }
        assertEquals(42.0, feed.prices.value["BTCUSDT"])
        assertEquals(FeedState.LIVE, feed.state.value)
    }

    @Test
    fun whenTheServerHangsUpItReconnectsAndKeepsGoing() {
        serve(frame("BTCUSDT", "100"), hangUp = true)
        serve(frame("BTCUSDT", "200"))
        val feed = feed()
        feed.watch(setOf("BTCUSDT"))
        eventually { feed.prices.value["BTCUSDT"]?.takeIf { it == 200.0 } }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRefusedConnectionIsRetriedWithGrowingWaits() {
        val failures = 3
        repeat(failures) { server.enqueue(MockResponse.Builder().code(503).build()) }
        serve(frame("BTCUSDT", "7"))
        val feed = feed(backoff = 60)
        val began = System.currentTimeMillis()
        feed.watch(setOf("BTCUSDT"))
        eventually { feed.prices.value["BTCUSDT"] }
        // 60 + 120 + 200 (capped) milliseconds of waiting at the least.
        assertTrue(System.currentTimeMillis() - began >= 60 + 120 + 200, "reconnected too eagerly")
        assertEquals(failures + 1, server.requestCount)
    }

    @Test
    fun itLetsGoAShortWhileAfterTheLastWatcherLeaves() {
        serve(frame("BTCUSDT", "1"))
        val feed = feed(grace = 150)
        val handle = feed.watch(setOf("BTCUSDT"))
        eventually { feed.prices.value["BTCUSDT"] }
        handle.close()
        eventually { feed.state.value.takeIf { it == FeedState.IDLE } }
        assertEquals(1, server.requestCount)
        // Wanting prices again starts a fresh connection.
        serve(frame("BTCUSDT", "2"))
        feed.watch(setOf("BTCUSDT"))
        eventually { feed.prices.value["BTCUSDT"]?.takeIf { it == 2.0 } }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aScreenRotationInsideTheGracePeriodDoesNotReconnect() {
        serve(frame("BTCUSDT", "1"))
        val feed = feed(grace = 1_000)
        val first = feed.watch(setOf("BTCUSDT"))
        eventually { feed.prices.value["BTCUSDT"] }
        first.close()
        Thread.sleep(100)
        feed.watch(setOf("BTCUSDT"))
        Thread.sleep(400)
        assertEquals(1, server.requestCount)
        assertEquals(FeedState.LIVE, feed.state.value)
    }

    @Test
    fun aChangedSetOfCoinsReopensTheStreamForTheUnion() {
        serve(frame("BTCUSDT", "1"))
        serve(frame("BTCUSDT", "11"), frame("ETHUSDT", "22"))
        val feed = feed()
        feed.watch(setOf("BTCUSDT"))
        eventually { feed.prices.value["BTCUSDT"] }
        feed.watch(setOf("ETHUSDT"))
        eventually { feed.prices.value["ETHUSDT"] }
        server.takeRequest()
        assertEquals("btcusdt@miniTicker/ethusdt@miniTicker", server.takeRequest().url.queryParameter("streams"))
    }

    @Test
    fun itReconnectsBeforeTheExchangeCutsAnOldConnection() {
        serve(frame("BTCUSDT", "1"))
        serve(frame("BTCUSDT", "2"))
        val feed = feed(maxAge = 250)
        feed.watch(setOf("BTCUSDT"))
        eventually { feed.prices.value["BTCUSDT"]?.takeIf { it == 2.0 } }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theStreamHostFollowsTheChosenExchange() {
        assertEquals(DataConfig.STREAM_COM, DataConfig.streamFor(DataConfig.HOST_COM))
        assertEquals(DataConfig.STREAM_US, DataConfig.streamFor(DataConfig.HOST_US))
    }
}
