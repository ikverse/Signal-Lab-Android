package com.ikverse.signallab.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.SignalKey
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

private val context: Context get() = ApplicationProvider.getApplicationContext()

@RunWith(RobolectricTestRunner::class)
class CandleStoreTest {
    private fun store() = CandleStore(context, name = null, io = Dispatchers.Unconfined)

    @Test
    fun insertingTwiceStoresEachCandleOnce() = runTest {
        val s = store()
        val k = candlesOf(Timeframe.H1, EPOCH_START, 50)
        assertEquals(50, s.insertKlines("BTCUSDT", Timeframe.H1, k))
        assertEquals(0, s.insertKlines("BTCUSDT", Timeframe.H1, k))
        assertEquals(10, s.insertKlines("BTCUSDT", Timeframe.H1, candlesOf(Timeframe.H1, EPOCH_START + 45 * Timeframe.H1.ms, 15)))
        assertEquals(60, s.count("BTCUSDT", Timeframe.H1))
        assertEquals(0, s.count("BTCUSDT", Timeframe.H4))
    }

    @Test
    fun readingBackGivesExactlyTheDoublesThatWereStored() = runTest {
        val s = store()
        val tricky = listOf(
            Kline(EPOCH_START, 0.1 + 0.2, 1.0 / 3.0, 1e-9, 83566.97000000001, 12345.678901234567, EPOCH_START + Timeframe.D1.ms - 1),
            Kline(EPOCH_START + DAY, 7.1e-5, 7.2e-5, 6.9e-5, 7.0000000000000001e-5, 3.0e12, EPOCH_START + 2 * DAY - 1),
        )
        s.insertKlines("PEPEUSDT", Timeframe.D1, tricky)
        val c = assertNotNull(s.window("PEPEUSDT", Timeframe.D1))
        for ((i, k) in tricky.withIndex()) {
            assertEquals(k.openTime, c.t[i]); assertEquals(k.closeTime, c.closeTime[i])
            assertEquals(k.open.toRawBits(), c.open[i].toRawBits()); assertEquals(k.high.toRawBits(), c.high[i].toRawBits())
            assertEquals(k.low.toRawBits(), c.low[i].toRawBits()); assertEquals(k.close.toRawBits(), c.close[i].toRawBits())
            assertEquals(k.volume.toRawBits(), c.volume[i].toRawBits())
        }
        assertNull(s.window("NOPEUSDT", Timeframe.D1))
    }

    @Test
    fun windowsStartWhereAskedAndComeOldestFirst() = runTest {
        val s = store()
        s.insertKlines("A", Timeframe.H4, candlesOf(Timeframe.H4, EPOCH_START, 100).shuffled())
        val c = assertNotNull(s.window("A", Timeframe.H4, since = EPOCH_START + 90 * Timeframe.H4.ms))
        assertEquals(10, c.size)
        assertTrue(c.t.toList() == c.t.sorted())
        assertEquals(EPOCH_START, s.firstOpen("A", Timeframe.H4))
        assertEquals(EPOCH_START + 99 * Timeframe.H4.ms, s.lastOpen("A", Timeframe.H4))
        assertNull(s.lastOpen("A", Timeframe.H1))
    }

    @Test
    fun holesAreFoundBetweenStoredCandles() {
        val times = longArrayOf(0, 1, 2, 5, 6, 10).map { it * Timeframe.H1.ms }.toLongArray()
        assertEquals(listOf(3 * Timeframe.H1.ms..4 * Timeframe.H1.ms, 7 * Timeframe.H1.ms..9 * Timeframe.H1.ms), CandleSync.holesIn(times, Timeframe.H1))
        assertEquals(emptyList(), CandleSync.holesIn(longArrayOf(), Timeframe.H1))
    }

    @Test
    fun knownGapsAreRememberedPerCoinAndTimeframe() = runTest {
        val s = store()
        s.addKnownGap("A", Timeframe.H1, 10L..20L)
        s.addKnownGap("A", Timeframe.H1, 10L..30L) // same start: replaced
        assertEquals(listOf(10L..30L), s.knownGaps("A", Timeframe.H1))
        assertEquals(emptyList(), s.knownGaps("A", Timeframe.H4))
        assertEquals(emptyList(), s.knownGaps("B", Timeframe.H1))
    }

    private fun row(symbol: String, volume: Double, offered: Boolean = true, status: String = "TRADING", seen: Long = 1) =
        CoinRow(symbol, symbol.removeSuffix("USDT"), status, offered, false, false, volume, 2.0, 1.0, seen)

    @Test
    fun thePickerListsOnlyOfferedTradingCoinsBiggestFirstAndEscapesTheSearch() = runTest {
        val s = store()
        s.replaceCoins(listOf(row("SMALLUSDT", 10.0), row("BIGUSDT", 900.0), row("MIDUSDT", 100.0), row("HIDDENUSDT", 5000.0, offered = false),
            row("BREAKUSDT", 800.0, status = "BREAK"), row("A_BUSDT", 50.0), row("AXBUSDT", 60.0)))
        assertEquals(listOf("BIGUSDT", "MIDUSDT", "AXBUSDT", "A_BUSDT", "SMALLUSDT"), s.offeredCoins("", 10).map { it.symbol })
        assertEquals(listOf("BIGUSDT"), s.offeredCoins("big", 10).map { it.symbol })
        assertEquals(listOf("A_BUSDT"), s.offeredCoins("a_b", 10).map { it.symbol }) // _ is not a wildcard
        assertEquals(emptyList(), s.offeredCoins("%", 10).map { it.symbol }) // % is not a wildcard
        assertEquals(2, s.offeredCoins("", 2).size)
    }

    @Test
    fun aCoinThatLeavesThePairListIsMarkedDelistedAndComesBackIfItReturns() = runTest {
        val s = store()
        s.replaceCoins(listOf(row("AUSDT", 1.0), row("BUSDT", 2.0)))
        s.replaceCoins(listOf(row("AUSDT", 1.0)))
        assertEquals(true, s.coin("BUSDT")?.delisted)
        assertEquals(false, s.coin("AUSDT")?.delisted)
        assertEquals(setOf("AUSDT"), s.analysable(listOf("AUSDT", "BUSDT")))
        s.replaceCoins(listOf(row("AUSDT", 1.0), row("BUSDT", 2.0)))
        assertEquals(false, s.coin("BUSDT")?.delisted)
    }

    @Test
    fun aStablecoinMarkSurvivesPairListRefreshesAndHidesTheCoin() = runTest {
        val s = store()
        s.replaceCoins(listOf(row("AUSDT", 1.0), row("PEGUSDT", 5.0)))
        s.markStable("PEGUSDT")
        s.replaceCoins(listOf(row("AUSDT", 1.0), row("PEGUSDT", 6.0, seen = 2)))
        assertEquals(true, s.coin("PEGUSDT")?.stable)
        assertEquals(6.0, s.coin("PEGUSDT")?.quoteVolume)
        assertEquals(listOf("AUSDT"), s.offeredCoins("", 10).map { it.symbol })
        assertEquals(setOf("AUSDT", "UNKNOWNUSDT"), s.analysable(listOf("AUSDT", "PEGUSDT", "UNKNOWNUSDT")))
        assertEquals(2 to 1, s.coinCount())
    }

    @Test
    fun dailyClosesAreTheLastOnesOldestFirst() = runTest {
        val s = store()
        s.insertKlines("A", Timeframe.D1, candlesOf(Timeframe.D1, EPOCH_START, 40))
        val closes = s.dailyCloses("A", 30)
        assertEquals(30, closes.size)
        assertEquals(100.0 + 10 * 0.01 + 0.5, closes.first(), 1e-12)
        assertEquals(100.0 + 39 * 0.01 + 0.5, closes.last(), 1e-12)
    }

    @Test
    fun theFileSurvivesBeingClosedAndReopened() = runTest {
        val name = "candles-reopen-test.db"
        context.deleteDatabase(name)
        val first = CandleStore(context, name, io = Dispatchers.Unconfined)
        first.insertKlines("A", Timeframe.H1, candlesOf(Timeframe.H1, EPOCH_START, 30))
        first.close()
        assertEquals(30, CandleStore(context, name, io = Dispatchers.Unconfined).count("A", Timeframe.H1))
    }
}

@RunWith(RobolectricTestRunner::class)
class RecordDatabaseTest {
    private fun db() = RecordDatabase(context, name = null)

    private fun trade(variant: String = "donchian20_1d", symbol: String = "BTCUSDT", barTime: Long = 1000, listId: Long = 0) =
        NewTrade(variant, "donchian", symbol, Timeframe.D1, listId, barTime, barTime + 10, 1, barTime + 20, 100.0, 104.0, 98.0, 7, barTime + 9999)

    private val exit = TradeExit(5000, 104.0, ExitReason.TARGET, 3, 0.04, 0.036, 0.001, 0.035)

    @Test
    fun migrationsAreConsecutiveAndTheVersionMatchesThem() {
        assertEquals(RecordDatabase.MIGRATIONS.size, RecordDatabase.SCHEMA_VERSION)
        assertTrue(RecordDatabase.SCHEMA_VERSION >= 1)
        val d = db().writableDatabase
        assertEquals(RecordDatabase.SCHEMA_VERSION, d.version)
    }

    @Test
    fun aFreshInstallHasEveryTable() {
        val d = db().writableDatabase
        val tables = d.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(tables.containsAll(setOf("watchlists", "watchlist_coins", "settings", "live_trades", "live_exits", "alerts", "variants", "analyst_reports", "lab_patterns", "lab_stops")), "tables were $tables")
    }

    /** Version 1 as it first shipped, written out in full. If editing step 1 changes the schema, this fails. */
    private val frozenVersion1 = listOf(
        """CREATE TABLE watchlists (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE,
                    active INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, position INTEGER NOT NULL)""",
        """CREATE TABLE watchlist_coins (
                    list_id INTEGER NOT NULL REFERENCES watchlists(id) ON DELETE CASCADE,
                    symbol TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (list_id, symbol)) WITHOUT ROWID""",
        "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID",
        """CREATE TABLE live_trades (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, variant TEXT NOT NULL, family TEXT NOT NULL,
                    symbol TEXT NOT NULL, tf TEXT NOT NULL, list_id INTEGER NOT NULL DEFAULT 0,
                    bar_time INTEGER NOT NULL, detected_at INTEGER NOT NULL, regime INTEGER NOT NULL,
                    entry_time INTEGER NOT NULL, entry_price REAL NOT NULL, target REAL, stop REAL,
                    hold_bars INTEGER NOT NULL, exit_due INTEGER NOT NULL, opened_at INTEGER NOT NULL,
                    UNIQUE (variant, symbol, bar_time, list_id))""",
        """CREATE TABLE live_exits (
                    trade_id INTEGER PRIMARY KEY REFERENCES live_trades(id),
                    exit_time INTEGER NOT NULL, exit_price REAL NOT NULL, exit_reason TEXT NOT NULL,
                    bars_held INTEGER NOT NULL, gross REAL NOT NULL, net REAL NOT NULL,
                    random_mean REAL, excess REAL, closed_at INTEGER NOT NULL)""",
        """CREATE TABLE alerts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, kind TEXT NOT NULL,
                    symbol TEXT, tf TEXT, title TEXT NOT NULL, body TEXT NOT NULL, link TEXT)""",
        "CREATE INDEX alerts_by_time ON alerts (ts)",
        """CREATE TABLE variants (
                    variant TEXT PRIMARY KEY, family TEXT NOT NULL, tf TEXT NOT NULL, params TEXT NOT NULL,
                    first_seen_at INTEGER NOT NULL) WITHOUT ROWID""",
        "CREATE INDEX trades_by_symbol ON live_trades (symbol, detected_at)",
    ) + listOf("live_trades", "live_exits", "variants").flatMap { t ->
        listOf(
            "CREATE TRIGGER ${t}_no_update BEFORE UPDATE ON $t BEGIN SELECT RAISE(ABORT, '$t is append-only'); END",
            "CREATE TRIGGER ${t}_no_delete BEFORE DELETE ON $t BEGIN SELECT RAISE(ABORT, '$t is append-only'); END",
        )
    }

    private fun schemaOf(d: SQLiteDatabase): List<String> =
        d.rawQuery("SELECT type || ' ' || name || ' ' || COALESCE(sql, '') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0).replace(Regex("\\s+"), " ")) } }

    @Test
    fun aVersionOneDatabaseOpensWithTheCurrentCodeAndTheShippedSchemaIsUnchanged() {
        val name = "frozen-v1.db"
        context.deleteDatabase(name)
        val raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        frozenVersion1.forEach(raw::execSQL)
        raw.version = 1
        raw.close()
        val upgraded = RecordDatabase(context, name).writableDatabase // runs every step beyond version 1
        val fresh = RecordDatabase(context, null).writableDatabase
        // The upgraded version 1 file and a fresh install of the current version must end with the same schema.
        assertEquals(schemaOf(fresh), schemaOf(upgraded))
        assertEquals(RecordDatabase.SCHEMA_VERSION, upgraded.version)
    }

    @Test
    fun theDatabaseRefusesToChangeOrRemoveATradeAnExitOrAVariant() = runTest {
        val rdb = db()
        val log = TradeLog(rdb, io = Dispatchers.Unconfined)
        val id = assertNotNull(log.open(trade()))
        assertTrue(log.close(id, exit))
        log.registerVariant(SignalKey("donchian20_1d", "donchian", mapOf("lookback" to 20.0)), Timeframe.D1)
        val d = rdb.writableDatabase
        for (sql in listOf(
            "UPDATE live_trades SET entry_price = 1", "DELETE FROM live_trades",
            "UPDATE live_exits SET net = 1", "DELETE FROM live_exits",
            "UPDATE variants SET family = 'x'", "DELETE FROM variants",
        )) {
            val e = assertFailsWith<SQLiteException>(sql) { d.execSQL(sql) }
            assertTrue("append-only" in (e.message ?: ""), "$sql: ${e.message}")
        }
        assertEquals(1, log.trades().size)
        assertNotNull(log.trades().single().exit)
    }

    @Test
    fun aTradeIsOpenedOnceAndClosedOnce() = runTest {
        val log = TradeLog(db(), io = Dispatchers.Unconfined)
        val id = assertNotNull(log.open(trade()))
        assertNull(log.open(trade()), "the same signal on the same bar must not be traded twice")
        assertNotNull(log.open(trade(listId = 3)), "but a list-dependent signal is its own trade per list")
        assertTrue(log.hasOpen("donchian20_1d", "BTCUSDT", 0))
        assertTrue(log.close(id, exit))
        assertFalse(log.close(id, exit))
        assertFalse(log.hasOpen("donchian20_1d", "BTCUSDT", 0))
        assertEquals(setOf(false, true), log.trades().map { it.exit == null }.toSet())
    }

    @Test
    fun tradesComeBackNewestFirstWithEveryFieldAndFilterByStatusAndCoin() = runTest {
        val log = TradeLog(db(), io = Dispatchers.Unconfined)
        val a = assertNotNull(log.open(trade(barTime = 1000)))
        log.open(trade(symbol = "ETHUSDT", barTime = 2000))
        log.close(a, exit)
        assertEquals(listOf("ETHUSDT", "BTCUSDT"), log.trades().map { it.trade.symbol })
        assertEquals(listOf("ETHUSDT"), log.trades(TradeStatus.OPEN).map { it.trade.symbol })
        assertEquals(listOf("BTCUSDT"), log.trades(TradeStatus.CLOSED).map { it.trade.symbol })
        assertEquals(listOf("BTCUSDT"), log.trades(symbol = "BTCUSDT").map { it.trade.symbol })
        val closed = log.trades(TradeStatus.CLOSED).single()
        assertEquals(104.0, closed.trade.target); assertEquals(98.0, closed.trade.stop); assertEquals(ExitReason.TARGET, closed.exit?.reason)
        assertEquals(0.035, closed.exit?.excess); assertEquals(7, closed.trade.holdBars)
    }

    @Test
    fun aHeldTradeKeepsItsMissingTargetAndStopMissing() = runTest {
        val log = TradeLog(db(), io = Dispatchers.Unconfined)
        log.open(NewTrade("fade1h_hold24_1h", "big_move_fade", "SOLUSDT", Timeframe.H1, 0, 1, 2, -1, 3, 150.0, null, null, 24, 99))
        val t = log.trades().single().trade
        assertNull(t.target); assertNull(t.stop); assertEquals(-1, t.regime)
    }

    @Test
    fun alertsAreListedNewestFirstAndTheLastOneOfAKindIsFound() = runTest {
        var now = 100L
        val log = TradeLog(db(), clock = { now }, io = Dispatchers.Unconfined)
        log.addAlert("move", "ETH -5%", "Price alert", "ETHUSDT"); now = 200
        log.addAlert("signal", "BTC broke out", "daily", "BTCUSDT", "1d", "#/chart/BTCUSDT/1d"); now = 300
        log.addAlert("move", "ETH -6%", "Price alert", "ETHUSDT")
        assertEquals(listOf("ETH -6%", "BTC broke out", "ETH -5%"), log.alerts().map { it.title })
        assertEquals(300L, log.lastAlertTime("move", "ETHUSDT"))
        assertEquals(0L, log.lastAlertTime("move", "BTCUSDT"))
    }

    @Test
    fun everyVariantIsCountedOnce() = runTest {
        val log = TradeLog(db(), io = Dispatchers.Unconfined)
        val k = SignalKey("donchian20_1d", "donchian", mapOf("lookback" to 20.0))
        log.registerVariant(k, Timeframe.D1); log.registerVariant(k, Timeframe.D1)
        log.registerVariant(SignalKey("donchian20_4h", "donchian", mapOf("lookback" to 20.0)), Timeframe.H4)
        assertEquals(2, log.variantCount())
    }

    @Test
    fun settingsStoreAndReplace() = runTest {
        val s = SettingsStore(db(), Dispatchers.Unconfined)
        assertNull(s.get("k"))
        s.set("k", "a"); s.set("k", "b")
        assertEquals("b", s.get("k"))
        assertTrue(s.getBoolean("flag", true)); s.setBoolean("flag", false); assertFalse(s.getBoolean("flag", true))
    }

    @Test
    fun checkpointLeavesAFileThatHoldsEverything() = runTest {
        val name = "checkpoint-test.db"
        context.deleteDatabase(name)
        val rdb = RecordDatabase(context, name)
        TradeLog(rdb, io = Dispatchers.Unconfined).open(trade())
        rdb.checkpoint()
        val copy = context.getDatabasePath("checkpoint-copy.db").also { it.parentFile?.mkdirs() }
        context.getDatabasePath(name).copyTo(copy, overwrite = true)
        val reopened = RecordDatabase(context, "checkpoint-copy.db")
        assertEquals(1, TradeLog(reopened, io = Dispatchers.Unconfined).trades().size)
    }
}

@RunWith(RobolectricTestRunner::class)
class ReportLogTest {
    @Test
    fun `reports are kept newest first, with or without the question they answered, and each save is announced`() = runTest {
        var now = 1_000L
        val log = ReportLog(RecordDatabase(context, name = null), clock = { now }, io = Dispatchers.Unconfined)
        assertEquals(0L, log.version.value)
        val first = log.add("weekly-review", "Weekly review", "## Summary\nText")
        now = 2_000L
        val second = log.add(null, "A follow-up", "More")
        assertEquals(2L, log.version.value)
        val all = log.all()
        assertEquals(listOf(second, first), all.map { it.id })
        assertEquals(listOf(2_000L, 1_000L), all.map { it.receivedAt })
        assertNull(all[0].card)
        assertEquals("weekly-review", all[1].card)
        assertEquals("## Summary\nText", all[1].body)
        assertEquals(listOf(second), log.all(limit = 1).map { it.id })
    }
}

@RunWith(RobolectricTestRunner::class)
class LabStoreTest {
    @Test
    fun `lab patterns start and stop, never change, and name themselves in rows and alerts`() = runTest {
        var now = 1_000L
        val rdb = RecordDatabase(context, name = null)
        val store = LabStore(rdb, clock = { now }, io = Dispatchers.Unconfined)
        val first = store.start("Trend copy", "Because.", "{\"a\":1}", reportId = 3)
        now = 2_000L
        val second = store.start("Second", null, "{\"b\":2}", reportId = null)
        assertEquals(2L, store.version.value)
        assertEquals(listOf(second, first), store.all().map { it.id })
        assertTrue(store.all().all { it.running })
        assertEquals("Lab, forward-only: Trend copy", PatternLabels.describe("lab${first}_1h"))
        assertEquals("Lab, forward-only: pattern 99", PatternLabels.describe("lab99_1h"))
        assertTrue(PatternLabels.describe("donchian20_1h").startsWith("Breakout"))

        now = 3_000L
        assertTrue(store.stop(first))
        assertFalse(store.stop(first), "already stopped")
        assertFalse(store.stop(12345), "no such pattern")
        val stopped = store.all().single { it.id == first }
        assertEquals(3_000L, stopped.stoppedAt)
        assertFalse(stopped.running)
        assertEquals(3L, stopped.reportId)
        assertEquals("Because.", stopped.reason)

        val d = rdb.writableDatabase
        for (sql in listOf("UPDATE lab_patterns SET title = 'x'", "DELETE FROM lab_patterns", "UPDATE lab_stops SET stopped_at = 1", "DELETE FROM lab_stops")) {
            val e = assertFailsWith<SQLiteException>(sql) { d.execSQL(sql) }
            assertTrue(e.message!!.contains("append-only"), sql)
        }
        PatternLabels.labTitles = emptyMap()
    }
}
