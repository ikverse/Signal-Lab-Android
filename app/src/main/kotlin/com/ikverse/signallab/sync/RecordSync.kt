package com.ikverse.signallab.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ikverse.signallab.data.RecordDatabase
import com.ikverse.signallab.data.SettingsStore
import com.ikverse.signallab.engine.LabPatterns
import com.ikverse.signallab.engine.WatchlistRules
import com.ikverse.signallab.engine.Watchlist
import com.ikverse.signallab.engine.Timeframe
import org.json.JSONArray
import org.json.JSONObject

/** What one merge brought in, so the stores can tell their screens and the app can pick up a changed data host. */
data class MergeResult(
    val trades: Int = 0,
    val exits: Int = 0,
    val variants: Int = 0,
    val labPatterns: Int = 0,
    val labStops: Int = 0,
    val reports: Int = 0,
    val listsChanged: Int = 0,
    val settingsChanged: Set<String> = emptySet(),
) {
    val any: Boolean get() = trades + exits + variants + labPatterns + labStops + reports + listsChanged > 0 || settingsChanged.isNotEmpty()

    operator fun plus(o: MergeResult) = MergeResult(
        trades + o.trades, exits + o.exits, variants + o.variants, labPatterns + o.labPatterns, labStops + o.labStops,
        reports + o.reports, listsChanged + o.listsChanged, settingsChanged + o.settingsChanged,
    )
}

/**
 * The record as another device can read it, and the merge of another device's record into this one.
 *
 * Row ids belong to the phone that made them, so the exported record never relies on them: a trade names its list by the list's
 * sync id, a lab pattern's variant (`lab3_1h`) becomes `lab@<sync id>_1h`, and a lab pattern names the report it came from by the
 * report's sync id. Importing turns each back into this phone's own ids, making a row for anything not seen before.
 *
 * The merge only ever adds to the append-only tables (trades, exits, variants, lab patterns and stops), matching rows by what they
 * are rather than by id: a trade by its signal (variant, coin, candle, list), so the same signal seen on two devices is one trade, and
 * the copy already here stays. Lists and the [SettingsStore.SYNCED] settings can change, so for each the newer copy wins, and a
 * list's tombstone wins over any copy of it older than the deletion. Merging the same record twice changes nothing.
 */
class RecordSync(private val db: RecordDatabase) {

    // --- export ----------------------------------------------------------------------------------

    fun export(deviceId: String, now: Long): JSONObject {
        val d = db.readableDatabase
        val listSync = HashMap<Long, String>()
        val lists = JSONArray()
        val coins = HashMap<Long, JSONArray>()
        d.rawQuery("SELECT list_id, symbol, added_at FROM watchlist_coins ORDER BY list_id, added_at, symbol", null).use { c ->
            while (c.moveToNext()) coins.getOrPut(c.getLong(0)) { JSONArray() }.put(JSONArray().put(c.getString(1)).put(c.getLong(2)))
        }
        d.rawQuery("SELECT id, sync_id, name, active, created_at, position, timeframes, updated_at FROM watchlists ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val sync = c.getString(1) ?: continue
                listSync[c.getLong(0)] = sync
                lists.put(JSONObject().put("sync", sync).put("name", c.getString(2)).put("active", c.getInt(3) == 1)
                    .put("created", c.getLong(4)).put("position", c.getInt(5)).put("timeframes", c.getString(6))
                    .put("updated", c.getLong(7)).put("coins", coins[c.getLong(0)] ?: JSONArray()))
            }
        }
        val tombstones = JSONArray()
        d.rawQuery("SELECT sync_id, local_id, deleted_at FROM watchlist_tombstones ORDER BY sync_id", null).use { c ->
            while (c.moveToNext()) {
                listSync[c.getLong(1)] = c.getString(0)
                tombstones.put(JSONObject().put("sync", c.getString(0)).put("deleted", c.getLong(2)))
            }
        }

        val reportSync = HashMap<Long, String>()
        val reports = JSONArray()
        d.rawQuery("SELECT id, sync_id, received_at, card, title, body FROM analyst_reports ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val sync = c.getString(1) ?: continue
                reportSync[c.getLong(0)] = sync
                reports.put(JSONObject().put("sync", sync).put("received", c.getLong(2)).putOpt("card", c.str(3))
                    .put("title", c.getString(4)).put("body", c.getString(5)))
            }
        }

        val labSync = HashMap<Long, String>()
        val lab = JSONArray()
        d.rawQuery("SELECT p.id, p.sync_id, p.started_at, p.report_id, p.title, p.reason, p.definition, s.stopped_at " +
            "FROM lab_patterns p LEFT JOIN lab_stops s ON s.pattern_id=p.id ORDER BY p.id", null).use { c ->
            while (c.moveToNext()) {
                val sync = c.getString(1) ?: continue
                labSync[c.getLong(0)] = sync
                lab.put(JSONObject().put("sync", sync).put("started", c.getLong(2)).putOpt("report", c.long(3)?.let(reportSync::get))
                    .put("title", c.getString(4)).putOpt("reason", c.str(5)).put("definition", c.getString(6)).putOpt("stopped", c.long(7)))
            }
        }

        val variants = JSONArray()
        d.rawQuery("SELECT variant, family, tf, params, first_seen_at FROM variants ORDER BY variant", null).use { c ->
            while (c.moveToNext()) {
                val name = wireVariant(c.getString(0), labSync) ?: continue
                variants.put(JSONObject().put("variant", name).put("family", c.getString(1)).put("tf", c.getString(2))
                    .put("params", c.getString(3)).put("first_seen", c.getLong(4)))
            }
        }

        val trades = JSONArray()
        d.rawQuery("SELECT t.variant, t.family, t.symbol, t.tf, t.list_id, t.bar_time, t.detected_at, t.regime, t.entry_time, t.entry_price, " +
            "t.target, t.stop, t.hold_bars, t.exit_due, t.opened_at, t.cost, t.exit_mode, t.atr, " +
            "x.exit_time, x.exit_price, x.exit_reason, x.bars_held, x.gross, x.net, x.random_mean, x.excess, x.closed_at, x.max_up, x.max_down, x.bars_to_peak " +
            "FROM live_trades t LEFT JOIN live_exits x ON x.trade_id=t.id ORDER BY t.id", null).use { c ->
            while (c.moveToNext()) {
                val variant = wireVariant(c.getString(0), labSync) ?: continue
                val listId = c.getLong(4)
                val list = if (listId == 0L) null else listSync[listId] ?: continue
                val o = JSONObject().put("variant", variant).put("family", c.getString(1)).put("symbol", c.getString(2)).put("tf", c.getString(3))
                    .putOpt("list", list).put("bar_time", c.getLong(5)).put("detected_at", c.getLong(6)).put("regime", c.getInt(7))
                    .put("entry_time", c.getLong(8)).put("entry_price", c.getDouble(9)).putOpt("target", c.dbl(10)).putOpt("stop", c.dbl(11))
                    .put("hold_bars", c.getInt(12)).put("exit_due", c.getLong(13)).put("opened_at", c.getLong(14))
                    .putOpt("cost", c.dbl(15)).putOpt("exit_mode", c.str(16)).putOpt("atr", c.dbl(17))
                if (!c.isNull(18)) {
                    o.put("exit", JSONObject().put("exit_time", c.getLong(18)).put("exit_price", c.getDouble(19)).put("exit_reason", c.getString(20))
                        .put("bars_held", c.getInt(21)).put("gross", c.getDouble(22)).put("net", c.getDouble(23))
                        .putOpt("random_mean", c.dbl(24)).putOpt("excess", c.dbl(25)).put("closed_at", c.getLong(26))
                        .putOpt("max_up", c.dbl(27)).putOpt("max_down", c.dbl(28)).putOpt("bars_to_peak", c.long(29)))
                }
                trades.put(o)
            }
        }

        val settings = JSONArray()
        d.rawQuery("SELECT key, value, updated_at FROM settings ORDER BY key", null).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) !in SettingsStore.SYNCED) continue
                settings.put(JSONObject().put("key", c.getString(0)).put("value", c.getString(1)).put("updated", c.getLong(2)))
            }
        }

        return JSONObject().put("format", FORMAT).put("device", deviceId).put("exported_at", now)
            .put("lists", lists).put("tombstones", tombstones).put("settings", settings).put("reports", reports)
            .put("lab", lab).put("variants", variants).put("trades", trades)
    }

    /**
     * The record's content without when it was exported, so two exports of an unchanged record compare equal and nothing is sent
     * for nothing.
     */
    fun fingerprint(export: JSONObject): String {
        val copy = JSONObject(export.toString())
        copy.remove("exported_at")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(copy.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // --- merge -----------------------------------------------------------------------------------

    /** Merges another device's exported record into this one, in one transaction. A record in a format this app does not know is refused. */
    fun merge(remote: JSONObject): MergeResult {
        require(remote.optInt("format") == FORMAT) { "Sync data is format ${remote.optInt("format")}; this version reads format $FORMAT." }
        val d = db.writableDatabase
        d.beginTransaction()
        try {
            var r = mergeReports(d, remote.optJSONArray("reports") ?: JSONArray())
            r += mergeLists(d, remote.optJSONArray("lists") ?: JSONArray(), remote.optJSONArray("tombstones") ?: JSONArray())
            r += mergeLab(d, remote.optJSONArray("lab") ?: JSONArray(), idsBySync(d, "SELECT sync_id, id FROM analyst_reports WHERE sync_id IS NOT NULL"))
            val labIds = idsBySync(d, "SELECT sync_id, id FROM lab_patterns WHERE sync_id IS NOT NULL")
            r += mergeVariants(d, remote.optJSONArray("variants") ?: JSONArray(), labIds)
            r += mergeTrades(d, remote.optJSONArray("trades") ?: JSONArray(), labIds)
            r += mergeSettings(d, remote.optJSONArray("settings") ?: JSONArray())
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    private fun idsBySync(d: SQLiteDatabase, sql: String, args: Array<String>? = null): Map<String, Long> =
        d.rawQuery(sql, args).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }

    private fun mergeReports(d: SQLiteDatabase, reports: JSONArray): MergeResult {
        var added = 0
        for (o in reports.objects()) {
            val cv = ContentValues().apply {
                put("sync_id", o.getString("sync")); put("received_at", o.getLong("received")); put("card", o.optStr("card"))
                put("title", o.getString("title")); put("body", o.getString("body"))
            }
            if (d.insertWithOnConflict("analyst_reports", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
        }
        return MergeResult(reports = added)
    }

    private fun mergeLab(d: SQLiteDatabase, lab: JSONArray, reportIds: Map<String, Long>): MergeResult {
        var patterns = 0
        var stops = 0
        for (o in lab.objects()) {
            val sync = o.getString("sync")
            var id = idsBySync(d, "SELECT sync_id, id FROM lab_patterns WHERE sync_id=?", arrayOf(sync))[sync]
            if (id == null) {
                id = d.insertOrThrow("lab_patterns", null, ContentValues().apply {
                    put("sync_id", sync); put("started_at", o.getLong("started")); put("report_id", o.optStr("report")?.let(reportIds::get))
                    put("title", o.getString("title")); put("reason", o.optStr("reason")); put("definition", o.getString("definition"))
                })
                patterns++
            }
            if (o.has("stopped") && !o.isNull("stopped")) {
                val cv = ContentValues().apply { put("pattern_id", id); put("stopped_at", o.getLong("stopped")) }
                if (d.insertWithOnConflict("lab_stops", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) stops++
            }
        }
        return MergeResult(labPatterns = patterns, labStops = stops)
    }

    private class LocalList(val id: Long, val name: String, val active: Boolean, val updated: Long, val timeframes: String, val coins: List<String>)

    private fun localLists(d: SQLiteDatabase): Map<String, LocalList> {
        val coins = HashMap<Long, MutableList<String>>()
        d.rawQuery("SELECT list_id, symbol FROM watchlist_coins ORDER BY added_at, symbol", null).use { c ->
            while (c.moveToNext()) coins.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getString(1))
        }
        return d.rawQuery("SELECT sync_id, id, name, active, updated_at, timeframes FROM watchlists WHERE sync_id IS NOT NULL", null).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(c.getString(0), LocalList(c.getLong(1), c.getString(2), c.getInt(3) == 1, c.getLong(4), c.getString(5), coins[c.getLong(1)] ?: emptyList()))
                }
            }
        }
    }

    private fun mergeLists(d: SQLiteDatabase, lists: JSONArray, tombstones: JSONArray): MergeResult {
        var changed = 0
        val tombs = d.rawQuery("SELECT sync_id, local_id, deleted_at FROM watchlist_tombstones", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1) to c.getLong(2)) }
        }.toMutableMap()

        // Deletions first: a tombstone newer than the copy here removes the list, and keeps its id for the trades that carry it.
        for (o in tombstones.objects()) {
            val sync = o.getString("sync")
            val deleted = o.getLong("deleted")
            val local = localLists(d)[sync]
            when {
                local != null && deleted >= local.updated -> {
                    d.execSQL("INSERT OR REPLACE INTO watchlist_tombstones (sync_id, local_id, deleted_at) VALUES (?,?,?)", arrayOf<Any?>(sync, local.id, deleted))
                    d.execSQL("DELETE FROM watchlists WHERE id=?", arrayOf<Any?>(local.id))
                    tombs[sync] = local.id to deleted
                    changed++
                }
                local == null && sync !in tombs -> {
                    // Never seen here: remember it under an id of its own, below zero so no list made here can ever take it.
                    val id = minOf(0L, d.rawQuery("SELECT COALESCE(MIN(local_id), 0) FROM watchlist_tombstones", null).use { it.moveToFirst(); it.getLong(0) }) - 1
                    d.execSQL("INSERT INTO watchlist_tombstones (sync_id, local_id, deleted_at) VALUES (?,?,?)", arrayOf<Any?>(sync, id, deleted))
                    tombs[sync] = id to deleted
                }
                local == null && deleted > tombs.getValue(sync).second -> {
                    d.execSQL("UPDATE watchlist_tombstones SET deleted_at=? WHERE sync_id=?", arrayOf<Any?>(deleted, sync))
                    tombs[sync] = tombs.getValue(sync).first to deleted
                }
            }
        }

        for (o in lists.objects()) {
            val sync = o.getString("sync")
            val updated = o.getLong("updated")
            val local = localLists(d)[sync]
            val tomb = tombs[sync]
            if (local == null && tomb != null && tomb.second >= updated) continue // deleted here after that copy was made
            if (local != null && local.updated >= updated) continue // the copy here is as new or newer
            val coins = o.getJSONArray("coins").let { a -> (0 until a.length()).map { a.getJSONArray(it) } }
            val name = o.getString("name")
            var active = o.getBoolean("active")
            if (local != null) {
                val free = freeName(d, name, local.id)
                d.execSQL("UPDATE watchlists SET name=?, name_key=?, active=?, position=?, timeframes=?, updated_at=? WHERE id=?",
                    arrayOf<Any?>(free, free.lowercase(), if (active) 1 else 0, o.getInt("position"), o.getString("timeframes"), updated, local.id))
                d.execSQL("DELETE FROM watchlist_coins WHERE list_id=?", arrayOf<Any?>(local.id))
                for (c in coins) d.execSQL("INSERT OR IGNORE INTO watchlist_coins VALUES (?,?,?)", arrayOf<Any?>(local.id, c.getString(0), c.getLong(1)))
            } else {
                // New here, or brought back by an edit made after it was deleted here. Brought back, it takes the id its tombstone kept
                // (below zero if it was only ever known from another device), so the trades already here that carry that id are its again.
                if (active && !canActivate(d, coins.map { it.getString(0) }, Timeframe.parseSet(o.getString("timeframes")))) active = false
                val free = freeName(d, name, null)
                val id = d.insertOrThrow("watchlists", null, ContentValues().apply {
                    tomb?.let { put("id", it.first) }
                    put("sync_id", sync); put("name", free); put("name_key", free.lowercase())
                    put("active", if (active) 1 else 0); put("created_at", o.getLong("created")); put("position", o.getInt("position"))
                    put("timeframes", o.getString("timeframes")); put("updated_at", updated)
                })
                if (tomb != null) {
                    d.execSQL("DELETE FROM watchlist_tombstones WHERE sync_id=?", arrayOf<Any?>(sync))
                    tombs.remove(sync)
                }
                for (c in coins) d.execSQL("INSERT OR IGNORE INTO watchlist_coins VALUES (?,?,?)", arrayOf<Any?>(id, c.getString(0), c.getLong(1)))
            }
            changed++
        }
        return MergeResult(listsChanged = changed)
    }

    /** Whether switching on a list with these coins and charts keeps the active lists within the caps. */
    private fun canActivate(d: SQLiteDatabase, coins: List<String>, timeframes: Set<Timeframe>): Boolean {
        val active = ArrayList<Watchlist>()
        val coinsBy = HashMap<Long, MutableList<String>>()
        d.rawQuery("SELECT list_id, symbol FROM watchlist_coins", null).use { c -> while (c.moveToNext()) coinsBy.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getString(1)) }
        d.rawQuery("SELECT id, name, active, timeframes FROM watchlists WHERE active=1", null).use { c ->
            while (c.moveToNext()) active.add(Watchlist(c.getLong(0), c.getString(1), coinsBy[c.getLong(0)] ?: emptyList(), true, Timeframe.parseSet(c.getString(3))))
        }
        val draft = Watchlist(0, "", coins, false, timeframes.ifEmpty { Timeframe.LEGACY_DEFAULT })
        return WatchlistRules.checkActivate(active + draft, draft) == null
    }

    /** [name], or "name 2", "name 3"… if another list here already has it (names are unique, case aside). */
    private fun freeName(d: SQLiteDatabase, name: String, self: Long?): String {
        var candidate = name
        var n = 2
        while (d.rawQuery("SELECT 1 FROM watchlists WHERE name_key=? AND id IS NOT ?", arrayOf(candidate.lowercase(), (self ?: -1L).toString())).use { it.moveToFirst() }) {
            candidate = "$name $n"
            n++
        }
        return candidate
    }

    private fun mergeVariants(d: SQLiteDatabase, variants: JSONArray, labIds: Map<String, Long>): MergeResult {
        var added = 0
        for (o in variants.objects()) {
            val name = localVariant(o.getString("variant"), labIds) ?: continue
            val params = localParams(o.getString("params"), name)
            val cv = ContentValues().apply {
                put("variant", name); put("family", o.getString("family")); put("tf", o.getString("tf")); put("params", params); put("first_seen_at", o.getLong("first_seen"))
            }
            if (d.insertWithOnConflict("variants", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
        }
        return MergeResult(variants = added)
    }

    private fun mergeTrades(d: SQLiteDatabase, trades: JSONArray, labIds: Map<String, Long>): MergeResult {
        val listIds = HashMap<String, Long>()
        d.rawQuery("SELECT sync_id, id FROM watchlists WHERE sync_id IS NOT NULL", null).use { c -> while (c.moveToNext()) listIds[c.getString(0)] = c.getLong(1) }
        d.rawQuery("SELECT sync_id, local_id FROM watchlist_tombstones", null).use { c -> while (c.moveToNext()) listIds.putIfAbsent(c.getString(0), c.getLong(1)) }
        var opened = 0
        var closed = 0
        for (o in trades.objects()) {
            val variant = localVariant(o.getString("variant"), labIds) ?: continue
            val listId = o.optStr("list")?.let { listIds[it] ?: continue } ?: 0L
            val cv = ContentValues().apply {
                put("variant", variant); put("family", o.getString("family")); put("symbol", o.getString("symbol")); put("tf", o.getString("tf"))
                put("list_id", listId); put("bar_time", o.getLong("bar_time")); put("detected_at", o.getLong("detected_at")); put("regime", o.getInt("regime"))
                put("entry_time", o.getLong("entry_time")); put("entry_price", o.getDouble("entry_price")); put("target", o.optDbl("target")); put("stop", o.optDbl("stop"))
                put("hold_bars", o.getInt("hold_bars")); put("exit_due", o.getLong("exit_due")); put("opened_at", o.getLong("opened_at"))
                put("cost", o.optDbl("cost")); put("exit_mode", o.optStr("exit_mode")); put("atr", o.optDbl("atr"))
            }
            if (d.insertWithOnConflict("live_trades", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) opened++
            val x = o.optJSONObject("exit") ?: continue
            val tradeId = d.rawQuery("SELECT id FROM live_trades WHERE variant=? AND symbol=? AND bar_time=? AND list_id=?",
                arrayOf(variant, o.getString("symbol"), o.getLong("bar_time").toString(), listId.toString())).use { if (it.moveToFirst()) it.getLong(0) else null } ?: continue
            val xv = ContentValues().apply {
                put("trade_id", tradeId); put("exit_time", x.getLong("exit_time")); put("exit_price", x.getDouble("exit_price")); put("exit_reason", x.getString("exit_reason"))
                put("bars_held", x.getInt("bars_held")); put("gross", x.getDouble("gross")); put("net", x.getDouble("net"))
                put("random_mean", x.optDbl("random_mean")); put("excess", x.optDbl("excess")); put("closed_at", x.getLong("closed_at"))
                put("max_up", x.optDbl("max_up")); put("max_down", x.optDbl("max_down")); put("bars_to_peak", x.optLng("bars_to_peak"))
            }
            if (d.insertWithOnConflict("live_exits", null, xv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) closed++
        }
        return MergeResult(trades = opened, exits = closed)
    }

    private fun mergeSettings(d: SQLiteDatabase, settings: JSONArray): MergeResult {
        val changed = HashSet<String>()
        for (o in settings.objects()) {
            val key = o.getString("key")
            if (key !in SettingsStore.SYNCED) continue
            val updated = o.getLong("updated")
            val local = d.rawQuery("SELECT value, updated_at FROM settings WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null }
            if (local != null && local.second >= updated) continue
            d.execSQL("INSERT OR REPLACE INTO settings (key, value, updated_at) VALUES (?,?,?)", arrayOf<Any?>(key, o.getString("value"), updated))
            if (local?.first != o.getString("value")) changed += key
        }
        return MergeResult(settingsChanged = changed)
    }

    // --- names -----------------------------------------------------------------------------------

    /** A variant as other devices know it: a lab pattern's by its sync id. Null for a lab pattern with none (it cannot be named). */
    private fun wireVariant(variant: String, labSync: Map<Long, String>): String? {
        val id = LabPatterns.idOf(variant) ?: return variant
        val sync = labSync[id] ?: return null
        return "lab@$sync" + variant.substring(variant.indexOf('_'))
    }

    /** A variant from another device in this phone's terms; null when it names a lab pattern this phone does not have. */
    private fun localVariant(variant: String, labIds: Map<String, Long>): String? {
        if (!variant.startsWith("lab@")) return variant
        val cut = variant.indexOf('_')
        if (cut < 0) return null
        val id = labIds[variant.substring(4, cut)] ?: return null
        return "lab$id" + variant.substring(cut)
    }

    /** A variant's parameters with a lab pattern's id made this phone's, so the registry says what the name says. */
    private fun localParams(params: String, localName: String): String {
        val id = LabPatterns.idOf(localName) ?: return params
        val o = JSONObject(params)
        if (o.has("lab_id")) o.put("lab_id", id.toDouble())
        return o.toString()
    }

    companion object {
        /** The layout of the exported record. A device that finds a newer one leaves it alone rather than misread it. */
        const val FORMAT = 1
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.optStr(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optDbl(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null

private fun JSONObject.optLng(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

private fun Cursor.dbl(i: Int): Double? = if (isNull(i)) null else getDouble(i)

private fun Cursor.long(i: Int): Long? = if (isNull(i)) null else getLong(i)
