package com.ikverse.signallab.data

import android.database.Cursor
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.SignalKey
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A paper trade about to be opened. Entry is the next candle's open, so by the time it is logged the price is known. */
class NewTrade(
    val variant: String,
    val family: String,
    val symbol: String,
    val tf: Timeframe,
    /** 0 for a signal that belongs to the coin; a list's id for one that depends on the list. */
    val listId: Long,
    val barTime: Long,
    val detectedAt: Long,
    val regime: Int,
    val entryTime: Long,
    val entryPrice: Double,
    val target: Double?,
    val stop: Double?,
    val holdBars: Int,
    val exitDue: Long,
)

class TradeExit(
    val exitTime: Long,
    val exitPrice: Double,
    val reason: ExitReason,
    val barsHeld: Int,
    val gross: Double,
    val net: Double,
    val randomMean: Double?,
    val excess: Double?,
)

class LiveTrade(val id: Long, val trade: NewTrade, val openedAt: Long, val exit: TradeExit?, val closedAt: Long?)

class Alert(val id: Long, val ts: Long, val kind: String, val symbol: String?, val tf: String?, val title: String, val body: String, val link: String?)

enum class TradeStatus { OPEN, CLOSED, ALL }

/**
 * The paper-trade log, the alerts and the registry of signal variants, in the record database.
 *
 * A trade is opened with one row and closed with one row; nothing is ever updated or deleted (the
 * database refuses it), so what the scorecard says today is what it will say tomorrow about the
 * same past.
 */
class TradeLog(
    private val db: RecordDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()

    private suspend fun <T> access(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        lock.withLock { withContext(io) { block(db.writableDatabase) } }

    /** Opens the trade; null when that signal on that bar was already traded. */
    suspend fun open(t: NewTrade): Long? = access { d ->
        val cv = android.content.ContentValues().apply {
            put("variant", t.variant); put("family", t.family); put("symbol", t.symbol); put("tf", t.tf.label)
            put("list_id", t.listId); put("bar_time", t.barTime); put("detected_at", t.detectedAt)
            put("regime", t.regime); put("entry_time", t.entryTime); put("entry_price", t.entryPrice)
            put("target", t.target); put("stop", t.stop); put("hold_bars", t.holdBars)
            put("exit_due", t.exitDue); put("opened_at", clock())
        }
        val id = d.insertWithOnConflict("live_trades", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        if (id == -1L) null else id
    }

    /** Closes the trade; false when it was already closed. */
    suspend fun close(tradeId: Long, x: TradeExit): Boolean = access { d ->
        val cv = android.content.ContentValues().apply {
            put("trade_id", tradeId); put("exit_time", x.exitTime); put("exit_price", x.exitPrice)
            put("exit_reason", x.reason.label); put("bars_held", x.barsHeld); put("gross", x.gross)
            put("net", x.net); put("random_mean", x.randomMean); put("excess", x.excess); put("closed_at", clock())
        }
        d.insertWithOnConflict("live_exits", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    suspend fun hasOpen(variant: String, symbol: String, listId: Long): Boolean = access { d ->
        d.rawQuery("SELECT 1 FROM live_trades t LEFT JOIN live_exits x ON x.trade_id=t.id " +
            "WHERE t.variant=? AND t.symbol=? AND t.list_id=? AND x.trade_id IS NULL LIMIT 1",
            arrayOf(variant, symbol, listId.toString())).use { it.moveToFirst() }
    }

    /** Newest first. */
    suspend fun trades(status: TradeStatus = TradeStatus.ALL, symbol: String? = null, limit: Int = 500): List<LiveTrade> = access { d ->
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        when (status) {
            TradeStatus.OPEN -> where.add("x.trade_id IS NULL")
            TradeStatus.CLOSED -> where.add("x.trade_id IS NOT NULL")
            TradeStatus.ALL -> {}
        }
        if (symbol != null) { where.add("t.symbol=?"); args.add(symbol) }
        val sql = "SELECT t.id, t.variant, t.family, t.symbol, t.tf, t.list_id, t.bar_time, t.detected_at, t.regime, t.entry_time, " +
            "t.entry_price, t.target, t.stop, t.hold_bars, t.exit_due, t.opened_at, " +
            "x.exit_time, x.exit_price, x.exit_reason, x.bars_held, x.gross, x.net, x.random_mean, x.excess, x.closed_at " +
            "FROM live_trades t LEFT JOIN live_exits x ON x.trade_id=t.id" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY t.detected_at DESC, t.id DESC LIMIT ?"
        d.rawQuery(sql, (args + limit.toString()).toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(tradeOf(c)) } }
    }

    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    private fun tradeOf(c: Cursor): LiveTrade {
        val trade = NewTrade(
            variant = c.getString(1), family = c.getString(2), symbol = c.getString(3), tf = Timeframe.of(c.getString(4)),
            listId = c.getLong(5), barTime = c.getLong(6), detectedAt = c.getLong(7), regime = c.getInt(8),
            entryTime = c.getLong(9), entryPrice = c.getDouble(10), target = c.doubleOrNull(11), stop = c.doubleOrNull(12),
            holdBars = c.getInt(13), exitDue = c.getLong(14),
        )
        val exit = if (c.isNull(16)) null else TradeExit(
            exitTime = c.getLong(16), exitPrice = c.getDouble(17), reason = ExitReason.of(c.getString(18)),
            barsHeld = c.getInt(19), gross = c.getDouble(20), net = c.getDouble(21),
            randomMean = c.doubleOrNull(22), excess = c.doubleOrNull(23),
        )
        return LiveTrade(c.getLong(0), trade, c.getLong(15), exit, if (c.isNull(24)) null else c.getLong(24))
    }

    // --- alerts ---------------------------------------------------------------------

    suspend fun addAlert(kind: String, title: String, body: String, symbol: String? = null, tf: String? = null, link: String? = null): Long =
        access { d ->
            val cv = android.content.ContentValues().apply {
                put("ts", clock()); put("kind", kind); put("symbol", symbol); put("tf", tf)
                put("title", title); put("body", body); put("link", link)
            }
            d.insert("alerts", null, cv)
        }

    suspend fun alerts(limit: Int = 100): List<Alert> = access { d ->
        d.rawQuery("SELECT id, ts, kind, symbol, tf, title, body, link FROM alerts ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Alert(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7))) }
        }
    }

    suspend fun lastAlertTime(kind: String, symbol: String): Long = access { d ->
        d.rawQuery("SELECT COALESCE(MAX(ts), 0) FROM alerts WHERE kind=? AND symbol=?", arrayOf(kind, symbol)).use { it.moveToFirst(); it.getLong(0) }
    }

    // --- every variant ever tried --------------------------------------------------------

    /** Notes that a variant exists. The count of these is what the multiple-testing correction divides by. */
    suspend fun registerVariant(key: SignalKey, tf: Timeframe) {
        access { d ->
            val params = JSONObject(key.params.toSortedMap()).toString()
            d.execSQL("INSERT OR IGNORE INTO variants VALUES (?,?,?,?,?)", arrayOf<Any?>(key.name, key.family, tf.label, params, clock()))
        }
    }

    suspend fun variantCount(): Int = access { d ->
        d.rawQuery("SELECT COUNT(*) FROM variants", null).use { it.moveToFirst(); it.getInt(0) }
    }
}
