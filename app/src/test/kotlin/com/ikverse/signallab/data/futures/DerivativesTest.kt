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
    fun basisAndPositioningAreReadAndAccumulatedFromThirtyDays() = runTest {
        val s = store()
        val sync = DerivativesSync(client(), s, now = { now }, available = { true })
        server.enqueue(ok("""[{"indexPrice":"81489.1","contractType":"CURRENT_QUARTER","basisRate":"0.0097","futuresPrice":"82275.7","annualizedBasisRate":"0.0518","basis":"786.5","pair":"BTCUSDT","timestamp":${now - day}}]"""))
        assertEquals(1, sync.syncBasis("BTCUSDT", "CURRENT_QUARTER"))
        val req = server.takeRequest().url
        assertEquals("/futures/data/basis", req.encodedPath)
        assertEquals("CURRENT_QUARTER", req.queryParameter("contractType"))
        assertTrue(req.queryParameter("startTime")!!.toLong() >= now - 30 * day)
        val b = s.basis("BTCUSDT", "CURRENT_QUARTER").single()
        assertEquals(0.0518, b.annualized)
        assertEquals(82275.7, b.futuresPrice)

        server.enqueue(ok("""[{"symbol":"BTCUSDT","longAccount":"0.6461","longShortRatio":"1.8258","shortAccount":"0.3539","timestamp":${now - day}}]"""))
        server.enqueue(ok("""[{"symbol":"BTCUSDT","longAccount":"0.55","longShortRatio":"1.2222","shortAccount":"0.45","timestamp":${now - day}}]"""))
        server.enqueue(ok("""[{"buySellRatio":"0.95","sellVol":"2000.0","buyVol":"1900.0","timestamp":${now - day}}]"""))
        assertEquals(3, sync.syncPositioning("BTCUSDT"))
        assertEquals(listOf("/futures/data/topLongShortPositionRatio", "/futures/data/globalLongShortAccountRatio", "/futures/data/takerlongshortRatio"),
            List(3) { server.takeRequest().url.encodedPath })
        assertEquals(PositioningPoint(now - day, 1900.0, 2000.0, 0.95), s.positioning("BTCUSDT", Positioning.TAKER_VOLUME).single())
        assertEquals(1.8258, s.positioning("BTCUSDT", Positioning.TOP_POSITIONS).single().ratio)

        server.enqueue(ok("[]"))
        sync.syncBasis("BTCUSDT", "CURRENT_QUARTER")
        assertEquals((now - day + 1).toString(), server.takeRequest().url.queryParameter("startTime"), "the next run starts after what is stored")
    }

    @Test
    fun deribitVolatilityIsPagedFromItsStartAndTheLastDayIsRefreshed() = runTest {
        val s = store()
        val deribit = DeribitClient(BinanceClient(host = { "https://unused.invalid" }, sleep = {}), host = hostUrl)
        val start = now - 400 * day
        val sync = DerivativesSync(client(), s, now = { now }, available = { false }, deribit = deribit)
        s.insertDvol("BTC", listOf(DvolPoint(start, 40.0, 41.0, 39.0, 40.5)))
        server.enqueue(ok("""{"jsonrpc":"2.0","result":{"data":[[${start},40.0,42.0,39.0,41.0],[${start + day},41.0,43.0,40.0,42.5]],"continuation":null}}"""))
        server.enqueue(ok("""{"jsonrpc":"2.0","result":{"data":[[${now - day},50.0,52.0,49.0,51.0]],"continuation":null}}"""))
        sync.syncDvol("BTC")
        val first = server.takeRequest().url
        assertEquals("/api/v2/public/get_volatility_index_data", first.encodedPath)
        assertEquals(start.toString(), first.queryParameter("start_timestamp"), "the last stored day is asked again")
        assertEquals("1D", first.queryParameter("resolution"))
        server.takeRequest()
        val got = s.dvol("BTC")
        assertEquals(listOf(start, start + day, now - day), got.map { it.time })
        assertEquals(41.0, got.first().close, "a day still forming is replaced by its final value")
    }

    @Test
    fun aNoticeSourceThatFailsLeavesTheOtherRead() = runTest {
        val s = store()
        val notices = NoticesClient(BinanceClient(host = { "https://unused.invalid" }, sleep = {}), binanceHost = hostUrl, upbitHost = hostUrl)
        val sync = DerivativesSync(client(), s, now = { now }, available = { true }, notices = notices)
        server.enqueue(MockResponse.Builder().code(403).body("Forbidden").build())
        server.enqueue(ok("""{"success":true,"data":{"total_count":3,"notices":[
            {"listed_at":"2026-10-21T21:50:00+09:00","id":6687,"title":"질리카(ZIL) 거래지원 종료 안내 (11/16 15:00)","category":"거래"},
            {"listed_at":"2026-10-20T18:00:00+09:00","id":6680,"title":"블러(BLUR) 거래 유의 종목 지정 안내","category":"거래"},
            {"listed_at":"2026-10-19T18:00:00+09:00","id":6670,"title":"액시스로보틱스(AXIS) 신규 거래지원 안내 (KRW, BTC, USDT 마켓)","category":"거래"}]}}"""))
        assertEquals(2, sync.syncNotices())
        assertEquals(listOf("ZIL" to NoticeKind.DELISTING, "BLUR" to NoticeKind.CAUTION), s.notices().map { it.coin to it.kind })
        assertEquals(java.time.Instant.parse("2026-10-21T12:50:00Z").toEpochMilli(), s.notices().first().time, "read with its +09:00 offset")
    }

    @Test
    fun binanceDelistingHeadlinesNameOnlySpotCoins() = runTest {
        server.enqueue(ok("""{"code":"000000","data":{"catalogs":[{"catalogId":161,"catalogName":"Delisting","articles":[
            {"id":1,"code":"a","title":"Binance Will Delist EPIC, QUICK on 2026-10-28","type":1,"releaseDate":1792000000000},
            {"id":2,"code":"b","title":"Notice of Removal of Spot Trading Pairs - 2026-10-23","type":1,"releaseDate":1792000000001},
            {"id":3,"code":"c","title":"Binance Futures Will Delist USDⓈ-M FOOUSDT Perpetual Contract","type":1,"releaseDate":1792000000002},
            {"id":4,"code":"d","title":"Binance Will Delist BAR and BAZ on 2026-11-02","type":1,"releaseDate":1792000000003}]}]}}"""))
        val got = NoticesClient(BinanceClient(host = { "https://unused.invalid" }, sleep = {}), binanceHost = hostUrl).binanceDelistings()
        assertEquals(listOf("EPIC", "QUICK", "BAR", "BAZ"), got.map { it.coin })
        assertTrue(got.all { it.kind == NoticeKind.DELISTING && it.source == "binance" })
        assertEquals("161", server.takeRequest().url.queryParameter("catalogId"))
    }

    @Test
    fun aVersionOneFileGainsTheNewTablesAndKeepsItsFunding() = runTest {
        val name = "derivatives-upgrade-test.db"
        context.deleteDatabase(name)
        val v1 = object : android.database.sqlite.SQLiteOpenHelper(context, name, null, 1) {
            override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
                db.execSQL("CREATE TABLE funding (symbol TEXT NOT NULL, time INTEGER NOT NULL, rate REAL NOT NULL, mark_price REAL, PRIMARY KEY (symbol, time)) WITHOUT ROWID")
                db.execSQL("CREATE TABLE open_interest (symbol TEXT NOT NULL, period TEXT NOT NULL, time INTEGER NOT NULL, contracts REAL NOT NULL, value REAL NOT NULL, PRIMARY KEY (symbol, period, time)) WITHOUT ROWID")
                db.execSQL("INSERT INTO funding VALUES ('BTCUSDT', 5, 0.0001, NULL)")
            }

            override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v1.writableDatabase.close()
        v1.close()
        val s = DerivativesStore(context, name = name, io = Dispatchers.Unconfined)
        assertEquals(listOf(5L), s.funding("BTCUSDT").map { it.time })
        assertEquals(1, s.insertDvol("BTC", listOf(DvolPoint(1, 1.0, 1.0, 1.0, 1.0))))
        assertEquals(1, s.insertNotices(listOf(Notice("binance", "1", 1, NoticeKind.DELISTING, "EPIC", "x"))))
        s.close()
        context.deleteDatabase(name)
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
