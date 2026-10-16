package com.ikverse.signallab.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.binance.BinanceException
import com.ikverse.signallab.engine.Refusal
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.WatchlistRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val context: Context get() = ApplicationProvider.getApplicationContext()

/** A stand-in exchange whose pair list is [pairs]; the second element of each is its 24h ticker. */
private fun marketWith(vararg pairs: Triple<com.ikverse.signallab.data.binance.SpotSymbol, com.ikverse.signallab.data.binance.Ticker24h, String>) =
    FakeMarket(EPOCH_START + 10 * DAY).also { m ->
        m.symbols = pairs.map { it.first }
        m.tickers = pairs.map { it.second }
    }

@RunWith(RobolectricTestRunner::class)
class UniverseRepositoryTest {
    private fun store() = CandleStore(context, name = null, io = Dispatchers.Unconfined)

    @Test
    fun theFilterIsAppliedAndOnlyUsdtTradingPairsAreKept() = runTest {
        val m = marketWith(pair("BTC", 900.0, 90_000.0, 85_000.0), pair("USDC", 800.0, 1.001, 0.999), pair("BTCUP", 700.0, 10.0, 8.0),
            pair("SOL", 500.0, 130.0, 120.0), pair("OLD", 400.0, status = "BREAK"), pair("FOO", 300.0, 1.01, 0.99))
        m.symbols = m.symbols + com.ikverse.signallab.data.binance.SpotSymbol("BTCEUR", "BTC", "EUR", "TRADING")
        val s = store()
        val u = UniverseRepository(m, s, clock = { m.now })
        assertTrue(u.refreshIfStale())
        assertEquals(listOf("BTCUSDT", "SOLUSDT"), u.search("").map { it.symbol }) // USDC pegged, BTCUP leveraged, FOO a peg, OLD not trading
        assertTrue(u.isOffered("BTCUSDT"))
        assertFalse(u.isOffered("USDCUSDT")); assertFalse(u.isOffered("OLDUSDT")); assertFalse(u.isOffered("NOPEUSDT"))
        assertEquals(listOf("BTCUSDT"), u.top(1).map { it.symbol })
    }

    @Test
    fun theListIsRefreshedAtMostOncePerDayUnlessForced() = runTest {
        val m = marketWith(pair("BTC", 900.0, 90_000.0, 85_000.0))
        val u = UniverseRepository(m, store(), clock = { m.now })
        assertTrue(u.refreshIfStale())
        m.now += 23 * 3_600_000L
        assertFalse(u.refreshIfStale()); assertEquals(1, m.symbolCalls)
        m.now += 2 * 3_600_000L
        assertTrue(u.refreshIfStale()); assertEquals(2, m.symbolCalls)
        assertTrue(u.refreshIfStale(force = true)); assertEquals(3, m.symbolCalls)
    }

    @Test
    fun aCoinThatVanishesFromTheListIsDelisted() = runTest {
        val m = marketWith(pair("BTC", 900.0, 90_000.0, 85_000.0), pair("ETH", 800.0, 3000.0, 2900.0))
        val s = store()
        val u = UniverseRepository(m, s, clock = { m.now })
        u.refreshIfStale()
        m.symbols = m.symbols.filter { it.base == "BTC" }; m.tickers = m.tickers.filter { it.symbol == "BTCUSDT" }
        u.refreshIfStale(force = true)
        assertEquals(true, s.coin("ETHUSDT")?.delisted)
        assertFalse(u.isOffered("ETHUSDT"))
    }
}

@RunWith(RobolectricTestRunner::class)
class CandleSyncTest {
    private val s = CandleStore(context, name = null, io = Dispatchers.Unconfined)

    private fun market(tf: Timeframe, count: Int, firstOpen: Long = EPOCH_START, coin: String = "A") =
        FakeMarket(firstOpen + count * tf.ms + tf.ms).also { it.series[coin to tf] = candlesOf(tf, firstOpen, count) }

    @Test
    fun firstSyncStoresEveryClosedCandleAcrossPages() = runTest {
        val m = market(Timeframe.H1, 2500)
        val r = CandleSync(m, s).syncCoin("A", Timeframe.H1, EPOCH_START)
        assertEquals(2500, r.inserted); assertEquals(2500, s.count("A", Timeframe.H1))
        assertEquals(3, m.requestsFor("A", Timeframe.H1)) // 1000 + 1000 + 500
        assertFalse(r.youngerThanWindow)
    }

    @Test
    fun anExactMultipleOfThePageSizeEndsCleanly() = runTest {
        val m = market(Timeframe.H1, 2000)
        CandleSync(m, s).syncCoin("A", Timeframe.H1, EPOCH_START)
        assertEquals(2000, s.count("A", Timeframe.H1))
        assertEquals(3, m.requestsFor("A", Timeframe.H1)) // the third page comes back empty
    }

    @Test
    fun aCandleStillFormingIsNeverStoredAndIsPickedUpOnceItCloses() = runTest {
        val tf = Timeframe.H1
        val m = market(tf, 100)
        m.now = EPOCH_START + 99 * tf.ms + 1_000 // candle 99 is still forming (closes at +tf.ms-1)
        val sync = CandleSync(m, s)
        sync.syncCoin("A", tf, EPOCH_START)
        assertEquals(99, s.count("A", tf))
        m.now = EPOCH_START + 100 * tf.ms
        assertEquals(1, sync.syncCoin("A", tf, EPOCH_START).inserted)
        assertEquals(100, s.count("A", tf))
    }

    @Test
    fun theWrongPhoneClockCannotMakeAFormingCandleLookClosedBecauseTheServerClockDecides() = runTest {
        val tf = Timeframe.H1
        val m = market(tf, 10)
        m.now = EPOCH_START + 9 * tf.ms + 5 // the exchange says candle 9 is open; the phone may say anything
        CandleSync(m, s).syncCoin("A", tf, EPOCH_START)
        assertEquals(9, s.count("A", tf))
    }

    @Test
    fun aCutOffDownloadResumesWhereItStoppedWithoutRefetchingOrDuplicating() = runTest {
        val tf = Timeframe.H1
        val m = market(tf, 3500)
        m.failKlinesFrom = 3 // two pages arrive, the third request fails
        val sync = CandleSync(m, s)
        runCatching { sync.syncCoin("A", tf, EPOCH_START) }.also { assertTrue(it.isFailure) }
        assertEquals(2000, s.count("A", tf))
        m.failKlinesFrom = null; m.klineCalls.clear()
        sync.syncCoin("A", tf, EPOCH_START)
        assertEquals(3500, s.count("A", tf))
        assertEquals(EPOCH_START + 2000 * tf.ms, m.klineCalls.first().third) // resumed right after what was stored
    }

    @Test
    fun aHoleInsideTheStoredHistoryIsRefilled() = runTest {
        val tf = Timeframe.H1
        val m = market(tf, 300)
        val sync = CandleSync(m, s)
        sync.syncCoin("A", tf, EPOCH_START)
        // Punch a hole by storing a series that skips candles 100..119, as an interrupted old download might have.
        val fresh = CandleStore(context, name = null, io = Dispatchers.Unconfined)
        fresh.insertKlines("A", tf, m.series["A" to tf]!!.filterIndexed { i, _ -> i < 100 || i >= 120 })
        val r = CandleSync(m, fresh).syncCoin("A", tf, EPOCH_START)
        assertEquals(20, r.inserted); assertEquals(1, r.healedHoles); assertEquals(300, fresh.count("A", tf))
        assertEquals(emptyList(), CandleSync.holesIn(fresh.openTimes("A", tf, 0), tf))
    }

    @Test
    fun aStretchBinanceHasNothingForIsRememberedAndNotAskedForAgain() = runTest {
        val tf = Timeframe.H1
        val m = market(tf, 300)
        m.series["A" to tf] = m.series["A" to tf]!!.filterIndexed { i, _ -> i < 100 || i >= 120 } // the exchange itself skips 20 candles
        val sync = CandleSync(m, s)
        sync.syncCoin("A", tf, EPOCH_START)
        assertEquals(1, s.knownGaps("A", tf).size)
        m.klineCalls.clear()
        val again = sync.syncCoin("A", tf, EPOCH_START)
        assertEquals(0, again.inserted)
        assertEquals(1, m.klineCalls.size, "only the forward check, not the hole: ${m.klineCalls}")
    }

    @Test
    fun aCoinYoungerThanTheWindowIsFlaggedAndItsMissingPastIsAskedForOnlyOnce() = runTest {
        val tf = Timeframe.D1
        val listed = EPOCH_START + 50 * DAY
        val m = market(tf, 100, firstOpen = listed) // the window starts 50 days before it listed
        val sync = CandleSync(m, s)
        val r = sync.syncCoin("A", tf, EPOCH_START)
        assertTrue(r.youngerThanWindow); assertEquals(100, s.count("A", tf))
        m.klineCalls.clear()
        assertTrue(sync.syncCoin("A", tf, EPOCH_START + DAY).youngerThanWindow)
        assertEquals(1, m.klineCalls.size, "the pre-listing stretch must not be re-requested: ${m.klineCalls}")
    }

    @Test
    fun aWindowThatGrowsFetchesTheEarlierHistory() = runTest {
        val tf = Timeframe.D1
        val m = market(tf, 200)
        val sync = CandleSync(m, s)
        sync.syncCoin("A", tf, EPOCH_START + 100 * DAY)
        assertEquals(100, s.count("A", tf))
        val r = sync.syncCoin("A", tf, EPOCH_START)
        assertEquals(100, r.inserted); assertEquals(200, s.count("A", tf)); assertFalse(r.youngerThanWindow)
    }

    @Test
    fun anUnknownCoinStoresNothingAndDoesNotFail() = runTest {
        val m = market(Timeframe.D1, 10)
        val r = CandleSync(m, s).syncCoin("NOPE", Timeframe.D1, EPOCH_START)
        assertEquals(0, r.inserted); assertNull(s.lastOpen("NOPE", Timeframe.D1))
    }

    @Test
    fun twoSyncsOfTheSameSeriesAtOnceDownloadItOnce() = runTest {
        val m = market(Timeframe.H1, 1500)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var held = false
        // The first request is held open until the second sync has started, so both are in flight together.
        m.onKlines = { _, _ -> if (!held) { held = true; gate.await() } }
        val sync = CandleSync(m, s)
        val first = async { sync.syncCoin("A", Timeframe.H1, EPOCH_START) }
        val second = async { sync.syncCoin("A", Timeframe.H1, EPOCH_START) }
        kotlinx.coroutines.yield()
        gate.complete(Unit)
        assertEquals(1500, first.await().inserted)
        assertEquals(0, second.await().inserted)
        assertEquals(3, m.requestsFor("A", Timeframe.H1), "two pages for the first sync, one empty look past the end for the second")
        assertEquals(1500, s.count("A", Timeframe.H1))
    }
}
