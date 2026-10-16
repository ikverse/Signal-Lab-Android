package com.ikverse.signallab.ledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.ledger.legacy.SignalLabV6
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LegacyImporterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val legacyName = "signal_lab_import_test.db"
    private lateinit var ledger: LedgerDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(legacyName)
        File(context.filesDir, "legacy").deleteRecursively()
        ledger = LedgerDatabase.open(context, name = null)
    }

    @After
    fun tearDown() {
        ledger.close()
        context.deleteDatabase(legacyName)
    }

    private fun importer() = LegacyImporter(context, ledger, now = { 1_760_000_000_000L }, legacyName = legacyName, io = Dispatchers.Unconfined)

    /** A Signal Lab 1 record at version 6 with one of everything worth importing. */
    private fun writeFixture() {
        SignalLabV6(context, legacyName).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO watchlists (id, name, name_key, active, created_at, position, timeframes, sync_id, updated_at) VALUES (1, 'Majors', 'majors', 1, 1, 0, '1h,4h,1d', 'list-a', 1)")
            db.execSQL("INSERT INTO watchlists (id, name, name_key, active, created_at, position, timeframes, sync_id, updated_at) VALUES (2, 'Old', 'old', 0, 1, 1, '1h', 'list-b', 1)")
            db.execSQL("INSERT INTO watchlist_coins VALUES (1, 'BTCUSDT', 1), (1, 'SOLUSDT', 2), (2, 'XRPUSDT', 3)")
            db.execSQL("INSERT INTO watchlist_tombstones VALUES ('list-gone', 7, 50)")
            db.execSQL("INSERT INTO variants VALUES ('donchian20_1d', 'donchian', '1d', '{}', 1), ('trend_ma20_1d', 'trend_state', '1d', '{}', 1), " +
                "('fade1h_1h', 'big_move_fade', '1h', '{}', 2), ('lab1_4h', 'lab', '4h', '{}', 3)")
            // A closed trade on list 1, an open one on a deleted list, and an old one with no cost or exit mode stored.
            db.execSQL("INSERT INTO live_trades (id, variant, family, symbol, tf, list_id, bar_time, detected_at, regime, entry_time, entry_price, " +
                "target, stop, hold_bars, exit_due, opened_at, cost, exit_mode, atr) VALUES " +
                "(1, 'donchian20_1d', 'donchian', 'SOLUSDT', '1d', 1, 100, 101, 1, 200, 10.0, NULL, 9.0, 30, 999, 201, 0.002, 'trail', 0.5), " +
                "(2, 'fade1h_1h', 'big_move_fade', 'ETHUSDT', '1h', 7, 300, 301, 0, 400, 20.0, 21.0, 19.0, 6, 999, 401, 0.002, 'learned', 0.4), " +
                "(3, 'trend_ma20_1d', 'trend_state', 'ADAUSDT', '1d', 0, 500, 501, 1, 600, 0.5, 0.6, 0.45, 7, 999, 601, NULL, NULL, NULL)")
            db.execSQL("INSERT INTO live_exits (trade_id, exit_time, exit_price, exit_reason, bars_held, gross, net, random_mean, excess, closed_at, max_up, max_down, bars_to_peak) " +
                "VALUES (1, 900, 11.0, 'stop', 5, 0.1, 0.098, 0.01, 0.088, 901, 0.2, -0.05, 3)")
            db.execSQL("INSERT INTO analyst_reports (id, received_at, card, title, body, sync_id) VALUES (1, 10, 'weekly-review', 'Week', 'Body', 'rep-1')")
            db.execSQL("INSERT INTO lab_patterns (id, started_at, report_id, title, reason, definition, sync_id) VALUES (1, 20, 1, 'RSI dip', 'why', '{\"charts\":[\"4h\"]}', 'lab-1')")
            db.execSQL("INSERT INTO lab_stops VALUES (1, 30)")
            db.execSQL("INSERT INTO settings (key, value, updated_at) VALUES ('fee_per_side', '0.00075', 5), ('scan_in_background', '1', 6), " +
                "('follow_fast_charts', '1', 7), ('asked_alerts', '1', 8), ('scan_cursor_1h', '123', 9)")
            repeat(5) { db.execSQL("INSERT INTO alerts (ts, kind, title, body) VALUES ($it, 'signal', 't', 'b')") }
        }
    }

    @Test
    fun withNoOldFileThereIsNothingToImport() = runTest {
        assertIs<LegacyImporter.Result.NoLegacyFile>(importer().run())
    }

    @Test
    fun everyKindOfRecordComesOverOnce() = runTest {
        writeFixture()
        val result = assertIs<LegacyImporter.Result.Imported>(importer().run())
        val log = result.log
        assertEquals(3, log.calls)
        assertEquals(1, log.exits)
        assertEquals(4, log.strategies)
        assertEquals(1, log.customs)
        assertEquals(1, log.reports)
        assertEquals(2, log.pins, "the coins of the active list only")
        assertEquals(3, log.settings, "fee, the renamed background switch, and a permission already asked")
        assertEquals(5, log.alertsDropped)
        assertTrue(File(log.copyPath).exists(), "a copy of the old file is kept")

        val dao = ledger.dao()
        val calls = dao.allCalls().associateBy { it.legacyId }
        assertEquals("v1:donchian20_1d", calls.getValue(1).strategyId)
        assertEquals("list-a", calls.getValue(1).scope)
        assertEquals("v1", calls.getValue(1).status)
        assertEquals("list-gone", calls.getValue(2).scope, "a deleted list is named by its tombstone")
        assertEquals("v1", calls.getValue(2).status)
        assertTrue(calls.values.all { it.archived && it.source == "v1" })
        val old = calls.getValue(3)
        assertEquals("", old.scope)
        assertTrue(old.costAssumed)
        assertEquals(EngineConfig.costFor("ADAUSDT"), old.cost)
        assertEquals("classic", old.exitMode, "a stored target means the classic exit")
        val exit = dao.exitOf(calls.getValue(1).id)!!
        assertEquals("stop", exit.reason)
        assertEquals(0.088, exit.excess)
        assertNull(dao.exitOf(calls.getValue(2).id))
        assertEquals(4, dao.strategiesTried())
        assertEquals(setOf("BTCUSDT", "SOLUSDT"), dao.pins().map { it.symbol }.toSet())
        assertTrue(dao.pins().all { it.tag == "Majors" && it.kind == "pin" })
        assertEquals("1", dao.setting("background_enabled"))
        assertNull(dao.setting("follow_fast_charts"))
        assertNull(dao.setting("scan_cursor_1h"))
    }

    @Test
    fun aSecondRunAddsNothing() = runTest {
        writeFixture()
        importer().run()
        assertIs<LegacyImporter.Result.AlreadyImported>(importer().run())
        assertEquals(3, ledger.dao().countCalls("v1"))
        assertEquals(1, ledger.dao().importLogs().size)
    }

    @Test
    fun aChangedFileOnlyAddsWhatIsNew() = runTest {
        writeFixture()
        importer().run()
        SignalLabV6(context, legacyName).use { h ->
            h.writableDatabase.execSQL("INSERT INTO live_trades (id, variant, family, symbol, tf, list_id, bar_time, detected_at, regime, entry_time, entry_price, " +
                "target, stop, hold_bars, exit_due, opened_at) VALUES (4, 'donchian20_1d', 'donchian', 'BTCUSDT', '1d', 0, 700, 701, 1, 800, 50.0, NULL, NULL, 10, 999, 801)")
        }
        val second = assertIs<LegacyImporter.Result.Imported>(importer().run())
        assertEquals(1, second.log.calls)
        assertEquals(ledger.dao().importLogs().first().copyPath, second.log.copyPath, "later runs keep pointing at the first copy")
        // Only this test's copies (named by its clock): the app's own start may import its own file in the background.
        assertEquals(1, File(context.filesDir, "legacy").listFiles()!!.count { it.name.startsWith("signal_lab.v6.1760000000000.") })
        assertEquals(0, second.log.strategies)
        assertEquals(4, ledger.dao().countCalls("v1"))
    }

    @Test
    fun theLedgerRefusesToRewriteItsRecord() = runTest {
        writeFixture()
        importer().run()
        val db = ledger.openHelper.writableDatabase
        assertFailsWith<SQLiteException> { db.execSQL("UPDATE calls SET entry_price = 1") }
        assertFailsWith<SQLiteException> { db.execSQL("DELETE FROM exits") }
        assertFailsWith<SQLiteException> { db.execSQL("DELETE FROM strategies") }
    }

    @Test
    fun aFileFromSignalLab1sFirstVersionIsUpgradedThenImported() = runTest {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(legacyName), null).use { db ->
            SignalLabV6.MIGRATIONS[0].forEach(db::execSQL)
            db.execSQL("INSERT INTO live_trades (id, variant, family, symbol, tf, list_id, bar_time, detected_at, regime, entry_time, entry_price, " +
                "target, stop, hold_bars, exit_due, opened_at) VALUES (1, 'donchian20_1d', 'donchian', 'BTCUSDT', '1d', 0, 1, 2, 1, 3, 50.0, 55.0, 48.0, 7, 999, 4)")
            db.version = 1
        }
        val result = assertIs<LegacyImporter.Result.Imported>(importer().run())
        assertEquals(1, result.log.calls)
        val call = ledger.dao().allCalls().single()
        assertTrue(call.costAssumed)
        assertEquals("classic", call.exitMode)
    }
}
