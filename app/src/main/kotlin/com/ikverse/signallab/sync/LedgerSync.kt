package com.ikverse.signallab.sync

import androidx.room.withTransaction
import com.ikverse.signallab.data.futures.BasisPoint
import com.ikverse.signallab.data.futures.DerivativesStore
import com.ikverse.signallab.data.futures.Positioning
import com.ikverse.signallab.data.futures.PositioningPoint
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.ledger.AnalystNotes
import com.ikverse.signallab.ledger.CallRow
import com.ikverse.signallab.ledger.CustomStopRow
import com.ikverse.signallab.ledger.CustomStrategyRow
import com.ikverse.signallab.ledger.ExitRow
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.PinRow
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.ledger.ReportRow
import com.ikverse.signallab.ledger.SettingRow
import com.ikverse.signallab.ledger.StrategyRow
import org.json.JSONArray
import org.json.JSONObject

/** What one merge brought in. */
data class MergeResult(
    val calls: Int = 0,
    val exits: Int = 0,
    val strategies: Int = 0,
    val customs: Int = 0,
    val reports: Int = 0,
    val pins: Int = 0,
    val settingsChanged: Set<String> = emptySet(),
    /** Futures basis and positioning points brought in; not part of [any], which is about the ledger. */
    val market: Int = 0,
) {
    val any: Boolean get() = calls + exits + strategies + customs + reports + pins > 0 || settingsChanged.isNotEmpty()

    operator fun plus(o: MergeResult) = MergeResult(calls + o.calls, exits + o.exits, strategies + o.strategies, customs + o.customs,
        reports + o.reports, pins + o.pins, settingsChanged + o.settingsChanged, market + o.market)
}

/**
 * The ledger as another device reads it (format 2), and the merge of another device's record into this one: format 2 from
 * Signal Lab 2, and format 1 from a device still on Signal Lab 1.
 *
 * Everything is matched by what it is, never by a row id: a call by its strategy, coin, signal candle and scope (so the same
 * signal seen by two devices is one call, and the copy already here stays), a custom strategy and a report by their sync ids.
 * The append-only tables only ever gain rows. The few settings that follow the owner from device to device ([isSynced]) take
 * the newer value. Merging the same record twice changes nothing.
 *
 * A Signal Lab 1 lab pattern's strategy id carries the row id it had on its own phone (`v1:lab3_1h`); on the wire it becomes
 * `v1:lab@<sync id>_1h`, and back here it takes this phone's id for that pattern when there is one.
 */
class LedgerSync(
    private val ledger: LedgerDatabase,
    /** The futures series that cannot be downloaded again after 30 days (basis, positioning) travel too; null leaves them out. */
    private val market: DerivativesStore? = null,
) {
    private val dao get() = ledger.dao()

    // --- export ---------------------------------------------------------------------------------------------------------

    suspend fun export(deviceId: String, now: Long): JSONObject {
        val customs = dao.allCustoms()
        val labSync = customs.mapNotNull { c -> c.legacyId?.let { it to c.syncId } }.toMap()
        val exits = dao.allExits().associateBy { it.callId }
        val stops = dao.allCustomStops().associateBy { it.customId }

        val strategies = JSONArray()
        for (s in dao.allStrategies()) {
            strategies.put(JSONObject().put("id", wire(s.id, labSync)).put("family", s.family).put("tf", s.tf).put("params", s.params)
                .put("first_seen", s.firstSeenAt).put("retired", s.retired).put("source", s.source))
        }
        val calls = JSONArray()
        for (c in dao.allCalls()) {
            val o = JSONObject().put("strategy", wire(c.strategyId, labSync)).put("symbol", c.symbol).put("tf", c.tf)
                .put("signal_time", c.signalTime).put("entry_time", c.entryTime).put("entry_price", c.entryPrice).putOpt("atr", c.atr)
                .putOpt("stop", c.stop).putOpt("target", c.target).put("max_bars", c.maxBars).put("regime", c.regime)
                .putOpt("size", c.sizeFraction).put("cost", c.cost).put("cost_assumed", c.costAssumed).putOpt("exit_mode", c.exitMode)
                .put("scope", c.scope).put("source", c.source).put("archived", c.archived).put("status", c.status).put("opened_at", c.openedAt)
                .putOpt("plain_size", c.plainSize).putOpt("veto", c.veto)
            exits[c.id]?.let { e ->
                o.put("exit", JSONObject().put("exit_time", e.exitTime).put("exit_price", e.exitPrice).put("reason", e.reason)
                    .put("bars_held", e.barsHeld).put("gross", e.gross).put("net", e.net).putOpt("random_mean", e.randomMean)
                    .putOpt("excess", e.excess).putOpt("max_up", e.maxUp).putOpt("max_down", e.maxDown).putOpt("bars_to_peak", e.barsToPeak)
                    .put("closed_at", e.closedAt))
            }
            calls.put(o)
        }
        val customArray = JSONArray()
        for (c in customs) {
            customArray.put(JSONObject().put("sync", c.syncId).put("created", c.createdAt).put("title", c.title).putOpt("reason", c.reason)
                .putOpt("definition_v1", c.definitionV1).putOpt("definition_v2", c.definitionV2).put("archived", c.archived)
                .putOpt("report", c.reportSyncId).putOpt("stopped", stops[c.id]?.stoppedAt))
        }
        val reports = JSONArray()
        for (r in dao.allReports()) {
            reports.put(JSONObject().put("sync", r.syncId).put("received", r.receivedAt).putOpt("card", r.card).put("title", r.title).put("body", r.body))
        }
        val pins = JSONArray()
        for (p in dao.pins()) pins.put(JSONObject().put("symbol", p.symbol).put("kind", p.kind).put("added", p.addedAt).putOpt("tag", p.tag))
        val settings = JSONArray()
        for (s in dao.allSettings()) if (isSynced(s.key)) settings.put(JSONObject().put("key", s.key).put("value", s.value).put("updated", s.updatedAt))

        return JSONObject().put("format", FORMAT).put("device", deviceId).put("exported_at", now).put("strategies", strategies)
            .put("calls", calls).put("customs", customArray).put("reports", reports).put("pins", pins).put("settings", settings)
            .putOpt("market", exportMarket())
    }

    /** Basis and positioning, the futures series Binance keeps for 30 days only, so no device loses what another collected. */
    private suspend fun exportMarket(): JSONObject? {
        val m = market ?: return null
        val basis = JSONArray()
        for ((pair, contract) in m.basisSeries()) for (p in m.basis(pair, contract)) {
            basis.put(JSONArray().put(pair).put(contract).put(p.time).put(p.futuresPrice).put(p.indexPrice).put(p.basisRate)
                .put(if (p.annualized.isNaN()) JSONObject.NULL else p.annualized))
        }
        val positioning = JSONArray()
        for ((symbol, kind) in m.positioningSeries()) for (p in m.positioning(symbol, kind)) {
            positioning.put(JSONArray().put(symbol).put(kind.label).put(p.time).put(p.longSide).put(p.shortSide).put(p.ratio))
        }
        return JSONObject().put("basis", basis).put("positioning", positioning)
    }

    /** Stores the other device's basis and positioning points this one lacks; returns how many were new. */
    private suspend fun mergeMarket(o: JSONObject?): Int {
        val m = market ?: return 0
        if (o == null) return 0
        var added = 0
        val basis = o.optJSONArray("basis")
        if (basis != null) {
            val rows = (0 until basis.length()).map { basis.getJSONArray(it) }
            for ((key, group) in rows.groupBy { it.getString(0) to it.getString(1) }) {
                added += m.insertBasis(key.first, key.second, group.map { r ->
                    BasisPoint(r.getLong(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), if (r.isNull(6)) Double.NaN else r.getDouble(6))
                })
            }
        }
        val positioning = o.optJSONArray("positioning")
        if (positioning != null) {
            val rows = (0 until positioning.length()).map { positioning.getJSONArray(it) }
            for ((key, group) in rows.groupBy { it.getString(0) to it.getString(1) }) {
                val kind = Positioning.entries.firstOrNull { it.label == key.second } ?: continue
                added += m.insertPositioning(key.first, kind, group.map { r -> PositioningPoint(r.getLong(2), r.getDouble(3), r.getDouble(4), r.getDouble(5)) })
            }
        }
        return added
    }

    /** The record's content without when it was exported, so an unchanged record is not sent again. */
    fun fingerprint(export: JSONObject): String {
        val copy = JSONObject(export.toString())
        copy.remove("exported_at")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(copy.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // --- merge ----------------------------------------------------------------------------------------------------------

    /** Merges another device's record, format 1 or 2, in one transaction; a format this version does not know is refused. */
    suspend fun merge(remote: JSONObject): MergeResult = when (val format = remote.optInt("format")) {
        // The futures series live in their own file, so they are merged after the ledger's transaction, and only add rows.
        FORMAT -> ledger.withTransaction { mergeV2(remote) }.let { it.copy(market = mergeMarket(remote.optJSONObject("market"))) }
        1 -> ledger.withTransaction { mergeV1(remote) }
        else -> throw IllegalArgumentException("Sync data is format $format; this version reads formats 1 and $FORMAT.")
    }

    private suspend fun labIds(): Map<String, Long> = dao.allCustoms().mapNotNull { c -> c.legacyId?.let { c.syncId to it } }.toMap()

    private suspend fun mergeV2(o: JSONObject): MergeResult {
        var r = MergeResult()
        // Custom strategies first, so a lab pattern's strategy id can be turned into this phone's.
        for (c in o.optJSONArray("customs").objects()) {
            val row = CustomStrategyRow(syncId = c.getString("sync"), createdAt = c.getLong("created"), title = c.getString("title"),
                reason = c.str("reason"), definitionV1 = c.str("definition_v1"), definitionV2 = c.str("definition_v2"),
                archived = c.getBoolean("archived"), reportSyncId = c.str("report"))
            val id = dao.insertCustom(row).let { if (it != -1L) { r = r.copy(customs = r.customs + 1); it } else dao.customId(row.syncId) }
            val stopped = c.lng("stopped")
            if (id != null && stopped != null) dao.insertCustomStop(CustomStopRow(id, stopped))
        }
        val labs = labIds()
        for (s in o.optJSONArray("strategies").objects()) {
            val row = StrategyRow(local(s.getString("id"), labs), s.getString("family"), s.getString("tf"), s.getString("params"),
                s.getLong("first_seen"), s.getBoolean("retired"), s.getString("source"))
            if (dao.insertStrategy(row) != -1L) r = r.copy(strategies = r.strategies + 1)
        }
        for (rep in o.optJSONArray("reports").objects()) {
            val row = ReportRow(syncId = rep.getString("sync"), receivedAt = rep.getLong("received"), card = rep.str("card"),
                title = rep.getString("title"), body = rep.getString("body"))
            if (dao.insertReport(row) != -1L) r = r.copy(reports = r.reports + 1)
        }
        for (c in o.optJSONArray("calls").objects()) {
            val row = CallRow(
                strategyId = local(c.getString("strategy"), labs), symbol = c.getString("symbol"), tf = c.getString("tf"),
                signalTime = c.getLong("signal_time"), entryTime = c.getLong("entry_time"), entryPrice = c.getDouble("entry_price"),
                atr = c.dbl("atr"), stop = c.dbl("stop"), target = c.dbl("target"), maxBars = c.getInt("max_bars"), regime = c.getInt("regime"),
                sizeFraction = c.dbl("size"), cost = c.getDouble("cost"), costAssumed = c.optBoolean("cost_assumed"), exitMode = c.str("exit_mode"),
                scope = c.optString("scope"), source = c.getString("source"), archived = c.getBoolean("archived"), status = c.getString("status"),
                openedAt = c.getLong("opened_at"), plainSize = c.dbl("plain_size"), veto = c.str("veto"),
            )
            r = r + addCall(row, c.optJSONObject("exit")?.let { e ->
                ExitRow(callId = 0, exitTime = e.getLong("exit_time"), exitPrice = e.getDouble("exit_price"), reason = e.getString("reason"),
                    barsHeld = e.getInt("bars_held"), gross = e.getDouble("gross"), net = e.getDouble("net"), randomMean = e.dbl("random_mean"),
                    excess = e.dbl("excess"), maxUp = e.dbl("max_up"), maxDown = e.dbl("max_down"), barsToPeak = e.lng("bars_to_peak")?.toInt(),
                    closedAt = e.getLong("closed_at"))
            })
        }
        for (p in o.optJSONArray("pins").objects()) {
            if (dao.insertPin(PinRow(p.getString("symbol"), p.getString("kind"), p.getLong("added"), p.str("tag"))) != -1L) r = r.copy(pins = r.pins + 1)
        }
        return r.copy(settingsChanged = mergeSettings(o.optJSONArray("settings")))
    }

    private suspend fun addCall(row: CallRow, exit: ExitRow?): MergeResult {
        var calls = 0
        var id = dao.insertCall(row)
        if (id == -1L) id = dao.callId(row.strategyId, row.symbol, row.signalTime, row.scope) ?: return MergeResult() else calls++
        val exits = if (exit != null && dao.insertExit(exit.copy(callId = id)) != -1L) 1 else 0
        return MergeResult(calls = calls, exits = exits)
    }

    private suspend fun mergeSettings(settings: JSONArray?): Set<String> {
        val changed = HashSet<String>()
        val here = dao.allSettings().associateBy { it.key }
        for (s in settings.objects()) {
            val key = s.getString("key")
            if (!isSynced(key)) continue
            val updated = s.getLong("updated")
            val local = here[key]
            if (local != null && local.updatedAt >= updated) continue
            dao.putSetting(SettingRow(key, s.getString("value"), updated))
            if (local?.value != s.getString("value")) changed += key
        }
        return changed
    }

    /**
     * A record from a device still on Signal Lab 1, mapped as the importer maps that version's own file: trades become
     * archived calls of `v1:` strategies, scoped by their list; lab patterns become archived custom strategies; the coins
     * of its active lists become pins. Its settings are left alone: Signal Lab 2's are not the same.
     */
    private suspend fun mergeV1(o: JSONObject): MergeResult {
        var r = MergeResult()
        val reportBySync = HashSet<String>()
        for (rep in o.optJSONArray("reports").objects()) {
            val sync = rep.getString("sync")
            reportBySync += sync
            val row = ReportRow(syncId = sync, receivedAt = rep.getLong("received"), card = rep.str("card"), title = rep.getString("title"), body = rep.getString("body"))
            if (dao.insertReport(row) != -1L) r = r.copy(reports = r.reports + 1)
        }
        for (p in o.optJSONArray("lab").objects()) {
            val row = CustomStrategyRow(syncId = p.getString("sync"), createdAt = p.getLong("started"), title = p.getString("title"),
                reason = p.str("reason"), definitionV1 = p.getString("definition"), definitionV2 = null, archived = true,
                reportSyncId = p.str("report"))
            val id = dao.insertCustom(row).let { if (it != -1L) { r = r.copy(customs = r.customs + 1); it } else dao.customId(row.syncId) }
            val stopped = p.lng("stopped")
            if (id != null && stopped != null) dao.insertCustomStop(CustomStopRow(id, stopped))
        }
        val labs = labIds()
        for (v in o.optJSONArray("variants").objects()) {
            val row = StrategyRow(local("v1:" + v.getString("variant"), labs), v.getString("family"), v.getString("tf"), v.getString("params"),
                v.getLong("first_seen"), retired = true, source = "v1")
            if (dao.insertStrategy(row) != -1L) r = r.copy(strategies = r.strategies + 1)
        }
        for (t in o.optJSONArray("trades").objects()) {
            val symbol = t.getString("symbol")
            val target = t.dbl("target")
            val cost = t.dbl("cost")
            val row = CallRow(
                strategyId = local("v1:" + t.getString("variant"), labs), symbol = symbol, tf = t.getString("tf"), signalTime = t.getLong("bar_time"),
                entryTime = t.getLong("entry_time"), entryPrice = t.getDouble("entry_price"), atr = t.dbl("atr"), stop = t.dbl("stop"),
                target = target, maxBars = t.getInt("hold_bars"), regime = t.getInt("regime"), sizeFraction = null,
                cost = cost ?: EngineConfig.costFor(symbol), costAssumed = cost == null,
                exitMode = t.str("exit_mode") ?: if (target != null) "classic" else "held",
                scope = t.str("list") ?: "", source = "v1", archived = true, status = "v1", openedAt = t.getLong("opened_at"),
            )
            r = r + addCall(row, t.optJSONObject("exit")?.let { x ->
                ExitRow(callId = 0, exitTime = x.getLong("exit_time"), exitPrice = x.getDouble("exit_price"), reason = x.getString("exit_reason"),
                    barsHeld = x.getInt("bars_held"), gross = x.getDouble("gross"), net = x.getDouble("net"), randomMean = x.dbl("random_mean"),
                    excess = x.dbl("excess"), maxUp = x.dbl("max_up"), maxDown = x.dbl("max_down"), barsToPeak = x.lng("bars_to_peak")?.toInt(),
                    closedAt = x.getLong("closed_at"))
            })
        }
        for (l in o.optJSONArray("lists").objects()) {
            if (!l.optBoolean("active")) continue
            val coins = l.optJSONArray("coins") ?: continue
            for (i in 0 until coins.length()) {
                val c = coins.getJSONArray(i)
                if (dao.insertPin(PinRow(c.getString(0), "pin", c.getLong(1), l.optString("name"))) != -1L) r = r.copy(pins = r.pins + 1)
            }
        }
        return r
    }

    companion object {
        /** The layout of the record Signal Lab 2 writes. Signal Lab 1 wrote format 1, which this version still reads. */
        const val FORMAT = 2

        private val SYNCED_KEYS = setOf(
            Preferences.ACCOUNT_SIZE, Preferences.TRADE_MONEY, Preferences.COST_MAJORS, Preferences.COST_OTHERS, Preferences.DATA_HOST, Preferences.FUTURES,
            Preferences.ANALYST_MODEL, Preferences.ANALYST_CAP,
        )
        private val SYNCED_PREFIXES = listOf(Preferences.SWITCH_PREFIX, Preferences.CUSTOM_LIVE_PREFIX, AnalystNotes.SUGGESTION_PREFIX, AnalystNotes.CONTEXT_PREFIX)

        /**
         * The settings that follow the owner to every device: the account and its costs, the data source, which strategies
         * are on and running, the analyst's model and budget, its suggestions and context lines. Each device keeps its own
         * notifications, schedule, tiers (it computes them), spending meter and sync state; and only the device the owner
         * chose runs the brief and the review, so they are not paid for twice.
         */
        fun isSynced(key: String): Boolean = key in SYNCED_KEYS || SYNCED_PREFIXES.any { key.startsWith(it) }

        private val LOCAL_LAB = Regex("""^v1:lab(\d+)_(.+)$""")
        private val WIRE_LAB = Regex("""^v1:lab@([^_]+)_(.+)$""")

        /** A strategy id as other devices know it: a Signal Lab 1 lab pattern's by its sync id. */
        fun wire(id: String, labSync: Map<Long, String>): String {
            val m = LOCAL_LAB.matchEntire(id) ?: return id
            val sync = labSync[m.groupValues[1].toLong()] ?: return id
            return "v1:lab@$sync" + "_" + m.groupValues[2]
        }

        /** A strategy id from another device in this phone's terms: its own id for a lab pattern it knows. */
        fun local(id: String, labIds: Map<String, Long>): String {
            val m = WIRE_LAB.matchEntire(id) ?: return id
            val legacy = labIds[m.groupValues[1]] ?: return id
            return "v1:lab$legacy" + "_" + m.groupValues[2]
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null

private fun JSONObject.lng(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
