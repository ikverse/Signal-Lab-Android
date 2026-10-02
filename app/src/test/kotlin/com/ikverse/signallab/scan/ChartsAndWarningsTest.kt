package com.ikverse.signallab.scan

import com.ikverse.signallab.data.CostModel
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.NewTrade
import com.ikverse.signallab.data.SettingsStore
import com.ikverse.signallab.data.TradeStatus
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.ExitMode
import com.ikverse.signallab.engine.ExitRule
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.PaperTrading
import com.ikverse.signallab.engine.Signals
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun near(a: Double, b: Double) = abs(a - b) <= 1e-9 * maxOf(1.0, abs(a), abs(b))

/** Warnings, the cost setting, exits of older trades, and scans of different charts running side by side. */
@RunWith(RobolectricTestRunner::class)
class ChartsAndWarningsTest {
    /** The moment the research's daily candles end: a UTC midnight, so BTC's daily chart is current for scans just after it. */
    private val end = 1_790_812_800_000L
    private val day = 86_400_000L
    private val btcDaily = ("BTCUSDT" to Timeframe.D1) to GoldenData.klines(Timeframe.D1).getValue("BTCUSDT")

    /** [n] candles ending at [end]; with [forming] the last one is the candle that has just begun at [end], not yet closed. */
    private fun klines(tf: Timeframe, n: Int, price: (Int) -> Double, volume: (Int) -> Double, forming: Boolean = false): List<Kline> = List(n) { i ->
        val open = if (i == 0) price(0) else price(i - 1)
        val close = price(i)
        val t = (if (forming) end else end - tf.ms) - (n - 1 - i) * tf.ms
        Kline(t, open, maxOf(open, close) * 1.0005, minOf(open, close) * 0.9995, close, volume(i), t + tf.ms - 1)
    }

    /** A flat minute chart ending at [end], then five candles that rise [rise] in all on [times] the usual volume. */
    private fun pumpingMinutes(rise: Double, times: Double): List<Kline> {
        val step = (1 + rise).pow(1.0 / 5)
        // 420 closed candles, the last five of them the pump, and the next candle just beginning.
        return klines(Timeframe.M1, 421, { i -> val k = minOf(i, 419); if (k < 415) 100.0 else 100.0 * step.pow(k - 414) },
            { i -> if (i in 415..419) 10.0 * times else 10.0 }, forming = true)
    }

    private fun env(symbol: String, tf: Timeframe, series: List<Kline>, timeframes: Set<Timeframe> = setOf(tf)) =
        ScanEnv(listOf(symbol), tf, mapOf((symbol to tf) to series, btcDaily), timeframes)

    // --- market warnings --------------------------------------------------------------------------------------------

    @Test
    fun aPumpOnAMinuteChartIsAWarningAndNeverAPaperTrade() = runTest {
        val e = env("PMPUSDT", Timeframe.M1, pumpingMinutes(0.06, 15.0))
        e.at(end + 5_000)
        val r = e.scanner.scan(Timeframe.M1)
        assertEquals(1, r.warnings)
        val warning = e.log.alerts(100).single { it.kind == AlertText.KIND_WARNING }
        assertEquals("Pump warning: PMP", warning.title)
        assertTrue(warning.body.contains("rose 6.0% in 5 minutes on 15 times its usual volume"), warning.body)
        assertTrue(warning.body.contains("late buyers lose") && warning.body.contains("No paper trade is opened"))
        assertEquals("PMPUSDT", warning.symbol)
        assertTrue(e.sink.delivered.any { it.id == warning.id }, "a warning is sent as a notification too")
        assertTrue(e.log.trades(TradeStatus.ALL, limit = Int.MAX_VALUE).none { it.trade.variant.contains("pump") || it.trade.family == "warning" })
    }

    @Test
    fun theSamePumpIsNotWarnedAboutTwice() = runTest {
        val e = env("PMPUSDT", Timeframe.M1, pumpingMinutes(0.06, 15.0))
        e.at(end + 5_000)
        e.scanner.scan(Timeframe.M1)
        assertEquals(0, e.scanner.scan(Timeframe.M1).warnings, "scanning the same candle again")
        e.at(end + 10 * 60_000L)
        assertEquals(0, e.scanner.scan(Timeframe.M1).warnings, "still inside the cooldown, and no new pump")
        assertEquals(1, e.log.alerts(100).count { it.kind == AlertText.KIND_WARNING })
    }

    @Test
    fun aRiseThatIsTooSmallOrOnOrdinaryVolumeWarnsOfNothing() = runTest {
        for ((rise, times) in listOf(0.04 to 15.0, 0.06 to 4.0)) {
            val e = env("PMPUSDT", Timeframe.M1, pumpingMinutes(rise, times))
            e.at(end + 5_000)
            assertEquals(0, e.scanner.scan(Timeframe.M1).warnings, "rise $rise on $times times the volume")
        }
    }

    @Test
    fun aPumpOnAnHourChartIsNotLookedFor() = runTest {
        val series = klines(Timeframe.H1, 300, { i -> if (i < 299) 100.0 else 120.0 }, { i -> if (i < 299) 1.0 else 500.0 })
        val e = env("PMPUSDT", Timeframe.H1, series)
        e.at(end + 5_000)
        assertEquals(0, e.scanner.scan(Timeframe.H1).warnings)
    }

    @Test
    fun aDayOfHeavyVolumeIsAWarningOnTheDailyChart() = runTest {
        val e = env("VOLUSDT", Timeframe.D1, klines(Timeframe.D1, 60, { 100.0 + it * 0.01 }, { i -> if (i < 59) 10.0 else 50.0 }))
        e.at(end + 5_000)
        val r = e.scanner.scan(Timeframe.D1)
        assertEquals(1, r.warnings)
        val w = e.log.alerts(100).single { it.kind == AlertText.KIND_WARNING }
        assertEquals("Volume spike: VOL", w.title)
        assertTrue(w.body.contains("traded 5.0 times its usual daily volume") && w.body.contains("lower prices the next day"), w.body)
        assertEquals(0, e.scanner.scan(Timeframe.D1).warnings)
    }

    @Test
    fun aCoinListedLessThanAMonthAgoGetsALineOnItsAlerts() = runTest {
        val e = env("PMPUSDT", Timeframe.M1, pumpingMinutes(0.06, 15.0))
        e.candles.setListedAt("PMPUSDT", end - 5 * day)
        e.at(end + 5_000)
        e.scanner.scan(Timeframe.M1)
        val body = e.log.alerts(100).single { it.kind == AlertText.KIND_WARNING }.body
        assertTrue(body.contains("PMP was listed on Binance less than 30 days ago. New coins fell on average in their first month."), body)
    }

    @Test
    fun theLineIsOnPaperTradeAlertsToo() = runTest {
        val tf = Timeframe.D1
        val eth = GoldenData.candles("ETHUSDT", tf)
        val bar = Signals.trendState(eth).entries.first { it.key.name == "trend_ma20_1d" }.value.let { f -> f.indices.last { f[it] && it > 900 && it < eth.size - 60 } }
        val e = ScanEnv(GoldenData.klines(tf).keys.toList(), tf, GoldenData.klines(tf).mapKeys { it.key to tf })
        val now = eth.closeTime[bar] + 1 + 5_000
        e.candles.setListedAt("ETHUSDT", now - 5 * day)
        e.at(now)
        e.scanner.scan(tf)
        val eth1 = e.log.alerts(500).filter { it.kind == AlertText.KIND_SIGNAL && it.symbol == "ETHUSDT" }
        val others = e.log.alerts(500).filter { it.kind == AlertText.KIND_SIGNAL && it.symbol != "ETHUSDT" }
        assertTrue(eth1.isNotEmpty() && eth1.all { it.body.contains("ETH was listed on Binance less than 30 days ago") })
        assertTrue(others.none { it.body.contains("listed on Binance") })
    }

    @Test
    fun anOlderCoinGetsNoSuchLine() = runTest {
        val e = env("PMPUSDT", Timeframe.M1, pumpingMinutes(0.06, 15.0))
        e.candles.setListedAt("PMPUSDT", end - 200 * day)
        e.at(end + 5_000)
        e.scanner.scan(Timeframe.M1)
        assertTrue(e.log.alerts(100).none { it.body.contains("listed on Binance") })
        val unknown = env("PMPUSDT", Timeframe.M1, pumpingMinutes(0.06, 15.0))
        unknown.at(end + 5_000)
        unknown.scanner.scan(Timeframe.M1)
        assertTrue(unknown.log.alerts(100).none { it.body.contains("listed on Binance") }, "a coin whose listing date is not known is not called new")
    }

    // --- the fee ------------------------------------------------------------------------------------------------------

    @Test
    fun theDefaultCostIsBinancesFeeBothWaysAndNothingElse() {
        val c = CostModel()
        assertEquals(0.002, c.costFor("BTCUSDT"))
        assertEquals(0.002, c.costFor("SOLUSDT"))
        assertFalse(c.includesExtra)
        assertEquals(0.0015, CostModel(feePerSide = 0.00075).costFor("SOLUSDT"))
        val thin = CostModel(extraMajors = 0.0005, extraOthers = 0.002)
        assertEquals(0.0025, thin.costFor("ETHUSDT"))
        assertEquals(0.004, thin.costFor("DOGEUSDT"))
        assertTrue(thin.includesExtra)
    }

    @Test
    fun theCostComesFromTheSettingsAndEachTradeKeepsWhatItWasChargedWhenItOpened() = runTest {
        val tf = Timeframe.D1
        val eth = GoldenData.candles("ETHUSDT", tf)
        val bar = Signals.trendState(eth).entries.first { it.key.name == "trend_ma20_1d" }.value.let { f -> f.indices.last { f[it] && it > 900 && it < eth.size - 60 } }
        val e = ScanEnv(GoldenData.klines(tf).keys.toList(), tf, GoldenData.klines(tf).mapKeys { it.key to tf })
        e.settings.setDouble(SettingsStore.FEE_PER_SIDE, 0.0005)
        e.settings.setDouble(SettingsStore.EXTRA_COST_OTHERS, 0.001)
        e.at(eth.closeTime[bar] + 1 + 5_000)
        e.scanner.scan(tf)
        val trades = e.log.trades(TradeStatus.OPEN, limit = Int.MAX_VALUE)
        assertTrue(trades.isNotEmpty())
        for (t in trades) {
            val expected = if (t.trade.symbol in EngineConfig.MAJORS) 0.001 else 0.002
            assertEquals(expected, t.trade.cost, "${t.trade.symbol}")
        }
        // The setting changes; the trade that is already open keeps the cost it opened with, and so does its result.
        val mine = trades.first { it.trade.variant == "trend_ma20_1d" && it.trade.symbol == "ETHUSDT" }
        e.settings.setDouble(SettingsStore.FEE_PER_SIDE, 0.002)
        val spec = ExitRule(ExitMode.TRAIL, mine.trade.holdBars).spec(mine.trade.entryPrice, mine.trade.atr!!, mine.trade.holdBars)
        val exitIdx = assertNotNull(PaperTrading.resolve(eth, bar + 1, spec)).exitIdx
        for (j in bar + 1..exitIdx) {
            e.at(eth.closeTime[j] + 1 + 5_000)
            e.scanner.scan(tf)
        }
        val exit = assertNotNull(e.log.trades(TradeStatus.CLOSED, limit = Int.MAX_VALUE).first { it.id == mine.id }.exit)
        assertEquals(exit.gross - 0.001, exit.net, 1e-15, "closed with the cost it opened with, not today's setting")
    }

    // --- trades from before exits were chosen -----------------------------------------------------------------------------

    @Test
    fun aTradeOpenedByTheOldRulesStillClosesByThem() = runTest {
        val tf = Timeframe.D1
        val eth = GoldenData.candles("ETHUSDT", tf)
        val bar = 1000
        val atr = Indicators.atr(eth.high, eth.low, eth.close, EngineConfig.ATR_WINDOW)[bar]
        val entry = eth.open[bar + 1]
        val target = PaperTrading.targetPrice(entry, atr)
        val stop = PaperTrading.stopPrice(entry, atr)
        val e = ScanEnv(listOf("BTCUSDT"), tf, GoldenData.klines(tf).mapKeys { it.key to tf })
        // As M4 wrote it: a target and a stop, no stored cost, no exit rule, no candle size.
        e.at(eth.closeTime[bar] + 1 + 5_000)
        e.log.open(NewTrade("trend_ma20_1d", "trend_state", "ETHUSDT", tf, 0, eth.t[bar], eth.closeTime[bar], 1, eth.t[bar + 1], entry, target, stop,
            EngineConfig.timeLimitBars(tf), eth.t[bar + 1] + EngineConfig.timeLimitBars(tf) * tf.ms - 1))
        val want = assertNotNull(PaperTrading.resolve(eth, bar + 1, target, stop, EngineConfig.timeLimitBars(tf)))
        for (j in bar + 1..want.exitIdx) {
            e.at(eth.closeTime[j] + 1 + 5_000)
            e.scanner.scan(tf)
        }
        val exit = assertNotNull(e.log.trades(TradeStatus.CLOSED, limit = Int.MAX_VALUE).single { it.trade.symbol == "ETHUSDT" && it.trade.variant == "trend_ma20_1d" }.exit)
        assertEquals(eth.closeTime[want.exitIdx], exit.exitTime)
        assertTrue(near(want.exitPrice, exit.exitPrice))
        assertTrue(near(exit.gross - EngineConfig.costFor("ETHUSDT"), exit.net), "an old trade keeps the old cost")
    }

    // --- charts scan side by side ----------------------------------------------------------------------------------------

    @Test
    fun aSlowScanOfOneChartNeverHoldsUpAnother() = runTest {
        val h1 = GoldenData.klines(Timeframe.H1)
        val m1 = GoldenData.klines(Timeframe.M1)
        val source = mapOf(("BTCUSDT" to Timeframe.H1) to h1.getValue("BTCUSDT"), ("BTCUSDT" to Timeframe.M1) to m1.getValue("BTCUSDT"), btcDaily)
        val e = ScanEnv(listOf("BTCUSDT"), Timeframe.M1, source, setOf(Timeframe.M1, Timeframe.H1))
        e.at(end - 3_600_000L + 5_000)
        val gate = CompletableDeferred<Unit>()
        e.market.onKlines = { _, tf -> if (tf == Timeframe.H1) gate.await() }
        val slow = async(start = CoroutineStart.UNDISPATCHED) { e.scanner.scan(Timeframe.H1) }
        val fast = e.scanner.scan(Timeframe.M1)
        assertTrue(fast.completed, "the minute chart was scanned while the hour chart was still waiting for Binance")
        assertFalse(slow.isCompleted)
        gate.complete(Unit)
        assertTrue(slow.await().completed)
    }

    @Test
    fun theSameChartIsNeverScannedTwiceAtOnce() = runTest {
        val tf = Timeframe.D1
        val e = ScanEnv(listOf("BTCUSDT"), tf, GoldenData.klines(tf).filterKeys { it == "BTCUSDT" }.mapKeys { it.key to tf })
        e.at(end + 5_000)
        val gate = CompletableDeferred<Unit>()
        e.market.onKlines = { _, _ -> gate.await() }
        val first = async(start = CoroutineStart.UNDISPATCHED) { e.scanner.scan(tf) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { e.scanner.scan(tf) }
        gate.complete(Unit)
        val a = first.await()
        val b = second.await()
        assertTrue(a.completed && b.completed)
        assertEquals(e.log.trades(TradeStatus.ALL, limit = Int.MAX_VALUE).size, a.opened + b.opened, "no trade was opened by both")
    }

    @Test
    fun theChartsInUseAreTheOnesAnActiveListIsWatchedOnPlusAnyWithAnOpenTrade() = runTest {
        val e = ScanEnv(listOf("BTCUSDT"), Timeframe.D1, mapOf(btcDaily), setOf(Timeframe.M5, Timeframe.H4))
        assertEquals(setOf(Timeframe.M5, Timeframe.H4), e.scanner.timeframesInUse())
        e.lists = emptyList()
        assertEquals(emptySet(), e.scanner.timeframesInUse())
        e.log.open(NewTrade("x_1h", "x", "BTCUSDT", Timeframe.H1, 0, 1, 2, 1, 3, 100.0, 104.0, 98.0, 7, 99))
        assertEquals(setOf(Timeframe.H1), e.scanner.timeframesInUse())
    }

    @Test
    fun historyKeptMatchesTheChartAndTheMorePatternsHaveToWarmUpTheMoreTheKeep() {
        assertEquals(listOf(7, 30, 60, 60, 100, 400, 400), Timeframe.entries.map { DataConfig.historyDays(it) })
    }
}
