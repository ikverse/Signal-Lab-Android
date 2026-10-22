package com.ikverse.signallab.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.futures.BasisPoint
import com.ikverse.signallab.data.futures.DerivativesStore
import com.ikverse.signallab.data.futures.Positioning
import com.ikverse.signallab.data.futures.PositioningPoint
import com.ikverse.signallab.ledger.CallRow
import com.ikverse.signallab.ledger.CustomStrategyRow
import com.ikverse.signallab.ledger.ExitRow
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.PinRow
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.ledger.ReportRow
import com.ikverse.signallab.ledger.SettingRow
import com.ikverse.signallab.ledger.StrategyRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SyncTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var a: LedgerDatabase
    private lateinit var b: LedgerDatabase

    @Before
    fun setUp() {
        a = LedgerDatabase.open(context, name = null)
        b = LedgerDatabase.open(context, name = null)
    }

    @After
    fun tearDown() {
        a.close()
        b.close()
    }

    private fun call(strategy: String, symbol: String, signal: Long, scope: String = "", source: String = "v2") = CallRow(
        strategyId = strategy, symbol = symbol, tf = "1d", signalTime = signal, entryTime = signal + DAY, entryPrice = 100.0, atr = 4.0,
        stop = 88.0, target = null, maxBars = 60, regime = 1, sizeFraction = if (source == "v2") 0.1 else null, cost = 0.004, scope = scope,
        source = source, archived = source == "v1", status = if (source == "v1") "v1" else "live", openedAt = signal + DAY,
    )

    private fun exit(id: Long, net: Double) = ExitRow(callId = id, exitTime = 5 * DAY, exitPrice = 110.0, reason = "signal", barsHeld = 4,
        gross = net + 0.004, net = net, randomMean = 0.01, excess = net - 0.01, maxUp = 0.12, maxDown = -0.03, barsToPeak = null, closedAt = 5 * DAY)

    /** A device with a little of everything: calls of both versions, a lab pattern's call, a custom strategy, a report, pins, settings. */
    private suspend fun fill(db: LedgerDatabase) {
        val dao = db.dao()
        dao.insertStrategy(StrategyRow("breakout50_1d", "Breakout", "1d", "{}", 1, retired = false, source = "v2"))
        dao.insertStrategy(StrategyRow("v1:lab3_1h", "lab", "1h", "{}", 1, retired = true, source = "v1"))
        val c1 = dao.insertCall(call("breakout50_1d", "SOLUSDT", 10 * DAY))
        dao.insertExit(exit(c1, 0.05))
        dao.insertCall(call("breakout50_1d", "ETHUSDT", 20 * DAY))
        dao.insertCall(call("v1:trend_ma20_1d", "BTCUSDT", 2 * DAY, scope = "list-sync-1", source = "v1"))
        dao.insertCall(call("v1:lab3_1h", "ADAUSDT", 3 * DAY, scope = "list-sync-1", source = "v1"))
        dao.insertCustom(CustomStrategyRow(syncId = "lab-sync-S", createdAt = 1, title = "Old lab", reason = null, definitionV1 = "{}",
            definitionV2 = null, archived = true, legacyId = 3))
        dao.insertCustom(CustomStrategyRow(syncId = "custom-sync-X", createdAt = 2, title = "Quiet breakout", reason = "why",
            definitionV1 = null, definitionV2 = "(strategy (timeframe 1d) (entry (> close (highest high 20))) (max_bars 20))", archived = false,
            reportSyncId = "report-1"))
        dao.insertReport(ReportRow(syncId = "report-1", receivedAt = 3, card = "proposal", title = "Proposed", body = "text"))
        dao.insertPin(PinRow("DOGEUSDT", "pin", 4, "Memes"))
        dao.putSetting(SettingRow(Preferences.ACCOUNT_SIZE, "25000.0", 100))
        dao.putSetting(SettingRow(Preferences.SWITCH_PREFIX + "trend50_1d", "1", 100))
        dao.putSetting(SettingRow(Preferences.NOTIFICATIONS, "0", 100))
        dao.putSetting(SettingRow(Preferences.TIER_PREFIX + "breakout50_1d", "WATCH", 100))
    }

    @Test
    fun aRecordMergesIntoAnotherDeviceOnceAndComesBackUnchanged() = runTest {
        fill(a)
        val sa = LedgerSync(a)
        val sb = LedgerSync(b)
        val record = sa.export("device-a", 1000)
        assertEquals(LedgerSync.FORMAT, record.getInt("format"))
        assertEquals("v1:lab@lab-sync-S_1h", record.getJSONArray("calls").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("strategy") } }
            .first { it.contains("lab") }, "a lab pattern's call is named by its sync id on the wire")

        val first = sb.merge(JSONObject(record.toString()))
        assertEquals(4, first.calls)
        assertEquals(1, first.exits)
        assertEquals(2, first.customs)
        assertEquals(1, first.reports)
        assertEquals(1, first.pins)
        assertEquals(setOf(Preferences.ACCOUNT_SIZE, Preferences.SWITCH_PREFIX + "trend50_1d"), first.settingsChanged)
        val dao = b.dao()
        assertEquals("25000.0", dao.setting(Preferences.ACCOUNT_SIZE))
        assertEquals(null, dao.setting(Preferences.NOTIFICATIONS), "each device keeps its own notifications")
        assertEquals(null, dao.setting(Preferences.TIER_PREFIX + "breakout50_1d"), "each device computes its own tiers")
        val sol = dao.allCalls().first { it.symbol == "SOLUSDT" }
        assertEquals(0.05, assertNotNull(dao.exitOf(sol.id)).net)
        assertEquals("custom-sync-X", dao.allCustoms().first { it.definitionV2 != null }.syncId)

        assertFalse(sb.merge(JSONObject(record.toString())).any, "the same record twice changes nothing")
        // And back: device a learns nothing new from b, though b knew the lab pattern only by its sync id.
        assertFalse(sa.merge(sb.export("device-b", 2000)).any)
        assertEquals(4, a.dao().allCalls().size)
        assertEquals(sa.fingerprint(sa.export("device-a", 1)), sa.fingerprint(sa.export("device-a", 2)), "the time of export is not content")
    }

    @Test
    fun theThirtyDayFuturesSeriesTravelWithTheLedger() = runTest {
        val ma = DerivativesStore(context, name = null, io = Dispatchers.Unconfined)
        val mb = DerivativesStore(context, name = null, io = Dispatchers.Unconfined)
        ma.insertBasis("BTCUSDT", "CURRENT_QUARTER", listOf(BasisPoint(10, 101.0, 100.0, 0.01, 0.05), BasisPoint(20, 102.0, 100.0, 0.02, Double.NaN)))
        ma.insertPositioning("BTCUSDT", Positioning.TOP_POSITIONS, listOf(PositioningPoint(10, 0.6, 0.4, 1.5)))
        mb.insertBasis("BTCUSDT", "CURRENT_QUARTER", listOf(BasisPoint(30, 103.0, 100.0, 0.03, 0.07)))
        val r = LedgerSync(b, mb).merge(JSONObject(LedgerSync(a, ma).export("a", 1).toString()))
        assertEquals(3, r.market)
        assertFalse(r.any, "market data alone is not a change to the ledger")
        assertEquals(listOf(10L, 20L, 30L), mb.basis("BTCUSDT", "CURRENT_QUARTER").map { it.time })
        assertTrue(mb.basis("BTCUSDT", "CURRENT_QUARTER")[1].annualized.isNaN())
        assertEquals(listOf(PositioningPoint(10, 0.6, 0.4, 1.5)), mb.positioning("BTCUSDT", Positioning.TOP_POSITIONS))
        assertEquals(0, LedgerSync(b, mb).merge(JSONObject(LedgerSync(a, ma).export("a", 2).toString())).market, "merging twice adds nothing")
        assertEquals(0, LedgerSync(b).merge(LedgerSync(a, ma).export("a", 3)).market, "a device without the store skips it")
    }

    @Test
    fun theNewerSettingWinsAndAnUnknownFormatIsRefused() = runTest {
        a.dao().putSetting(SettingRow(Preferences.ACCOUNT_SIZE, "5000.0", 200))
        b.dao().putSetting(SettingRow(Preferences.ACCOUNT_SIZE, "8000.0", 100))
        LedgerSync(b).merge(LedgerSync(a).export("a", 1))
        assertEquals("5000.0", b.dao().setting(Preferences.ACCOUNT_SIZE))
        b.dao().putSetting(SettingRow(Preferences.ACCOUNT_SIZE, "9000.0", 300))
        LedgerSync(b).merge(LedgerSync(a).export("a", 2))
        assertEquals("9000.0", b.dao().setting(Preferences.ACCOUNT_SIZE), "an older value does not win")
        val refused = runCatching { LedgerSync(b).merge(JSONObject().put("format", 3)) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
    }

    /** A record as Signal Lab 1 wrote it (format 1), with one trade this device already imported and one it has not. */
    private fun formatOne() = JSONObject().put("format", 1).put("device", "old-phone").put("exported_at", 1)
        .put("lists", JSONArray().put(JSONObject().put("sync", "list-sync-1").put("name", "Majors").put("active", true).put("created", 1)
            .put("position", 0).put("timeframes", "1d").put("updated", 1).put("coins", JSONArray().put(JSONArray().put("XRPUSDT").put(5L)))))
        .put("tombstones", JSONArray())
        .put("settings", JSONArray().put(JSONObject().put("key", "fee_per_side").put("value", "0.002").put("updated", 1)))
        .put("reports", JSONArray().put(JSONObject().put("sync", "report-old").put("received", 1).put("title", "Old report").put("body", "b")))
        .put("lab", JSONArray().put(JSONObject().put("sync", "lab-sync-S").put("started", 1).put("title", "Old lab").put("definition", "{}")))
        .put("variants", JSONArray().put(JSONObject().put("variant", "lab@lab-sync-S_1h").put("family", "lab").put("tf", "1h").put("params", "{}").put("first_seen", 1)))
        .put("trades", JSONArray()
            .put(trade("trend_ma20_1d", "BTCUSDT", 2 * DAY))
            .put(trade("lab@lab-sync-S_1h", "ADAUSDT", 3 * DAY))
            .put(trade("donchian20_1d", "LINKUSDT", 7 * DAY).put("exit", JSONObject().put("exit_time", 9 * DAY).put("exit_price", 12.0)
                .put("exit_reason", "target").put("bars_held", 2).put("gross", 0.2).put("net", 0.196).put("closed_at", 9 * DAY))))

    private fun trade(variant: String, symbol: String, bar: Long) = JSONObject().put("variant", variant).put("family", "x").put("symbol", symbol)
        .put("tf", "1d").put("list", "list-sync-1").put("bar_time", bar).put("detected_at", bar).put("regime", 1).put("entry_time", bar + DAY)
        .put("entry_price", 10.0).put("target", 11.0).put("stop", 9.0).put("hold_bars", 10).put("exit_due", bar + 11 * DAY).put("opened_at", bar + DAY)

    @Test
    fun aSignalLab1RecordAddsOnlyWhatThisDeviceDidNotImport() = runTest {
        fill(a)
        val r = LedgerSync(a).merge(formatOne())
        assertEquals(1, r.calls, "the trend and lab trades were already imported here; only the Donchian trade is new")
        assertEquals(1, r.exits)
        assertEquals(1, r.reports)
        assertEquals(0, r.customs, "the lab pattern is already here")
        assertEquals(1, r.pins)
        assertTrue(r.settingsChanged.isEmpty(), "Signal Lab 1's settings are not Signal Lab 2's")
        val link = a.dao().allCalls().first { it.symbol == "LINKUSDT" }
        assertEquals("v1:donchian20_1d", link.strategyId)
        assertEquals("list-sync-1", link.scope)
        assertEquals("classic", link.exitMode)
        assertEquals(0.004, link.cost, "no cost recorded: the research's default for the coin")
        assertTrue(link.costAssumed)
        assertEquals("target", assertNotNull(a.dao().exitOf(link.id)).reason)
        assertFalse(LedgerSync(a).merge(formatOne()).any)
    }

    private class Folder : RemoteFolder {
        val files = LinkedHashMap<String, Triple<String, ByteArray, Int>>()
        private var next = 0

        override suspend fun list() = files.map { (id, f) -> RemoteFile(id, f.first, "v${f.third}") }
        override suspend fun download(id: String) = files.getValue(id).second
        override suspend fun create(name: String, bytes: ByteArray): String = "id${next++}".also { files[it] = Triple(name, bytes, 1) }
        override suspend fun update(id: String, bytes: ByteArray) {
            val f = files.getValue(id)
            files[id] = Triple(f.first, bytes, f.third + 1)
        }
        override suspend fun accountEmail(): String? = "owner@example.com"
    }

    private class Memory(val id: String) : SyncMemory {
        val m = HashMap<String, String>()
        override suspend fun deviceId() = id
        override suspend fun ownFileId() = m["own"]
        override suspend fun saveOwnFileId(id: String) { m["own"] = id }
        override suspend fun lastSent() = m["sent"]
        override suspend fun saveLastSent(fingerprint: String) { m["sent"] = fingerprint }
        override suspend fun seen(fileId: String) = m["seen$fileId"]
        override suspend fun saveSeen(fileId: String, modified: String) { m["seen$fileId"] = modified }
    }

    @Test
    fun devicesMeetThroughTheFolderAndAnOldPhonesFileIsRead() = runTest {
        fill(a)
        val folder = Folder()
        folder.files["old"] = Triple("device-old-phone.json.gz", SyncEngine.gzip(formatOne().toString()), 1)
        folder.files["future"] = Triple("device-new.v2.json.gz", SyncEngine.gzip(JSONObject().put("format", 9).toString()), 1)
        val ea = SyncEngine(folder, LedgerSync(a), Memory("A"))
        val eb = SyncEngine(folder, LedgerSync(b), Memory("B"))

        val ra = ea.round()
        assertTrue(ra.sent)
        assertEquals(1, ra.unreadable, "a record from a newer version is left alone")
        assertTrue(folder.files.values.any { it.first == "device-A.v2.json.gz" })

        val rb = eb.round()
        assertTrue(rb.merged.calls >= 5, "b has a's calls and the old phone's: ${rb.merged}")
        assertEquals(3, rb.devices)
        assertTrue(rb.sent)
        assertEquals(a.dao().allCalls().size, b.dao().allCalls().size)

        val again = eb.round()
        assertFalse(again.merged.any)
        assertFalse(again.sent, "nothing changed, nothing sent")
    }

    @Test
    fun onlyTheOwnersChoicesTravel() {
        for (k in listOf(Preferences.ACCOUNT_SIZE, Preferences.COST_OTHERS, Preferences.DATA_HOST, "strategy_on.x", "custom_live.y", "analyst_call.z", "context.w")) {
            assertTrue(LedgerSync.isSynced(k), k)
        }
        for (k in listOf(Preferences.NOTIFICATIONS, Preferences.ANALYST_BRIEF, Preferences.ANALYST_REVIEW, "tier.x", "analyst_spend.2026-10", DeviceSync.ENABLED, "daily_pass_day")) {
            assertFalse(LedgerSync.isSynced(k), k)
        }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
