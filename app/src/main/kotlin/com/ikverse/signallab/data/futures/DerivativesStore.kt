package com.ikverse.signallab.data.futures

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Funding and open interest, in their own file. Unlike candles, open interest older than 30 days cannot be downloaded
 * again, so this file belongs in backups and sync (milestone 9).
 */
class DerivativesStore(
    context: Context?,
    name: String? = "derivatives.db",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SQLiteOpenHelper(context, name, null, VERSION) {
    private val lock = Mutex()

    override fun onConfigure(db: SQLiteDatabase) {
        if (databaseName != null) setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE funding (
                symbol TEXT NOT NULL, time INTEGER NOT NULL, rate REAL NOT NULL, mark_price REAL,
                PRIMARY KEY (symbol, time)
            ) WITHOUT ROWID
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE open_interest (
                symbol TEXT NOT NULL, period TEXT NOT NULL, time INTEGER NOT NULL, contracts REAL NOT NULL, value REAL NOT NULL,
                PRIMARY KEY (symbol, period, time)
            ) WITHOUT ROWID
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private suspend fun <T> access(block: (SQLiteDatabase) -> T): T =
        lock.withLock { withContext(io) { block(writableDatabase) } }

    /** Stores settlements not already stored; returns how many were new. */
    suspend fun insertFunding(symbol: String, points: List<FundingPoint>): Int = access { db ->
        var added = 0
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT OR IGNORE INTO funding (symbol, time, rate, mark_price) VALUES (?,?,?,?)")
            for (p in points) {
                stmt.clearBindings()
                stmt.bindString(1, symbol)
                stmt.bindLong(2, p.time)
                stmt.bindDouble(3, p.rate)
                if (p.markPrice.isNaN()) stmt.bindNull(4) else stmt.bindDouble(4, p.markPrice)
                if (stmt.executeInsert() != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        added
    }

    suspend fun lastFundingTime(symbol: String): Long? = access { db ->
        db.rawQuery("SELECT MAX(time) FROM funding WHERE symbol=?", arrayOf(symbol)).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    /** Settlements from [since] on, oldest first. */
    suspend fun funding(symbol: String, since: Long = 0): List<FundingPoint> = access { db ->
        db.rawQuery("SELECT time, rate, mark_price FROM funding WHERE symbol=? AND time>=? ORDER BY time", arrayOf(symbol, since.toString())).use { c ->
            buildList { while (c.moveToNext()) add(FundingPoint(c.getLong(0), c.getDouble(1), if (c.isNull(2)) Double.NaN else c.getDouble(2))) }
        }
    }

    suspend fun insertOpenInterest(symbol: String, period: String, points: List<OpenInterestPoint>): Int = access { db ->
        var added = 0
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT OR IGNORE INTO open_interest (symbol, period, time, contracts, value) VALUES (?,?,?,?,?)")
            for (p in points) {
                stmt.clearBindings()
                stmt.bindString(1, symbol)
                stmt.bindString(2, period)
                stmt.bindLong(3, p.time)
                stmt.bindDouble(4, p.contracts)
                stmt.bindDouble(5, p.value)
                if (stmt.executeInsert() != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        added
    }

    suspend fun lastOpenInterestTime(symbol: String, period: String): Long? = access { db ->
        db.rawQuery("SELECT MAX(time) FROM open_interest WHERE symbol=? AND period=?", arrayOf(symbol, period))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    suspend fun openInterest(symbol: String, period: String, since: Long = 0): List<OpenInterestPoint> = access { db ->
        db.rawQuery("SELECT time, contracts, value FROM open_interest WHERE symbol=? AND period=? AND time>=? ORDER BY time",
            arrayOf(symbol, period, since.toString())).use { c ->
            buildList { while (c.moveToNext()) add(OpenInterestPoint(c.getLong(0), c.getDouble(1), c.getDouble(2))) }
        }
    }

    companion object {
        const val VERSION = 1
    }
}
