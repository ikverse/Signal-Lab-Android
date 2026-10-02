package com.ikverse.signallab.scan

import com.ikverse.signallab.data.NewTrade
import com.ikverse.signallab.data.TradeStatus
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.ExitMode
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.PaperTrading
import com.ikverse.signallab.engine.Scorecard
import com.ikverse.signallab.engine.SignalKey
import com.ikverse.signallab.engine.Signals
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.Trade
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The test the live scanner stands or falls on. Real candles are fed to the real scanner one close at a time, as
 * the phone would have seen them, and every paper trade it opens and closes is compared with what the backtest says for
 * the same candles, under the same exit rules and costs the live scan uses: the same trades, entered at the same price, left
 * on the same candle, for the same result. A trade the live scan makes that the backtest does not, or one the backtest makes
 * that the live scan misses, fails it. It runs on 1-day, 1-hour, 15-minute and 1-minute charts, for every pattern on each:
 * trailing, learned, held and end-of-day exits alike.
 *
 * Live and backtest differ in two small ways, both checked for rather than hidden: the live scan reads a window of
 * recent candles, so its average true range rounds differently in the last digits, and a baseline can only use candles
 * that exist when the trade closes, so it is checked separately (see LiveScanTest).
 */
@RunWith(RobolectricTestRunner::class)
class LiveEqualsBacktestTest {
    private fun near(a: Double, b: Double) = abs(a - b) <= 1e-9 * maxOf(1.0, abs(a), abs(b))

    private class Expected(val key: SignalKey, val listId: Long, val signal: Int, val exitIdx: Int, val trade: Trade)

    private val cost = 0.002

    private fun replay(tf: Timeframe, coins: List<String>, steps: Int, tail: Int, minCompared: Int, minClosed: Int) = runTest {
        val series: Map<String, List<Kline>> = GoldenData.klines(tf).filterKeys { it in coins }
        val source = series.mapKeys { it.key to tf } + mapOf(("BTCUSDT" to Timeframe.D1) to GoldenData.klines(Timeframe.D1).getValue("BTCUSDT"))
        val panel: Map<String, Candles> = coins.associateWith { GoldenData.candles(it, tf) }
        val btc = GoldenData.candles("BTCUSDT", Timeframe.D1)

        // What the backtest says, with the live scan's exits and costs.
        val regimes = panel.mapValues { PaperTrading.regimeSeries(btc, it.value.closeTime) }
        val expected = ArrayList<Expected>()
        for ((key, bySymbol) in Signals.compute(panel)) {
            val run = Scorecard.runVariant(key, bySymbol, panel, tf, regimes, costFor = { cost }, perPatternExits = true)
            for (t in run.trades) {
                val c = panel.getValue(t.symbol)
                val s = c.t.indexOf(t.barTime)
                expected.add(Expected(key, if (key.family == "xs_momentum") 1L else 0L, s, s + t.barsHeld, t))
            }
        }

        // Replay the last [steps] closes, stopping [tail] candles before the data ends so every trade has its whole window.
        val reference = panel.getValue(coins.first())
        val last = reference.size - 1 - tail
        val first = last - steps + 1
        val firstOpen = reference.t[first]
        val lastOpen = reference.t[last]
        val env = ScanEnv(coins, tf, source)

        // The world as it stood just before the first scan: trades already open are open, because their signals were handled
        // by scans that came earlier than this test starts.
        val preOpened = HashSet<Triple<String, String, Long>>()
        for (x in expected) {
            val t = x.trade
            val c = panel.getValue(t.symbol)
            if (t.barTime < firstOpen && c.t[x.exitIdx] >= firstOpen) {
                env.at(firstOpen + 5_000)
                val atr = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)[x.signal]
                val limit = t.rule.limitAt(t.entryTime, tf)
                val spec = t.rule.spec(t.entryPrice, atr, limit)
                env.log.open(NewTrade(x.key.name, x.key.family, t.symbol, tf, x.listId, t.barTime, t.detectedAt, t.regime, t.entryTime,
                    t.entryPrice, spec.target, spec.stop, limit, t.entryTime + limit * tf.ms - 1, cost = cost, exitMode = t.rule.mode.label, atr = atr))
                preOpened.add(Triple(x.key.name, t.symbol, t.barTime))
            }
        }

        for (i in first..last) {
            env.at(reference.closeTime[i] + 1 + 5_000)
            env.scanner.scan(tf)
        }

        val live = env.log.trades(TradeStatus.ALL, limit = Int.MAX_VALUE)
            .filter { Triple(it.trade.variant, it.trade.symbol, it.trade.barTime) !in preOpened }
        val liveBy = live.associateBy { Triple(it.trade.variant, it.trade.symbol, it.trade.barTime) to it.trade.listId }
        assertEquals(live.size, liveBy.size, "a trade was opened twice")

        // Every trade the backtest makes on those candles, the live scan made too...
        var compared = 0
        var closed = 0
        var noBaseline = 0
        val modes = HashSet<ExitMode>()
        for (x in expected.filter { it.trade.barTime in firstOpen..lastOpen }) {
            val t = x.trade
            val c = panel.getValue(t.symbol)
            val what = "${tf.label} ${x.key.name} ${t.symbol} signal ${x.signal}"
            val lt = assertNotNull(liveBy[Triple(x.key.name, t.symbol, t.barTime) to x.listId], "$what: the backtest traded it, the live scan did not")
            assertEquals(t.entryTime, lt.trade.entryTime, "$what entry time")
            assertEquals(t.entryPrice, lt.trade.entryPrice, "$what entry price")
            assertEquals(t.regime, lt.trade.regime, "$what regime")
            assertEquals(t.rule.mode.label, lt.trade.exitMode, "$what exit rule")
            assertEquals(t.rule.limitAt(t.entryTime, tf), lt.trade.holdBars, "$what time limit")
            assertEquals(cost, lt.trade.cost, "$what cost")
            if (t.rule.mode == ExitMode.LEARNED && t.rule.targetPct != null) {
                assertTrue(near(t.entryPrice * (1 + t.rule.targetPct!!), lt.trade.target!!), "$what learned target")
            }
            modes.add(t.rule.mode)
            compared++
            if (c.t[x.exitIdx] <= lastOpen) {
                val exit = assertNotNull(lt.exit, "$what: the backtest closed it on candle ${x.exitIdx}, the live scan never did")
                assertEquals(t.exitTime, exit.exitTime, "$what exit time")
                assertEquals(t.exitReason.label, exit.reason.label, "$what exit reason")
                assertEquals(t.barsHeld, exit.barsHeld, "$what bars held")
                assertTrue(near(t.exitPrice, exit.exitPrice), "$what exit price ${t.exitPrice} vs ${exit.exitPrice}")
                assertTrue(near(t.gross, exit.gross), "$what gross")
                assertTrue(near(t.net, exit.net), "$what net")
                assertNotNull(exit.maxUp, "$what has no excursion")
                // A baseline needs matched random entries in the same BTC regime nearby, drawn only from candles that exist
                // when the trade closes; when a regime has just changed there can be none.
                if (exit.randomMean == null) {
                    assertEquals(null, exit.excess, "$what has an excess without a baseline")
                    noBaseline++
                } else {
                    assertTrue(near(exit.net - exit.randomMean!!, exit.excess!!), "$what excess")
                }
                closed++
            } else {
                assertEquals(null, lt.exit, "$what closed early")
            }
        }

        // ...and the live scan made no trade the backtest did not.
        val backtestKeys = expected.map { Triple(it.key.name, it.trade.symbol, it.trade.barTime) to it.listId }.toSet()
        for ((k, lt) in liveBy) assertTrue(k in backtestKeys, "live traded ${lt.trade.variant} ${lt.trade.symbol} at ${lt.trade.barTime}, the backtest did not")

        assertTrue(compared >= minCompared, "only $compared trades were compared")
        assertTrue(closed >= minClosed, "only $closed closed trades were compared")
        assertEquals(compared, live.size, "the live scan opened trades beyond those compared")
        assertTrue(noBaseline * 3 <= closed || closed < 30, "$noBaseline of $closed closed trades had no baseline")
        println("LIVE==BACKTEST ${tf.label}: $compared trades compared, $closed closed ($noBaseline without a baseline), exits $modes, ${preOpened.size} already open at the start")
    }

    @Test
    fun onDailyCandles() = replay(Timeframe.D1, GoldenData.klines(Timeframe.D1).keys.toList(), steps = 150, tail = 41, minCompared = 100, minClosed = 60)

    @Test
    fun onHourlyCandles() = replay(Timeframe.H1, GoldenData.klines(Timeframe.H1).keys.toList().let { all -> listOf("BTCUSDT") + all.filter { it != "BTCUSDT" }.take(4) }, steps = 240, tail = 80, minCompared = 40, minClosed = 20)

    @Test
    fun onFifteenMinuteCandles() = replay(Timeframe.M15, listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT"), steps = 300, tail = 100, minCompared = 40, minClosed = 20)

    @Test
    fun onOneMinuteCandles() = replay(Timeframe.M1, listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT"), steps = 150, tail = 130, minCompared = 40, minClosed = 20)
}
