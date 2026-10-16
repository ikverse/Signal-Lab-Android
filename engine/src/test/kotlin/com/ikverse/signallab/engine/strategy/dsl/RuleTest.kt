package com.ikverse.signallab.engine.strategy.dsl

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Golden
import com.ikverse.signallab.engine.Synth
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.head
import com.ikverse.signallab.engine.strategy.Breakout
import com.ikverse.signallab.engine.strategy.Tier
import com.ikverse.signallab.engine.strategy.TrendTimer
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleTest {
    private val sample = """
        (strategy
          (timeframe 4h)
          (entry (and (> close (highest high 20)) (< (ret 1) 0.15) (> volume (* 1.5 (median volume 30)))))
          (exit (< close (sma close 20)))   ; leave on a close under the average
          (max_bars 30)
          (stop_atr 2.5)
          (target_atr 5)
          (bull_only)
          (ranks 1 40))
    """.trimIndent()

    @Test
    fun aDefinitionReadsIntoItsPartsAndPrintsBackToTheSameDefinition() {
        val d = Rule.parse(sample)
        assertEquals(Timeframe.H4, d.timeframe)
        assertEquals(30, d.maxBars)
        assertEquals(2.5, d.stopAtr)
        assertEquals(5.0, d.targetAtr)
        assertEquals(5.0, CustomStrategy("c", "C", d).exit.targetAtr)
        assertTrue(d.bullOnly)
        assertEquals(1..40, d.ranks)
        assertEquals("(< close (sma close 20))", d.exit.toString())
        assertEquals(d, Rule.parse(d.print()))
        assertEquals(d.print(), Rule.parse(d.print()).print())
    }

    @Test
    fun everyTimeframeSurvivesARoundTrip() {
        for (tf in listOf("1h", "4h", "1d")) {
            val d = Rule.parse("(strategy (timeframe $tf) (entry (> close (sma close 5))) (max_bars 5))")
            assertEquals(tf, d.timeframe.label)
            assertEquals(d, Rule.parse(d.print()), "$tf prints back to itself")
        }
    }

    @Test
    fun theOptionalPartsHaveDefaults() {
        val d = Rule.parse("(strategy (entry (cross_above close (sma close 50))) (max_bars 10))")
        assertEquals(Timeframe.D1, d.timeframe)
        assertNull(d.exit)
        assertNull(d.stopAtr)
        assertNull(d.ranks)
        assertEquals(false, d.bullOnly)
        val s = CustomStrategy("custom_1", "Mine", d)
        assertEquals(Tier.TESTING, s.launchTier)
        assertNull(s.exitSignals(Synth.candles(100, Timeframe.D1, 1)))
    }

    @Test
    fun theBuiltInBreakoutAndTrendTimerWrittenInTheLanguageGiveTheSameFlags() {
        val breakout = CustomStrategy.of("b", "Breakout",
            "(strategy (entry (> close (highest high 50))) (exit (< close (lowest low 20))) (max_bars 60) (stop_atr 3))")
        val trend = CustomStrategy.of("t", "Trend", "(strategy (entry (cross_above close (sma close 50))) (exit (< close (sma close 50))) (max_bars 200))")
        val b = Breakout()
        val t = TrendTimer()
        assertEquals(b.exit.maxBars, breakout.exit.maxBars)
        assertEquals(b.exit.safetyStopAtr, breakout.exit.safetyStopAtr)
        for (c in Golden.panel(Timeframe.D1).values) {
            assertContentEquals(b.entries(c), breakout.entries(c), "breakout entries on ${c.symbol}")
            assertContentEquals(b.exitSignals(c), breakout.exitSignals(c), "breakout exits on ${c.symbol}")
            assertContentEquals(t.entries(c), trend.entries(c), "trend entries on ${c.symbol}")
            assertContentEquals(t.exitSignals(c), trend.exitSignals(c), "trend exits on ${c.symbol}")
            assertTrue(breakout.entries(c).any { it }, "breakout never fired on ${c.symbol}")
        }
    }

    @Test
    fun aConditionThatHoldsForSeveralCandlesEntersOnTheFirstOnly() {
        val c = Synth.candles(400, Timeframe.D1, 3)
        val s = CustomStrategy.of("x", "Above", "(strategy (entry (> close (sma close 10))) (max_bars 5))")
        val raw = Rule.evaluate(Rule.parseCondition("(> close (sma close 10))"), c)
        val e = s.entries(c)
        for (i in e.indices) assertEquals(raw[i] && (i == 0 || !raw[i - 1]), e[i], "candle $i")
        assertTrue(raw.count { it } > e.count { it })
    }

    @Test
    fun theOperationsComputeWhatTheirNamesSay() {
        val c = Synth.candles(200, Timeframe.D1, 5)
        fun num(text: String) = Rule.evaluateNumber(Rule.parseCondition("(> $text 0)").let { (it as Node.Call).args[0] }, c)
        val lag = num("(lag close 3)")
        for (i in 3 until c.size) assertEquals(c.close[i - 3], lag[i])
        assertTrue(lag[2].isNaN())
        val med = num("(median close 5)")
        for (i in 4 until c.size) assertEquals((i - 4..i).map { c.close[it] }.sorted()[2], med[i])
        val med4 = num("(median close 4)")
        val s = (6..9).map { c.close[it] }.sorted()
        assertEquals((s[1] + s[2]) / 2, med4[9], 1e-12)
        val q = num("(/ close 0)")
        assertTrue(q.all { it.isNaN() }, "division by zero gives no value rather than infinity")
        val held = Rule.evaluate(Rule.parseCondition("(held (> close (lag close 1)) 3)"), c)
        for (i in 3 until c.size) assertEquals((0..2).all { c.close[i - it] > c.close[i - it - 1] }, held[i], "held @$i")
        val ago = Rule.evaluate(Rule.parseCondition("(ago (> close (lag close 1)) 2)"), c)
        for (i in 3 until c.size) assertEquals(c.close[i - 2] > c.close[i - 3], ago[i], "ago @$i")
        val below = Rule.evaluate(Rule.parseCondition("(cross_below close (sma close 10))"), c)
        val avg = num("(sma close 10)")
        for (i in 10 until c.size) assertEquals(c.close[i] < avg[i] && c.close[i - 1] >= avg[i - 1], below[i], "cross_below @$i")
    }

    @Test
    fun aDefinitionThatIsNotValidSaysWhatIsWrong() {
        val cases = listOf(
            "" to "empty",
            "(entry (> close 1))" to "starts with (strategy",
            "(strategy (max_bars 5))" to "needs one (entry",
            "(strategy (entry (> close 1)))" to "max_bars",
            "(strategy (entry (> close 1)) (max_bars 0))" to "from 1 to 500",
            "(strategy (entry (> close 1)) (max_bars 5) (timeframe 15m))" to "not allowed",
            "(strategy (entry (> close 1)) (max_bars 5) (colour red))" to "Unknown part",
            "(strategy (entry (> close 1)) (entry (< close 1)) (max_bars 5))" to "given twice",
            "(strategy (entry (sma close 20)) (max_bars 5))" to "must be a true-or-false condition",
            "(strategy (entry (> close (sma close 0))) (max_bars 5))" to "a window from 1 to 500",
            "(strategy (entry (> close (sma close 2.5))) (max_bars 5))" to "whole number",
            "(strategy (entry (> close (lag close -1))) (max_bars 5))" to "whole number",
            "(strategy (entry (> close (sma close 20 3))) (max_bars 5))" to "takes 2 arguments",
            "(strategy (entry (> close (future close 1))) (max_bars 5))" to "Unknown operation",
            "(strategy (entry (> price 1)) (max_bars 5))" to "Unknown name price",
            "(strategy (entry (> (close) 1)) (max_bars 5))" to "takes nothing",
            "(strategy (entry (> sma 1)) (max_bars 5))" to "needs arguments",
            "(strategy (entry (and (> close 1))) (max_bars 5))" to "at least 2",
            "(strategy (entry (> close 1)) (max_bars 5) (stop_atr 0))" to "between 0 and 20",
            "(strategy (entry (> close 1)) (max_bars 5) (target_atr -1))" to "between 0 and 50",
            "(strategy (entry (> close 1)) (max_bars 5) (ranks 30 10))" to "1 ≤ from ≤ to",
            "(strategy (entry (> close 1)) (max_bars 5)" to "not closed",
            "(strategy (entry (> close 1)) (max_bars 5)))" to "after the end",
        )
        for ((text, words) in cases) {
            val e = assertFailsWith<RuleError>(text) { Rule.parse(text) }
            assertTrue(e.message!!.contains(words), "\"$text\" said \"${e.message}\", expected it to mention \"$words\"")
        }
        val huge = (1..70).fold("(> close 1)") { acc, _ -> "(and $acc (> close 1))" }
        assertFailsWith<RuleError> { Rule.parse("(strategy (entry $huge) (max_bars 5))") }
    }

    @Test
    fun theReferenceListsEveryOperation() {
        val ref = Ops.reference
        for (name in Ops.all.keys) assertTrue(ref.lines().any { it.startsWith(name) || it.startsWith("($name ") }, "$name missing from the reference")
        assertTrue(ref.contains("(sma x n)"), ref)
        assertTrue(ref.contains("(> a b)"), ref)
    }

    // Random rules: whatever can be written in the language must not look ahead, and must print back to itself.

    private class Gen(seed: Long) {
        val r = Random(seed)
        private fun win() = Node.Num((1 + r.nextInt(40)).toDouble())

        fun num(depth: Int): Node {
            if (depth <= 0 || r.nextInt(4) == 0) {
                return when (r.nextInt(7)) {
                    0 -> Node.Name("open")
                    1 -> Node.Name("high")
                    2 -> Node.Name("low")
                    3 -> Node.Name("volume")
                    4 -> Node.Num(Math.round(r.nextDouble(-2.0, 2.0) * 1000) / 1000.0)
                    else -> Node.Name("close")
                }
            }
            return when (r.nextInt(13)) {
                0 -> Node.Call("sma", listOf(num(depth - 1), win()))
                1 -> Node.Call("ema", listOf(num(depth - 1), win()))
                2 -> Node.Call("highest", listOf(num(depth - 1), win()))
                3 -> Node.Call("lowest", listOf(num(depth - 1), win()))
                4 -> Node.Call("median", listOf(num(depth - 1), win()))
                5 -> Node.Call("std", listOf(num(depth - 1), win()))
                6 -> Node.Call("lag", listOf(num(depth - 1), win()))
                7 -> Node.Call("atr", listOf(win()))
                8 -> Node.Call("rsi", listOf(win()))
                9 -> Node.Call("ret", listOf(win()))
                else -> Node.Call(listOf("+", "-", "*", "/")[r.nextInt(4)], listOf(num(depth - 1), num(depth - 1)))
            }
        }

        fun cond(depth: Int): Node {
            if (depth <= 0 || r.nextInt(3) == 0) {
                val op = listOf(">", "<", ">=", "<=", "cross_above", "cross_below")[r.nextInt(6)]
                return Node.Call(op, listOf(num(2), num(2)))
            }
            return when (r.nextInt(5)) {
                0 -> Node.Call("and", List(2 + r.nextInt(2)) { cond(depth - 1) })
                1 -> Node.Call("or", List(2 + r.nextInt(2)) { cond(depth - 1) })
                2 -> Node.Call("not", listOf(cond(depth - 1)))
                3 -> Node.Call("held", listOf(cond(depth - 1), win()))
                else -> Node.Call("ago", listOf(cond(depth - 1), win()))
            }
        }
    }

    private fun scrambledAfter(c: Candles, k: Int): Candles {
        val n = c.size
        fun mix(a: DoubleArray) = DoubleArray(n) { i -> if (i < k) a[i] else a[n - 1 - (i - k)] * (1.0 + 0.3 * ((i * 7919) % 13 - 6) / 6.0) }
        val open = mix(c.open)
        val close = mix(c.close)
        val high = DoubleArray(n) { i -> if (i < k) c.high[i] else maxOf(open[i], close[i]) * 1.01 }
        val low = DoubleArray(n) { i -> if (i < k) c.low[i] else minOf(open[i], close[i]) * 0.99 }
        return Candles(c.symbol, c.tf, c.t, open, high, low, close, mix(c.volume), c.closeTime)
    }

    @Test
    fun noRandomRuleLooksAheadAndEveryOnePrintsBackToItself() {
        val g = Gen(20261016)
        val candles = (1L..3L).map { Synth.candles(500, Timeframe.D1, it) }
        var fired = 0
        repeat(300) { n ->
            val rule = g.cond(3)
            val text = rule.toString()
            assertEquals(rule, Rule.parseCondition(text), "rule $n does not print back to itself: $text")
            for (c in candles) {
                val full = Rule.evaluate(rule, c)
                fired += full.count { it }
                for (k in listOf(60, 170, 333, c.size - 3)) {
                    val cut = Rule.evaluate(rule, c.head(k))
                    val scrambled = Rule.evaluate(rule, scrambledAfter(c, k))
                    for (i in 0 until k) {
                        assertEquals(full[i], cut[i], "rule $n looks ahead (cut at $k, candle $i): $text")
                        assertEquals(full[i], scrambled[i], "rule $n looks ahead (scrambled after $k, candle $i): $text")
                    }
                }
            }
        }
        assertTrue(fired > 10_000, "the random rules fired only $fired times, too few to test anything")
    }
}
