package com.ikverse.signallab.ledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A ledger written by Signal Lab 2.1 (Room version 1) opens in 2.3 with its calls intact and still append-only. */
@RunWith(RobolectricTestRunner::class)
class LedgerMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Builds a version 1 file exactly as Room's exported schema describes it, with one call in it. */
    private fun versionOneFile(name: String) {
        val schema = JSONObject(File("schemas/com.ikverse.signallab.ledger.LedgerDatabase/1.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            e.optJSONArray("indices")?.let { ix -> for (k in 0 until ix.length()) db.execSQL(ix.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", table)) }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.execSQL("INSERT INTO calls (strategy_id, symbol, tf, signal_time, entry_time, entry_price, max_bars, regime, size_fraction, cost, cost_assumed, scope, source, archived, status, opened_at) " +
            "VALUES ('breakout50_1d', 'SOLUSDT', '1d', 10, 20, 142.3, 60, 1, 0.12, 0.004, 0, '', 'v2', 0, 'live', 30)")
        db.version = 1
        db.close()
    }

    @Test
    fun aVersionOneLedgerGainsTheNewColumnsAndKeepsItsCalls() = runTest {
        val name = "ledger-migration-test.db"
        context.deleteDatabase(name)
        versionOneFile(name)
        val ledger = LedgerDatabase.open(context, name)
        val call = ledger.dao().allCalls().single()
        assertEquals("SOLUSDT", call.symbol)
        assertEquals(0.12, call.sizeFraction)
        assertNull(call.plainSize)
        assertNull(call.veto)
        val id = ledger.dao().insertCall(call.copy(id = 0, symbol = "ETHUSDT", plainSize = 0.1, veto = "leverage is overheated"))
        assertEquals("leverage is overheated", ledger.dao().allCalls().first { it.id == id }.veto)
        val refused = runCatching { ledger.openHelper.writableDatabase.execSQL("UPDATE calls SET symbol='X'") }.exceptionOrNull()
        assertTrue(refused != null && refused.message.orEmpty().contains("append-only"), "the record is still append-only: $refused")
        ledger.close()
        context.deleteDatabase(name)
    }
}
