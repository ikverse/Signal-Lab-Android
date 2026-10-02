package com.ikverse.signallab.scan

import com.ikverse.signallab.data.NewTrade
import com.ikverse.signallab.data.TradeStatus
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.PaperTrading
import com.ikverse.signallab.engine.Scorecard
import com.ikverse.signallab.engine.Signals
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The test the live scanner stands or falls on. Real daily candles are fed to the real scanner one
 * close at a time, as the phone would have seen them, and every paper trade it opens and closes is
 * compared with what the backtest says for the same candles: the same trades, entered at the same
 * price, left on the same candle, for the same result. A trade the live scan makes that the backtest
 * does not, or one the backtest makes that the live scan misses, fails it.
 *
 * Live and backtest differ in two small ways, both checked for rather than hidden: the live scan reads
 * a 420-day window, so its average true range rounds differently in the last digits, and a baseline
 * can only use candles that exist when the trade closes, so it is checked separately (see LiveScanTest).
 */
@RunWith(RobolectricTestRunner::class)
class LiveEqualsBacktestTest {
    private val tf = Timeframe.D1
    private val source = GoldenData.klines(tf).mapKeys { it.key to tf }
    private val panel = GoldenData.panel(tf)
    private val btc = panel.getValue("BTCUSDT")

    private fun near(a: Double, b: Double) = abs(a - b) <= 1e-9 * maxOf(1.0, abs(a), abs(b))

    private class Expected(
        val variant: String, val family: String, val symbol: String, val listId: Long, val signal: Int, val entryIdx: Int,
        val exitIdx: Int, val entryPrice: Double, val exitPrice: Double, val reason: String, val gross: Double, val net: Double,
        val regime: Int, val target: Double?, val stop: Double?, val limit: Int,
    )

    @Test
    fun everyLiveTradeIsTheBacktestsTradeOnTheSameCandles() = runTest {
        val regimes = panel.mapValues { PaperTrading.regimeSeries(btc, it.value.closeTime) }
        val flags = Signals.compute(panel)
        val expected = ArrayList<Expected>()
        for ((key, bySymbol) in flags) {
            val hold = key.holdBars
            val limit = hold ?: EngineConfig.timeLimitBars(tf)
            val run = Scorecard.runVariant(key, bySymbol, panel, tf, regimes)
            for (t in run.trades) {
                val c = panel.getValue(t.symbol)
                val s = c.t.indexOf(t.barTime)
                val atr = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)[s]
                expected.add(Expected(
                    key.name, key.family, t.symbol, if (key.family == "xs_momentum") 1L else 0L, s, s + 1, s + t.barsHeld,
                    t.entryPrice, t.exitPrice, t.exitReason.label, t.gross, t.net, t.regime,
                    if (hold == null) PaperTrading.targetPrice(t.entryPrice, atr) else null,
                    if (hold == null) PaperTrading.stopPrice(t.entryPrice, atr) else null, limit,
                ))
            }
        }

        // The last 190 days but 40: far enough from the end of the data that every trade has its whole window.
        val last = btc.size - 41
        val first = last - 150
        // Coins list on different days and so have different numbers of candles: positions are per coin, so every comparison is by time.
        val firstOpen = btc.t[first]
        val lastOpen = btc.t[last]
        val env = ScanEnv(panel.keys.toList(), tf, source)

        // The world as it stood just before the first scan: trades already open are open, because their
        // signals were handled by scans that came earlier than this test starts.
        val preOpened = HashSet<Triple<String, String, Long>>()
        for (x in expected) {
            val c = panel.getValue(x.symbol)
            if (c.t[x.signal] < firstOpen && c.t[x.exitIdx] >= firstOpen) {
                env.at(firstOpen + 5_000)
                env.log.open(NewTrade(x.variant, x.family, x.symbol, tf, x.listId, c.t[x.signal], c.closeTime[x.signal], x.regime,
                    c.t[x.entryIdx], x.entryPrice, x.target, x.stop, x.limit, c.t[x.entryIdx] + x.limit * tf.ms - 1))
                preOpened.add(Triple(x.variant, x.symbol, c.t[x.signal]))
            }
        }
        assertTrue(preOpened.isNotEmpty(), "the replay should start with trades already open")

        for (i in first..last) {
            env.at(btc.closeTime[i] + 1 + 5_000)
            env.scanner.scan(tf)
        }

        val live = env.log.trades(TradeStatus.ALL, limit = Int.MAX_VALUE)
            .filter { Triple(it.trade.variant, it.trade.symbol, it.trade.barTime) !in preOpened }
        val liveBy = live.associateBy { Triple(it.trade.variant, it.trade.symbol, it.trade.barTime) to it.trade.listId }
        assertEquals(live.size, liveBy.size, "a trade was opened twice")

        // Every trade the backtest makes on those days, the live scan made too...
        var compared = 0
        var closed = 0
        var noBaseline = 0
        val inRange = expected.filter { panel.getValue(it.symbol).t[it.signal] in firstOpen..lastOpen }
        for (x in inRange) {
            val c = panel.getValue(x.symbol)
            val what = "${x.variant} ${x.symbol} signal ${x.signal}"
            val t = assertNotNull(liveBy[Triple(x.variant, x.symbol, c.t[x.signal]) to x.listId], "$what: the backtest traded it, the live scan did not")
            assertEquals(c.t[x.entryIdx], t.trade.entryTime, "$what entry time")
            assertEquals(x.entryPrice, t.trade.entryPrice, "$what entry price")
            assertEquals(x.regime, t.trade.regime, "$what regime")
            assertEquals(x.limit, t.trade.holdBars, "$what time limit")
            if (x.target != null) assertTrue(near(x.target, t.trade.target!!), "$what target")
            compared++
            if (c.t[x.exitIdx] <= lastOpen) {
                val exit = assertNotNull(t.exit, "$what: the backtest closed it on candle ${x.exitIdx}, the live scan never did")
                assertEquals(c.closeTime[x.exitIdx], exit.exitTime, "$what exit time")
                assertEquals(x.reason, exit.reason.label, "$what exit reason")
                assertEquals(x.exitIdx - x.signal, exit.barsHeld, "$what bars held")
                assertTrue(near(x.exitPrice, exit.exitPrice), "$what exit price ${x.exitPrice} vs ${exit.exitPrice}")
                assertTrue(near(x.gross, exit.gross), "$what gross")
                assertTrue(near(x.net, exit.net), "$what net")
                // A baseline needs matched random entries in the same BTC regime within 90 days, drawn only from
                // candles that exist when the trade closes; when a regime has just changed there can be none.
                if (exit.randomMean == null) {
                    assertEquals(null, exit.excess, "$what has an excess without a baseline")
                    noBaseline++
                } else {
                    assertTrue(near(exit.net - exit.randomMean!!, exit.excess!!), "$what excess")
                }
                closed++
            } else {
                assertEquals(null, t.exit, "$what closed early")
            }
        }

        // ...and the live scan made no trade the backtest did not.
        val backtestKeys = expected.map { Triple(it.variant, it.symbol, panel.getValue(it.symbol).t[it.signal]) to it.listId }.toSet()
        for ((k, t) in liveBy) assertTrue(k in backtestKeys, "live traded ${t.trade.variant} ${t.trade.symbol} at ${t.trade.barTime}, the backtest did not")

        assertTrue(compared > 100, "only $compared trades were compared")
        assertTrue(closed > 60, "only $closed closed trades were compared")
        assertEquals(compared, live.size, "the live scan opened trades beyond those compared")
        assertTrue(noBaseline * 6 <= closed, "$noBaseline of $closed closed trades had no baseline")
        println("LIVE==BACKTEST: $compared trades compared, $closed of them closed ($noBaseline without a baseline), ${preOpened.size} already open at the start")
    }
}
