package com.ikverse.signallab.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Golden
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.strategy.Breakout
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.ledger.CallRow
import com.ikverse.signallab.ledger.ExitRow
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.PreferenceStore
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.view.common.Words
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ModelsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val day = 86_400_000L
    private lateinit var ledger: LedgerDatabase

    @Before
    fun setUp() {
        ledger = LedgerDatabase.open(context, name = null)
    }

    @After
    fun tearDown() {
        ledger.close()
    }

    @Test
    fun theMarketLineReadsBitcoinsTrendAndGatesTheBullOnlyStrategies() {
        val btc = Golden.panel(Timeframe.D1).getValue("BTCUSDT")
        val m = MarketLine.of(btc, 0.0001, Builtins.all)
        assertTrue(m.vsAverage != null && m.swings != null)
        assertTrue(m.sentence.startsWith("Bitcoin is"))
        assertTrue(m.sentence.endsWith("futures traders are neutral."))
        assertEquals("All ${Builtins.all.size} strategies may fire.", m.allowedSentence)
        assertEquals("Bitcoin is below its 200-day average.", MarketLine(1.0, -0.1, null, null, 1, 2).sentence)
        assertEquals("1 of 2 strategies may fire; bull-market ones wait.", MarketLine(1.0, -0.1, null, null, 1, 2).allowedSentence)
    }

    @Test
    fun theFuturesLineSaysWhenShortsPayAndWhenLeverageIsOverheated() = runTest {
        val hated = FuturesContext(-0.021, -0.01, 0.054, 48.0, 3.0)
        assertTrue(hated.hated && !hated.hot)
        assertEquals("Over 30 days shorts have paid longs (−2.1% a year), a sign that has come before strong months; " +
            "the quarterly futures premium is 5.4% a year; implied volatility is 48, up 3 in a week.", hated.sentence)
        val hot = FuturesContext(0.18, 0.31, 0.17, 55.0, null)
        assertTrue(hot.hot && !hot.hated)
        assertTrue(hot.sentence!!.startsWith("Longs are paying a lot to stay long (18.0% a year over 30 days)"))
        assertTrue(hot.sentence!!.endsWith("Leverage looks overheated."))
        assertTrue(FuturesContext(0.05, 0.05, 0.05, 40.0, 16.0).hot, "a jump in implied volatility alone is overheated")
        assertEquals(null, FuturesContext(null, null, null, null, null).sentence)

        val day = 86_400_000L
        val now = 1_792_000_000_000L
        val store = com.ikverse.signallab.data.futures.DerivativesStore(context, name = null, io = Dispatchers.Unconfined)
        assertEquals(null, FuturesContext.read(store, now), "nothing stored, nothing said")
        // 30 days of settlements at -0.0001 per 8 hours: -0.0001 * 3 * 365 = -10.95% a year.
        store.insertFunding("BTCUSDT", List(90) { com.ikverse.signallab.data.futures.FundingPoint(now - 30 * day + 1 + it * 8 * 3_600_000L, -0.0001, Double.NaN) })
        store.insertDvol("BTC", listOf(com.ikverse.signallab.data.futures.DvolPoint(now - 8 * day, 1.0, 1.0, 1.0, 40.0),
            com.ikverse.signallab.data.futures.DvolPoint(now - day, 1.0, 1.0, 1.0, 46.0)))
        val read = FuturesContext.read(store, now)!!
        assertEquals(-0.1095, read.funding30d!!, 1e-9)
        assertEquals(46.0, read.impliedVol)
        assertEquals(6.0, read.impliedVolChange7d)
        assertTrue(read.hated)
    }

    @Test
    fun aBreakoutSaysWhatItBrokeAndWhatEndsIt() {
        val c = Golden.panel(Timeframe.D1).getValue("SOLUSDT")
        val s = Breakout()
        val i = s.entries(c).indexOfLast { it }
        val why = StrategyTexts.why(s, c, i)
        assertTrue(why.startsWith("Closed at ${Words.price(c.close[i])}, above its highest price of the prior 50 days"), why)
        val level = StrategyTexts.exitLevel(s, c)!!
        assertEquals((c.size - 20 until c.size).minOf { c.low[it] }, level)
        assertEquals("Hold. Exit if a daily close is below ${Words.price(level)}.", StrategyTexts.holdInstruction(s, c))
    }

    @Test
    fun aStrategyStandsAheadBehindOrTooEarly() {
        fun calls(n: Int, mean: Double) = List(n) { mean + if (it % 2 == 0) 0.04 else -0.04 }
        assertEquals("Too few calls to say anything yet.", LedgerModel.standing(calls(3, 0.02)))
        val ahead = LedgerModel.standing(calls(40, 0.01))
        assertTrue(ahead.startsWith("Ahead so far. About ") && ahead.endsWith(" more calls before a live verdict."), ahead)
        assertEquals("Ahead so far, but by too little to confirm soon.", LedgerModel.standing(calls(40, 0.0001)))
        assertEquals("Behind so far, over 40 calls.", LedgerModel.standing(calls(40, -0.01)))
        assertEquals("Behind random entries over 120 calls.", LedgerModel.standing(calls(120, -0.01)))
        assertEquals("Ahead of random entries over 400 calls.", LedgerModel.standing(calls(400, 0.03)))
    }

    @Test
    fun wordsForNumbers() {
        assertEquals("142.30", Words.price(142.3))
        assertEquals("67,840.00", Words.price(67_840.0))
        assertEquals("0.1712", Words.price(0.1712))
        assertEquals("−2.1%", Words.pct(-0.021))
        assertEquals("+$118", Words.usd(118.0, signed = true))
        assertEquals("−$300", Words.usd(-300.0))
    }

    private fun klines(c: Candles, n: Int) = List(n) { i -> Kline(c.t[i], c.open[i], c.high[i], c.low[i], c.close[i], c.volume[i], c.closeTime[i]) }

    @Test
    fun todaySortsCallsIntoNewOpenLeaveNowAndClosed() = runTest {
        val btc = Golden.panel(Timeframe.D1).getValue("BTCUSDT")
        val sol = Golden.panel(Timeframe.D1).getValue("SOLUSDT")
        val candles = CandleStore(context, name = null, io = Dispatchers.Unconfined)
        candles.insertKlines("BTCUSDT", Timeframe.D1, klines(btc, btc.size))
        candles.insertKlines("SOLUSDT", Timeframe.D1, klines(sol, sol.size))
        val last = sol.t.last()
        val now = last + day + 3_600_000L
        val dao = ledger.dao()
        fun row(signal: Long, opened: Long, strategy: String = "breakout50_1d") = CallRow(
            strategyId = strategy, symbol = "SOLUSDT", tf = if (strategy.endsWith("_1h")) "1h" else "1d", signalTime = signal, entryTime = signal + day, entryPrice = 100.0,
            atr = 5.0, stop = 85.0, target = null, maxBars = 60, regime = 1, sizeFraction = 0.1, cost = 0.004, source = "v2",
            archived = false, status = "live", openedAt = opened,
        )
        dao.insertCall(row(last, now - 60_000))                 // fired at the latest close: new
        val older = dao.insertCall(row(last - 10 * day, last - 9 * day)) // still open
        val left = dao.insertCall(row(last - 30 * day, last - 29 * day))  // left at this pass: leave now
        val gone = dao.insertCall(row(last - 40 * day, last - 39 * day))  // left three days ago: closed this week
        dao.insertExit(ExitRow(left, now - 3_600_000L, 110.0, "signal", 30, 0.1, 0.096, 0.01, 0.086, 0.2, -0.05, null, now - 60_000))
        dao.insertExit(ExitRow(gone, now - 3 * day, 95.0, "stop", 10, -0.05, -0.054, 0.0, -0.054, 0.02, -0.06, null, now - 3 * day))
        // Hourly calls say "leave now" only for the hour after they left; one that left two hours ago is closed.
        val hourlyGone = dao.insertCall(row(last - 2 * day, last - 2 * day, "hourlytrend_1h"))
        dao.insertExit(ExitRow(hourlyGone, now - 2 * 3_600_000L, 104.0, "signal", 5, 0.04, 0.036, 0.0, 0.036, 0.0, 0.0, null, now - 2 * 3_600_000L))
        val prefs = PreferenceStore(dao)
        prefs.switch("hourlytrend_1h", true)
        val model = TodayModel(dao, prefs.preferences, candles, null, { Builtins.all }, backgroundScope, now = { now })
        val state = model.state.first { it.loaded }
        assertEquals(1, state.newCalls.size)
        assertEquals(listOf(true, false), state.open.map { it.leaveNow })
        assertEquals(older, state.open.last().call.id)
        assertTrue(state.open.first().instruction.startsWith("Leave now: exit signal."))
        assertEquals(listOf(hourlyGone, gone), state.closedThisWeek.map { it.call.id })
        assertEquals(listOf("hourlytrend_1h", "breakout50_1d"), state.closedByStrategy.map { it.strategyId })
        assertEquals(1, state.closedByStrategy.first().won)
        assertEquals(1_000.0, state.newCalls.single().sizeUsd, 1e-9)
    }

    @Test
    fun switchingAStrategyOffHidesItFromToday() = runTest {
        val prefs = PreferenceStore(ledger.dao())
        assertTrue(Preferences().isOn(Breakout()))
        prefs.switch("breakout50_1d", false)
        val p = prefs.preferences.first()
        assertTrue(!p.isOn(Breakout()))
        assertEquals(0.0025, p.costFor("BTCUSDT"), 1e-12)
        assertEquals(0.004, p.costFor("SOLUSDT"), 1e-12)
    }
}
