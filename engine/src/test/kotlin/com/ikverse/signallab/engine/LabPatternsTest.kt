package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lab patterns: they flag only what the candles up to each one show, they mean what they say, and they are judged on live trades only. */
class LabPatternsTest {
    private fun op(block: LabBlock, n: Int = 0) = LabOperand(block, n)
    private fun num(v: Double) = LabOperand(LabBlock.NUMBER, value = v)
    private fun pattern(vararg c: LabCondition, tfs: Set<Timeframe> = setOf(Timeframe.D1), exit: LabExit = LabExit.Trail, id: Long = 7) =
        LabPattern(id, tfs, c.toList(), exit)

    private fun sameUpTo(want: DoubleArray, got: DoubleArray): Boolean =
        got.indices.all { i -> (want[i].isNaN() && got[i].isNaN()) || want[i] == got[i] }

    private fun candles(close: DoubleArray, volume: DoubleArray = DoubleArray(close.size) { 1.0 }, tf: Timeframe = Timeframe.D1): Candles {
        val t = LongArray(close.size) { Synth.START_MS + it * tf.ms }
        return Candles("TESTUSDT", tf, t, close, close, close, close, volume, LongArray(close.size) { t[it] + tf.ms - 1 })
    }

    // --- nothing looks ahead

    @Test
    fun `every block gives the same values up to a candle whether or not later candles exist`() {
        val c = Synth.candles(600, Timeframe.H1, seed = 3)
        for (b in LabBlock.entries) {
            val o = if (b == LabBlock.NUMBER) num(1.5) else op(b, 14)
            val full = LabPatterns.series(c, o)
            for (k in listOf(1, 13, 14, 15, 16, 100, 333, 599)) assertTrue(sameUpTo(full, LabPatterns.series(c.head(k), o)), "${b.id} cut at $k")
        }
    }

    /** A random pattern from the whole vocabulary, the same one every run. */
    private fun randomPattern(seed: Long): LabPattern {
        fun pick(i: Long, n: Int) = (Rng.unit(seed, i, 0) * n).toInt().coerceAtMost(n - 1)
        val blocks = LabBlock.entries.filter { it != LabBlock.NUMBER }
        fun operand(i: Long): LabOperand {
            val b = blocks[pick(i, blocks.size)]
            return LabOperand(b, if (b.takesN) 2 + pick(i + 1, 59) else 0)
        }
        val numbers = doubleArrayOf(0.0, 1.0, 30.0, 50.0, 70.0, 0.02, -0.02, 1.5)
        val conditions = (0 until 1 + pick(1, 3)).map { j ->
            val base = 10L + j * 10
            val left = operand(base)
            val right = if (pick(base + 3, 3) == 0) num(numbers[pick(base + 4, numbers.size)]) else operand(base + 5)
            LabCondition(left, LabCompare.entries[pick(base + 7, 4)], right)
        }
        return pattern(*conditions.toTypedArray(), tfs = setOf(Timeframe.H1))
    }

    @Test
    fun `hundreds of random patterns flag the same candles when history is cut short`() {
        val c = Synth.candles(1_200, Timeframe.H1, seed = 11)
        var fired = 0
        for (s in 1L..300L) {
            val p = randomPattern(s)
            val full = LabPatterns.flags(c, p)
            if (full.any { it }) fired++
            for (k in listOf(50, 400, 777, 1_100)) {
                val cut = LabPatterns.flags(c.head(k), p)
                assertTrue(full.copyOf(k).contentEquals(cut), "pattern ${p.summary} cut at $k")
            }
        }
        // Random conditions rarely all hold at once (about a quarter of these patterns ever fire), which is still dozens of real checks.
        assertTrue(fired > 50, "only $fired of 300 random patterns ever fired; the test would prove little")
    }

    // --- they mean what they say

    @Test
    fun `above, below and the two crossings fire where they should`() {
        val c = candles(doubleArrayOf(1.0, 2.0, 3.0, 2.0, 1.0, 2.0, 3.0))
        val close = op(LabBlock.CLOSE)
        fun fired(compare: LabCompare) = LabPatterns.flags(c, pattern(LabCondition(close, compare, num(2.5)))).withIndex().filter { it.value }.map { it.index }
        assertEquals(listOf(2, 6), fired(LabCompare.ABOVE), "the first candle of each run above")
        assertEquals(listOf(0, 3), fired(LabCompare.BELOW))
        assertEquals(listOf(2, 6), fired(LabCompare.CROSSES_ABOVE))
        assertEquals(listOf(3), fired(LabCompare.CROSSES_BELOW), "the first candle cannot be a crossing")
    }

    @Test
    fun `nothing fires before a block has the history it needs`() {
        val c = candles(DoubleArray(30) { 100.0 + it })
        val flags = LabPatterns.flags(c, pattern(LabCondition(op(LabBlock.CLOSE), LabCompare.ABOVE, op(LabBlock.SMA, 10))))
        assertEquals(9, flags.indexOfFirst { it }, "the 10-candle average exists from the tenth candle")
    }

    @Test
    fun `the two new indicators compute what they say`() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val ema = Indicators.ema(x, 3)
        assertTrue(ema[0].isNaN() && ema[1].isNaN())
        assertEquals(2.0, ema[2], "seeded with the plain average of the first three")
        assertEquals(3.0, ema[3], 1e-12)
        assertEquals(4.0, ema[4], 1e-12)
        val low = Indicators.rollingMinPrior(doubleArrayOf(5.0, 3.0, 4.0, 1.0, 2.0), 2)
        assertTrue(low[0].isNaN() && low[1].isNaN())
        assertEquals(listOf(3.0, 3.0, 1.0), low.drop(2), "the lowest of the two before, never the candle itself")
    }

    @Test
    fun `the volume ratio compares a candle with the ones before it`() {
        val c = candles(DoubleArray(6) { 1.0 }, volume = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 4.0, 1.0))
        val ratio = LabPatterns.series(c, op(LabBlock.VOLUME_RATIO, 4))
        assertTrue(ratio[3].isNaN(), "four candles before are needed")
        assertEquals(4.0, ratio[4])
        assertEquals(1.0 / ((1.0 + 1.0 + 1.0 + 4.0) / 4), ratio[5])
    }

    @Test
    fun `a breakout and a trend written as lab patterns flag exactly what the built-in ones do`() {
        val panel = Golden.panel(Timeframe.D1)
        for ((sym, c) in panel) {
            val donchian = Signals.donchian(c).entries.single { it.key.name == "donchian20_1d" }.value
            val lab = LabPatterns.flags(c, pattern(LabCondition(op(LabBlock.CLOSE), LabCompare.ABOVE, op(LabBlock.HIGHEST, 20))))
            assertTrue(donchian.contentEquals(lab), "breakout on $sym")
            val trend = Signals.trendState(c).entries.single { it.key.name == "trend_ma20_1d" }.value
            val crossing = LabPatterns.flags(c, pattern(LabCondition(op(LabBlock.CLOSE), LabCompare.CROSSES_ABOVE, op(LabBlock.SMA, 20))))
            assertTrue(trend.contentEquals(crossing), "trend on $sym")
        }
    }

    // --- what may be run

    @Test
    fun `a pattern outside the rules is refused with the reason`() {
        val ok = LabCondition(op(LabBlock.RSI, 14), LabCompare.CROSSES_ABOVE, num(30.0))
        assertNull(LabPatterns.problem(pattern(ok)))
        assertNotNull(LabPatterns.problem(pattern(ok, tfs = emptySet())))
        assertNotNull(LabPatterns.problem(pattern()))
        assertTrue(LabPatterns.problem(pattern(ok, ok, ok, ok, ok))!!.contains("at most ${LabPatterns.MAX_CONDITIONS}"))
        assertTrue(LabPatterns.problem(pattern(LabCondition(op(LabBlock.SMA, 1), LabCompare.ABOVE, num(1.0))))!!.contains("from 2 to 200"))
        assertNotNull(LabPatterns.problem(pattern(LabCondition(op(LabBlock.SMA, 201), LabCompare.ABOVE, num(1.0)))))
        assertTrue(LabPatterns.problem(pattern(LabCondition(num(1.0), LabCompare.ABOVE, num(2.0))))!!.contains("two numbers"))
        assertTrue(LabPatterns.problem(pattern(LabCondition(op(LabBlock.CLOSE), LabCompare.ABOVE, op(LabBlock.CLOSE))))!!.contains("itself"))
        assertNotNull(LabPatterns.problem(pattern(ok, exit = LabExit.Hold(0))))
        assertNotNull(LabPatterns.problem(pattern(ok, exit = LabExit.Hold(201))))
    }

    @Test
    fun `a lab pattern is named by its number and chart, exits as it asks, and is always forward-only`() {
        val p = pattern(LabCondition(op(LabBlock.CLOSE), LabCompare.ABOVE, op(LabBlock.EMA, 50)), id = 3)
        assertEquals("lab3_1h", LabPatterns.key(p, Timeframe.H1).name)
        assertEquals(ExitMode.TRAIL, ExitPolicy.modeOf(LabPatterns.key(p, Timeframe.H1)))
        assertEquals(ExitMode.LEARNED, ExitPolicy.modeOf(LabPatterns.key(p.copy(exit = LabExit.Learned), Timeframe.H1)))
        val held = LabPatterns.key(p.copy(exit = LabExit.Hold(12)), Timeframe.H1)
        assertEquals(ExitMode.HELD, ExitPolicy.modeOf(held))
        assertEquals(12, held.holdBars)
        assertEquals(3L, LabPatterns.idOf("lab3_1h"))
        assertNull(LabPatterns.idOf("lab3_2h"))
        assertNull(LabPatterns.idOf("donchian20_1h"))
        assertTrue(EngineConfig.isForwardOnly("lab3_1h"))
        assertTrue(EngineConfig.isForwardOnly("fade1h_hold24_1h"))
        assertFalse(EngineConfig.isForwardOnly("donchian20_1h"))
        val judged = Statistics.judge(listOf(Statistics.Judged("lab3_1h", 500, 6.0, 1e-12), Statistics.Judged("live:lab3_1h", 500, 6.0, 1e-12)), 2)
        assertEquals(EngineConfig.FORWARD_ONLY_VERDICT, judged[0].verdict, "history cannot promote it")
        assertEquals("Edge", judged[1].verdict, "its live trades can")
    }

    @Test
    fun `a sentence and the written form say what the pattern is`() {
        val p = pattern(
            LabCondition(op(LabBlock.RSI, 14), LabCompare.CROSSES_ABOVE, num(30.0)),
            LabCondition(op(LabBlock.VOLUME_RATIO, 20), LabCompare.ABOVE, num(1.5)),
            tfs = setOf(Timeframe.H4, Timeframe.H1), exit = LabExit.Hold(12),
        )
        assertEquals("RSI(14) crosses above 30, and volume ratio(20) above 1.5 on 1h and 4h; held 12 candles", p.summary)
        assertEquals("rsi(14)", p.conditions[0].left.text)
        assertEquals("-0.05", num(-0.05).text)
        assertEquals("hold(12)", p.exit.text)
    }

    // --- in the live scan

    @Test
    fun `the live scan runs lab patterns on the charts they name, beside the built-in ones`() {
        val c = Synth.candles(400, Timeframe.H1, seed = 5)
        val p = pattern(LabCondition(op(LabBlock.CLOSE), LabCompare.ABOVE, num(0.0)), tfs = setOf(Timeframe.H1), id = 9)
        val other = pattern(LabCondition(op(LabBlock.CLOSE), LabCompare.ABOVE, num(0.0)), tfs = setOf(Timeframe.H4), id = 10)
        val now = c.closeTime.last() + 1
        val scan = LiveScan.scan(Timeframe.H1, mapOf("TESTUSDT" to c), emptyMap(), after = null, now = now, lab = listOf(p, other))
        val names = scan.variants.map { it.name }
        assertTrue("lab9_1h" in names)
        assertFalse("lab10_1h" in names, "a pattern for 4h does not run on 1h")
        assertTrue(names.any { it.startsWith("donchian") }, "the built-in patterns still run")
    }
}
