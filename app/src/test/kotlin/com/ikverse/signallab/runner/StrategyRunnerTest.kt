package com.ikverse.signallab.runner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.CandleSync
import com.ikverse.signallab.data.FakeMarket
import com.ikverse.signallab.data.UniverseRepository
import com.ikverse.signallab.data.binance.BinanceException
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.data.binance.SpotSymbol
import com.ikverse.signallab.data.binance.Ticker24h
import com.ikverse.signallab.data.history.DailyHistory
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Golden
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.eval.Backtest
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.ledger.LedgerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The runner on real candles, one daily close at a time, as the phone will run it: the calls it makes and closes must be the
 * backtest's trades, it must not repeat itself, and every trusted call is notified.
 */
@RunWith(RobolectricTestRunner::class)
class StrategyRunnerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val day = Timeframe.D1.ms
    private val coins = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT")
    private val panel: Map<String, Candles> = Golden.panel(Timeframe.D1).filterKeys { it in coins }
    private lateinit var ledger: LedgerDatabase

    private class Collected : CallAlerts {
        val posted = ArrayList<CallAlert>()
        override fun post(alert: CallAlert) {
            posted.add(alert)
        }
    }

    @Before
    fun setUp() {
        ledger = LedgerDatabase.open(context, name = null)
    }

    @After
    fun tearDown() {
        ledger.close()
    }

    private fun klines(c: Candles, through: Int) = List(through + 1) { i -> Kline(c.t[i], c.open[i], c.high[i], c.low[i], c.close[i], c.volume[i], c.closeTime[i]) }

    /** Binance as it looked an hour into day [d + 1] of BTC's history: closed candles up to day [d], and day d+1 forming. */
    private fun marketAt(market: FakeMarket, d: Int) {
        val btc = panel.getValue("BTCUSDT")
        val formingOpen = btc.t[d + 1]
        market.now = formingOpen + 3_600_000L
        for ((symbol, c) in panel) {
            val last = c.t.indexOfLast { it <= formingOpen }
            if (last >= 0) market.series[symbol to Timeframe.D1] = klines(c, last)
        }
    }

    private fun setUpMarket(): FakeMarket {
        val market = FakeMarket(0)
        market.symbols = coins.map { SpotSymbol(it, it.removeSuffix("USDT"), "USDT", "TRADING") }
        market.tickers = coins.map { Ticker24h(it, 100.0, 120.0, 80.0, 1e9, open = 100.0) }
        return market
    }

    private fun runner(market: FakeMarket, alerts: CallAlerts, store: CandleStore) = StrategyRunner(
        market, UniverseRepository(market, store, clock = { market.now }), store, DailyHistory(CandleSync(market, store)), ledger, alerts,
    )

    @Test
    fun dayByDayTheRunnerMakesTheBacktestsTrades() = runTest {
        val market = setUpMarket()
        val store = CandleStore(context, name = null, io = Dispatchers.Unconfined)
        val alerts = Collected()
        val r = runner(market, alerts, store)
        val btc = panel.getValue("BTCUSDT")
        // Start on a day no backtest trade is in the middle of: the runner starts with nothing open, as the backtest then has.
        val spans = Builtins.all.flatMap { s -> panel.flatMap { (sym, c) -> Backtest.run(s, mapOf(sym to c), btc) } }.map { it.entryTime..it.exitTime }
        val from = (btc.size - 600 until btc.size - 60).first { d -> spans.none { btc.t[d] in it } }
        val until = btc.size - 2
        for (d in from..until) {
            marketAt(market, d)
            assertNotNull(r.run().universe)
        }
        val dao = ledger.dao()
        val calls = dao.allCalls().filter { it.source == "v2" }
        assertTrue(calls.isNotEmpty(), "no calls in the replay")
        val exits = dao.allExits().associateBy { it.callId }

        // Every closed call is a backtest trade with the same entry and exit; every backtest trade the replay covered was made.
        val windowStart = btc.t[from]
        val windowEnd = btc.t[until]
        var matched = 0
        for (s in Builtins.all) {
            for ((symbol, c) in panel) {
                val bt = Backtest.run(s, mapOf(symbol to c), btc).filter { it.signalTime in windowStart..windowEnd }
                val live = calls.filter { it.strategyId == s.id && it.symbol == symbol }.associateBy { it.signalTime }
                for (t in bt) {
                    val call = live[t.signalTime] ?: error("${s.id} $symbol: backtest trade at ${t.signalTime} was not made live")
                    assertEquals(t.entryPrice, call.entryPrice, "${s.id} $symbol entry")
                    val exit = exits[call.id] ?: continue // still open when the replay ended
                    assertEquals(t.exitPrice, exit.exitPrice, "${s.id} $symbol exit price")
                    assertEquals(t.exitTime, exit.exitTime, "${s.id} $symbol exit time")
                    assertEquals(t.reason.label, exit.reason, "${s.id} $symbol reason")
                    matched++
                }
            }
        }
        assertTrue(matched > 3, "only $matched trades matched")
        // Both built-ins are trusted enough to make calls, so each call and each exit was notified once.
        assertEquals(calls.size + exits.size, alerts.posted.size)
    }

    @Test
    fun runningTheSameDayTwiceChangesNothing() = runTest {
        val market = setUpMarket()
        val store = CandleStore(context, name = null, io = Dispatchers.Unconfined)
        val alerts = Collected()
        val r = runner(market, alerts, store)
        val btc = panel.getValue("BTCUSDT")
        for (d in btc.size - 120 until btc.size - 2) marketAt(market, d).also { r.run() }
        val calls = ledger.dao().allCalls().size
        val exits = ledger.dao().allExits().size
        val posted = alerts.posted.size
        val again = r.run()
        assertEquals(0, again.newCalls)
        assertEquals(0, again.exits)
        assertEquals(calls, ledger.dao().allCalls().size)
        assertEquals(exits, ledger.dao().allExits().size)
        assertEquals(posted, alerts.posted.size)
        assertEquals(Builtins.all.size, ledger.dao().strategiesTried(), "each strategy is registered once")
    }

    @Test
    fun aBlockedNetworkIsReportedNotThrown() = runTest {
        val market = setUpMarket()
        val store = CandleStore(context, name = null, io = Dispatchers.Unconfined)
        marketAt(market, panel.getValue("BTCUSDT").size - 10)
        market.failKlinesFrom = 0
        market.failWith = BinanceException.Blocked()
        val report = runner(market, Collected(), store).run()
        assertEquals("Binance refuses this network.", report.problem)
    }

    @Test
    fun theDailyPassRunsJustAfterEachUtcMidnight() {
        val midnight = 1_760_054_400_000L // 10 Oct 2025 00:00 UTC
        assertEquals(midnight + DailyCloseScheduler.SETTLE_MS, DailyCloseScheduler.nextRunAt(midnight - 1))
        assertEquals(midnight + day + DailyCloseScheduler.SETTLE_MS, DailyCloseScheduler.nextRunAt(midnight))
        assertEquals(midnight + day + DailyCloseScheduler.SETTLE_MS, DailyCloseScheduler.nextRunAt(midnight + 5 * 3_600_000L))
    }

    @Test
    fun theWorkerRetriesAFailedPassAndSucceedsOnAGoodOne() = runTest {
        val saved = ScanWorker.run
        try {
            ScanWorker.run = { RunReport(emptyList(), 0, 0, emptyMap()) }
            assertIs<ListenableWorker.Result.Success>(TestListenableWorkerBuilder<ScanWorker>(context).build().doWork())
            ScanWorker.run = { throw BinanceException.Network(null) }
            assertIs<ListenableWorker.Result.Retry>(TestListenableWorkerBuilder<ScanWorker>(context).build().doWork())
            ScanWorker.run = { RunReport(emptyList(), 0, 0, emptyMap(), problem = "No Bitcoin daily history yet.") }
            assertIs<ListenableWorker.Result.Retry>(TestListenableWorkerBuilder<ScanWorker>(context).build().doWork())
            assertIs<ListenableWorker.Result.Success>(TestListenableWorkerBuilder<ScanWorker>(context).setRunAttemptCount(ScanWorker.MAX_ATTEMPTS).build().doWork())
        } finally {
            ScanWorker.run = saved
        }
    }
}
