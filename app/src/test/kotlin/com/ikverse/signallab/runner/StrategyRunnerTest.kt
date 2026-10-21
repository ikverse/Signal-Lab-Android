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
import com.ikverse.signallab.engine.strategy.dsl.CustomStrategy
import com.ikverse.signallab.engine.strategy.AnalystCall
import com.ikverse.signallab.engine.eval.Fills
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.engine.strategy.Tier
import com.ikverse.signallab.ledger.CallRow
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.Preferences
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

    /** The strategies a daily replay on three coins can compare with their backtest: daily, one coin at a time, ranks 1 to 3 allowed. */
    private val replayed = Builtins.all.filter { s -> s.timeframe == Timeframe.D1 && !s.crossSectional && s.ranks.let { it == null || 1 in it } }

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
        val spans = replayed.flatMap { s -> panel.flatMap { (sym, c) -> Backtest.run(s, mapOf(sym to c), btc) } }.map { it.entryTime..it.exitTime }
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
        for (s in replayed) {
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
        // The rest made no calls here: a ranking needs 8 coins, Quiet dip trades ranks 10 to 80, and the hourly pass has no
        // hourly candles in this test.
        val skipped = Builtins.all.map { it.id } - replayed.map { it.id }.toSet()
        assertEquals(emptyList(), calls.filter { it.strategyId in skipped }.map { "${it.strategyId} ${it.symbol}" })
        // Only strategies whose tier makes calls notify, and nothing is notified twice.
        assertTrue(alerts.posted.size <= calls.size + exits.size, "${alerts.posted.size} alerts for ${calls.size} calls and ${exits.size} exits")
    }

    @Test
    fun aRunningCustomStrategyMakesItsBacktestsTrades() = runTest {
        val custom = CustomStrategy.of("custom_test", "Mine",
            "(strategy (entry (> close (highest high 20))) (exit (< close (sma close 10))) (max_bars 30) (stop_atr 3))")
        val market = setUpMarket()
        val store = CandleStore(context, name = null, io = Dispatchers.Unconfined)
        val r = StrategyRunner(market, UniverseRepository(market, store, clock = { market.now }), store, DailyHistory(CandleSync(market, store)),
            ledger, Collected(), strategies = emptyList(), customs = { listOf(custom) })
        val btc = panel.getValue("BTCUSDT")
        val spans = panel.flatMap { (sym, c) -> Backtest.run(custom, mapOf(sym to c), btc) }.map { it.entryTime..it.exitTime }
        val from = (btc.size - 400 until btc.size - 60).first { d -> spans.none { btc.t[d] in it } }
        val until = btc.size - 2
        for (d in from..until) {
            marketAt(market, d)
            r.run()
        }
        val calls = ledger.dao().allCalls().filter { it.strategyId == custom.id }
        val exits = ledger.dao().allExits().associateBy { it.callId }
        var matched = 0
        for ((symbol, c) in panel) {
            val bt = Backtest.run(custom, mapOf(symbol to c), btc).filter { it.signalTime in btc.t[from]..btc.t[until] }
            val live = calls.filter { it.symbol == symbol }.associateBy { it.signalTime }
            // The backtest counts only trades whose whole window lies in the data; the runner also opens the last ones.
            for (extra in live.keys - bt.map { it.signalTime }.toSet()) {
                assertTrue(c.t.indexOfFirst { it == extra } + custom.exit.maxBars >= c.size, "$symbol: live call at $extra is not a backtest trade")
            }
            for (t in bt) {
                val call = live[t.signalTime] ?: error("$symbol: backtest trade at ${t.signalTime} was not made live")
                assertEquals(t.entryPrice, call.entryPrice)
                val exit = exits[call.id] ?: continue
                assertEquals(t.exitPrice, exit.exitPrice, "$symbol exit price")
                assertEquals(t.reason.label, exit.reason)
                matched++
            }
        }
        assertTrue(matched > 3, "only $matched trades matched")
        assertEquals("custom", ledger.dao().strategy(custom.id)?.source, "it is registered as tried")
    }

    private class Waiting(var calls: MutableList<PendingCall>) : PendingCalls {
        val opened = HashMap<String, Long>()
        val expired = HashMap<String, String>()
        override suspend fun accepted() = calls.toList()
        override suspend fun opened(id: String, callId: Long, now: Long) {
            opened[id] = callId
            calls.removeAll { it.id == id }
        }
        override suspend fun expired(id: String, why: String, now: Long) {
            expired[id] = why
            calls.removeAll { it.id == id }
        }
    }

    @Test
    fun aRecordedAnalystCallOpensAtTheNextOpenAndEndsByItsOwnPlan() = runTest {
        val market = setUpMarket()
        val store = CandleStore(context, name = null, io = Dispatchers.Unconfined)
        val btc = panel.getValue("BTCUSDT")
        val eth = panel.getValue("ETHUSDT")
        val d = btc.size - 120
        val formingOpen = btc.t[d + 1]
        val pending = Waiting(mutableListOf(
            PendingCall("early", "ETHUSDT", 0.05, 0.08, 10, acceptedAt = formingOpen - 3_600_000L),
            // Recorded after the candle it would enter at had opened: it waits for the next open.
            PendingCall("late", "SOLUSDT", 0.05, null, 10, acceptedAt = formingOpen + 60_000L),
        ))
        val alerts = Collected()
        val r = StrategyRunner(market, UniverseRepository(market, store, clock = { market.now }), store, DailyHistory(CandleSync(market, store)),
            ledger, alerts, strategies = emptyList(), pending = pending, preferences = { Preferences(switches = mapOf(AnalystCall.id to true)) })
        marketAt(market, d)
        r.run()
        val id = assertNotNull(pending.opened["early"])
        assertTrue("late" !in pending.opened, "a call never enters at an open that came before it was recorded")
        val call = ledger.dao().allCalls().first { it.id == id }
        assertEquals(AnalystCall.id, call.strategyId)
        assertEquals(eth.t[d + 1], call.entryTime)
        assertEquals(eth.open[d + 1], call.entryPrice)
        assertEquals(eth.open[d + 1] * 0.95, call.stop!!, 1e-9)
        assertEquals(eth.open[d + 1] * 1.08, call.target!!, 1e-9)
        assertEquals(10, call.maxBars)
        assertEquals(1, alerts.posted.size, "the owner's own call is notified")

        for (k in d + 1 until btc.size - 2) {
            marketAt(market, k)
            r.run()
        }
        assertNotNull(pending.opened["late"], "the late one opened at the next open")
        val exit = assertNotNull(ledger.dao().exitOf(id), "a 10-day call has ended")
        val atr = assertNotNull(call.atr)
        val plan = AnalystCall.forCall(call.entryPrice, atr, call.stop, call.target, call.maxBars).exit
        val fill = assertNotNull(Fills.resolve(eth, d + 1, plan, atr, null))
        assertEquals(fill.exitPrice, exit.exitPrice)
        assertEquals(fill.reason.label, exit.reason)
        assertNotNull(exit.excess, "it was compared with random entries")
    }

    @Test
    fun callsTrackedWithNoSizeLeaveTheAccountRoom() = runTest {
        val market = setUpMarket()
        val store = CandleStore(context, name = null, io = Dispatchers.Unconfined)
        val btc = panel.getValue("BTCUSDT")
        // More unsized open calls than the account has room for, from a strategy that no longer runs.
        repeat(StrategyRunner.MAX_OPEN_CALLS + 2) { k ->
            ledger.dao().insertCall(CallRow(
                strategyId = "parked", symbol = "BTCUSDT", tf = "1d", signalTime = k.toLong(), entryTime = k.toLong(), entryPrice = 1.0,
                atr = null, stop = null, target = null, maxBars = 1, regime = -1, sizeFraction = 0.0, cost = 0.0, source = "v2",
                archived = false, status = "live", openedAt = 0,
            ))
        }
        val r = runner(market, Collected(), store)
        for (d in btc.size - 300 until btc.size - 2) marketAt(market, d).also { r.run() }
        val made = ledger.dao().allCalls().filter { it.strategyId != "parked" }
        assertTrue(made.isNotEmpty(), "no calls in the replay")
        assertTrue(made.any { (it.sizeFraction ?: 0.0) > 0 }, "unsized calls filled the account: every call got no size")
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
        // The daily pass judged every daily strategy from its evidence and said why; the hourly one waits for hourly candles.
        for (s in Builtins.all.filter { it.timeframe == Timeframe.D1 }) {
            val tier = ledger.dao().setting(Preferences.TIER_PREFIX + s.id)
            assertTrue(Tier.entries.any { it.name == tier }, "${s.id} has no tier: $tier")
            assertTrue(!ledger.dao().setting(Preferences.TIER_REASON_PREFIX + s.id).isNullOrBlank(), "${s.id} has no reason")
        }
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
    fun theRunnerWakesJustAfterEachHourlyClose() {
        val midnight = 1_760_054_400_000L // 10 Oct 2025 00:00 UTC
        val hour = 3_600_000L
        assertEquals(midnight + DailyCloseScheduler.SETTLE_MS, DailyCloseScheduler.nextRunAt(midnight - 1))
        assertEquals(midnight + hour + DailyCloseScheduler.SETTLE_MS, DailyCloseScheduler.nextRunAt(midnight))
        assertEquals(midnight + 6 * hour + DailyCloseScheduler.SETTLE_MS, DailyCloseScheduler.nextRunAt(midnight + 5 * hour + 600_000L))
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
