package com.ikverse.signallab.ledger

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.ledger.legacy.SignalLabV6
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Brings Signal Lab 1's record (`signal_lab.db`) into the ledger, once.
 *
 * The original file is never changed beyond what Signal Lab 1 itself does on open (its write-ahead log is folded in, and a
 * file older than version 6 is upgraded by Signal Lab 1's own SQL). A copy of it is kept under `files/legacy/` with the
 * date in its name, and its SHA-256 goes in the import log. Everything is written in one transaction, every row keyed so a
 * second run adds nothing: a file already imported is recognised by its SHA and skipped, and a changed one only adds what
 * is new.
 *
 * Old trades become archived calls (strategy `v1:<variant>`), with their exits; a trade Signal Lab 1 has not closed simply
 * has no exit yet, and gets one on a later run if Signal Lab 1 closes it. Every variant Signal Lab 1 ever ran joins the strategy registry as retired, so it still counts as a
 * try. Lab patterns become archived custom strategies with their definition kept verbatim. Reports come over as they are.
 * The coins of active lists become pins named after their list. Alerts are not brought over.
 */
class LegacyImporter(
    private val context: Context,
    private val ledger: LedgerDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val legacyName: String = "signal_lab.db",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface Result {
        data object NoLegacyFile : Result
        data class AlreadyImported(val log: ImportLogRow) : Result
        data class Imported(val log: ImportLogRow) : Result
    }

    private val dao get() = ledger.dao()

    suspend fun run(): Result = withContext(io) {
        val file = context.getDatabasePath(legacyName)
        if (!file.exists()) return@withContext Result.NoLegacyFile
        val legacy = SignalLabV6(context, legacyName)
        try {
            legacy.checkpoint()
            val sha = sha256(file)
            dao.importLog(sha)?.let { return@withContext Result.AlreadyImported(it) }
            // The first import keeps a copy of the file as it was. Signal Lab 1 may go on writing to it until its screens are
            // removed, so later runs only add what is new, and point at that first copy rather than piling up more.
            val copyPath = dao.importLogs().firstOrNull()?.copyPath ?: keepCopy(file, sha).path
            val log = importFrom(legacy.readableDatabase, sha, copyPath)
            Result.Imported(log)
        } finally {
            legacy.close()
        }
    }

    /** A copy named by when it was taken and the start of its SHA-256, so two copies never share a name. */
    private fun keepCopy(file: File, sha: String): File {
        val dir = File(context.filesDir, "legacy").apply { mkdirs() }
        val copy = File(dir, "signal_lab.v6.${now()}.${sha.take(12)}.db")
        if (!copy.exists()) file.copyTo(copy)
        return copy
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun importFrom(src: SQLiteDatabase, sha: String, copyPath: String): ImportLogRow {
        val at = now()
        // Signal Lab 1's lists, live and deleted, so each trade can still name its list.
        class OldList(val id: Long, val name: String?, val syncId: String, val active: Boolean, val deletedAt: Long?)
        val lists = ArrayList<OldList>()
        src.query("SELECT id, name, sync_id, active FROM watchlists ORDER BY position, id").use { c ->
            while (c.moveToNext()) lists.add(OldList(c.getLong(0), c.getString(1), c.str(2) ?: "list:${c.getLong(0)}", c.getInt(3) == 1, null))
        }
        src.query("SELECT sync_id, local_id, deleted_at FROM watchlist_tombstones").use { c ->
            while (c.moveToNext()) lists.add(OldList(c.getLong(1), null, c.getString(0), false, c.getLong(2)))
        }
        val scopeOf = lists.associate { it.id to it.syncId }
        val coinsByList = HashMap<Long, MutableList<String>>()
        src.query("SELECT list_id, symbol FROM watchlist_coins ORDER BY added_at, symbol").use { c ->
            while (c.moveToNext()) coinsByList.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getString(1))
        }
        val reportSync = HashMap<Long, String>()
        src.query("SELECT id, sync_id FROM analyst_reports").use { c -> while (c.moveToNext()) c.str(1)?.let { reportSync[c.getLong(0)] = it } }

        var calls = 0
        var exits = 0
        var strategies = 0
        var customs = 0
        var reports = 0
        var pins = 0
        var settings = 0
        val alerts = src.query("SELECT COUNT(*) FROM alerts").use { it.moveToFirst(); it.getInt(0) }

        val log = ledger.withTransaction {
            for (l in lists) dao.insertLegacyList(LegacyListRow(l.syncId, l.id, l.name, l.deletedAt))

            src.query("SELECT variant, family, tf, params, first_seen_at FROM variants").use { c ->
                while (c.moveToNext()) {
                    val row = StrategyRow("v1:" + c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), retired = true, source = "v1")
                    if (dao.insertStrategy(row) != -1L) strategies++
                }
            }

            val exitsByTrade = HashMap<Long, ExitRow>()
            src.query("SELECT trade_id, exit_time, exit_price, exit_reason, bars_held, gross, net, random_mean, excess, closed_at, " +
                "max_up, max_down, bars_to_peak FROM live_exits").use { c ->
                while (c.moveToNext()) {
                    exitsByTrade[c.getLong(0)] = ExitRow(
                        callId = 0, exitTime = c.getLong(1), exitPrice = c.getDouble(2), reason = c.getString(3), barsHeld = c.getInt(4),
                        gross = c.getDouble(5), net = c.getDouble(6), randomMean = c.dbl(7), excess = c.dbl(8), maxUp = c.dbl(10),
                        maxDown = c.dbl(11), barsToPeak = c.int(12), closedAt = c.getLong(9),
                    )
                }
            }

            src.query("SELECT id, variant, symbol, tf, list_id, bar_time, regime, entry_time, entry_price, target, stop, hold_bars, " +
                "opened_at, cost, exit_mode, atr FROM live_trades ORDER BY id").use { c ->
                while (c.moveToNext()) {
                    val oldId = c.getLong(0)
                    val symbol = c.getString(2)
                    val listId = c.getLong(4)
                    val scope = if (listId == 0L) "" else scopeOf[listId] ?: "list:$listId"
                    val target = c.dbl(9)
                    val cost = c.dbl(13)
                    val exit = exitsByTrade[oldId]
                    val row = CallRow(
                        strategyId = "v1:" + c.getString(1), symbol = symbol, tf = c.getString(3), signalTime = c.getLong(5),
                        entryTime = c.getLong(7), entryPrice = c.getDouble(8), atr = c.dbl(15), stop = c.dbl(10), target = target,
                        maxBars = c.getInt(11), regime = c.getInt(6), sizeFraction = null,
                        cost = cost ?: EngineConfig.costFor(symbol), costAssumed = cost == null,
                        exitMode = c.str(14) ?: if (target != null) "classic" else "held",
                        scope = scope, source = "v1", archived = true, status = "v1",
                        legacyId = oldId, openedAt = c.getLong(12),
                    )
                    var id = dao.insertCall(row)
                    if (id == -1L) id = dao.callId(row.strategyId, row.symbol, row.signalTime, row.scope) ?: continue else calls++
                    if (exit != null && dao.insertExit(exit.copy(callId = id)) != -1L) exits++
                }
            }

            val stops = HashMap<Long, Long>()
            src.query("SELECT pattern_id, stopped_at FROM lab_stops").use { c -> while (c.moveToNext()) stops[c.getLong(0)] = c.getLong(1) }
            src.query("SELECT id, started_at, report_id, title, reason, definition, sync_id FROM lab_patterns ORDER BY id").use { c ->
                while (c.moveToNext()) {
                    val oldId = c.getLong(0)
                    val syncId = c.str(6) ?: "lab:$oldId"
                    val row = CustomStrategyRow(
                        syncId = syncId, createdAt = c.getLong(1), title = c.getString(3), reason = c.str(4), definitionV1 = c.getString(5),
                        definitionV2 = null, archived = true, legacyId = oldId, reportSyncId = c.long(2)?.let { reportSync[it] },
                    )
                    val inserted = dao.insertCustom(row)
                    if (inserted != -1L) customs++
                    val id = if (inserted != -1L) inserted else dao.customId(syncId)
                    val stoppedAt = stops[oldId]
                    if (id != null && stoppedAt != null) dao.insertCustomStop(CustomStopRow(id, stoppedAt))
                }
            }

            src.query("SELECT id, received_at, card, title, body, sync_id FROM analyst_reports ORDER BY id").use { c ->
                while (c.moveToNext()) {
                    val row = ReportRow(syncId = c.str(5) ?: "report:${c.getLong(0)}", receivedAt = c.getLong(1), card = c.str(2),
                        title = c.getString(3), body = c.getString(4), legacyId = c.getLong(0))
                    if (dao.insertReport(row) != -1L) reports++
                }
            }

            for (l in lists.filter { it.active && it.deletedAt == null }) {
                for (symbol in coinsByList[l.id].orEmpty()) {
                    if (dao.insertPin(PinRow(symbol, kind = "pin", addedAt = at, tag = l.name)) != -1L) pins++
                }
            }

            src.query("SELECT key, value, updated_at FROM settings").use { c ->
                while (c.moveToNext()) {
                    val key = SETTING_RENAMES[c.getString(0)] ?: c.getString(0)
                    if (key !in KEPT_SETTINGS && !key.startsWith(ASKED_PREFIX)) continue
                    if (dao.insertSettingIfAbsent(SettingRow(key, c.getString(1), c.getLong(2))) != -1L) settings++
                }
            }

            val entry = ImportLogRow(sourceSha256 = sha, importedAt = at, copyPath = copyPath, calls = calls, exits = exits,
                strategies = strategies, customs = customs, reports = reports, pins = pins, settings = settings, alertsDropped = alerts)
            entry.copy(id = dao.insertImportLog(entry))
        }
        return log
    }

    private fun SQLiteDatabase.query(sql: String): Cursor = rawQuery(sql, null)

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.dbl(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    private fun Cursor.int(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.long(i: Int): Long? = if (isNull(i)) null else getLong(i)

    companion object {
        /** Signal Lab 1 settings that still mean something, by their old keys (renamed ones by their new key). */
        val KEPT_SETTINGS = setOf(
            "data_host", "alert_notifications", "dim_screen", "dim_level", "rail_on_right", "fee_per_side", "extra_cost_majors",
            "extra_cost_others", "last_update_check", "sync_enabled", "sync_account", "sync_device_id", "background_enabled",
        )
        val SETTING_RENAMES = mapOf("scan_in_background" to "background_enabled")
        const val ASKED_PREFIX = "asked_"
    }
}
