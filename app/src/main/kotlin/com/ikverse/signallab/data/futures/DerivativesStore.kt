package com.ikverse.signallab.data.futures

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Futures and market data that is not candles, in its own file: funding, open interest, the futures basis, the positioning
 * ratios, Deribit's volatility index and exchange notices. Open interest, basis and positioning older than 30 days cannot
 * be downloaded again; basis and positioning travel in device sync (LedgerSync).
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
        createVersion2(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createVersion2(db)
    }

    /** Version 2 (Signal Lab 2.2): the futures basis, the positioning ratios, Deribit's volatility index and exchange notices. */
    private fun createVersion2(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS basis (
                pair TEXT NOT NULL, contract TEXT NOT NULL, time INTEGER NOT NULL, futures_price REAL NOT NULL, index_price REAL NOT NULL,
                basis_rate REAL NOT NULL, annualized REAL,
                PRIMARY KEY (pair, contract, time)
            ) WITHOUT ROWID
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS positioning (
                symbol TEXT NOT NULL, kind TEXT NOT NULL, time INTEGER NOT NULL, long_side REAL NOT NULL, short_side REAL NOT NULL,
                ratio REAL NOT NULL,
                PRIMARY KEY (symbol, kind, time)
            ) WITHOUT ROWID
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS dvol (
                currency TEXT NOT NULL, time INTEGER NOT NULL, open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL,
                PRIMARY KEY (currency, time)
            ) WITHOUT ROWID
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS notices (
                source TEXT NOT NULL, id TEXT NOT NULL, coin TEXT NOT NULL, time INTEGER NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL,
                PRIMARY KEY (source, id, coin)
            ) WITHOUT ROWID
        """.trimIndent())
    }

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

    /** Stores basis points not already stored; returns how many were new. */
    suspend fun insertBasis(pair: String, contract: String, points: List<BasisPoint>): Int = insertAll(
        "INSERT OR IGNORE INTO basis (pair, contract, time, futures_price, index_price, basis_rate, annualized) VALUES (?,?,?,?,?,?,?)", points,
    ) { stmt, p ->
        stmt.bindString(1, pair)
        stmt.bindString(2, contract)
        stmt.bindLong(3, p.time)
        stmt.bindDouble(4, p.futuresPrice)
        stmt.bindDouble(5, p.indexPrice)
        stmt.bindDouble(6, p.basisRate)
        if (p.annualized.isNaN()) stmt.bindNull(7) else stmt.bindDouble(7, p.annualized)
    }

    suspend fun lastBasisTime(pair: String, contract: String): Long? = maxTime("SELECT MAX(time) FROM basis WHERE pair=? AND contract=?", pair, contract)

    /** Basis points from [since] on, oldest first. */
    suspend fun basis(pair: String, contract: String, since: Long = 0): List<BasisPoint> = access { db ->
        db.rawQuery("SELECT time, futures_price, index_price, basis_rate, annualized FROM basis WHERE pair=? AND contract=? AND time>=? ORDER BY time",
            arrayOf(pair, contract, since.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(BasisPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), if (c.isNull(4)) Double.NaN else c.getDouble(4)))
            }
        }
    }

    /** Every stored basis series, as (pair, contract) pairs, for sync. */
    suspend fun basisSeries(): List<Pair<String, String>> = access { db ->
        db.rawQuery("SELECT DISTINCT pair, contract FROM basis", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    }

    suspend fun insertPositioning(symbol: String, kind: Positioning, points: List<PositioningPoint>): Int = insertAll(
        "INSERT OR IGNORE INTO positioning (symbol, kind, time, long_side, short_side, ratio) VALUES (?,?,?,?,?,?)", points,
    ) { stmt, p ->
        stmt.bindString(1, symbol)
        stmt.bindString(2, kind.label)
        stmt.bindLong(3, p.time)
        stmt.bindDouble(4, p.longSide)
        stmt.bindDouble(5, p.shortSide)
        stmt.bindDouble(6, p.ratio)
    }

    suspend fun lastPositioningTime(symbol: String, kind: Positioning): Long? =
        maxTime("SELECT MAX(time) FROM positioning WHERE symbol=? AND kind=?", symbol, kind.label)

    suspend fun positioning(symbol: String, kind: Positioning, since: Long = 0): List<PositioningPoint> = access { db ->
        db.rawQuery("SELECT time, long_side, short_side, ratio FROM positioning WHERE symbol=? AND kind=? AND time>=? ORDER BY time",
            arrayOf(symbol, kind.label, since.toString())).use { c ->
            buildList { while (c.moveToNext()) add(PositioningPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3))) }
        }
    }

    /** Every stored positioning series, as (symbol, kind) pairs, for sync. */
    suspend fun positioningSeries(): List<Pair<String, Positioning>> = access { db ->
        db.rawQuery("SELECT DISTINCT symbol, kind FROM positioning", null).use { c ->
            buildList { while (c.moveToNext()) Positioning.entries.firstOrNull { it.label == c.getString(1) }?.let { add(c.getString(0) to it) } }
        }
    }

    /** Stores DVOL days; a day already stored is replaced, because today's candle changes until the day closes. */
    suspend fun insertDvol(currency: String, points: List<DvolPoint>): Int = insertAll(
        "INSERT OR REPLACE INTO dvol (currency, time, open, high, low, close) VALUES (?,?,?,?,?,?)", points,
    ) { stmt, p ->
        stmt.bindString(1, currency)
        stmt.bindLong(2, p.time)
        stmt.bindDouble(3, p.open)
        stmt.bindDouble(4, p.high)
        stmt.bindDouble(5, p.low)
        stmt.bindDouble(6, p.close)
    }

    suspend fun lastDvolTime(currency: String): Long? = maxTime("SELECT MAX(time) FROM dvol WHERE currency=?", currency)

    suspend fun dvol(currency: String, since: Long = 0): List<DvolPoint> = access { db ->
        db.rawQuery("SELECT time, open, high, low, close FROM dvol WHERE currency=? AND time>=? ORDER BY time", arrayOf(currency, since.toString())).use { c ->
            buildList { while (c.moveToNext()) add(DvolPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4))) }
        }
    }

    suspend fun insertNotices(points: List<Notice>): Int = insertAll(
        "INSERT OR IGNORE INTO notices (source, id, coin, time, kind, title) VALUES (?,?,?,?,?,?)", points,
    ) { stmt, n ->
        stmt.bindString(1, n.source)
        stmt.bindString(2, n.id)
        stmt.bindString(3, n.coin)
        stmt.bindLong(4, n.time)
        stmt.bindString(5, n.kind.name)
        stmt.bindString(6, n.title)
    }

    /** Notices published from [since] on, newest first. */
    suspend fun notices(since: Long = 0): List<Notice> = access { db ->
        db.rawQuery("SELECT source, id, time, kind, coin, title FROM notices WHERE time>=? ORDER BY time DESC", arrayOf(since.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val kind = NoticeKind.entries.firstOrNull { it.name == c.getString(3) } ?: continue
                    add(Notice(c.getString(0), c.getString(1), c.getLong(2), kind, c.getString(4), c.getString(5)))
                }
            }
        }
    }

    private suspend fun maxTime(sql: String, vararg args: String): Long? = access { db ->
        db.rawQuery(sql, arrayOf(*args)).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    private suspend fun <T> insertAll(sql: String, points: List<T>, bind: (SQLiteStatement, T) -> Unit): Int = access { db ->
        var added = 0
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(sql)
            for (p in points) {
                stmt.clearBindings()
                bind(stmt, p)
                if (stmt.executeInsert() != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        added
    }

    companion object {
        const val VERSION = 2
    }
}
