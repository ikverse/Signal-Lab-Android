package com.ikverse.signallab.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.binance.BinanceException
import com.ikverse.signallab.engine.Refusal
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.WatchlistRules
import kotlinx.coroutines.Dispatchers
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
class WatchlistRepositoryTest {
    private val names = (1..200).map { "C$it" }

    private fun setup(dbName: String? = null): Pair<WatchlistRepository, RecordDatabase> = runTestBlocking {
        val m = marketWith(*names.mapIndexed { i, n -> pair(n, 1000.0 - i, 10.0, 5.0) }.toTypedArray())
        val s = CandleStore(context, name = null, io = Dispatchers.Unconfined)
        val u = UniverseRepository(m, s, clock = { m.now })
        u.refreshIfStale()
        val db = RecordDatabase(context, dbName)
        WatchlistRepository(db, u, clock = { 1L }, io = Dispatchers.Unconfined).also { it.load() } to db
    }

    private fun <T> runTestBlocking(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

    private fun sym(i: Int) = "C${i}USDT"

    @Test
    fun createRenameAndDeleteWithTheNameRules() = runTest {
        val (w, _) = setup()
        val a = (w.create("  My   coins ") as WatchlistResult.Ok).value
        assertEquals("My coins", a.name); assertFalse(a.active); assertEquals(emptyList(), a.symbols)
        assertEquals(Refusal.NAME_TAKEN, (w.create("MY COINS") as WatchlistResult.Refused).reason)
        assertEquals(Refusal.NAME_BLANK, (w.create("  ") as WatchlistResult.Refused).reason)
        val b = (w.create("Other") as WatchlistResult.Ok).value
        assertEquals(Refusal.NAME_TAKEN, (w.rename(b.id, "my coins") as WatchlistResult.Refused).reason)
        assertIs<WatchlistResult.Ok<Unit>>(w.rename(a.id, "MY COINS")) // renaming to itself in another case is fine
        assertEquals(listOf("MY COINS", "Other"), w.lists.value.map { it.name })
        assertEquals(Refusal.LIST_NOT_FOUND, (w.rename(999, "x") as WatchlistResult.Refused).reason)
        assertIs<WatchlistResult.Ok<List<String>>>(w.delete(a.id))
        assertEquals(listOf("Other"), w.lists.value.map { it.name })
    }

    @Test
    fun aListTakesThirtyCoinsAndRefusesDuplicatesStrangersAndTheThirtyFirst() = runTest {
        val (w, _) = setup()
        val id = (w.create("A") as WatchlistResult.Ok).value.id
        for (i in 1..30) assertIs<WatchlistResult.Ok<Unit>>(w.add(id, sym(i)))
        assertEquals(Refusal.LIST_FULL, (w.add(id, sym(31)) as WatchlistResult.Refused).reason)
        assertEquals(Refusal.DUPLICATE_COIN, (w.add(id, sym(5)) as WatchlistResult.Refused).reason)
        assertEquals(Refusal.UNKNOWN_COIN, (w.add(id, "NOTACOINUSDT") as WatchlistResult.Refused).reason)
        assertEquals(30, w.lists.value.single().symbols.size)
        assertIs<WatchlistResult.Ok<Unit>>(w.remove(id, sym(5)))
        assertIs<WatchlistResult.Ok<Unit>>(w.add(id, sym(31)))
    }

    @Test
    fun onlyActiveListsCountAndACoinInTwoActiveListsCountsOnce() = runTest {
        val (w, _) = setup()
        val a = (w.create("A") as WatchlistResult.Ok).value.id
        val b = (w.create("B") as WatchlistResult.Ok).value.id
        for (i in 1..3) w.add(a, sym(i))
        for (i in 3..5) w.add(b, sym(i))
        assertEquals(emptySet(), w.activeCoins())
        w.setActive(a, true); assertEquals(setOf(sym(1), sym(2), sym(3)), w.activeCoins())
        w.setActive(b, true); assertEquals(5, w.activeCoins().size)
        w.setActive(a, false); assertEquals(setOf(sym(3), sym(4), sym(5)), w.activeCoins())
    }

    @Test
    fun theActiveCapOfOneHundredFiftyIsEnforcedOnActivatingAndOnAdding() = runTest {
        val (w, _) = setup()
        val ids = (0 until 5).map { l ->
            val id = (w.create("L$l") as WatchlistResult.Ok).value.id
            for (i in 1..30) w.add(id, sym(l * 30 + i))
            w.setActive(id, true)
            id
        }
        assertEquals(150, w.activeCoins().size)
        val extra = (w.create("Extra") as WatchlistResult.Ok).value.id
        w.add(extra, sym(151)) // an inactive list may hold a coin that would break the cap
        assertEquals(Refusal.OVER_ACTIVE_CAP, (w.setActive(extra, true) as WatchlistResult.Refused).reason)
        assertFalse(w.lists.value.first { it.id == extra }.active)
        w.add(extra, sym(1)); w.remove(extra, sym(151)) // a coin already counted costs nothing
        assertIs<WatchlistResult.Ok<Unit>>(w.setActive(extra, true))
        assertEquals(Refusal.OVER_ACTIVE_CAP, (w.add(extra, sym(152)) as WatchlistResult.Refused).reason)
        assertIs<WatchlistResult.Ok<Unit>>(w.setActive(ids[0], false)) // freeing room
        assertIs<WatchlistResult.Ok<Unit>>(w.add(extra, sym(152)))
        assertTrue(w.activeCoins().size <= WatchlistRules.MAX_ACTIVE_COINS)
    }

    @Test
    fun listsAndCoinsSurviveARestartAndDeletingAListDeletesItsCoins() = runTest {
        val name = "watchlists-restart.db"
        context.deleteDatabase(name)
        val (w, db) = setup(name)
        val a = (w.create("A") as WatchlistResult.Ok).value.id
        val b = (w.create("B") as WatchlistResult.Ok).value.id
        w.add(a, sym(1)); w.add(a, sym(2)); w.add(b, sym(3)); w.setActive(a, true)
        val (again, _) = setup(name)
        assertEquals(listOf("A", "B"), again.lists.value.map { it.name })
        assertEquals(listOf(sym(1), sym(2)), again.lists.value[0].symbols)
        assertTrue(again.lists.value[0].active); assertFalse(again.lists.value[1].active)
        again.delete(a)
        val left = db.writableDatabase.rawQuery("SELECT COUNT(*) FROM watchlist_coins", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, left) // only B's coin remains
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
}

@RunWith(RobolectricTestRunner::class)
class HistoryManagerTest {
    private val s = CandleStore(context, name = null, io = Dispatchers.Unconfined)

    /** Coins in [pegged] trade flat at one dollar; the rest drift upward from 100. */
    private fun market(vararg coins: String, count: Int = 30, pegged: Set<String> = emptySet()): FakeMarket {
        val m = FakeMarket(EPOCH_START + 1000 * DAY)
        for (c in coins) for (tf in Timeframe.entries) {
            val end = m.now - (m.now % tf.ms) // the last candle that has closed
            val first = end - count * tf.ms
            m.series[c to tf] = if (c in pegged) candlesOf(tf, first, count, base = 1.0, drift = 0.00005, spread = 0.004)
            else candlesOf(tf, first, count)
        }
        return m
    }

    private fun manager(m: FakeMarket) = HistoryManager(CandleSync(m, s), s, m, windowDays = 365)

    @Test
    fun everyCoinGetsAllThreeTimeframesAndProgressCountsThemOff() = runTest {
        val m = market("AUSDT", "BUSDT")
        val h = manager(m)
        assertFalse(h.isReady("AUSDT"))
        h.ensureHistory(listOf("AUSDT", "BUSDT"))
        for (c in listOf("AUSDT", "BUSDT")) for (tf in Timeframe.entries) assertEquals(30, s.count(c, tf), "$c ${tf.label}")
        assertTrue(h.isReady("AUSDT"))
        val p = h.progress.value
        assertEquals(2, p.total); assertEquals(2, p.ready); assertFalse(p.running); assertNull(p.current); assertTrue(p.failures.isEmpty())
    }

    @Test
    fun aSecondRunDownloadsNothingBecauseEverythingIsReady() = runTest {
        val m = market("AUSDT")
        val h = manager(m)
        h.ensureHistory(listOf("AUSDT"))
        val calls = m.klineCalls.size
        h.ensureHistory(listOf("AUSDT"))
        assertEquals(calls, m.klineCalls.size)
    }

    @Test
    fun aStablecoinIsSpottedFromItsDailiesAndSkipsTheHeavyHourlyDownload() = runTest {
        val m = market("PEGUSDT", "BTCUSDT", count = 40, pegged = setOf("PEGUSDT"))
        val h = manager(m)
        h.ensureHistory(listOf("PEGUSDT", "BTCUSDT"))
        assertEquals(true, s.analysable(listOf("PEGUSDT")).isEmpty())
        assertEquals(0, s.count("PEGUSDT", Timeframe.H1))
        assertEquals(40, s.count("BTCUSDT", Timeframe.H1))
        assertTrue(h.isReady("PEGUSDT"))
    }

    @Test
    fun delistedCoinsAreLeftAlone() = runTest {
        val m = market("AUSDT", "GONEUSDT")
        val row = { sym: String -> CoinRow(sym, sym.removeSuffix("USDT"), "TRADING", true, false, false, 1.0, 2.0, 1.0, 1) }
        s.replaceCoins(listOf(row("AUSDT"), row("GONEUSDT")))
        s.replaceCoins(listOf(row("AUSDT"))) // GONE left the pair list: delisted
        manager(m).ensureHistory(listOf("AUSDT", "GONEUSDT"))
        assertEquals(0, s.count("GONEUSDT", Timeframe.D1))
        assertEquals(30, s.count("AUSDT", Timeframe.D1))
    }

    @Test
    fun oneCoinFailingDoesNotStopTheOthersAndIsReported() = runTest {
        val m = market("AUSDT", "BUSDT")
        m.series.remove("AUSDT" to Timeframe.H1)
        m.failKlinesFrom = 1
        val h = manager(m)
        h.ensureHistory(listOf("AUSDT", "BUSDT"))
        assertEquals(setOf("AUSDT", "BUSDT"), h.progress.value.failures.keys) // everything fails while the network does
        m.failKlinesFrom = null
        m.series["AUSDT" to Timeframe.H1] = candlesOf(Timeframe.H1, m.now - (m.now % Timeframe.H1.ms) - 30 * Timeframe.H1.ms, 30)
        h.ensureHistory(listOf("AUSDT", "BUSDT"))
        assertTrue(h.progress.value.failures.isEmpty()); assertEquals(2, h.progress.value.ready)
    }

    @Test
    fun aBlockedNetworkIsReportedForEveryRemainingCoinAndStopsTheRun() = runTest {
        val m = market("AUSDT", "BUSDT", "CUSDT")
        m.failKlinesFrom = 1
        m.failWith = BinanceException.Blocked()
        val h = manager(m)
        h.ensureHistory(listOf("AUSDT", "BUSDT", "CUSDT"))
        assertEquals(3, h.progress.value.failures.size)
        assertEquals(1, m.klineCalls.size, "it must stop at the first refusal instead of asking 3 more times")
        assertTrue(h.progress.value.failures.values.all { "not available" in it })
    }

    @Test
    fun cancellingMidRunKeepsWhatWasDownloadedAndTheNextRunFinishesTheJob() = runTest {
        val m = market("AUSDT", "BUSDT", "CUSDT")
        val h = manager(m)
        val job = launch { h.ensureHistory(listOf("AUSDT", "BUSDT", "CUSDT")) }
        job.cancelAndJoin() // cancelled before it could finish anything
        assertFalse(h.progress.value.running)
        h.ensureHistory(listOf("AUSDT", "BUSDT", "CUSDT"))
        assertEquals(3, h.progress.value.ready)
    }
}
