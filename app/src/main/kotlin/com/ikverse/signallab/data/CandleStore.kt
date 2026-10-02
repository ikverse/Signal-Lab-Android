package com.ikverse.signallab.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A pair as the pair list describes it, with what the picker needs to rank and filter it. */
data class CoinRow(
    val symbol: String,
    val base: String,
    val status: String,
    /** Passed the picker's filter (not a stablecoin, wrapped asset or leveraged token). */
    val offered: Boolean,
    /** Its daily closes sat inside the stablecoin band for 30 days. Set once history arrives. */
    val stable: Boolean,
    /** Binance no longer trades it. Its stored history is kept. */
    val delisted: Boolean,
    val quoteVolume: Double,
    val high24: Double,
    val low24: Double,
    val seenAt: Long,
    /** When Binance first listed it (its first daily candle); null until it has been asked once. */
    val listedAt: Long? = null,
)

/**
 * Market data the app can download again: candles, the pair list, and stretches Binance has no
 * candles for. It lives in its own file and is deliberately outside every backup, so a backup stays
 * small and a restore re-downloads rather than trusting a copy.
 *
 * Android 10 ships SQLite 3.22, which has no UPSERT, so writes are `INSERT OR IGNORE` and an
 * update followed by an insert where needed.
 */
class CandleStore(
    context: Context?,
    name: String? = "candles.db",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SQLiteOpenHelper(context, name, null, VERSION) {
    private val lock = Mutex()

    override fun onConfigure(db: SQLiteDatabase) {
        if (databaseName != null) setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE candles (
                symbol TEXT NOT NULL, tf TEXT NOT NULL, open_time INTEGER NOT NULL,
                open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL,
                volume REAL NOT NULL, close_time INTEGER NOT NULL,
                PRIMARY KEY (symbol, tf, open_time)
            ) WITHOUT ROWID
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE coins (
                symbol TEXT PRIMARY KEY, base TEXT NOT NULL, status TEXT NOT NULL,
                offered INTEGER NOT NULL, stable INTEGER NOT NULL DEFAULT 0, delisted INTEGER NOT NULL DEFAULT 0,
                quote_volume REAL NOT NULL DEFAULT 0, high24 REAL NOT NULL DEFAULT 0, low24 REAL NOT NULL DEFAULT 0,
                seen_at INTEGER NOT NULL, listed_at INTEGER
            )
        """.trimIndent())
        // A stretch Binance has no candles for (maintenance, a halt). Remembered so it is not asked for again forever.
        db.execSQL("""
            CREATE TABLE known_gaps (
                symbol TEXT NOT NULL, tf TEXT NOT NULL, from_time INTEGER NOT NULL, to_time INTEGER NOT NULL,
                PRIMARY KEY (symbol, tf, from_time)
            ) WITHOUT ROWID
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This file only holds what can be downloaded again, so an incompatible change may drop and rebuild it
        // rather than migrate it. Version 2 only adds when each coin was listed.
        if (oldVersion < 2) db.execSQL("ALTER TABLE coins ADD COLUMN listed_at INTEGER")
    }

    private suspend fun <T> access(block: (SQLiteDatabase) -> T): T =
        lock.withLock { withContext(io) { block(writableDatabase) } }

    // --- candles -------------------------------------------------------------------

    /** Stores candles that are not already stored; returns how many were new. */
    suspend fun insertKlines(symbol: String, tf: Timeframe, klines: List<Kline>): Int = access { db ->
        var added = 0
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT OR IGNORE INTO candles VALUES (?,?,?,?,?,?,?,?,?)")
            for (k in klines) {
                stmt.clearBindings()
                stmt.bindString(1, symbol)
                stmt.bindString(2, tf.label)
                stmt.bindLong(3, k.openTime)
                stmt.bindDouble(4, k.open)
                stmt.bindDouble(5, k.high)
                stmt.bindDouble(6, k.low)
                stmt.bindDouble(7, k.close)
                stmt.bindDouble(8, k.volume)
                stmt.bindLong(9, k.closeTime)
                if (stmt.executeInsert() != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        added
    }

    suspend fun firstOpen(symbol: String, tf: Timeframe): Long? = scalar("MIN(open_time)", symbol, tf)

    suspend fun lastOpen(symbol: String, tf: Timeframe): Long? = scalar("MAX(open_time)", symbol, tf)

    private suspend fun scalar(expr: String, symbol: String, tf: Timeframe): Long? = access { db ->
        db.rawQuery("SELECT $expr FROM candles WHERE symbol=? AND tf=?", arrayOf(symbol, tf.label)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    }

    suspend fun count(symbol: String, tf: Timeframe): Int = access { db ->
        db.rawQuery("SELECT COUNT(*) FROM candles WHERE symbol=? AND tf=?", arrayOf(symbol, tf.label)).use {
            it.moveToFirst()
            it.getInt(0)
        }
    }

    suspend fun openTimes(symbol: String, tf: Timeframe, since: Long): LongArray = access { db ->
        db.rawQuery("SELECT open_time FROM candles WHERE symbol=? AND tf=? AND open_time>=? ORDER BY open_time",
            arrayOf(symbol, tf.label, since.toString())).use { c ->
            LongArray(c.count) { c.moveToNext(); c.getLong(0) }
        }
    }

    /** The stored candles from [since] on, as the engine's type, with exactly the doubles that were stored. Null when there are none. */
    suspend fun window(symbol: String, tf: Timeframe, since: Long = 0): Candles? = access { db ->
        db.rawQuery("SELECT open_time, open, high, low, close, volume, close_time FROM candles " +
            "WHERE symbol=? AND tf=? AND open_time>=? ORDER BY open_time", arrayOf(symbol, tf.label, since.toString())).use { c ->
            val n = c.count
            if (n == 0) return@use null
            val t = LongArray(n); val o = DoubleArray(n); val h = DoubleArray(n); val l = DoubleArray(n)
            val cl = DoubleArray(n); val v = DoubleArray(n); val ct = LongArray(n)
            var i = 0
            while (c.moveToNext()) {
                t[i] = c.getLong(0); o[i] = c.getDouble(1); h[i] = c.getDouble(2); l[i] = c.getDouble(3)
                cl[i] = c.getDouble(4); v[i] = c.getDouble(5); ct[i] = c.getLong(6)
                i++
            }
            Candles(symbol, tf, t, o, h, l, cl, v, ct)
        }
    }

    /** The last [n] daily closes, oldest first. */
    suspend fun dailyCloses(symbol: String, n: Int): DoubleArray = access { db ->
        db.rawQuery("SELECT close FROM (SELECT open_time, close FROM candles WHERE symbol=? AND tf=? " +
            "ORDER BY open_time DESC LIMIT ?) ORDER BY open_time", arrayOf(symbol, Timeframe.D1.label, n.toString())).use { c ->
            DoubleArray(c.count) { c.moveToNext(); c.getDouble(0) }
        }
    }

    suspend fun totalRows(): Long = access { db ->
        db.rawQuery("SELECT COUNT(*) FROM candles", null).use { it.moveToFirst(); it.getLong(0) }
    }

    suspend fun rowsByTimeframe(): Map<Timeframe, Long> = access { db ->
        db.rawQuery("SELECT tf, COUNT(*) FROM candles GROUP BY tf", null).use { c ->
            buildMap { while (c.moveToNext()) put(Timeframe.of(c.getString(0)), c.getLong(1)) }
        }
    }

    // --- stretches with no candles -----------------------------------------------------

    suspend fun knownGaps(symbol: String, tf: Timeframe): List<LongRange> = access { db ->
        db.rawQuery("SELECT from_time, to_time FROM known_gaps WHERE symbol=? AND tf=? ORDER BY from_time",
            arrayOf(symbol, tf.label)).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)..c.getLong(1)) } }
    }

    suspend fun addKnownGap(symbol: String, tf: Timeframe, range: LongRange) {
        access { db ->
            db.execSQL("INSERT OR REPLACE INTO known_gaps VALUES (?,?,?,?)", arrayOf<Any?>(symbol, tf.label, range.first, range.last))
        }
    }

    // --- the pair list ------------------------------------------------------------------

    /**
     * Records a fresh pair list. Coins in it are updated (keeping a stablecoin mark), new ones added,
     * and coins that were known but are no longer in it are marked delisted, history kept.
     */
    suspend fun replaceCoins(rows: List<CoinRow>) {
        access { db ->
            db.beginTransaction()
            try {
                val seen = HashSet<String>()
                for (r in rows) {
                    seen.add(r.symbol)
                    db.execSQL("UPDATE coins SET base=?, status=?, offered=?, delisted=0, quote_volume=?, high24=?, low24=?, seen_at=? WHERE symbol=?",
                        arrayOf<Any?>(r.base, r.status, if (r.offered) 1 else 0, r.quoteVolume, r.high24, r.low24, r.seenAt, r.symbol))
                    db.execSQL("INSERT OR IGNORE INTO coins (symbol, base, status, offered, stable, delisted, quote_volume, high24, low24, seen_at) " +
                        "VALUES (?,?,?,?,0,0,?,?,?,?)",
                        arrayOf<Any?>(r.symbol, r.base, r.status, if (r.offered) 1 else 0, r.quoteVolume, r.high24, r.low24, r.seenAt))
                }
                val known = db.rawQuery("SELECT symbol FROM coins WHERE delisted=0", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) }
                }
                for (s in known) if (s !in seen) db.execSQL("UPDATE coins SET delisted=1 WHERE symbol=?", arrayOf(s))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun coin(symbol: String): CoinRow? = access { db ->
        db.rawQuery("SELECT $COIN_COLUMNS FROM coins WHERE symbol=?", arrayOf(symbol)).use { c -> if (c.moveToFirst()) coinOf(c) else null }
    }

    /** What the picker offers, biggest 24-hour volume first. [query] matches the start or any part of a name. */
    suspend fun offeredCoins(query: String, limit: Int): List<CoinRow> = access { db ->
        val like = "%" + query.trim().uppercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        db.rawQuery("SELECT $COIN_COLUMNS FROM coins WHERE offered=1 AND stable=0 AND delisted=0 AND status='TRADING' " +
            "AND (symbol LIKE ? ESCAPE '\\' OR base LIKE ? ESCAPE '\\') ORDER BY quote_volume DESC, symbol LIMIT ?",
            arrayOf(like, like, limit.toString())).use { c -> buildList { while (c.moveToNext()) add(coinOf(c)) } }
    }

    /**
     * Marks a coin as a stablecoin. A coin with no row yet gets a minimal one, hidden from the picker,
     * so the mark is never lost; the next pair-list refresh fills in the rest and keeps the mark.
     */
    suspend fun markStable(symbol: String) {
        access { db ->
            db.execSQL("UPDATE coins SET stable=1 WHERE symbol=?", arrayOf<Any?>(symbol))
            db.execSQL("INSERT OR IGNORE INTO coins (symbol, base, status, offered, stable, delisted, seen_at) VALUES (?,?,'UNKNOWN',0,1,0,0)",
                arrayOf<Any?>(symbol, symbol.removeSuffix("USDT")))
        }
    }

    /** Which of [symbols] are neither delisted nor stablecoins, so are worth analysing. Unknown symbols are kept. */
    suspend fun analysable(symbols: Collection<String>): Set<String> = access { db ->
        val bad = db.rawQuery("SELECT symbol FROM coins WHERE stable=1 OR delisted=1", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        symbols.filterTo(LinkedHashSet()) { it !in bad }
    }

    /** When Binance listed [symbol], or null if it has not been looked up. */
    suspend fun listedAt(symbol: String): Long? = access { db ->
        db.rawQuery("SELECT listed_at FROM coins WHERE symbol=?", arrayOf(symbol)).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    /** Remembers when [symbol] was listed. A coin with no row yet gets a minimal one, which the next pair-list refresh fills in. */
    suspend fun setListedAt(symbol: String, time: Long) {
        access { db ->
            db.execSQL("INSERT OR IGNORE INTO coins (symbol, base, status, offered, stable, delisted, seen_at) VALUES (?,?,'UNKNOWN',0,0,0,0)",
                arrayOf<Any?>(symbol, symbol.removeSuffix("USDT")))
            db.execSQL("UPDATE coins SET listed_at=? WHERE symbol=?", arrayOf<Any?>(time, symbol))
        }
    }

    /**
     * Deletes what is no longer needed: candles older than [keepDays] allows for each chart, and every candle of a chart
     * no list uses ([inUse]), except [keep], the one series that is always kept (BTC's daily chart, for the market regime).
     * Trades and scores live in the record database and are never touched. Returns how many candles went.
     */
    suspend fun trim(now: Long, keepDays: (Timeframe) -> Int, inUse: Set<Timeframe>, keep: Pair<String, Timeframe>): Int = access { db ->
        var removed = 0
        for (tf in Timeframe.entries) {
            val cutoff = now - keepDays(tf) * 86_400_000L
            if (tf in inUse) {
                removed += db.delete("candles", "tf=? AND open_time<?", arrayOf(tf.label, cutoff.toString()))
            } else if (tf == keep.second) {
                removed += db.delete("candles", "tf=? AND (symbol<>? OR open_time<?)", arrayOf(tf.label, keep.first, cutoff.toString()))
            } else {
                removed += db.delete("candles", "tf=?", arrayOf(tf.label))
            }
            db.delete("known_gaps", "tf=? AND to_time<?", arrayOf(tf.label, cutoff.toString()))
        }
        removed
    }

    suspend fun lastPairListRefresh(): Long? = access { db ->
        db.rawQuery("SELECT MAX(seen_at) FROM coins", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    suspend fun coinCount(): Pair<Int, Int> = access { db ->
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(offered=1 AND stable=0 AND delisted=0), 0) FROM coins", null).use {
            it.moveToFirst()
            it.getInt(0) to it.getInt(1)
        }
    }

    private fun coinOf(c: android.database.Cursor) = CoinRow(
        c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getInt(4) == 1, c.getInt(5) == 1,
        c.getDouble(6), c.getDouble(7), c.getDouble(8), c.getLong(9), if (c.isNull(10)) null else c.getLong(10),
    )

    companion object {
        const val VERSION = 2
        private const val COIN_COLUMNS = "symbol, base, status, offered, stable, delisted, quote_volume, high24, low24, seen_at, listed_at"
    }
}
