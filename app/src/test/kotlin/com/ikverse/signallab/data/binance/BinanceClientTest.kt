package com.ikverse.signallab.data.binance

import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BinanceClientTest {
    private lateinit var server: MockWebServer
    private val sleeps = ArrayList<Long>()
    private var clock = 1_000_000L

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun client() = BinanceClient(
        host = { server.url("/").toString().trimEnd('/') },
        localClock = { clock },
        sleep = { sleeps.add(it); clock += it },
    )

    private fun ok(body: String, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(200).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

    private fun status(code: Int, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).body("{}").apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

    private val klineRow = """[1704067200000,"100.5","101.25","99.75","100.875","12.5",1704070799999,"1260.0",10,"6.0","600.0","0"]"""

    @Test
    fun parsesCandlesAndAsksForTheRightPage() = runTest {
        server.enqueue(ok("[$klineRow]"))
        val k = client().klines("BTCUSDT", Timeframe.H1, 1_704_067_200_000L, 500).single()
        assertEquals(Kline(1704067200000L, 100.5, 101.25, 99.75, 100.875, 12.5, 1704070799999L), k)
        val url = server.takeRequest().url
        assertEquals("/api/v3/klines", url.encodedPath)
        assertEquals("BTCUSDT", url.queryParameter("symbol"))
        assertEquals("1h", url.queryParameter("interval"))
        assertEquals("1704067200000", url.queryParameter("startTime"))
        assertEquals("500", url.queryParameter("limit"))
    }

    @Test
    fun parsesThePairListAndTheTickers() = runTest {
        server.enqueue(ok("""{"symbols":[{"symbol":"BTCUSDT","status":"TRADING","baseAsset":"BTC","quoteAsset":"USDT"},
            {"symbol":"OLDUSDT","status":"BREAK","baseAsset":"OLD","quoteAsset":"USDT"}]}"""))
        server.enqueue(ok("""[{"symbol":"BTCUSDT","openPrice":"1","highPrice":"90000.5","lowPrice":"85000.25","lastPrice":"88000","volume":"5","quoteVolume":"440000000.5","count":785501}]"""))
        val c = client()
        assertEquals(listOf(SpotSymbol("BTCUSDT", "BTC", "USDT", "TRADING"), SpotSymbol("OLDUSDT", "OLD", "USDT", "BREAK")), c.spotSymbols())
        assertEquals(Ticker24h("BTCUSDT", 88000.0, 90000.5, 85000.25, 440000000.5, open = 1.0, trades = 785_501), c.tickers24h().single())
        assertEquals("SPOT", server.takeRequest().url.queryParameter("permissions"))
        assertEquals("MINI", server.takeRequest().url.queryParameter("type"))
    }

    @Test
    fun aTickerWithoutAnOpeningPriceOrTradeCountStillReadsAndSaysZeroForThem() = runTest {
        server.enqueue(ok("""[{"symbol":"BTCUSDT","highPrice":"2","lowPrice":"1","lastPrice":"1.5","quoteVolume":"10"}]"""))
        assertEquals(Ticker24h("BTCUSDT", 1.5, 2.0, 1.0, 10.0, open = 0.0, trades = 0), client().tickers24h().single())
    }

    @Test
    fun aRollingWindowAsksInBatchesOfAHundredAndReadsTheChangeOverIt() = runTest {
        server.enqueue(ok("""[{"symbol":"AAAUSDT","openPrice":"100","lastPrice":"110"},{"symbol":"BBBUSDT","openPrice":"50","lastPrice":"45"},
            {"symbol":"ZEROUSDT","openPrice":"0","lastPrice":"3"}]"""))
        server.enqueue(ok("""[{"symbol":"LASTUSDT","openPrice":"2","lastPrice":"3"}]"""))
        val symbols = listOf("AAAUSDT", "BBBUSDT", "ZEROUSDT") + (1..97).map { "X${it}USDT" } + "LASTUSDT" // 101 in all
        val changes = client().rollingChange(symbols, "1h")
        assertEquals(setOf("AAAUSDT", "BBBUSDT", "LASTUSDT"), changes.keys, "a symbol with no opening price has no change")
        assertEquals(0.1, changes.getValue("AAAUSDT"), 1e-9)
        assertEquals(-0.1, changes.getValue("BBBUSDT"), 1e-9)
        assertEquals(0.5, changes.getValue("LASTUSDT"), 1e-9)
        val first = server.takeRequest().url
        val second = server.takeRequest().url
        assertEquals("/api/v3/ticker", first.encodedPath)
        assertEquals("1h", first.queryParameter("windowSize")); assertEquals("MINI", first.queryParameter("type"))
        assertEquals(100, org.json.JSONArray(first.queryParameter("symbols")).length())
        assertEquals(1, org.json.JSONArray(second.queryParameter("symbols")).length())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun noSymbolsMeansNoRequest() = runTest {
        assertEquals(emptyMap(), client().rollingChange(emptyList(), "7d"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theServerClockOffsetDecidesWhatIsNow() = runTest {
        server.enqueue(ok("""{"serverTime":${clock + 7_000}}"""))
        val c = client()
        c.serverTime()
        assertEquals(7_000L, c.clockSkewMs)
        assertEquals(clock + 7_000, c.nowMs())
    }

    @Test
    fun aRateLimitIsWaitedOutForAsLongAsBinanceSays() = runTest {
        server.enqueue(status(429, "Retry-After" to "7"))
        server.enqueue(status(418, "Retry-After" to "3"))
        server.enqueue(ok("[]"))
        assertEquals(emptyList(), client().klines("BTCUSDT", Timeframe.D1, 0))
        assertTrue(7_000L in sleeps && 3_000L in sleeps, "waits were $sleeps")
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aServerErrorIsRetriedWithBackoffThenSucceeds() = runTest {
        server.enqueue(status(503))
        server.enqueue(status(500))
        server.enqueue(ok("[]"))
        client().klines("BTCUSDT", Timeframe.D1, 0)
        assertEquals(listOf(500L, 1000L), sleeps.filter { it >= 500 && it != DataConfig.REQUEST_PAUSE_MS })
        assertEquals(3, server.requestCount)
    }

    @Test
    fun givesUpAfterTheAttemptLimitAndSaysWhy() = runTest {
        repeat(DataConfig.MAX_ATTEMPTS + 2) { server.enqueue(status(503)) }
        val e = assertFailsWith<BinanceException.Http> { client().klines("BTCUSDT", Timeframe.D1, 0) }
        assertEquals(503, e.code)
        assertEquals(DataConfig.MAX_ATTEMPTS, server.requestCount)
    }

    @Test
    fun blockedNetworkIsReportedImmediatelyWithoutRetrying() = runTest {
        server.enqueue(status(451))
        assertFailsWith<BinanceException.Blocked> { client().klines("BTCUSDT", Timeframe.D1, 0) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun otherClientErrorsAreNotRetried() = runTest {
        server.enqueue(status(400))
        assertEquals(400, assertFailsWith<BinanceException.Http> { client().klines("NOPE", Timeframe.D1, 0) }.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anUnreadableAnswerIsABadResponseNotACrash() = runTest {
        server.enqueue(ok("<html>maintenance</html>"))
        assertFailsWith<BinanceException.BadResponse> { client().klines("BTCUSDT", Timeframe.D1, 0) }
        server.enqueue(ok("""[[1,"not a number","1","1","1","1",2]]"""))
        assertFailsWith<BinanceException.BadResponse> { client().klines("BTCUSDT", Timeframe.D1, 0) }
    }

    @Test
    fun anUnreachableServerIsANetworkErrorAfterRetries() = runTest {
        val dead = BinanceClient(host = { "http://127.0.0.1:1" }, localClock = { clock }, sleep = { sleeps.add(it); clock += it })
        assertFailsWith<BinanceException.Network> { dead.klines("BTCUSDT", Timeframe.D1, 0) }
        assertTrue(sleeps.count { it >= 500 } >= DataConfig.MAX_ATTEMPTS - 1, "backoff waits were $sleeps")
    }

    @Test
    fun heavyUsedWeightMakesItBackOffBeforeTheNextCall() = runTest {
        server.enqueue(ok("[]", "X-MBX-USED-WEIGHT-1M" to (DataConfig.MAX_USED_WEIGHT + 1).toString()))
        client().klines("BTCUSDT", Timeframe.D1, 0)
        assertTrue(30_000L in sleeps, "waits were $sleeps")
    }

    @Test
    fun callsAreKeptApart() = runTest {
        server.enqueue(ok("[]"))
        server.enqueue(ok("[]"))
        val c = client()
        c.klines("BTCUSDT", Timeframe.D1, 0)
        sleeps.clear()
        c.klines("BTCUSDT", Timeframe.D1, 0)
        assertTrue(sleeps.any { it in 1..DataConfig.REQUEST_PAUSE_MS }, "expected a pacing wait, waits were $sleeps")
    }
}
