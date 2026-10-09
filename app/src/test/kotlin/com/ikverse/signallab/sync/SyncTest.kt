package com.ikverse.signallab.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.EPOCH_START
import com.ikverse.signallab.data.FakeMarket
import com.ikverse.signallab.data.LabStore
import com.ikverse.signallab.data.NewTrade
import com.ikverse.signallab.data.RecordDatabase
import com.ikverse.signallab.data.ReportLog
import com.ikverse.signallab.data.SettingsStore
import com.ikverse.signallab.data.TradeExit
import com.ikverse.signallab.data.TradeLog
import com.ikverse.signallab.data.UniverseRepository
import com.ikverse.signallab.data.WatchlistRepository
import com.ikverse.signallab.data.WatchlistResult
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.SignalKey
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val context: Context get() = ApplicationProvider.getApplicationContext()

/** One device's record and the stores that write it, on an in-memory database and a clock the test moves. */
private class Device {
    var now = 1_000L
    val db = RecordDatabase(context, null)
    val clock = { now }
    val log = TradeLog(db, clock = clock, io = Dispatchers.Unconfined)
    val lab = LabStore(db, clock = clock, io = Dispatchers.Unconfined)
    val reports = ReportLog(db, clock = clock, io = Dispatchers.Unconfined)
    val settings = SettingsStore(db, io = Dispatchers.Unconfined, clock = clock)
    val lists = WatchlistRepository(
        db, UniverseRepository(FakeMarket(EPOCH_START), CandleStore(context, name = null, io = Dispatchers.Unconfined)), clock = clock, io = Dispatchers.Unconfined,
    )
    val sync = RecordSync(db)

    /** What another device would read: this record, through text, as it travels. */
    fun record(): JSONObject = JSONObject(sync.export("test", now).toString())

    fun mergeFrom(other: Device): MergeResult = sync.merge(other.record())

    suspend fun newList(name: String, vararg coins: String): Long {
        val id = (lists.create(name) as WatchlistResult.Ok).value.id
        for ((i, c) in coins.withIndex()) db.writableDatabase.execSQL("INSERT INTO watchlist_coins VALUES (?,?,?)", arrayOf<Any?>(id, c, now + i))
        lists.load()
        return id
    }
}

private fun trade(variant: String = "donchian20_1d", symbol: String = "BTCUSDT", barTime: Long = 100_000, listId: Long = 0, family: String = "donchian") =
    NewTrade(variant, family, symbol, Timeframe.D1, listId, barTime, barTime + 10, 1, barTime + 20, 100.0, 104.0, 98.0, 7, barTime + 9999,
        cost = 0.0025, exitMode = "classic", atr = 2.0)

private val exit = TradeExit(500_000, 104.0, ExitReason.TARGET, 3, 0.04, 0.036, 0.001, 0.035, maxUp = 0.05, maxDown = -0.01, barsToPeak = 2)

@RunWith(RobolectricTestRunner::class)
class RecordSyncTest {

    @Test
    fun aTradeAndItsExitReachTheOtherDeviceWithEveryField() = runTest {
        val a = Device()
        val b = Device()
        val id = assertNotNull(a.log.open(trade()))
        a.log.close(id, exit)
        a.log.registerVariant(SignalKey("donchian20_1d", "donchian", mapOf("lookback" to 20.0)), Timeframe.D1)

        val r = b.mergeFrom(a)
        assertEquals(1, r.trades)
        assertEquals(1, r.exits)
        assertEquals(1, r.variants)
        val got = b.log.trades().single()
        val want = a.log.trades().single()
        assertEquals(want.trade.variant, got.trade.variant)
        assertEquals(want.trade.entryPrice, got.trade.entryPrice)
        assertEquals(want.trade.cost, got.trade.cost)
        assertEquals(want.trade.exitMode, got.trade.exitMode)
        assertEquals(want.trade.atr, got.trade.atr)
        assertEquals(want.openedAt, got.openedAt)
        val x = assertNotNull(got.exit)
        assertEquals(exit.net, x.net)
        assertEquals(exit.barsToPeak, x.barsToPeak)
        assertEquals(exit.maxDown, x.maxDown)
        assertEquals(1, b.log.variantCount())
    }

    @Test
    fun mergingTheSameRecordTwiceChangesNothing() = runTest {
        val a = Device()
        val b = Device()
        a.log.open(trade())
        a.newList("Majors", "BTCUSDT", "ETHUSDT")
        a.lab.start("Dip", null, "def", null)
        a.reports.add(null, "Title", "Body")
        a.settings.setDouble(SettingsStore.FEE_PER_SIDE, 0.00075)

        assertTrue(b.mergeFrom(a).any)
        val before = b.sync.fingerprint(b.record())
        assertFalse(b.mergeFrom(a).any)
        assertEquals(before, b.sync.fingerprint(b.record()))
    }

    @Test
    fun theSameSignalSeenOnBothDevicesIsOneTradeAndTheCopyHereStays() = runTest {
        val a = Device()
        val b = Device()
        a.now = 5_000
        a.log.open(trade())
        b.now = 9_000
        b.log.open(trade())
        assertEquals(0, b.mergeFrom(a).trades)
        val only = b.log.trades().single()
        assertEquals(9_000, only.openedAt)
    }

    @Test
    fun anExitFromTheOtherDeviceClosesTheTradeStillOpenHere() = runTest {
        val a = Device()
        val b = Device()
        val ida = assertNotNull(a.log.open(trade()))
        b.log.open(trade())
        a.log.close(ida, exit)
        val r = b.mergeFrom(a)
        assertEquals(0, r.trades)
        assertEquals(1, r.exits)
        assertNotNull(b.log.trades().single().exit)
    }

    @Test
    fun aTradeOnAListLandsOnThatListWhateverItsIdIsHere() = runTest {
        val a = Device()
        val b = Device()
        b.newList("Mine") // takes id 1 here
        val listA = a.newList("Alts", "SOLUSDT")
        a.log.open(trade(listId = listA, symbol = "SOLUSDT"))

        b.mergeFrom(a)
        b.lists.load()
        val alts = b.lists.lists.value.single { it.name == "Alts" }
        assertTrue(alts.id != listA, "the list got a new id here")
        assertEquals(listOf("SOLUSDT"), alts.symbols)
        assertEquals(alts.id, b.log.trades().single().trade.listId)

        // And back: the other device reads the trade as on its own list.
        assertEquals(0, a.mergeFrom(b).trades)
        assertEquals(listA, a.log.trades().single().trade.listId)
    }

    @Test
    fun aLabPatternGetsAnIdOfItsOwnHereAndItsTradesAndVariantFollow() = runTest {
        val a = Device()
        val b = Device()
        b.lab.start("Mine", null, "mine", null) // id 1 here
        val report = a.reports.add("card", "Idea", "Body")
        val pa = a.lab.start("Theirs", "because", "theirs", report)
        val variant = "lab${pa}_1h"
        a.log.registerVariant(SignalKey(variant, "lab", mapOf("lab_id" to pa.toDouble(), "hold_bars" to 5.0)), Timeframe.H1)
        a.log.open(trade(variant = variant, family = "lab"))
        a.lab.stop(pa)

        val r = b.mergeFrom(a)
        assertEquals(1, r.labPatterns)
        assertEquals(1, r.labStops)
        val theirs = b.lab.all().single { it.title == "Theirs" }
        assertTrue(theirs.id != pa)
        assertFalse(theirs.running)
        assertEquals("because", theirs.reason)
        val reportHere = b.reports.all().single()
        assertEquals(reportHere.id, theirs.reportId)
        assertEquals("lab${theirs.id}_1h", b.log.trades().single().trade.variant)
        val params = b.db.readableDatabase.rawQuery("SELECT params FROM variants WHERE variant=?", arrayOf("lab${theirs.id}_1h")).use { it.moveToFirst(); JSONObject(it.getString(0)) }
        assertEquals(theirs.id.toDouble(), params.getDouble("lab_id"))
        assertEquals(5.0, params.getDouble("hold_bars"))

        // The pattern that was already here is untouched, and the merge back finds nothing new.
        assertEquals("mine", b.lab.all().single { it.title == "Mine" }.definition)
        assertEquals(1, a.mergeFrom(b).labPatterns, "only the one made over there")
        assertFalse(a.mergeFrom(b).any)
    }

    @Test
    fun theNewerCopyOfAListWins() = runTest {
        val a = Device()
        val b = Device()
        val id = a.newList("Majors", "BTCUSDT")
        b.mergeFrom(a)
        val stale = Device()
        stale.mergeFrom(a)
        a.now = 2_000
        a.lists.rename(id, "Big ones")
        // The fake market offers no coins, so the coin goes in directly; the rename and switch-on stamp the list as changed.
        a.db.writableDatabase.execSQL("INSERT INTO watchlist_coins VALUES (?,?,?)", arrayOf<Any?>(id, "ETHUSDT", 2_000))
        a.lists.setActive(id, true)

        assertEquals(1, b.mergeFrom(a).listsChanged)
        b.lists.load()
        val l = b.lists.lists.value.single()
        assertEquals("Big ones", l.name)
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), l.symbols)
        assertTrue(l.active)

        // The copy from before the change does not undo it.
        assertEquals(0, b.mergeFrom(stale).listsChanged)
        b.lists.load()
        assertEquals("Big ones", b.lists.lists.value.single().name)
    }

    @Test
    fun aDeletedListGoesOnTheOtherDeviceUnlessItWasChangedThereSince() = runTest {
        val a = Device()
        val b = Device()
        val keep = a.newList("Keep")
        val gone = a.newList("Gone")
        b.mergeFrom(a)
        b.lists.load()

        a.now = 3_000
        a.lists.delete(gone)
        a.lists.delete(keep)
        b.now = 4_000
        b.lists.rename(b.lists.lists.value.single { it.name == "Keep" }.id, "Kept")

        b.mergeFrom(a)
        b.lists.load()
        assertEquals(listOf("Kept"), b.lists.lists.value.map { it.name })

        // And the edit made after the deletion brings the list back where it was deleted.
        a.mergeFrom(b)
        a.lists.load()
        assertEquals(listOf("Kept"), a.lists.lists.value.map { it.name })
        assertEquals(keep, a.lists.lists.value.single().id, "it comes back with the id its trades carry")
    }

    @Test
    fun tradesOfAListDeletedBeforeTheOtherDeviceSawItStillTravel() = runTest {
        val a = Device()
        val b = Device()
        val list = a.newList("Short lived")
        a.log.open(trade(listId = list))
        a.now = 2_000
        a.lists.delete(list)

        assertEquals(1, b.mergeFrom(a).trades)
        val here = b.log.trades().single().trade.listId
        assertTrue(here < 0, "a list never seen here is remembered under an id no list made here can take")
        assertTrue(b.lists.lists.value.isEmpty())
        assertFalse(a.mergeFrom(b).any)
    }

    @Test
    fun twoListsWithTheSameNameAreBothKept() = runTest {
        val a = Device()
        val b = Device()
        a.newList("Main", "BTCUSDT")
        b.newList("main", "ETHUSDT")
        b.mergeFrom(a)
        b.lists.load()
        assertEquals(setOf("main", "Main 2"), b.lists.lists.value.map { it.name }.toSet())
    }

    @Test
    fun onlySettingsThatTravelAreSentAndTheNewerOneWins() = runTest {
        val a = Device()
        val b = Device()
        b.settings.setDouble(SettingsStore.FEE_PER_SIDE, 0.001)
        a.now = 2_000
        a.settings.setDouble(SettingsStore.FEE_PER_SIDE, 0.00075)
        a.settings.setBoolean(SettingsStore.DIM_SCREEN, true)
        a.settings.set("scan_cursor_1h", "123")

        val keys = a.record().getJSONArray("settings").let { s -> (0 until s.length()).map { s.getJSONObject(it).getString("key") } }
        assertEquals(listOf(SettingsStore.FEE_PER_SIDE), keys)
        assertEquals(setOf(SettingsStore.FEE_PER_SIDE), b.mergeFrom(a).settingsChanged)
        assertEquals(0.00075, b.settings.getDouble(SettingsStore.FEE_PER_SIDE, 0.0))
        assertNull(b.settings.get(SettingsStore.DIM_SCREEN))

        b.now = 1_500 // an older change does not win
        b.settings.setDouble(SettingsStore.FEE_PER_SIDE, 0.002)
        a.mergeFrom(b)
        assertEquals(0.00075, a.settings.getDouble(SettingsStore.FEE_PER_SIDE, 0.0))
    }

    @Test
    fun aListSwitchedOnElsewhereArrivesOffWhenItWouldPassTheCapHere() = runTest {
        val a = Device()
        val b = Device()
        for (n in 0 until 5) {
            val full = b.newList("Full $n", *(1..30).map { "C${n}_${it}USDT" }.toTypedArray())
            b.db.writableDatabase.execSQL("UPDATE watchlists SET active=1 WHERE id=?", arrayOf<Any?>(full))
        }
        val other = a.newList("Other", "NEWUSDT")
        a.db.writableDatabase.execSQL("UPDATE watchlists SET active=1 WHERE id=?", arrayOf<Any?>(other))

        b.mergeFrom(a)
        b.lists.load()
        assertFalse(b.lists.lists.value.single { it.name == "Other" }.active)
    }

    @Test
    fun aRecordInAFormatThisVersionDoesNotKnowIsRefusedWhole() = runTest {
        val a = Device()
        a.log.open(trade())
        val newer = a.record().put("format", RecordSync.FORMAT + 1)
        val b = Device()
        assertFailsWith<IllegalArgumentException> { b.sync.merge(newer) }
        assertTrue(b.log.trades().isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
class SyncMigrationTest {
    @Test
    fun aVersionFiveDatabaseGetsSyncIdsAndATombstoneForEachListItsTradesStillNameAndKeepsItsTriggers() {
        val name = "v5-sync.db"
        context.deleteDatabase(name)
        val raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        RecordDatabase.MIGRATIONS.take(5).flatten().forEach(raw::execSQL)
        raw.version = 5
        raw.execSQL("INSERT INTO watchlists (id, name, name_key, active, created_at, position) VALUES (3, 'A', 'a', 1, 77, 1)")
        raw.execSQL("INSERT INTO lab_patterns (started_at, title, definition) VALUES (5, 'P', 'def')")
        raw.execSQL("INSERT INTO analyst_reports (received_at, title, body) VALUES (6, 'R', 'b')")
        raw.execSQL("INSERT INTO settings VALUES ('fee_per_side', '0.001')")
        for ((bar, list) in listOf(1L to 3L, 2L to 9L, 3L to 0L)) {
            raw.execSQL("INSERT INTO live_trades (variant, family, symbol, tf, list_id, bar_time, detected_at, regime, entry_time, entry_price, hold_bars, exit_due, opened_at) " +
                "VALUES ('v', 'f', 'BTCUSDT', '1d', $list, $bar, 1, 1, 1, 1.0, 1, 1, 1)")
        }
        raw.close()

        val d = RecordDatabase(context, name).writableDatabase
        assertEquals(RecordDatabase.SCHEMA_VERSION, d.version)
        d.rawQuery("SELECT sync_id, updated_at FROM watchlists", null).use { it.moveToFirst(); assertEquals(32, it.getString(0).length); assertEquals(77, it.getLong(1)) }
        d.rawQuery("SELECT sync_id FROM lab_patterns", null).use { it.moveToFirst(); assertEquals(32, it.getString(0).length) }
        d.rawQuery("SELECT sync_id FROM analyst_reports", null).use { it.moveToFirst(); assertEquals(32, it.getString(0).length) }
        d.rawQuery("SELECT local_id, deleted_at FROM watchlist_tombstones", null).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(9, c.getLong(0))
            assertEquals(0, c.getLong(1))
        }
        d.rawQuery("SELECT updated_at FROM settings", null).use { it.moveToFirst(); assertEquals(0, it.getLong(0)) }
        for (sql in listOf("UPDATE lab_patterns SET title='x'", "DELETE FROM lab_patterns")) {
            val e = assertFailsWith<SQLiteException>(sql) { d.execSQL(sql) }
            assertTrue("append-only" in (e.message ?: ""))
        }
        // Every trade, the one on the deleted list too, can be named to another device.
        assertEquals(3, RecordSync(RecordDatabase(context, name)).export("x", 1).getJSONArray("trades").length())
    }
}

/** A folder in memory; [version] stands in for Drive's modified time. */
private class MapFolder : RemoteFolder {
    val files = LinkedHashMap<String, Pair<String, ByteArray>>()
    val modified = HashMap<String, Int>()
    var downloads = 0
    var uploads = 0
    private var next = 0

    override suspend fun list() = files.map { (id, f) -> RemoteFile(id, f.first, modified.getValue(id).toString()) }
    override suspend fun download(id: String): ByteArray { downloads++; return files.getValue(id).second }
    override suspend fun create(name: String, bytes: ByteArray): String {
        uploads++
        val id = "f${next++}"
        files[id] = name to bytes
        modified[id] = 1
        return id
    }
    override suspend fun update(id: String, bytes: ByteArray) {
        uploads++
        files[id] = files.getValue(id).first to bytes
        modified[id] = modified.getValue(id) + 1
    }
    override suspend fun accountEmail() = "me@example.com"
}

private class MapMemory(private val id: String) : SyncMemory {
    val map = HashMap<String, String>()
    override suspend fun deviceId() = id
    override suspend fun ownFileId() = map["own"]
    override suspend fun saveOwnFileId(id: String) { map["own"] = id }
    override suspend fun lastSent() = map["sent"]
    override suspend fun saveLastSent(fingerprint: String) { map["sent"] = fingerprint }
    override suspend fun seen(fileId: String) = map["seen$fileId"]
    override suspend fun saveSeen(fileId: String, modified: String) { map["seen$fileId"] = modified }
}

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    @Test
    fun threeDevicesThatEachOnlyReadTheFolderEndUpWithEveryTradeAndQuietRoundsSendNothing() = runTest {
        val folder = MapFolder()
        val devices = List(3) { Device() }
        val engines = devices.mapIndexed { i, d -> SyncEngine(folder, d.sync, MapMemory("d$i"), d.clock, Dispatchers.Unconfined) }
        devices.forEachIndexed { i, d -> d.log.open(trade(symbol = "C${i}USDT")) }

        // Two rounds bring everything everywhere; in the third, devices read the records the second sent and find nothing new.
        repeat(3) { for (e in engines) e.round() }
        for (d in devices) assertEquals(setOf("C0USDT", "C1USDT", "C2USDT"), d.log.trades().map { it.trade.symbol }.toSet())
        assertEquals(3, folder.files.size)

        val uploads = folder.uploads
        val downloads = folder.downloads
        val quiet = engines.map { it.round() }
        assertEquals(uploads, folder.uploads, "nothing changed, so nothing is sent")
        assertEquals(downloads, folder.downloads, "and no unchanged file is read again")
        assertTrue(quiet.all { !it.sent && !it.merged.any && it.devices == 2 })
    }

    @Test
    fun aDamagedRecordIsCountedAndSkippedAndTheRestStillSyncs() = runTest {
        val folder = MapFolder()
        folder.create(SyncEngine.fileName("broken"), byteArrayOf(1, 2, 3))
        val a = Device()
        a.log.open(trade())
        val round = SyncEngine(folder, a.sync, MapMemory("a"), a.clock, Dispatchers.Unconfined).round()
        assertEquals(1, round.unreadable)
        assertTrue(round.sent)
        val b = Device()
        SyncEngine(folder, b.sync, MapMemory("b"), b.clock, Dispatchers.Unconfined).round()
        assertEquals(1, b.log.trades().size)
    }

    @Test
    fun recordsAreSmallerGzipped() {
        val text = "x".repeat(10_000)
        assertTrue(SyncEngine.gzip(text).size < 200)
        assertEquals(text, SyncEngine.gunzip(SyncEngine.gzip(text)))
    }
}

class DriveFolderTest {
    private fun withServer(block: suspend (MockWebServer, DriveFolder) -> Unit) = kotlinx.coroutines.runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            var tokens = 0
            val folder = DriveFolder(OkHttpClient(), token = { "t${tokens}" }, refreshToken = { tokens++; "t$tokens" }, api = server.url("/").toString().trimEnd('/'))
            block(server, folder)
        } finally {
            server.close()
        }
    }

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test
    fun listsEveryPageOfTheAppFolder() = withServer { server, folder ->
        server.enqueue(ok("""{"files":[{"id":"1","name":"device-a.json.gz","modifiedTime":"2026-10-09T10:00:00Z"}],"nextPageToken":"p2"}"""))
        server.enqueue(ok("""{"files":[{"id":"2","name":"device-b.json.gz","modifiedTime":"2026-10-09T11:00:00Z"}]}"""))
        val files = folder.list()
        assertEquals(listOf("1", "2"), files.map { it.id })
        val first = server.takeRequest()
        assertEquals("appDataFolder", first.url.queryParameter("spaces"))
        assertEquals("Bearer t0", first.headers["Authorization"])
        assertEquals("p2", server.takeRequest().url.queryParameter("pageToken"))
    }

    @Test
    fun createsInTheAppFolderAndUpdatesInPlace() = withServer { server, folder ->
        server.enqueue(ok("""{"id":"new"}"""))
        server.enqueue(ok("{}"))
        assertEquals("new", folder.create("device-a.json.gz", byteArrayOf(9)))
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("multipart", create.url.queryParameter("uploadType"))
        assertTrue("appDataFolder" in (create.body?.utf8() ?: ""))
        folder.update("new", byteArrayOf(7))
        val update = server.takeRequest()
        assertEquals("PATCH", update.method)
        assertEquals("/upload/drive/v3/files/new", update.url.encodedPath)
    }

    @Test
    fun aTurnedDownTokenIsRenewedOnceAndASecondRefusalNeedsTheUser() = withServer { server, folder ->
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        server.enqueue(ok("""{"user":{"emailAddress":"me@example.com"}}"""))
        assertEquals("me@example.com", folder.accountEmail())
        assertEquals("Bearer t0", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer t1", server.takeRequest().headers["Authorization"])

        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"Invalid Credentials"}}""").build())
        assertFailsWith<SyncAuthException> { folder.list() }
    }
}

private class FakeAuthorizer : Authorizer {
    var needsUser = true
    val intent: PendingIntent = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
    override suspend fun authorize(): AuthOutcome = if (needsUser) AuthOutcome.NeedsUser(intent) else AuthOutcome.Token("token")
    override fun tokenFrom(data: Intent?): String { needsUser = false; return "token" }
}

@RunWith(RobolectricTestRunner::class)
class DeviceSyncTest {
    private fun setup(): Triple<DeviceSync, FakeAuthorizer, MapFolder> {
        val d = Device()
        val auth = FakeAuthorizer()
        val folder = MapFolder()
        val sync = DeviceSync(d.settings, auth, { folder }, d.sync, MapMemory("me"), afterMerge = {}, clock = d.clock)
        return Triple(sync, auth, folder)
    }

    @Test
    fun signingInTheFirstTimeShowsGoogleThenTheFirstSyncSendsTheRecord() = runTest {
        val (sync, auth, folder) = setup()
        val shown = async(start = CoroutineStart.UNDISPATCHED) { sync.consent.first() }
        sync.signIn()
        assertEquals(auth.intent, shown.await())
        assertFalse(sync.status.value.enabled)

        sync.onConsent(ok = true, data = Intent())
        val s = sync.status.value
        assertTrue(s.enabled)
        assertEquals("me@example.com", s.account)
        assertNotNull(s.lastSyncedAt)
        assertNull(s.problem)
        assertEquals(1, folder.files.size)
    }

    @Test
    fun backingOutOfGoogleLeavesSyncOff() = runTest {
        val (sync, _, folder) = setup()
        sync.onConsent(ok = false, data = null)
        assertFalse(sync.status.value.enabled)
        assertEquals("Sign-in was cancelled.", sync.status.value.problem)
        sync.syncNow()
        assertTrue(folder.files.isEmpty())
    }

    @Test
    fun whenGoogleNeedsTheUserAgainSettingsSaysSoAndTurningOffKeepsEverything() = runTest {
        val (sync, auth, folder) = setup()
        auth.needsUser = false
        sync.signIn()
        assertTrue(sync.status.value.enabled)
        auth.needsUser = true
        sync.syncNow()
        assertTrue(sync.status.value.needsSignIn)
        sync.turnOff()
        assertFalse(sync.status.value.enabled)
        assertEquals(1, folder.files.size, "turning off deletes nothing in Drive")
    }
}
