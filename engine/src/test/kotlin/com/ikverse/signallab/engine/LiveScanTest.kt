package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules that turn closed candles into paper trades while the market is running, held to the
 * backtest's rules: the same exits, the same signals, and nothing that depends on how a trade ended.
 */
class LiveScanTest {
    private val tfs = listOf(Timeframe.D1, Timeframe.H4, Timeframe.H1)

    private fun flat(n: Int = 12): Candles {
        val t = LongArray(n) { Synth.START_MS + it * Timeframe.D1.ms }
        return Candles("FLATUSDT", Timeframe.D1, t, DoubleArray(n) { 100.0 }, DoubleArray(n) { 101.0 },
            DoubleArray(n) { 99.0 }, DoubleArray(n) { 100.0 }, DoubleArray(n) { 1.0 },
            LongArray(n) { t[it] + Timeframe.D1.ms - 1 })
    }

    // --- resolve: an open trade's exit, from the candles so far -------------------------------

    @Test
    fun resolveGivesTheBacktestsExitForEveryRealSignal() {
        var compared = 0
        var early = 0
        for (tf in tfs) {
            val panel = Golden.panel(tf)
            for ((key, bySymbol) in Signals.compute(panel)) {
                for ((sym, flags) in bySymbol) {
                    val c = panel.getValue(sym)
                    val idx = flags.indices.filter { flags[it] }.toIntArray()
                    if (idx.isEmpty()) continue
                    val atr = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)
                    val hold = key.holdBars
                    val ex = PaperTrading.simulateExits(c, idx, tf, atr, hold)
                    val limit = hold ?: EngineConfig.timeLimitBars(tf)
                    for (j in idx.indices) {
                        if (!ex.valid[j]) continue
                        val s = idx[j]
                        val target = if (hold == null) PaperTrading.targetPrice(ex.entryPrice[j], atr[s]) else null
                        val stop = if (hold == null) PaperTrading.stopPrice(ex.entryPrice[j], atr[s]) else null
                        val what = "${tf.label} ${key.name} $sym bar $s"
                        val r = assertNotNull(PaperTrading.resolve(c, s + 1, target, stop, limit), what)
                        assertEquals(ex.exitIdx[j], r.exitIdx, "$what exit candle")
                        assertEquals(ex.exitPrice[j], r.exitPrice, "$what exit price")
                        assertEquals(ex.reason[j], r.reason, "$what reason")
                        compared++
                        // The moment the exit candle has closed the answer is already known, and one candle sooner it is not.
                        if (compared % 10 == 0) {
                            val known = PaperTrading.resolve(c.head(r.exitIdx + 1), s + 1, target, stop, limit)
                            assertEquals(r.exitIdx, known?.exitIdx, "$what resolved when its candle closed")
                            assertEquals(r.exitPrice, known?.exitPrice, "$what price when its candle closed")
                            assertNull(PaperTrading.resolve(c.head(r.exitIdx), s + 1, target, stop, limit), "$what before its candle")
                            if (r.exitIdx < s + limit) early++
                        }
                    }
                }
            }
        }
        assertTrue(compared > 10_000, "only $compared real trades compared")
        assertTrue(early > 100, "only $early trades ended before their time limit")
    }

    @Test
    fun theStopIsAssumedFirstWhenOneCandleTouchesBoth() {
        val c = flat()
        c.high[5] = 110.0
        c.low[5] = 90.0
        val r = assertNotNull(PaperTrading.resolve(c, 4, target = 104.0, stop = 98.0, limit = 7))
        assertEquals(ExitReason.STOP, r.reason)
        assertEquals(98.0, r.exitPrice)
        assertEquals(5, r.exitIdx)
    }

    @Test
    fun aGapThroughALevelFillsAtTheOpen() {
        val down = flat()
        down.open[6] = 90.0; down.low[6] = 89.0; down.high[6] = 91.0
        assertEquals(90.0, PaperTrading.resolve(down, 4, 104.0, 98.0, 7)?.exitPrice)
        val up = flat()
        up.open[6] = 110.0; up.low[6] = 109.0; up.high[6] = 112.0
        val r = assertNotNull(PaperTrading.resolve(up, 4, 104.0, 98.0, 7))
        assertEquals(ExitReason.TARGET, r.reason)
        assertEquals(110.0, r.exitPrice)
    }

    @Test
    fun aTradeStaysOpenUntilALevelIsTouchedOrTheLimitCandleCloses() {
        val c = flat()
        // Candles 4 to 10 are the seven the trade may use, so it can only end once candle 10 has closed.
        assertNull(PaperTrading.resolve(c.head(10), 4, 104.0, 98.0, 7))
        val onTime = assertNotNull(PaperTrading.resolve(c.head(11), 4, 104.0, 98.0, 7))
        assertEquals(ExitReason.TIME, onTime.reason)
        assertEquals(10, onTime.exitIdx)
        assertEquals(100.0, onTime.exitPrice)
    }

    @Test
    fun aTradeWhoseEntryCandleHasNotClosedYetIsOpen() {
        val c = flat(5)
        assertNull(PaperTrading.resolve(c, 5, 104.0, 98.0, 7))
        assertNull(PaperTrading.resolve(c, 9, 104.0, 98.0, 7))
        assertNull(PaperTrading.resolve(c, -1, 104.0, 98.0, 7))
    }

    @Test
    fun aHeldTradeIgnoresLevelsAndEndsAtTheClose() {
        val c = flat()
        c.low[5] = 50.0
        c.close[6] = 103.0
        val r = assertNotNull(PaperTrading.resolve(c, 4, null, null, 3))
        assertEquals(ExitReason.TIME, r.reason)
        assertEquals(6, r.exitIdx)
        assertEquals(103.0, r.exitPrice)
        assertNull(PaperTrading.resolve(c.head(6), 4, null, null, 3))
    }

    // --- scan: what fired on the candles that closed -----------------------------------------

    private fun donchianBar(c: Candles, name: String): Int {
        val key = Signals.donchian(c).keys.first { it.name == name }
        return Signals.donchian(c).getValue(key).indices.first { Signals.donchian(c).getValue(key)[it] && it > 150 }
    }

    private val daily = Synth.candles(900, Timeframe.D1, seed = 3, symbol = "DONUSDT")
    private val firedAt = donchianBar(daily, "donchian20_1d")

    private fun scanAt(head: Int, now: Long, after: Long? = null): LiveScan.Scan =
        LiveScan.scan(Timeframe.D1, mapOf("DONUSDT" to daily.head(head)), emptyMap(), after, now)

    private fun just(c: Candles, i: Int) = c.closeTime[i] + 1 + 5_000

    @Test
    fun aSignalOnTheCandleThatJustClosedIsTradeable() {
        val scan = scanAt(firedAt + 1, just(daily, firedAt))
        val hit = scan.found.single { it.key.name == "donchian20_1d" }
        assertEquals(firedAt, hit.barIdx)
        assertEquals(daily.t[firedAt], hit.barTime)
        assertTrue(hit.tradeable)
        assertEquals(0L, hit.listId)
    }

    @Test
    fun aFirstRunLooksAtTheNewestCandleOnly() {
        // The signal is a candle old by the clock, and no cursor says it was handled: it is not even reported.
        val scan = scanAt(firedAt + 1, just(daily, firedAt + 1))
        assertTrue(scan.found.none { it.key.name == "donchian20_1d" && it.barIdx == firedAt })
    }

    @Test
    fun aSignalNoticedAfterItsEntryCandleEndedIsReportedAsMissedNotTradeable() {
        // A cursor says everything up to the candle before it was handled; the phone was off for one more candle.
        val scan = scanAt(firedAt + 1, just(daily, firedAt + 1), after = daily.closeTime[firedAt - 1])
        val hit = scan.found.single { it.key.name == "donchian20_1d" }
        assertEquals(firedAt, hit.barIdx)
        assertFalse(hit.tradeable)
    }

    @Test
    fun theCursorHidesWhatWasAlreadyHandled() {
        val scan = scanAt(firedAt + 1, just(daily, firedAt), after = daily.closeTime[firedAt])
        assertTrue(scan.found.none { it.key.name == "donchian20_1d" })
    }

    @Test
    fun aScanDoesNotLookFurtherBackThanTheLimit() {
        val lastFlagged = Signals.donchian(daily).getValue(Signals.donchian(daily).keys.first { it.name == "donchian20_1d" })
        val flaggedBars = lastFlagged.indices.filter { lastFlagged[it] && it > 150 }
        val farBack = flaggedBars.first()
        val now = just(daily, farBack + 100)
        val scan = LiveScan.scan(Timeframe.D1, mapOf("DONUSDT" to daily.head(farBack + 101)), emptyMap(), daily.closeTime[0], now)
        assertTrue(scan.found.none { it.barIdx == farBack })
        assertTrue(scan.found.all { it.barIdx > farBack + 100 - LiveScan.MAX_LOOKBACK_BARS })
    }

    @Test
    fun signalsThatRankWithinAListCarryTheListAndOnlyRankItsCoins() {
        val panel = Synth.panel(10, 900, Timeframe.D1)
        val full = Signals.xsMomentum(panel)
        val key = full.keys.first { it.family == "xs_momentum" }
        val flags = full.getValue(key)
        // The last Monday on which some coin ranks in the top fifth.
        val bar = flags.values.first().indices.last { i -> flags.values.any { it[i] } }
        val coins = panel.keys.take(9)
        val head = panel.mapValues { it.value.head(bar + 1) }
        val scan = LiveScan.scan(Timeframe.D1, head, mapOf(7L to coins), null, just(panel.values.first(), bar))
        val xs = scan.found.filter { it.key.family == "xs_momentum" }
        assertTrue(xs.isNotEmpty(), "no cross-sectional signal found")
        assertTrue(xs.all { it.listId == 7L && it.symbol in coins })
        // Ranked among the nine, not the ten: the same as running the rule on those nine directly.
        val direct = Signals.xsMomentum(head.filterKeys { it in coins })
        val expected = direct.flatMap { (k, by) -> by.filter { it.value[bar] }.keys.map { k.name to it } }.toSet()
        assertEquals(expected, xs.map { it.key.name to it.symbol }.toSet())
    }

    @Test
    fun everyVariantThatRanIsReportedSoTheRegistryCountsThemAll() {
        val panel = Synth.panel(9, 900, Timeframe.D1)
        val scan = LiveScan.scan(Timeframe.D1, panel, mapOf(1L to panel.keys), null, just(panel.values.first(), 899))
        assertEquals(Signals.compute(panel).keys.map { it.name }.toSet(), scan.variants.map { it.name }.toSet())
    }

    /** Stepping through real hourly candles, each scan finds exactly the signals of the candle that just closed. */
    @Test
    fun scanningRealCandlesBarByBarFindsExactlyWhatTheFullRunFlagsOnThatBar() {
        val tf = Timeframe.H1
        val panel = Golden.panel(tf)
        val coins = panel.keys
        val full = Signals.compute(panel)
        val times = panel.values.first().t
        assertTrue(panel.values.all { it.t.contentEquals(times) }, "golden hourly coins should share a calendar")
        val flaggedBars = times.indices.filter { i -> full.values.any { by -> by.values.any { it[i] } } }
        val steps = (flaggedBars.filter { it > 500 }.let { f -> f.filterIndexed { n, _ -> n % maxOf(1, f.size / 40) == 0 } } +
            (600 until times.size step 97)).distinct().sorted()
        var signalsSeen = 0
        for (i in steps) {
            val head = panel.mapValues { it.value.head(i + 1) }
            val now = times[i] + tf.ms + 5_000
            val scan = LiveScan.scan(tf, head, mapOf(1L to coins), null, now)
            val got = scan.found.map { Triple(it.key.name, it.symbol, it.listId) }.sortedBy { it.toString() }
            val want = full.flatMap { (key, by) ->
                by.filter { it.value[i] }.keys.map { Triple(key.name, it, if (key.family == "xs_momentum") 1L else 0L) }
            }.sortedBy { it.toString() }
            assertEquals(want, got, "bar $i")
            assertTrue(scan.found.all { it.tradeable && it.barIdx == i })
            signalsSeen += got.size
        }
        assertTrue(signalsSeen > 30, "only $signalsSeen signals were stepped through")
    }

    // --- plan and close ------------------------------------------------------------------------

    private val atrOf = { c: Candles -> Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW) }

    private fun found(c: Candles, bar: Int, key: SignalKey = SignalKey("x_1d", "x", emptyMap())) =
        LiveScan.Found(key, c.symbol, 0, bar, c.t[bar], true)

    @Test
    fun aPlanEntersAtTheGivenOpenWithTheBacktestsLevelsAndLimit() {
        val c = daily.head(300)
        val atr = atrOf(c)
        val regime = IntArray(c.size) { 1 }
        val p = assertNotNull(LiveScan.plan(found(c, 250), c, atr, regime, entryOpen = 123.0))
        assertEquals(c.t[250] + Timeframe.D1.ms, p.entryTime)
        assertEquals(c.closeTime[250], p.detectedAt)
        assertEquals(123.0 + 2 * atr[250], p.target)
        assertEquals(123.0 - atr[250], p.stop)
        assertEquals(EngineConfig.timeLimitBars(Timeframe.D1), p.limit)
        assertEquals(p.entryTime + p.limit * Timeframe.D1.ms - 1, p.exitDue)
        assertEquals(1, p.regime)
    }

    @Test
    fun aHeldVariantHasNoTargetOrStopAndItsOwnLimit() {
        val c = Synth.candles(300, Timeframe.H1, 4)
        val key = SignalKey("fade1h_hold24_1h", "big_move_fade", mapOf("hold_bars" to 24.0))
        val p = assertNotNull(LiveScan.plan(found(c, 250, key), c, atrOf(c), IntArray(c.size), 50.0))
        assertNull(p.target)
        assertNull(p.stop)
        assertEquals(24, p.limit)
    }

    @Test
    fun aCoinWithTooLittleHistoryForAnAtrIsNotTraded() {
        val c = daily.head(300)
        assertNull(LiveScan.plan(found(c, 5), c, atrOf(c), IntArray(c.size), 100.0))
    }

    private fun open(c: Candles, bar: Int, entry: Double, atr: DoubleArray, hold: Int? = null) = LiveScan.OpenTrade(
        "x_1d", c.symbol, c.tf, c.t[bar] + c.tf.ms, entry,
        target = if (hold == null) PaperTrading.targetPrice(entry, atr[bar]) else null,
        stop = if (hold == null) PaperTrading.stopPrice(entry, atr[bar]) else null,
        limit = hold ?: EngineConfig.timeLimitBars(c.tf),
    )

    @Test
    fun aClosedTradeCarriesTheBacktestsNumbersAndABaselineDrawnFromTheCandlesThereAre() {
        val c = Synth.candles(700, Timeframe.D1, 9, "ABCUSDT")
        val atr = atrOf(c)
        val regime = IntArray(c.size) { 1 }
        val bar = 400
        val t = open(c, bar, c.open[bar + 1], atr)
        val full = PaperTrading.resolve(c, bar + 1, t.target, t.stop, t.limit)!!
        val head = c.head(full.exitIdx + 1)
        val hAtr = atrOf(head)
        val closed = assertNotNull(LiveScan.close(t, head, hAtr, IntArray(head.size) { 1 }))
        assertEquals(head.closeTime[full.exitIdx], closed.exitTime)
        assertEquals(full.exitPrice, closed.exitPrice)
        assertEquals(full.exitPrice / t.entryPrice - 1, closed.gross)
        assertEquals(closed.gross - EngineConfig.costFor("ABCUSDT"), closed.net)
        assertEquals(full.exitIdx - bar, closed.barsHeld)
        val mean = Scorecard.matchedRandom("x_1d", "ABCUSDT", head, hAtr, IntArray(head.size) { 1 }, intArrayOf(bar), Timeframe.D1,
            EngineConfig.costFor("ABCUSDT"), null, IntArray(0)).mean[0]
        assertFalse(mean.isNaN())
        assertEquals(mean, closed.randomMean)
        assertEquals(closed.net - mean, closed.excess)
    }

    @Test
    fun aTradeStaysOpenWhileNoLevelIsTouchedAndItsTimeIsNotUp() {
        val c = flat(12)
        val atr = DoubleArray(c.size) { 2.0 }
        val t = open(c, 3, 100.0, atr)
        assertNull(LiveScan.close(t, c.head(8), atr.copyOf(8), IntArray(8)))
        assertNull(LiveScan.close(t, c.head(4), atr.copyOf(4), IntArray(4))) // entry candle (4) not closed yet
        assertNotNull(LiveScan.close(t, c, atr, IntArray(c.size)))
    }

    @Test
    fun aHeldTradeIsJudgedAgainstHeldRandomEntries() {
        val c = Synth.candles(700, Timeframe.H1, 12, "HELDUSDT")
        val atr = atrOf(c)
        val regime = IntArray(c.size) { 0 }
        val t = open(c, 400, c.open[401], atr, hold = 24)
        val closed = assertNotNull(LiveScan.close(t, c, atr, regime))
        assertEquals(ExitReason.TIME, closed.reason)
        assertEquals(c.close[401 + 24 - 1], closed.exitPrice)
        val held = Scorecard.matchedRandom("x_1d", "HELDUSDT", c, atr, regime, intArrayOf(400), Timeframe.H1,
            EngineConfig.costFor("HELDUSDT"), hold = 24, horizons = IntArray(0)).mean[0]
        assertEquals(held, closed.randomMean)
    }

    // --- the BTC regime must be current ----------------------------------------------------------

    @Test
    fun theRegimeIsCurrentOnlyWhenBtcDailyReachesTheLastClosedDay() {
        val btc = Synth.candles(300, Timeframe.D1, 5, "BTCUSDT")
        val lastOpen = btc.t[299]
        assertTrue(LiveScan.regimeIsCurrent(btc, lastOpen + Timeframe.D1.ms + 5_000)) // its candle is the one that just closed
        assertFalse(LiveScan.regimeIsCurrent(btc, lastOpen + 2 * Timeframe.D1.ms + 5_000)) // a day has closed since
        assertFalse(LiveScan.regimeIsCurrent(btc, lastOpen + 5_000)) // that candle is still forming
        assertFalse(LiveScan.regimeIsCurrent(null, lastOpen))
    }

    @Test
    fun everyVariantTheEngineProducesHasARealDescription() {
        for (tf in tfs) {
            for (key in Signals.compute(Golden.panel(tf)).keys) {
                val text = VariantLabels.describe(key.name)
                assertTrue(text != key.name && text.length > 12, "${key.name} is described as '$text'")
            }
        }
    }
}
