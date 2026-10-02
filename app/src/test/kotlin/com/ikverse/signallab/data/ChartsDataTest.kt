package com.ikverse.signallab.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.Refusal
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ctx: Context get() = ApplicationProvider.getApplicationContext()

private fun store() = CandleStore(ctx, name = null, io = Dispatchers.Unconfined)

/** A fake exchange that has [count] candles of every chart for each of [symbols], ending at the last closed candle. */
private fun exchange(vararg symbols: String, count: Int = 30, now: Long = EPOCH_START + 700 * DAY, pegged: Set<String> = emptySet()) =
    FakeMarket(now).also { m ->
        for (c in symbols) for (tf in Timeframe.entries) {
            val end = now - (now % tf.ms)
            val first = end - count * tf.ms
            m.series[c to tf] = if (c in pegged) candlesOf(tf, first, count, base = 1.0, drift = 0.00005, spread = 0.004) else candlesOf(tf, first, count)
        }
    }

/** What is downloaded, how much of it is kept, and when it is thrown away. */
@RunWith(RobolectricTestRunner::class)
class HistoryChartsTest {
    private val s = store()

    private fun manager(m: FakeMarket) = HistoryManager(CandleSync(m, s), s, m)

    @Test
    fun onlyTheChartsAListIsWatchedOnAreDownloaded() = runTest {
        val m = exchange("AUSDT")
        manager(m).ensureHistory(mapOf("AUSDT" to setOf(Timeframe.M5, Timeframe.H1)))
        assertEquals(30, s.count("AUSDT", Timeframe.M5))
        assertEquals(30, s.count("AUSDT", Timeframe.H1))
        for (tf in Timeframe.entries - setOf(Timeframe.M5, Timeframe.H1)) assertEquals(0, s.count("AUSDT", tf), tf.label)
        assertTrue(m.klineCalls.none { it.second == Timeframe.H4 || it.second == Timeframe.M1 })
    }

    @Test
    fun differentCoinsCanBeWatchedOnDifferentCharts() = runTest {
        val m = exchange("AUSDT", "BUSDT")
        manager(m).ensureHistory(mapOf("AUSDT" to setOf(Timeframe.M15), "BUSDT" to setOf(Timeframe.D1, Timeframe.H4)))
        assertEquals(30, s.count("AUSDT", Timeframe.M15)); assertEquals(0, s.count("AUSDT", Timeframe.D1))
        assertEquals(30, s.count("BUSDT", Timeframe.D1)); assertEquals(30, s.count("BUSDT", Timeframe.H4)); assertEquals(0, s.count("BUSDT", Timeframe.M15))
    }

    @Test
    fun eachChartKeepsOnlyItsOwnNumberOfDays() = runTest {
        val now = EPOCH_START + 700 * DAY
        val m = FakeMarket(now)
        val end1m = now - now % Timeframe.M1.ms
        m.series["AUSDT" to Timeframe.M1] = candlesOf(Timeframe.M1, end1m - 14_400 * Timeframe.M1.ms, 14_400) // ten days of minutes
        val endDay = now - now % Timeframe.D1.ms
        m.series["AUSDT" to Timeframe.D1] = candlesOf(Timeframe.D1, endDay - 500 * Timeframe.D1.ms, 500)
        manager(m).ensureHistory(mapOf("AUSDT" to setOf(Timeframe.M1, Timeframe.D1)))
        val minutes = s.count("AUSDT", Timeframe.M1)
        assertTrue(minutes in 7 * 1440 - 2..7 * 1440 + 2, "kept $minutes one-minute candles, a week is ${7 * 1440}")
        assertTrue(s.firstOpen("AUSDT", Timeframe.M1)!! >= now - 7 * DAY - Timeframe.M1.ms)
        val days = s.count("AUSDT", Timeframe.D1)
        assertTrue(days in 399..401, "kept $days daily candles")
    }

    @Test
    fun theDailyChartIsFirstWhenWantedSoAStablecoinSkipsTheRest() = runTest {
        val m = exchange("PEGUSDT", count = 40, pegged = setOf("PEGUSDT"))
        manager(m).ensureHistory(mapOf("PEGUSDT" to setOf(Timeframe.D1, Timeframe.H1, Timeframe.M5)))
        assertEquals(true, s.analysable(listOf("PEGUSDT")).isEmpty())
        assertEquals(0, s.count("PEGUSDT", Timeframe.H1))
        assertEquals(0, s.count("PEGUSDT", Timeframe.M5))
        assertEquals(Timeframe.D1, m.klineCalls.first().second)
    }

    @Test
    fun whenBinanceListedACoinIsAskedOnceAndRemembered() = runTest {
        val m = exchange("AUSDT")
        val h = manager(m)
        h.ensureHistory(mapOf("AUSDT" to setOf(Timeframe.H1, Timeframe.D1)))
        assertEquals(m.series.getValue("AUSDT" to Timeframe.D1).first().openTime, s.listedAt("AUSDT"))
        val calls = m.klineCalls.size
        h.ensureHistory(mapOf("AUSDT" to setOf(Timeframe.H1, Timeframe.D1)))
        assertEquals(calls, m.klineCalls.size, "nothing is asked again")
    }

    @Test
    fun aCoinThatBecameReadyWhileOthersDownloadedIsStillCounted() = runTest {
        val m = exchange("AUSDT", "BUSDT")
        val h = manager(m)
        val sync = CandleSync(m, s)
        var fired = false
        // While A downloads, something else (the scanner) brings B up to date.
        m.onKlines = { symbol, _ ->
            if (symbol == "AUSDT" && !fired) {
                fired = true
                for (tf in Timeframe.LEGACY_DEFAULT) sync.syncCoin("BUSDT", tf, m.now - DataConfig.historyDays(tf) * DAY)
            }
        }
        h.ensureHistory(listOf("AUSDT", "BUSDT"))
        val p = h.progress.value
        assertEquals(2, p.total)
        assertEquals(2, p.ready, "both are ready, and the count says so")
        assertFalse(p.running)
    }

    @Test
    fun readyMeansEveryWantedChartIsCurrent() = runTest {
        val m = exchange("AUSDT")
        val h = manager(m)
        assertFalse(h.isReady("AUSDT", setOf(Timeframe.M5)))
        h.ensureHistory(mapOf("AUSDT" to setOf(Timeframe.M5)))
        assertTrue(h.isReady("AUSDT", setOf(Timeframe.M5)))
        assertFalse(h.isReady("AUSDT", setOf(Timeframe.M5, Timeframe.M1)), "the minute chart was never downloaded")
    }
}

@RunWith(RobolectricTestRunner::class)
class CandleStoreChartsTest {
    private val s = store()

    private fun klines(tf: Timeframe, firstOpen: Long, n: Int): List<Kline> = candlesOf(tf, firstOpen, n)

    @Test
    fun trimmingKeepsEachChartsDaysDropsUnusedChartsAndAlwaysKeepsBitcoinDaily() = runTest {
        val now = EPOCH_START + 700 * DAY
        // Hourly: 120 days of candles, of which 100 are kept. Minutes: a chart no list uses. Daily: BTC kept, ETH not (no list uses daily).
        s.insertKlines("BTCUSDT", Timeframe.H1, klines(Timeframe.H1, now - 120 * DAY, 120 * 24))
        s.insertKlines("BTCUSDT", Timeframe.M1, klines(Timeframe.M1, now - 2 * DAY, 2 * 1440))
        s.insertKlines("BTCUSDT", Timeframe.D1, klines(Timeframe.D1, now - 500 * DAY, 500))
        s.insertKlines("ETHUSDT", Timeframe.D1, klines(Timeframe.D1, now - 500 * DAY, 500))
        val removed = s.trim(now, DataConfig::historyDays, setOf(Timeframe.H1), "BTCUSDT" to Timeframe.D1)
        assertEquals(0, s.count("BTCUSDT", Timeframe.M1), "a chart nobody watches is gone")
        assertEquals(0, s.count("ETHUSDT", Timeframe.D1), "a coin's daily chart goes unless a list uses it")
        assertTrue(s.firstOpen("BTCUSDT", Timeframe.H1)!! >= now - 100 * DAY)
        assertEquals(100 * 24, s.count("BTCUSDT", Timeframe.H1))
        assertTrue(s.firstOpen("BTCUSDT", Timeframe.D1)!! >= now - 400 * DAY)
        assertEquals(400, s.count("BTCUSDT", Timeframe.D1), "bitcoin's daily candles are kept for the market regime")
        assertEquals(20 * 24 + 2 * 1440 + 100 + 500, removed)
    }

    @Test
    fun aChartAListUsesIsOnlyTrimmedByAge() = runTest {
        val now = EPOCH_START + 700 * DAY
        s.insertKlines("AUSDT", Timeframe.D1, klines(Timeframe.D1, now - 450 * DAY, 450))
        s.trim(now, DataConfig::historyDays, setOf(Timeframe.D1), "BTCUSDT" to Timeframe.D1)
        assertEquals(400, s.count("AUSDT", Timeframe.D1))
    }

    @Test
    fun trimmingForgetsTheStretchesWithNoCandlesThatAreNowTooOldToMatter() = runTest {
        val now = EPOCH_START + 700 * DAY
        s.addKnownGap("AUSDT", Timeframe.H1, (now - 200 * DAY)..(now - 190 * DAY))
        s.addKnownGap("AUSDT", Timeframe.H1, (now - 5 * DAY)..(now - 4 * DAY))
        s.trim(now, DataConfig::historyDays, setOf(Timeframe.H1), "BTCUSDT" to Timeframe.D1)
        assertEquals(1, s.knownGaps("AUSDT", Timeframe.H1).size)
    }

    @Test
    fun listingDatesAreKeptAcrossPairListRefreshes() = runTest {
        s.setListedAt("AUSDT", 12345)
        assertEquals(12345L, s.listedAt("AUSDT"), "a coin with no row yet gets one")
        s.replaceCoins(listOf(CoinRow("AUSDT", "A", "TRADING", true, false, false, 5.0, 2.0, 1.0, 99)))
        assertEquals(12345L, s.listedAt("AUSDT"))
        assertEquals(12345L, s.coin("AUSDT")!!.listedAt)
        assertNull(s.listedAt("BUSDT"))
    }

    @Test
    fun aVersionOneCandleFileOpensWithTheCurrentCodeAndGainsTheListingColumn() = runTest {
        val name = "candles-v1.db"
        ctx.deleteDatabase(name)
        val raw = ctx.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        raw.execSQL("CREATE TABLE candles (symbol TEXT NOT NULL, tf TEXT NOT NULL, open_time INTEGER NOT NULL, open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL, close_time INTEGER NOT NULL, PRIMARY KEY (symbol, tf, open_time)) WITHOUT ROWID")
        raw.execSQL("CREATE TABLE coins (symbol TEXT PRIMARY KEY, base TEXT NOT NULL, status TEXT NOT NULL, offered INTEGER NOT NULL, stable INTEGER NOT NULL DEFAULT 0, delisted INTEGER NOT NULL DEFAULT 0, quote_volume REAL NOT NULL DEFAULT 0, high24 REAL NOT NULL DEFAULT 0, low24 REAL NOT NULL DEFAULT 0, seen_at INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE known_gaps (symbol TEXT NOT NULL, tf TEXT NOT NULL, from_time INTEGER NOT NULL, to_time INTEGER NOT NULL, PRIMARY KEY (symbol, tf, from_time)) WITHOUT ROWID")
        raw.execSQL("INSERT INTO coins VALUES ('OLDUSDT','OLD','TRADING',1,0,0,9,2,1,5)")
        raw.version = 1
        raw.close()
        val upgraded = CandleStore(ctx, name, io = Dispatchers.Unconfined)
        assertEquals("OLDUSDT", upgraded.coin("OLDUSDT")?.symbol)
        assertNull(upgraded.listedAt("OLDUSDT"))
        upgraded.setListedAt("OLDUSDT", 777)
        assertEquals(777L, upgraded.listedAt("OLDUSDT"))
    }
}

@RunWith(RobolectricTestRunner::class)
class WatchlistChartsTest {
    private val names = (1..40).map { "C$it" }

    private fun setup(dbName: String? = null): WatchlistRepository = runBlocking {
        val m = FakeMarket(EPOCH_START + 10 * DAY).also { fm ->
            val pairs = names.mapIndexed { i, n -> pair(n, 1000.0 - i, 10.0, 5.0) }
            fm.symbols = pairs.map { it.first }
            fm.tickers = pairs.map { it.second }
        }
        val st = CandleStore(ctx, name = null, io = Dispatchers.Unconfined)
        val u = UniverseRepository(m, st, clock = { m.now })
        u.refreshIfStale()
        WatchlistRepository(RecordDatabase(ctx, dbName), u, clock = { 1L }, io = Dispatchers.Unconfined).also { it.load() }
    }

    private fun sym(i: Int) = "C${i}USDT"

    @Test
    fun aNewListIsWatchedOnFifteenMinutesOneHourAndFourHours() = runTest {
        val w = setup()
        val list = (w.create("Mine") as WatchlistResult.Ok).value
        assertEquals(setOf(Timeframe.M15, Timeframe.H1, Timeframe.H4), list.timeframes)
    }

    @Test
    fun theChosenChartsAreKeptAndComeBackAfterARestart() = runTest {
        val name = "charts-restart.db"
        ctx.deleteDatabase(name)
        val w = setup(name)
        val list = (w.create("Mine") as WatchlistResult.Ok).value
        assertIs<WatchlistResult.Ok<Unit>>(w.setTimeframes(list.id, setOf(Timeframe.M5, Timeframe.D1)))
        val again = setup(name)
        assertEquals(setOf(Timeframe.M5, Timeframe.D1), again.lists.value.single().timeframes)
    }

    @Test
    fun aListNeedsAtLeastOneChart() = runTest {
        val w = setup()
        val list = (w.create("Mine") as WatchlistResult.Ok).value
        assertEquals(Refusal.NO_TIMEFRAME, (w.setTimeframes(list.id, emptySet()) as WatchlistResult.Refused).reason)
        assertEquals(Refusal.LIST_NOT_FOUND, (w.setTimeframes(999, setOf(Timeframe.H1)) as WatchlistResult.Refused).reason)
    }

    @Test
    fun oneMinuteChartsAreCappedAtTenCoinsEvenThroughTheRepository() = runTest {
        val w = setup()
        val fast = (w.create("Fast") as WatchlistResult.Ok).value
        for (i in 1..11) w.add(fast.id, sym(i))
        // A list that is off may be set up first, but it cannot be switched on with eleven coins on a minute chart.
        assertIs<WatchlistResult.Ok<Unit>>(w.setTimeframes(fast.id, setOf(Timeframe.M1)))
        assertEquals(Refusal.OVER_FAST_CAP, (w.setActive(fast.id, true) as WatchlistResult.Refused).reason)
        w.remove(fast.id, sym(11))
        assertIs<WatchlistResult.Ok<Unit>>(w.setActive(fast.id, true))
        assertEquals(Refusal.OVER_FAST_CAP, (w.add(fast.id, sym(11)) as WatchlistResult.Refused).reason)
        // Another list's coins count against the same cap, and changing an active list's charts is checked too.
        val slow = (w.create("Slow") as WatchlistResult.Ok).value
        for (i in 20..31) w.add(slow.id, sym(i))
        assertIs<WatchlistResult.Ok<Unit>>(w.setActive(slow.id, true))
        assertEquals(Refusal.OVER_FAST_CAP, (w.setTimeframes(slow.id, setOf(Timeframe.H1, Timeframe.M1)) as WatchlistResult.Refused).reason)
        assertIs<WatchlistResult.Ok<Unit>>(w.setTimeframes(slow.id, setOf(Timeframe.H1, Timeframe.M15)))
    }

    @Test
    fun aListThatExistedBeforeChartsCouldBeChosenKeepsOneHourFourHoursAndOneDay() = runTest {
        val name = "legacy-lists.db"
        ctx.deleteDatabase(name)
        val raw = ctx.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        raw.execSQL("CREATE TABLE watchlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, active INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, position INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE watchlist_coins (list_id INTEGER NOT NULL REFERENCES watchlists(id) ON DELETE CASCADE, symbol TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (list_id, symbol)) WITHOUT ROWID")
        raw.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
        raw.execSQL("CREATE TABLE live_trades (id INTEGER PRIMARY KEY AUTOINCREMENT, variant TEXT NOT NULL, family TEXT NOT NULL, symbol TEXT NOT NULL, tf TEXT NOT NULL, list_id INTEGER NOT NULL DEFAULT 0, bar_time INTEGER NOT NULL, detected_at INTEGER NOT NULL, regime INTEGER NOT NULL, entry_time INTEGER NOT NULL, entry_price REAL NOT NULL, target REAL, stop REAL, hold_bars INTEGER NOT NULL, exit_due INTEGER NOT NULL, opened_at INTEGER NOT NULL, UNIQUE (variant, symbol, bar_time, list_id))")
        raw.execSQL("CREATE TABLE live_exits (trade_id INTEGER PRIMARY KEY REFERENCES live_trades(id), exit_time INTEGER NOT NULL, exit_price REAL NOT NULL, exit_reason TEXT NOT NULL, bars_held INTEGER NOT NULL, gross REAL NOT NULL, net REAL NOT NULL, random_mean REAL, excess REAL, closed_at INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, kind TEXT NOT NULL, symbol TEXT, tf TEXT, title TEXT NOT NULL, body TEXT NOT NULL, link TEXT)")
        raw.execSQL("CREATE TABLE variants (variant TEXT PRIMARY KEY, family TEXT NOT NULL, tf TEXT NOT NULL, params TEXT NOT NULL, first_seen_at INTEGER NOT NULL) WITHOUT ROWID")
        raw.execSQL("INSERT INTO watchlists VALUES (1, 'Old list', 'old list', 1, 5, 1)")
        raw.execSQL("INSERT INTO watchlist_coins VALUES (1, 'BTCUSDT', 5)")
        raw.execSQL("INSERT INTO live_trades VALUES (1, 'trend_ma20_1d', 'trend_state', 'BTCUSDT', '1d', 0, 100, 110, 1, 120, 50.0, 54.0, 48.0, 7, 999, 130)")
        raw.execSQL("INSERT INTO live_exits VALUES (1, 500, 54.0, 'target', 3, 0.08, 0.076, 0.001, 0.075, 600)")
        raw.version = 1
        raw.close()
        val db = RecordDatabase(ctx, name)
        val m = FakeMarket(EPOCH_START)
        val w = WatchlistRepository(db, UniverseRepository(m, store(), clock = { m.now }), clock = { 1L }, io = Dispatchers.Unconfined).also { it.load() }
        assertEquals(setOf(Timeframe.H1, Timeframe.H4, Timeframe.D1), w.lists.value.single().timeframes)
        assertTrue(w.lists.value.single().active)
        // A trade from before reads exactly as it was written, and the columns that did not exist are empty, not invented.
        val t = TradeLog(db, io = Dispatchers.Unconfined).trades().single()
        assertEquals(50.0, t.trade.entryPrice); assertEquals(54.0, t.trade.target)
        assertNull(t.trade.cost); assertNull(t.trade.exitMode); assertNull(t.trade.atr)
        val x = assertNotNull(t.exit)
        assertEquals(ExitReason.TARGET, x.reason); assertEquals(0.076, x.net)
        assertNull(x.maxUp); assertNull(x.maxDown); assertNull(x.barsToPeak)
    }

    @Test
    fun theNewTradeAndExitColumnsRoundTripAndTheLogStaysAppendOnly() = runTest {
        val db = RecordDatabase(ctx, name = null)
        val log = TradeLog(db, io = Dispatchers.Unconfined)
        val id = assertNotNull(log.open(NewTrade("trend_ma20_1h", "trend_state", "SOLUSDT", Timeframe.H1, 0, 1000, 1010, 1, 1020, 150.0, null, 146.0, 72, 9999,
            cost = 0.0021, exitMode = "trail", atr = 2.0)))
        assertTrue(log.close(id, TradeExit(5000, 155.0, ExitReason.STOP, 4, 0.033, 0.0309, null, null, maxUp = 0.06, maxDown = -0.01, barsToPeak = 3)))
        val t = log.trades().single()
        assertEquals(0.0021, t.trade.cost); assertEquals("trail", t.trade.exitMode); assertEquals(2.0, t.trade.atr)
        assertEquals(0.06, t.exit!!.maxUp); assertEquals(-0.01, t.exit!!.maxDown); assertEquals(3, t.exit!!.barsToPeak)
        val e = kotlin.runCatching { db.writableDatabase.execSQL("UPDATE live_trades SET cost = 0") }.exceptionOrNull()
        assertTrue("append-only" in (e?.message ?: ""), "the new columns are as protected as the old: ${e?.message}")
    }

    @Test
    fun feeSettingsAreKeptAsNumbers() = runTest {
        val settings = SettingsStore(RecordDatabase(ctx, name = null), io = Dispatchers.Unconfined)
        assertEquals(0.001, CostModel.load(settings).feePerSide)
        settings.setDouble(SettingsStore.FEE_PER_SIDE, 0.00075)
        settings.setDouble(SettingsStore.EXTRA_COST_OTHERS, 0.002)
        val c = CostModel.load(settings)
        assertEquals(0.00075, c.feePerSide); assertEquals(0.0, c.extraMajors); assertEquals(0.002, c.extraOthers)
        assertEquals(0.0035, c.costFor("DOGEUSDT"), 1e-15)
    }
}
