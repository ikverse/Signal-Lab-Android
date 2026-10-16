package com.ikverse.signallab.data.futures

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.binance.BinanceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DerivativesTest {
    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val day = 86_400_000L
    private val now = 1_760_000_000_000L

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private val hostUrl get() = server.url("/").toString().trimEnd('/')

    private fun client() = FuturesClient(BinanceClient(host = { "https://unused.invalid" }, sleep = {}), host = hostUrl)

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun fundingPage(from: Long, count: Int) = (0 until count).joinToString(",", "[", "]") { i ->
        """{"symbol":"BTCUSDT","fundingTime":${from + i * 8 * 3_600_000L},"fundingRate":"0.0001","markPrice":"60000.0"}"""
    }

    private fun store() = DerivativesStore(context, name = null, io = Dispatchers.Unconfined)

    @Test
    fun parsesFundingAndOpenInterestFromTheFuturesHost() = runTest {
        server.enqueue(ok("""[{"symbol":"BTCUSDT","fundingTime":1700000000000,"fundingRate":"-0.00025","markPrice":""}]"""))
        val f = client().fundingHistory("BTCUSDT", 1_699_000_000_000L).single()
        assertEquals(1_700_000_000_000L, f.time)
        assertEquals(-0.00025, f.rate)
        assertTrue(f.markPrice.isNaN(), "an empty mark price reads as unknown")
        val req = server.takeRequest().url
        assertEquals("/fapi/v1/fundingRate", req.encodedPath)
        assertEquals("1699000000000", req.queryParameter("startTime"))

        server.enqueue(ok("""[{"symbol":"BTCUSDT","sumOpenInterest":"80000.5","sumOpenInterestValue":"5200000000.0","timestamp":1700003600000}]"""))
        val oi = client().openInterestHistory("BTCUSDT", "1h", 1_700_000_000_000L).single()
        assertEquals(OpenInterestPoint(1_700_003_600_000L, 80000.5, 5.2e9), oi)
        assertEquals("/futures/data/openInterestHist", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aCoinWithNoPerpetualHasNoDataRatherThanAnError() = runTest {
        server.enqueue(MockResponse.Builder().code(400).body("""{"code":-1121,"msg":"Invalid symbol."}""").build())
        assertTrue(client().fundingHistory("NOPEUSDT", 0).isEmpty())
    }

    @Test
    fun fundingIsPagedBackToTheStartOnceAndThenFetchedForward() = runTest {
        val s = store()
        val sync = DerivativesSync(client(), s, now = { now }, available = { true })
        val start = 1_567_296_000_000L
        server.enqueue(ok(fundingPage(start, DerivativesSync.FUNDING_PAGE)))
        server.enqueue(ok(fundingPage(start + DerivativesSync.FUNDING_PAGE * 8 * 3_600_000L, 3)))
        assertEquals(DerivativesSync.FUNDING_PAGE + 3, sync.syncFunding("BTCUSDT"))
        assertEquals(start.toString(), server.takeRequest().url.queryParameter("startTime"))
        server.takeRequest()
        val last = s.lastFundingTime("BTCUSDT")!!
        server.enqueue(ok("[]"))
        assertEquals(0, sync.syncFunding("BTCUSDT"))
        assertEquals((last + 1).toString(), server.takeRequest().url.queryParameter("startTime"), "the next run starts after what is stored")
    }

    @Test
    fun openInterestNeverAsksBeyondBinancesThirtyDays() = runTest {
        val s = store()
        val sync = DerivativesSync(client(), s, now = { now }, available = { true })
        server.enqueue(ok("[]"))
        sync.syncOpenInterest("BTCUSDT")
        val asked = server.takeRequest().url.queryParameter("startTime")!!.toLong()
        assertTrue(asked >= now - 30 * day && asked <= now - 28 * day, "asked from $asked")
    }

    @Test
    fun withoutAFuturesMarketNothingIsAsked() = runTest {
        val sync = DerivativesSync(client(), store(), now = { now }, available = { false })
        assertEquals(0, sync.syncFunding("BTCUSDT"))
        assertEquals(0, sync.syncOpenInterest("BTCUSDT"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun storedPointsReadBackInOrderAndAreNotDuplicated() = runTest {
        val s = store()
        val pts = listOf(FundingPoint(20, 0.1, 1.0), FundingPoint(10, 0.2, Double.NaN))
        assertEquals(2, s.insertFunding("A", pts))
        assertEquals(0, s.insertFunding("A", pts))
        assertEquals(listOf(10L, 20L), s.funding("A").map { it.time })
        assertTrue(s.funding("A").first().markPrice.isNaN())
        assertEquals(1, s.insertOpenInterest("A", "1h", listOf(OpenInterestPoint(5, 1.0, 2.0))))
        assertEquals(listOf(OpenInterestPoint(5, 1.0, 2.0)), s.openInterest("A", "1h"))
        assertEquals(5L, s.lastOpenInterestTime("A", "1h"))
    }
}
