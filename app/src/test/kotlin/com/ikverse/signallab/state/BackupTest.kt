package com.ikverse.signallab.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.NewTrade
import com.ikverse.signallab.data.RecordDatabase
import com.ikverse.signallab.data.TradeLog
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.sync.RecordSync
import com.ikverse.signallab.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** A backup is the record as sync sends it: another phone, or this one after a reset, can merge it. */
@RunWith(RobolectricTestRunner::class)
class BackupTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val graph by lazy { AppGraph(ApplicationProvider.getApplicationContext<Context>(), scope) }

    @After
    fun stop() = scope.cancel()

    private fun trade(bar: Long) = NewTrade("donchian20_1d", "donchian", "BTCUSDT", Timeframe.D1, 0, bar, bar + 10, 1, bar + 20, 100.0, 104.0, 98.0, 7, bar + 9999, 0.0025, "classic", 2.0)

    @Test
    fun `a backup holds the trades and a fresh record merges them once`() = runBlocking {
        assertNotNull(graph.tradeLog.open(trade(100_000)))
        assertNotNull(graph.tradeLog.open(trade(200_000)))
        val bytes = graph.backupBytes()
        val file = JSONObject(SyncEngine.gunzip(bytes))
        assertEquals(AppGraph.BACKUP_DEVICE, file.getString("device"))
        assertEquals(2, file.getJSONArray("trades").length())

        val fresh = RecordDatabase(ApplicationProvider.getApplicationContext<Context>(), null)
        val first = RecordSync(fresh).merge(file)
        assertEquals(2, first.trades)
        assertEquals(2, TradeLog(fresh, io = Dispatchers.Unconfined).trades().size)
        val again = RecordSync(fresh).merge(file)
        assertEquals(0, again.trades, "merging the same backup twice changes nothing")
    }

    @Test
    fun `restoring the phone's own backup brings in nothing new`() = runBlocking {
        graph.tradeLog.open(trade(300_000))
        val r = graph.restoreBackup(graph.backupBytes())
        assertEquals(0, r.trades + r.exits + r.listsChanged + r.labPatterns + r.reports)
    }

    @Test
    fun `a file that is not a backup is refused with a reason, not a crash`() = runBlocking {
        val exchange = FileExchange()
        var said: FileOutcome? = null
        exchange.requestOpen({ graph.restoreBackup(it) }) { said = it }
        exchange.completeOpen { "not gzip".toByteArray() }
        assertIs<FileOutcome.Failed>(said)
        Unit
    }
}
