package com.ikverse.signallab.scan

import com.ikverse.signallab.data.NewTrade
import com.ikverse.signallab.data.TradeStatus
import com.ikverse.signallab.data.binance.BinanceException
import com.ikverse.signallab.engine.CandleClock
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.ExitMode
import com.ikverse.signallab.engine.ExitRule
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.PaperTrading
import com.ikverse.signallab.engine.Signals
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun near(a: Double, b: Double) = abs(a - b) <= 1e-9 * maxOf(1.0, abs(a), abs(b))

/** One scanner on real daily candles, moved from close to close like the phone would be. */
@RunWith(RobolectricTestRunner::class)
class ScannerTest {
    private val tf = Timeframe.D1
    private val coins = GoldenData.klines(tf).keys.toList()
    private val source = GoldenData.klines(tf).mapKeys { it.key to tf }
    private val eth = GoldenData.candles("ETHUSDT", tf)
    private val btc = GoldenData.candles("BTCUSDT", tf)
    private val variant = "trend_ma20_1d"

    private fun env(list: List<String> = coins) = ScanEnv(list, tf, source)

    /** A candle on ETH where [variant] fired, late enough in the data for a full window and early enough for an exit. */
    private val bar: Int = run {
        val flags = Signals.trendState(eth).entries.first { it.key.name == variant }.value
        flags.indices.last { flags[it] && it < eth.size - 60 && it > 900 }
    }

    private fun closeOf(c: Candles, i: Int) = c.closeTime[i] + 1 + 5_000

    private suspend fun ScanEnv.openTrades() = log.trades(TradeStatus.OPEN, limit = Int.MAX_VALUE)

    private val phrase = "its 20-candle average" // what the description of $variant says

    private suspend fun ScanEnv.theTrade() = log.trades(TradeStatus.ALL, limit = Int.MAX_VALUE)
        .single { it.trade.variant == variant && it.trade.symbol == "ETHUSDT" && it.trade.barTime == eth.t[bar] }

    @Test
    fun aSignalOnTheCandleThatJustClosedOpensAPaperTradeAtTheNextOpen() = runTest {
        val e = env()
        e.at(closeOf(eth, bar))
        val r = e.scanner.scan(tf)
        val t = e.theTrade().trade
        assertEquals(eth.t[bar], t.barTime)
        assertEquals(eth.closeTime[bar], t.detectedAt)
        assertEquals(eth.t[bar + 1], t.entryTime)
        assertEquals(eth.open[bar + 1], t.entryPrice, "entry is the open of the candle after the signal's, which was still forming")
        val atr = Indicators.atr(eth.high, eth.low, eth.close, EngineConfig.ATR_WINDOW)[bar]
        // A trend pattern trails: no target, a safety stop two candle sizes down, and the cap for the chart.
        assertEquals("trail", t.exitMode)
        assertNull(t.target)
        assertTrue(near(t.entryPrice - EngineConfig.TRAIL_STOP_ATR * atr, t.stop!!), "stop ${t.stop}")
        assertTrue(near(atr, t.atr!!), "the candle size is kept for the trailing stop")
        assertEquals(EngineConfig.trailCapBars(tf), t.holdBars)
        assertEquals(0.002, t.cost, "Binance's fee both ways and nothing else")
        assertEquals(t.entryTime + t.holdBars * tf.ms - 1, t.exitDue)
        assertEquals(PaperTrading.regimeSeries(btc, eth.closeTime)[bar], t.regime)
        assertTrue(e.openTrades().all { it.trade.barTime == eth.t[bar] }, "every trade belongs to the candle that just closed")
        assertEquals(e.openTrades().size, r.opened)
        val alert = e.sink.delivered.single { it.symbol == "ETHUSDT" && it.kind == AlertText.KIND_SIGNAL && it.body.contains(phrase) }
        assertEquals("Paper trade opened: ETH 1d", alert.title)
        assertEquals(AlertText.KIND_SIGNAL, alert.kind)
        assertEquals("signallab://coin/ETHUSDT?tf=1d", alert.link)
    }

    @Test
    fun scanningTheSameCandleTwiceChangesNothing() = runTest {
        val e = env()
        e.at(closeOf(eth, bar))
        val first = e.scanner.scan(tf)
        val trades = e.openTrades().size
        val alerts = e.log.alerts(1000).size
        val delivered = e.sink.delivered.size
        val second = e.scanner.scan(tf)
        assertTrue(first.opened > 0)
        assertEquals(0, second.opened)
        assertEquals(trades, e.openTrades().size)
        assertEquals(alerts, e.log.alerts(1000).size)
        assertEquals(delivered, e.sink.delivered.size)
        assertTrue(e.scanner.isUpToDate(tf))
    }

    @Test
    fun aTradeClosesOnTheCandleItsExitHappensOnAndNotBefore() = runTest {
        val e = env()
        e.at(closeOf(eth, bar))
        e.scanner.scan(tf)
        val t = e.theTrade().trade
        val want = assertNotNull(PaperTrading.resolve(eth, bar + 1, ExitRule(ExitMode.TRAIL, t.holdBars).spec(t.entryPrice, t.atr!!, t.holdBars)))
        for (j in bar + 1..want.exitIdx) {
            assertNull(e.theTrade().exit, "closed before its exit candle ($j of ${want.exitIdx})")
            e.at(closeOf(eth, j))
            e.scanner.scan(tf)
        }
        val x = assertNotNull(e.theTrade().exit, "still open after its exit candle")
        assertEquals(eth.closeTime[want.exitIdx], x.exitTime)
        assertTrue(near(want.exitPrice, x.exitPrice))
        assertEquals(want.reason, x.reason)
        assertEquals(want.exitIdx - bar, x.barsHeld)
        assertTrue(near(x.exitPrice / t.entryPrice - 1, x.gross))
        assertTrue(near(x.gross - 0.002, x.net), "net is gross less the cost the trade opened with")
        assertTrue(x.maxUp!! >= 0 && x.maxDown!! <= 0 && x.barsToPeak!! >= 1, "what the trade did on the way is kept")
        val baseline = assertNotNull(x.randomMean, "the baseline is drawn when the trade closes")
        assertTrue(near(x.net - baseline, x.excess!!))
        val alert = e.sink.delivered.single { it.kind == AlertText.KIND_EXIT && it.symbol == "ETHUSDT" && it.body.contains(phrase) }
        assertTrue(alert.title.startsWith("Paper trade closed: ETH 1d, "), alert.title)
    }

    @Test
    fun aTradeIsStillFollowedAfterItsListIsSwitchedOff() = runTest {
        val e = env()
        e.at(closeOf(eth, bar))
        e.scanner.scan(tf)
        val t = e.theTrade().trade
        val want = assertNotNull(PaperTrading.resolve(eth, bar + 1, ExitRule(ExitMode.TRAIL, t.holdBars).spec(t.entryPrice, t.atr!!, t.holdBars)))
        e.lists = emptyList()
        for (j in bar + 1..want.exitIdx) {
            e.at(closeOf(eth, j))
            e.scanner.scan(tf)
        }
        assertNotNull(e.theTrade().exit)
        assertEquals(0, e.openTrades().count { it.trade.barTime > eth.t[bar] }, "nothing new is opened for a list that is off")
    }

    @Test
    fun aSignalNoticedAfterItsEntryCandleHasEndedIsReportedAsMissedAndNotTraded() = runTest {
        val e = env()
        e.at(closeOf(eth, bar - 1))
        e.scanner.scan(tf) // the phone is awake for the candle before the signal...
        e.sink.delivered.clear()
        e.at(closeOf(eth, bar + 1))
        val r = e.scanner.scan(tf) // ...and asleep for the signal's own entry candle
        assertTrue(e.log.trades(TradeStatus.ALL, limit = Int.MAX_VALUE).none { it.trade.variant == variant && it.trade.symbol == "ETHUSDT" && it.trade.barTime == eth.t[bar] })
        val missed = e.log.alerts(1000).single { it.kind == AlertText.KIND_MISSED && it.symbol == "ETHUSDT" && it.body.contains(phrase) }
        assertEquals("Signal missed: ETH 1d", missed.title)
        assertTrue(r.missed > 0)
        assertTrue(e.sink.delivered.none { it.kind == AlertText.KIND_MISSED }, "a missed signal is for the inbox, not a notification")
        // And it is reported once: scanning on does not say it again.
        e.scanner.scan(tf)
        assertEquals(1, e.log.alerts(1000).count { it.kind == AlertText.KIND_MISSED && it.symbol == "ETHUSDT" && it.body.contains(phrase) })
    }

    @Test
    fun aFirstScanLooksOnlyAtTheNewestCandleSoSwitchingAListOnIsNotAFlood() = runTest {
        val e = env()
        e.at(closeOf(eth, bar + 20))
        val r = e.scanner.scan(tf)
        assertEquals(0, r.missed)
        assertTrue(e.openTrades().all { it.trade.barTime == eth.t[bar + 20] })
    }

    @Test
    fun withNoFirstPriceYetTheSignalWaitsAndTradesOnceItArrives() = runTest {
        val e = env()
        val now = closeOf(eth, bar)
        e.at(now)
        val key = "ETHUSDT" to tf
        val withForming = e.market.series.getValue(key)
        e.market.series[key] = withForming.filter { it.closeTime < now } // the new candle has not printed yet
        val first = e.scanner.scan(tf)
        assertTrue(first.waiting)
        assertTrue("ETHUSDT" in first.retryCoins)
        assertTrue(e.log.trades(TradeStatus.ALL, limit = Int.MAX_VALUE).none { it.trade.symbol == "ETHUSDT" && it.trade.variant == variant })
        assertFalse(e.scanner.isUpToDate(tf), "the candle stays open while something waits")
        e.market.series[key] = withForming
        val second = e.scanner.scan(tf, only = first.retryCoins)
        assertFalse(second.waiting)
        assertEquals(eth.open[bar + 1], e.theTrade().trade.entryPrice)
        assertTrue(e.scanner.isUpToDate(tf))
    }

    @Test
    fun withoutACurrentBtcRegimeNothingIsOpenedOrClosed() = runTest {
        val e = env()
        val now = closeOf(eth, bar)
        e.at(now)
        val key = "BTCUSDT" to tf
        val full = e.market.series.getValue(key)
        e.market.series[key] = full.filter { it.openTime < btc.t[bar] } // BTC is a day behind
        val r = e.scanner.scan(tf)
        assertTrue(r.waiting)
        assertEquals(0, r.opened)
        assertEquals(0, e.openTrades().size)
        e.market.series[key] = full
        val again = e.scanner.scan(tf)
        assertFalse(again.waiting)
        assertTrue(again.opened > 0)
    }

    @Test
    fun aSignalIsNotTradedWhileTheSameVariantIsAlreadyOpenOnThatCoin() = runTest {
        val e = env()
        e.at(closeOf(eth, bar - 1))
        e.scanner.scan(tf)
        // A trade of the same variant on ETH that started earlier and cannot end: no reachable target, no reachable stop.
        e.log.open(NewTrade(variant, "trend_state", "ETHUSDT", tf, 0, eth.t[bar - 3], eth.closeTime[bar - 3], 1, eth.t[bar - 2], eth.open[bar - 2], 1e12, 0.0, 30, eth.t[bar] + 30 * tf.ms))
        e.at(closeOf(eth, bar))
        e.scanner.scan(tf)
        assertEquals(1, e.log.trades(TradeStatus.ALL, limit = Int.MAX_VALUE).count { it.trade.variant == variant && it.trade.symbol == "ETHUSDT" })
    }

    @Test
    fun downloadFailuresAreReportedAndWorthRetrying() = runTest {
        val e = env(listOf("ETHUSDT"))
        e.at(closeOf(eth, bar))
        e.market.failKlinesFrom = 1
        val r = e.scanner.scan(tf)
        assertTrue(r.failures.isNotEmpty())
        assertTrue(r.waiting)
        assertFalse(r.blocked)
        assertFalse(r.completed)
    }

    @Test
    fun aBlockedNetworkIsReportedAndNotRetried() = runTest {
        val e = env(listOf("ETHUSDT", "BTCUSDT"))
        e.at(closeOf(eth, bar))
        e.market.failKlinesFrom = 1
        e.market.failWith = BinanceException.Blocked()
        val r = e.scanner.scan(tf)
        assertTrue(r.blocked)
        assertFalse(r.waiting)
        assertEquals(setOf("ETHUSDT", "BTCUSDT"), r.failures.keys)
    }

    @Test
    fun withNothingToWatchTheCursorIsForgottenSoAnOldOneCannotFloodTheNextList() = runTest {
        val e = env()
        e.settings.set("scan_cursor_1d", "123456")
        e.lists = emptyList()
        e.at(closeOf(eth, bar + 3))
        val r = e.scanner.scan(tf)
        assertEquals("nothing to scan", r.note)
        assertNull(e.scanner.cursor(tf))
        assertEquals(0, e.market.klineCalls.size, "nothing is downloaded for nothing")
    }

    @Test
    fun btcDailyIsKeptUpToDateEvenWhenNoListHoldsBtc() = runTest {
        val e = env(listOf("ETHUSDT"))
        e.at(closeOf(eth, bar))
        e.scanner.scan(tf)
        assertEquals(btc.t[bar], e.candles.lastOpen("BTCUSDT", tf), "BTC daily reaches the candle that just closed")
        assertTrue(e.openTrades().isNotEmpty(), "and its regime is what lets trades open")
        assertEquals(0, e.candles.count("BTCUSDT", Timeframe.H1))
    }

    @Test
    fun everyVariantThatRanIsRegistered() = runTest {
        val e = env()
        e.at(closeOf(eth, bar))
        e.scanner.scan(tf)
        assertEquals(Signals.compute(GoldenData.panel(tf)).keys.size, e.log.variantCount())
    }

    @Test
    fun theCloseScheduleAndTheCursorAgree() = runTest {
        val e = env()
        e.at(closeOf(eth, bar))
        assertFalse(e.scanner.isUpToDate(tf))
        e.scanner.scan(tf)
        assertEquals(CandleClock.lastClose(tf, e.market.now) - 1, e.scanner.cursor(tf))
        assertTrue(e.scanner.isUpToDate(tf))
        e.at(closeOf(eth, bar + 1))
        assertFalse(e.scanner.isUpToDate(tf))
    }
}
