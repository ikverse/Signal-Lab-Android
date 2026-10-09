package com.ikverse.signallab.data

import android.content.ContentValues
import com.ikverse.signallab.engine.Refusal
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.Watchlist
import com.ikverse.signallab.engine.WatchlistRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface WatchlistResult<out T> {
    data class Ok<T>(val value: T) : WatchlistResult<T>
    data class Refused(val reason: Refusal) : WatchlistResult<Nothing>
}

/**
 * The user's watchlists. Every rule is enforced here, not only in the screens: at most 30 coins in a
 * list, no coin twice in a list, at most 150 distinct coins across active lists, and a coin must be
 * one the picker offers. Only active lists are analysed ([activeCoins]).
 */
class WatchlistRepository(
    private val db: RecordDatabase,
    private val universe: UniverseRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val state = MutableStateFlow<List<Watchlist>>(emptyList())

    /** Every list in the user's order. Updated after every change. */
    val lists: StateFlow<List<Watchlist>> = state.asStateFlow()

    /** Reads the lists from the database. Call once at start; changes keep [lists] current after that. */
    suspend fun load() = lock.withLock { reload() }

    /** The distinct coins across every active list. */
    fun activeCoins(): Set<String> = WatchlistRules.activeCoins(state.value)

    private suspend fun reload() {
        state.value = withContext(io) {
            val d = db.writableDatabase
            val coins = HashMap<Long, MutableList<String>>()
            d.rawQuery("SELECT list_id, symbol FROM watchlist_coins ORDER BY added_at, symbol", null).use { c ->
                while (c.moveToNext()) coins.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getString(1))
            }
            d.rawQuery("SELECT id, name, active, timeframes FROM watchlists ORDER BY position, id", null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(Watchlist(c.getLong(0), c.getString(1), coins[c.getLong(0)] ?: emptyList(), c.getInt(2) == 1,
                            Timeframe.parseSet(c.getString(3)).ifEmpty { Timeframe.LEGACY_DEFAULT }))
                    }
                }
            }
        }
    }

    private fun find(id: Long): Watchlist? = state.value.firstOrNull { it.id == id }

    /** Marks a list as changed now, so that its copy here is the newer one when devices compare. */
    private fun touch(id: Long) {
        db.writableDatabase.execSQL("UPDATE watchlists SET updated_at=? WHERE id=?", arrayOf<Any?>(clock(), id))
    }

    suspend fun create(name: String): WatchlistResult<Watchlist> = lock.withLock {
        val (clean, refusal) = WatchlistRules.cleanName(name, state.value.map { it.name })
        if (clean == null) return@withLock WatchlistResult.Refused(refusal!!)
        val id = withContext(io) {
            val d = db.writableDatabase
            val next = d.rawQuery("SELECT COALESCE(MAX(position), 0) + 1 FROM watchlists", null).use { it.moveToFirst(); it.getInt(0) }
            d.insert("watchlists", null, ContentValues().apply {
                val now = clock()
                put("name", clean); put("name_key", clean.lowercase()); put("active", 0); put("created_at", now); put("position", next)
                put("timeframes", Timeframe.formatSet(Timeframe.NEW_LIST_DEFAULT)); put("sync_id", RecordDatabase.newSyncId()); put("updated_at", now)
            })
        }
        reload()
        WatchlistResult.Ok(find(id)!!)
    }

    /**
     * Makes a list with its charts and coins, and switches it on if asked, as one step. Everything is checked first and then
     * written in a single transaction, so [lists] never shows it half made (an empty list that is then filled coin by coin),
     * and it is written whole even if whoever asked has gone away by then, such as a screen that was replaced.
     */
    suspend fun createWith(name: String, symbols: List<String>, timeframes: Set<Timeframe>, activate: Boolean): WatchlistResult<Watchlist> = lock.withLock {
        val (clean, refusal) = WatchlistRules.cleanName(name, state.value.map { it.name })
        if (clean == null) return@withLock WatchlistResult.Refused(refusal!!)
        val coins = symbols.distinct()
        if (timeframes.isEmpty()) return@withLock WatchlistResult.Refused(Refusal.NO_TIMEFRAME)
        if (coins.size > WatchlistRules.MAX_COINS_PER_LIST) return@withLock WatchlistResult.Refused(Refusal.LIST_FULL)
        for (s in coins) if (!universe.isOffered(s)) return@withLock WatchlistResult.Refused(Refusal.UNKNOWN_COIN)
        if (activate) {
            val draft = Watchlist(0, clean, coins, false, timeframes)
            WatchlistRules.checkActivate(state.value + draft, draft)?.let { return@withLock WatchlistResult.Refused(it) }
        }
        withContext(NonCancellable) {
            val id = withContext(io) {
                val d = db.writableDatabase
                d.beginTransaction()
                try {
                    val next = d.rawQuery("SELECT COALESCE(MAX(position), 0) + 1 FROM watchlists", null).use { it.moveToFirst(); it.getInt(0) }
                    val now = clock()
                    val made = d.insert("watchlists", null, ContentValues().apply {
                        put("name", clean); put("name_key", clean.lowercase()); put("active", if (activate) 1 else 0); put("created_at", now); put("position", next)
                        put("timeframes", Timeframe.formatSet(timeframes)); put("sync_id", RecordDatabase.newSyncId()); put("updated_at", now)
                    })
                    // One tick apart, so the list keeps the order the coins were chosen in.
                    coins.forEachIndexed { i, s -> d.execSQL("INSERT INTO watchlist_coins VALUES (?,?,?)", arrayOf<Any?>(made, s, now + i)) }
                    d.setTransactionSuccessful()
                    made
                } finally {
                    d.endTransaction()
                }
            }
            reload()
            WatchlistResult.Ok(find(id)!!)
        }
    }

    suspend fun rename(id: Long, name: String): WatchlistResult<Unit> = lock.withLock {
        if (find(id) == null) return@withLock WatchlistResult.Refused(Refusal.LIST_NOT_FOUND)
        val (clean, refusal) = WatchlistRules.cleanName(name, state.value.filter { it.id != id }.map { it.name })
        if (clean == null) return@withLock WatchlistResult.Refused(refusal!!)
        withContext(io) {
            db.writableDatabase.execSQL("UPDATE watchlists SET name=?, name_key=?, updated_at=? WHERE id=?", arrayOf<Any?>(clean, clean.lowercase(), clock(), id))
        }
        reload()
        WatchlistResult.Ok(Unit)
    }

    /** Deletes a list. Paper trades already open on its coins are not touched. Returns the coins it held. */
    suspend fun delete(id: Long): WatchlistResult<List<String>> = lock.withLock {
        val list = find(id) ?: return@withLock WatchlistResult.Refused(Refusal.LIST_NOT_FOUND)
        withContext(io) {
            val d = db.writableDatabase
            d.beginTransaction()
            try {
                // The tombstone tells other devices the list is gone, and keeps the id its trades still carry.
                d.execSQL("INSERT OR REPLACE INTO watchlist_tombstones (sync_id, local_id, deleted_at) SELECT sync_id, id, ? FROM watchlists WHERE id=? AND sync_id IS NOT NULL", arrayOf<Any?>(clock(), id))
                d.execSQL("DELETE FROM watchlists WHERE id=?", arrayOf<Any?>(id))
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        reload()
        WatchlistResult.Ok(list.symbols)
    }

    suspend fun add(id: Long, symbol: String): WatchlistResult<Unit> = lock.withLock {
        val list = find(id) ?: return@withLock WatchlistResult.Refused(Refusal.LIST_NOT_FOUND)
        if (!universe.isOffered(symbol)) return@withLock WatchlistResult.Refused(Refusal.UNKNOWN_COIN)
        WatchlistRules.checkAdd(state.value, list, symbol)?.let { return@withLock WatchlistResult.Refused(it) }
        withContext(io) {
            db.writableDatabase.execSQL("INSERT OR IGNORE INTO watchlist_coins VALUES (?,?,?)", arrayOf<Any?>(id, symbol, clock()))
            touch(id)
        }
        reload()
        WatchlistResult.Ok(Unit)
    }

    suspend fun remove(id: Long, symbol: String): WatchlistResult<Unit> = lock.withLock {
        if (find(id) == null) return@withLock WatchlistResult.Refused(Refusal.LIST_NOT_FOUND)
        withContext(io) {
            db.writableDatabase.execSQL("DELETE FROM watchlist_coins WHERE list_id=? AND symbol=?", arrayOf<Any?>(id, symbol))
            touch(id)
        }
        reload()
        WatchlistResult.Ok(Unit)
    }

    /** The chart sizes a list is watched on. At least one; and on an active list the caps for 1-minute and 5-minute charts still hold. */
    suspend fun setTimeframes(id: Long, timeframes: Set<Timeframe>): WatchlistResult<Unit> = lock.withLock {
        val list = find(id) ?: return@withLock WatchlistResult.Refused(Refusal.LIST_NOT_FOUND)
        WatchlistRules.checkTimeframes(state.value, list, timeframes)?.let { return@withLock WatchlistResult.Refused(it) }
        withContext(io) {
            db.writableDatabase.execSQL("UPDATE watchlists SET timeframes=?, updated_at=? WHERE id=?", arrayOf<Any?>(Timeframe.formatSet(timeframes), clock(), id))
        }
        reload()
        WatchlistResult.Ok(Unit)
    }

    /** Switches a list on or off. Switching on is refused if the active coins would pass 150. */
    suspend fun setActive(id: Long, active: Boolean): WatchlistResult<Unit> = lock.withLock {
        val list = find(id) ?: return@withLock WatchlistResult.Refused(Refusal.LIST_NOT_FOUND)
        if (active) WatchlistRules.checkActivate(state.value, list)?.let { return@withLock WatchlistResult.Refused(it) }
        withContext(io) {
            db.writableDatabase.execSQL("UPDATE watchlists SET active=?, updated_at=? WHERE id=?", arrayOf<Any?>(if (active) 1 else 0, clock(), id))
        }
        reload()
        WatchlistResult.Ok(Unit)
    }
}
