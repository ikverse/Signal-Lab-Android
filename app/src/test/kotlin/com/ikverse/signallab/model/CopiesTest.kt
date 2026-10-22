package com.ikverse.signallab.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.ledger.CallRow
import com.ikverse.signallab.ledger.ExitRow
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.runner.CallWords
import com.ikverse.signallab.sync.LedgerSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CopiesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var ledger: LedgerDatabase

    @Before
    fun setUp() {
        ledger = LedgerDatabase.open(context, name = null)
    }

    @After
    fun tearDown() {
        ledger.close()
    }

    private suspend fun call(db: LedgerDatabase = ledger, symbol: String = "SOLUSDT", entry: Double = 100.0): Long = db.dao().insertCall(CallRow(
        strategyId = "breakout50_1d", symbol = symbol, tf = "1d", signalTime = 0, entryTime = 86_400_000L, entryPrice = entry, atr = 5.0,
        stop = 85.0, target = null, maxBars = 60, regime = 1, sizeFraction = 0.1, cost = 0.004, source = "v2", archived = false,
        status = "live", openedAt = 0,
    ))

    @Test
    fun theOwnersCopyIsRecordedSoldAndComparedWithPaper() = runTest {
        val id = call()
        val model = CallModel(ledger.dao(), CandleStore(context, name = null, io = Dispatchers.Unconfined), { Builtins.all })
        assertNull(model.page(id, 10_000.0)!!.copy)
        model.sold(id, 120.0, now = 5)
        assertNull(ledger.dao().copyOf(id), "nothing is sold before it was bought")
        model.took(id, 101.0, now = 10)
        val bought = assertNotNull(model.page(id, 10_000.0)!!.copy)
        assertEquals("Bought at 101.00, 1.0% above the paper entry.", bought.boughtLine)
        assertNull(bought.soldLine)
        ledger.dao().insertExit(ExitRow(callId = id, exitTime = 2, exitPrice = 110.0, reason = "signal", barsHeld = 3, gross = 0.1, net = 0.096,
            randomMean = 0.0, excess = 0.096, maxUp = 0.1, maxDown = 0.0, barsToPeak = null, closedAt = 2))
        model.sold(id, 109.08, now = 20)
        val sold = assertNotNull(model.page(id, 10_000.0)!!.copy)
        assertEquals(109.08 / 101.0 - 1 - 0.004, sold.net!!, 1e-12)
        assertEquals("Sold at 109.08: +7.6% after fees, paper +9.6%.", sold.soldLine)
        assertEquals(10L, ledger.dao().copyOf(id)!!.boughtAt, "a correction keeps when it was bought")
        model.forget(id)
        assertNull(ledger.dao().copyOf(id))
    }

    @Test
    fun theLedgerShowsMyCopiesBesideThePaper() = runTest {
        val a = call(symbol = "SOLUSDT")
        val b = call(symbol = "ETHUSDT")
        val model = CallModel(ledger.dao(), CandleStore(context, name = null, io = Dispatchers.Unconfined), { Builtins.all })
        model.took(a, 102.0, now = 1)
        model.sold(a, 112.2, now = 2)
        model.took(b, 99.0, now = 3)
        ledger.dao().insertExit(ExitRow(callId = a, exitTime = 2, exitPrice = 111.0, reason = "signal", barsHeld = 3, gross = 0.11, net = 0.106,
            randomMean = 0.0, excess = 0.106, maxUp = 0.1, maxDown = 0.0, barsToPeak = null, closedAt = 2))
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        val ledgerModel = LedgerModel(ledger.dao(), MutableStateFlow(Preferences()), { Builtins.all }, scope)
        val ui = ledgerModel.state.first { it.copies.size == 2 }
        assertEquals(listOf("ETH", "SOL"), ui.copies.map { it.base }, "newest first")
        assertEquals(112.2 / 102.0 - 1 - 0.004, ui.copiesNet!!, 1e-12)
        assertEquals(0.106, ui.copiesPaperNet!!, 1e-12)
        assertEquals((0.02 - 0.01) / 2, ui.copiesEntryGap!!, 1e-12)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun copiesTravelToTheOwnersOtherDevice() = runTest {
        val other = LedgerDatabase.open(context, name = null)
        val here = call()
        val there = call(db = other)
        val model = CallModel(ledger.dao(), CandleStore(context, name = null, io = Dispatchers.Unconfined), { Builtins.all })
        model.took(here, 101.0, now = 100)
        val r = LedgerSync(other).merge(JSONObject(LedgerSync(ledger).export("a", 1).toString()))
        assertEquals(1, r.copies)
        assertEquals(101.0, other.dao().copyOf(there)!!.boughtPrice)
        other.dao().putCopy(other.dao().copyOf(there)!!.copy(soldPrice = 120.0, updatedAt = 200))
        LedgerSync(ledger).merge(JSONObject(LedgerSync(other).export("b", 2).toString()))
        assertEquals(120.0, ledger.dao().copyOf(here)!!.soldPrice, "the newer correction wins")
        assertEquals(0, LedgerSync(ledger).merge(JSONObject(LedgerSync(other).export("b", 3).toString())).copies, "an older copy changes nothing")
        other.close()
    }

    @Test
    fun notificationsSayHowMuchToBuyAndWhenToSell() {
        val buy = CallWords.newCall(1, "Breakout", "SOLUSDT", 142.3, 127.3, 0.12, tradeMoney = 3_000.0)
        assertTrue(buy.text.endsWith("Buy 360 USDT (12% of your money)."), buy.text)
        val paper = CallWords.newCall(1, "Breakout", "SOLUSDT", 142.3, 127.3, 0.12)
        assertTrue(paper.text.endsWith("Size 12% of the paper account."), paper.text)
        val exit = CallWords.exit(1, "Breakout", "SOLUSDT", "notice", 120.0, -0.05)
        assertEquals("Sell now: SOL · Breakout", exit.title)
        assertTrue("the exchange will delist the coin" in exit.text)
    }
}
