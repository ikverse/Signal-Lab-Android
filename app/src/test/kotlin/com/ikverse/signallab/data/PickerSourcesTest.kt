package com.ikverse.signallab.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.binance.BinanceException
import com.ikverse.signallab.data.binance.SpotSymbol
import com.ikverse.signallab.data.binance.Ticker24h
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ctx: Context get() = ApplicationProvider.getApplicationContext()

/**
 * The ways the coin picker ranks coins, on made-up tickers: each source must rank by what it says, the thin coins must be kept out
 * of the lists where they would only be noise, and nothing may be asked of Binance that the answer does not need.
 */
@RunWith(RobolectricTestRunner::class)
class PickerSourcesTest {
    private val now = EPOCH_START + 100 * DAY

    // Five coins, chosen so that every ranking puts them in a different order.
    //   AAA +20%, 44% range, 1,000 trades, 5M volume      BBB -20%, 28% range, 5,000 trades, 3M volume
    //   CCC +90%, 111% range, 9,000 trades, 0.5M (thin)   DDD +1%, 6% range, 300 trades, 2M
    //   EEE 0%, 4% range, 700 trades, 8M
    private fun market() = FakeMarket(now).also { m ->
        val pairs = listOf(
            pair("AAA", 5e6, high = 130.0, low = 90.0, open = 100.0, last = 120.0, trades = 1_000),
            pair("BBB", 3e6, high = 100.0, low = 78.0, open = 100.0, last = 80.0, trades = 5_000),
            pair("CCC", 5e5, high = 200.0, low = 95.0, open = 100.0, last = 190.0, trades = 9_000),
            pair("DDD", 2e6, high = 105.0, low = 99.0, open = 100.0, last = 101.0, trades = 300),
            pair("EEE", 8e6, high = 51.0, low = 49.0, open = 50.0, last = 50.0, trades = 700),
        )
        m.symbols = pairs.map { it.first }
        m.tickers = pairs.map { it.second }
    }

    private fun repo(m: FakeMarket, store: CandleStore = CandleStore(ctx, name = null, io = Dispatchers.Unconfined)) =
        UniverseRepository(m, store, clock = { m.now })

    private fun List<Offer>.bases() = map { it.coin.base }

    @Test
    fun gainersAndLosersOverADayUseTheDaysChangeAndLeaveOutThinCoinsAndFlatOnes() = runTest {
        val u = repo(market()).also { it.refreshIfStale() }
        val gainers = u.offers("", PickerSource.GAINERS)
        assertEquals(listOf("AAA", "DDD"), gainers.bases()) // CCC rose most but traded under 1M; EEE did not move
        assertEquals(0.2, gainers.first().value!!, 1e-9)
        assertEquals(listOf("BBB"), u.offers("", PickerSource.LOSERS).bases())
        assertEquals(-0.2, u.offers("", PickerSource.LOSERS).single().value!!, 1e-9)
    }

    @Test
    fun mostTradesRanksByTradeCountAndHasNoVolumeFloor() = runTest {
        val u = repo(market()).also { it.refreshIfStale() }
        val active = u.offers("", PickerSource.ACTIVE)
        assertEquals(listOf("CCC", "BBB", "AAA", "EEE", "DDD"), active.bases())
        assertEquals(9_000.0, active.first().value)
    }

    @Test
    fun volatileRanksByTheDaysHighToLowRangeAmongLiquidCoins() = runTest {
        val u = repo(market()).also { it.refreshIfStale() }
        val v = u.offers("", PickerSource.VOLATILE)
        assertEquals(listOf("AAA", "BBB", "DDD", "EEE"), v.bases())
        assertEquals(130.0 / 90.0 - 1, v.first().value!!, 1e-9)
    }

    @Test
    fun volumeKeepsTheOldOrderFilterAndLimitAndCarriesNoNumber() = runTest {
        val u = repo(market()).also { it.refreshIfStale() }
        val all = u.offers("", PickerSource.VOLUME)
        assertEquals(listOf("EEE", "AAA", "BBB", "DDD", "CCC"), all.bases())
        assertTrue(all.all { it.value == null })
        assertEquals(listOf("AAA"), u.offers("aa", PickerSource.VOLUME).bases())
        assertEquals(listOf("EEE", "AAA"), u.offers("", PickerSource.VOLUME, limit = 2).bases())
    }

    @Test
    fun aSearchNarrowsEverySourceNotOnlyVolume() = runTest {
        val u = repo(market()).also { it.refreshIfStale() }
        assertEquals(listOf("DDD"), u.offers("dd", PickerSource.GAINERS).bases())
        assertEquals(emptyList(), u.offers("bbb", PickerSource.GAINERS).bases())
    }

    @Test
    fun anHourOrAWeekAsksBinanceOnlyAboutLiquidCoinsAndKeepsTheAnswerForTwoMinutes() = runTest {
        val m = market()
        m.rolling = mapOf("AAAUSDT" to 0.03, "BBBUSDT" to -0.04, "DDDUSDT" to 0.02, "EEEUSDT" to 0.0, "CCCUSDT" to 0.5)
        val u = repo(m).also { it.refreshIfStale() }
        assertEquals(listOf("AAA", "DDD"), u.offers("", PickerSource.GAINERS, MoveWindow.H1).bases())
        assertEquals(1, m.rollingCalls.size)
        assertEquals("1h", m.rollingCalls.single().second)
        assertEquals(setOf("AAAUSDT", "BBBUSDT", "DDDUSDT", "EEEUSDT"), m.rollingCalls.single().first.toSet()) // CCC is thin: not asked about
        assertEquals(listOf("BBB"), u.offers("", PickerSource.LOSERS, MoveWindow.H1).bases())
        assertEquals(1, m.rollingCalls.size, "the same hour, asked again within two minutes, is not asked of Binance again")
        m.now += 3 * 60_000L
        u.offers("", PickerSource.GAINERS, MoveWindow.H1)
        assertEquals(2, m.rollingCalls.size)
        u.offers("", PickerSource.GAINERS, MoveWindow.D7)
        assertEquals("7d", m.rollingCalls.last().second)
        assertEquals(3, m.rollingCalls.size, "a week is its own question")
        // A full refresh of the day's figures forgets the answers, which may be older than they look.
        u.refreshIfStale(force = true)
        u.offers("", PickerSource.GAINERS, MoveWindow.D7)
        assertEquals(4, m.rollingCalls.size)
    }

    @Test
    fun whenBinanceCannotAnswerAHourOrAWeekTheFailureReachesTheCaller() = runTest {
        val m = market()
        m.failRolling = BinanceException.Network(null)
        val u = repo(m).also { it.refreshIfStale() }
        assertFailsWith<BinanceException.Network> { u.offers("", PickerSource.GAINERS, MoveWindow.D7) }
        // A day needs no request at all, so it still works.
        assertEquals(listOf("AAA", "DDD"), u.offers("", PickerSource.GAINERS, MoveWindow.H24).bases())
    }

    @Test
    fun theDaysFiguresRefreshWhenOlderThanTheAgeAskedForNotBefore() = runTest {
        val m = market()
        val u = repo(m)
        assertTrue(u.refreshIfStale(maxAgeMs = DataConfig.PICKER_REFRESH_MS))
        m.now += 4 * 60_000L
        assertFalse(u.refreshIfStale(maxAgeMs = DataConfig.PICKER_REFRESH_MS))
        m.now += 2 * 60_000L
        // Six minutes on, the picker's age is over; the pair list's own age (a day) is not.
        m.tickers = m.tickers.map { if (it.symbol == "AAAUSDT") it.copy(lastPrice = 150.0) else it }
        assertFalse(u.refreshIfStale())
        assertTrue(u.refreshIfStale(maxAgeMs = DataConfig.PICKER_REFRESH_MS))
        assertEquals(0.5, u.offers("", PickerSource.GAINERS).first().value!!, 1e-9)
    }

    @Test
    fun theChangeAndTheTradeCountAreStoredAndReplacedOnEveryRefresh() = runTest {
        val m = market()
        val s = CandleStore(ctx, name = null, io = Dispatchers.Unconfined)
        val u = repo(m, s)
        u.refreshIfStale()
        assertEquals(0.2, s.coin("AAAUSDT")!!.change24, 1e-9)
        assertEquals(1_000L, s.coin("AAAUSDT")!!.trades24)
        m.tickers = m.tickers.map { if (it.symbol == "AAAUSDT") it.copy(lastPrice = 90.0, trades = 2_222) else it }
        u.refreshIfStale(force = true)
        assertEquals(-0.1, s.coin("AAAUSDT")!!.change24, 1e-9)
        assertEquals(2_222L, s.coin("AAAUSDT")!!.trades24)
    }

    @Test
    fun aTickerThatSaysNothingOfTheOpeningPriceIsNeitherAGainerNorALoser() = runTest {
        val m = FakeMarket(now).also {
            it.symbols = listOf(SpotSymbol("ZZZUSDT", "ZZZ", "USDT", "TRADING"))
            it.tickers = listOf(Ticker24h("ZZZUSDT", 12.0, 15.0, 9.0, 5e6)) // the older answer shape: no open, no count
        }
        val u = repo(m).also { it.refreshIfStale() }
        assertEquals(emptyList(), u.offers("", PickerSource.GAINERS).bases())
        assertEquals(emptyList(), u.offers("", PickerSource.LOSERS).bases())
        assertEquals(listOf("ZZZ"), u.offers("", PickerSource.VOLUME).bases())
    }

    // --- New listings

    private fun listingMarket(): FakeMarket = FakeMarket(now).also { m ->
        val pairs = listOf(
            pair("NEWA", 100.0, 10.0, 5.0), pair("NEWB", 200.0, 10.0, 5.0), pair("OLDC", 300.0, 10.0, 5.0),
        )
        m.symbols = pairs.map { it.first }
        m.tickers = pairs.map { it.second }
        m.series["NEWAUSDT" to Timeframe.D1] = candlesOf(Timeframe.D1, now - 10 * DAY, 3)
        m.series["NEWBUSDT" to Timeframe.D1] = candlesOf(Timeframe.D1, now - 40 * DAY, 3)
        m.series["OLDCUSDT" to Timeframe.D1] = candlesOf(Timeframe.D1, now - 400 * DAY, 3)
    }

    @Test
    fun newCoinsAreFoundFromEachCoinsFirstDailyCandleAskedOncePerCoinAndOnlyTheLastThirtyDaysShow() = runTest {
        val m = listingMarket()
        val u = repo(m).also { it.refreshIfStale() }
        assertEquals(3, u.missingListings())
        assertEquals(emptyList(), u.offers("", PickerSource.NEW).bases(), "nothing is known to be new until the lookup has run")
        u.lookUpListings()
        val new = u.offers("", PickerSource.NEW)
        assertEquals(listOf("NEWA"), new.bases()) // NEWB is 40 days old, OLDC a year
        assertEquals((now - 10 * DAY).toDouble(), new.single().value)
        assertEquals(0, u.missingListings())
        assertEquals(ListingLookup(running = false, done = 3, total = 3), u.listingLookup.value)
        assertEquals(3, m.klineCalls.size)
        assertTrue(m.klineCalls.all { it.second == Timeframe.D1 && it.third == 0L }, "the first daily candle: from the start of time, one candle")
        u.lookUpListings()
        assertEquals(3, m.klineCalls.size, "a coin whose day is known is never asked again")
    }

    @Test
    fun theNewestListingComesFirst() = runTest {
        val m = listingMarket()
        m.series["NEWBUSDT" to Timeframe.D1] = candlesOf(Timeframe.D1, now - 3 * DAY, 3)
        val u = repo(m).also { it.refreshIfStale() }
        u.lookUpListings()
        assertEquals(listOf("NEWB", "NEWA"), u.offers("", PickerSource.NEW).bases())
    }

    @Test
    fun aLookupThatFailsKeepsWhatItFoundSaysWhyAndPicksUpWhereItStopped() = runTest {
        val m = listingMarket()
        val s = CandleStore(ctx, name = null, io = Dispatchers.Unconfined)
        val u = repo(m, s).also { it.refreshIfStale() }
        m.failKlinesFrom = 3 // the smallest volumes go first: NEWA and NEWB are found, OLDC is not
        u.lookUpListings()
        val state = u.listingLookup.value
        assertFalse(state.running)
        assertNotNull(state.failed)
        assertEquals(2, state.done); assertEquals(3, state.total)
        assertNotNull(s.listedAt("NEWAUSDT")); assertNotNull(s.listedAt("NEWBUSDT")); assertNull(s.listedAt("OLDCUSDT"))
        assertEquals(listOf("NEWA"), u.offers("", PickerSource.NEW).bases(), "what was found is already usable")

        m.failKlinesFrom = null
        val before = m.klineCalls.size
        u.lookUpListings()
        assertEquals(1, m.klineCalls.size - before, "only the missing coin is asked about")
        assertEquals(ListingLookup(running = false, done = 1, total = 1), u.listingLookup.value)
        assertEquals(0, u.missingListings())
    }

    @Test
    fun aCoinBinanceHasNoDailyCandlesForDoesNotStopTheLookup() = runTest {
        val m = listingMarket()
        m.series.remove("NEWBUSDT" to Timeframe.D1)
        val u = repo(m).also { it.refreshIfStale() }
        u.lookUpListings()
        assertNull(u.listingLookup.value.failed)
        assertEquals(1, u.missingListings(), "it stays unknown, and is asked about again next time")
        assertEquals(listOf("NEWA"), u.offers("", PickerSource.NEW).bases())
    }

    // --- The coin file from before the day's change was stored

    @Test
    fun aVersionTwoCandleFileGainsTheDaysChangeAndTradeColumnsAndKeepsItsCoins() = runTest {
        val name = "candles-v2.db"
        ctx.deleteDatabase(name)
        val raw = ctx.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        raw.execSQL("CREATE TABLE candles (symbol TEXT NOT NULL, tf TEXT NOT NULL, open_time INTEGER NOT NULL, open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL, close_time INTEGER NOT NULL, PRIMARY KEY (symbol, tf, open_time)) WITHOUT ROWID")
        raw.execSQL("CREATE TABLE coins (symbol TEXT PRIMARY KEY, base TEXT NOT NULL, status TEXT NOT NULL, offered INTEGER NOT NULL, stable INTEGER NOT NULL DEFAULT 0, delisted INTEGER NOT NULL DEFAULT 0, quote_volume REAL NOT NULL DEFAULT 0, high24 REAL NOT NULL DEFAULT 0, low24 REAL NOT NULL DEFAULT 0, seen_at INTEGER NOT NULL, listed_at INTEGER)")
        raw.execSQL("CREATE TABLE known_gaps (symbol TEXT NOT NULL, tf TEXT NOT NULL, from_time INTEGER NOT NULL, to_time INTEGER NOT NULL, PRIMARY KEY (symbol, tf, from_time)) WITHOUT ROWID")
        raw.execSQL("INSERT INTO coins VALUES ('OLDUSDT','OLD','TRADING',1,0,0,9,2,1,5,777)")
        raw.version = 2
        raw.close()
        val upgraded = CandleStore(ctx, name, io = Dispatchers.Unconfined)
        val coin = upgraded.coin("OLDUSDT")!!
        assertEquals(777L, coin.listedAt)
        assertEquals(0.0, coin.change24); assertEquals(0L, coin.trades24)
    }
}
