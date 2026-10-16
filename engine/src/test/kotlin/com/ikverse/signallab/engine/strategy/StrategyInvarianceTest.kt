package com.ikverse.signallab.engine.strategy

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Golden
import com.ikverse.signallab.engine.Synth
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.head
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No strategy may look ahead. For every registered strategy: its entries and exit signals on history cut at candle k
 * equal the full history's up to k, and scrambling every candle after k changes nothing up to k.
 */
class StrategyInvarianceTest {
    private fun flags(s: Strategy, c: Candles): List<BooleanArray> = listOfNotNull(s.entries(c), s.exitSignals(c))

    /** Candles that agree with [c] up to [k] and are scrambled after it. */
    private fun scrambledAfter(c: Candles, k: Int): Candles {
        val n = c.size
        fun mix(a: DoubleArray) = DoubleArray(n) { i -> if (i < k) a[i] else a[n - 1 - (i - k)] * (1.0 + 0.3 * ((i * 7919) % 13 - 6) / 6.0) }
        val open = mix(c.open)
        val close = mix(c.close)
        val high = DoubleArray(n) { i -> if (i < k) c.high[i] else maxOf(open[i], close[i]) * 1.01 }
        val low = DoubleArray(n) { i -> if (i < k) c.low[i] else minOf(open[i], close[i]) * 0.99 }
        return Candles(c.symbol, c.tf, c.t, open, high, low, close, mix(c.volume), c.closeTime)
    }

    /** Positions where a cut history disagrees with the full one, as "kind@index" strings. */
    private fun mismatches(s: Strategy, c: Candles, cuts: List<Int>): List<String> {
        val full = flags(s, c)
        val out = ArrayList<String>()
        for (k in cuts) {
            for ((which, cut) in flags(s, c.head(k)).withIndex()) {
                for (i in 0 until k) if (cut[i] != full[which][i]) out.add("cut$k/$which@$i")
            }
            for ((which, scrambled) in flags(s, scrambledAfter(c, k)).withIndex()) {
                for (i in 0 until k) if (scrambled[i] != full[which][i]) out.add("scramble$k/$which@$i")
            }
        }
        return out
    }

    private fun cutsFor(c: Candles) = listOf(c.size / 5, c.size / 3, c.size / 2, (c.size * 3) / 4, c.size - 7)

    @Test
    fun everyStrategyGivesTheSameAnswerOnCutHistoryForSyntheticCandles() {
        for (s in Builtins.all) {
            for (seed in 1L..4L) {
                val c = Synth.candles(1500, s.timeframe, seed)
                assertEquals(emptyList(), mismatches(s, c, cutsFor(c)), "${s.id} looks ahead on synthetic seed $seed")
            }
        }
    }

    @Test
    fun everyStrategyGivesTheSameAnswerOnCutHistoryForRealCandles() {
        for (s in Builtins.all) {
            val panel = Golden.panel(s.timeframe)
            for (c in panel.values) assertEquals(emptyList(), mismatches(s, c, cutsFor(c)), "${s.id} looks ahead on ${c.symbol}")
            val fired = s.entriesAcross(panel).values.sumOf { f -> f.count { it } }
            assertTrue(fired > 10, "${s.id} fired only $fired times on the real candles, too few to test")
        }
    }

    /** A ranking compares coins at one moment: cutting every coin at the same time must not change any flag before it. */
    @Test
    fun aRankingGivesTheSameAnswerWhenEveryCoinIsCutAtTheSameMoment() {
        for (s in Builtins.all.filter { it.crossSectional }) {
            val panel = Golden.panel(s.timeframe)
            val full = s.entriesAcross(panel)
            val times = panel.values.flatMap { it.t.toList() }.sorted()
            for (frac in listOf(0.3, 0.5, 0.7, 0.9)) {
                val cut = times[(times.size * frac).toInt()]
                val head = panel.mapValues { (_, c) -> c.head(c.t.count { it <= cut }) }.filterValues { it.size > 0 }
                for ((sym, flags) in s.entriesAcross(head)) {
                    for (i in flags.indices) assertEquals(full.getValue(sym)[i], flags[i], "${s.id} $sym @$i cut at $cut")
                }
            }
            assertTrue(full.values.sumOf { f -> f.count { it } } > 20, "${s.id} hardly fired")
        }
    }

    @Test
    fun theCheckCatchesAStrategyThatPeeksAtTheNextCandle() {
        val peeking = object : Strategy {
            override val id = "peek_1d"
            override val name = "Peek"
            override val timeframe = Timeframe.D1
            override val launchTier = Tier.TESTING
            override val bullOnly = false
            override val exit = ExitPlan(5)
            override fun entries(c: Candles) = BooleanArray(c.size) { i -> i + 1 < c.size && c.close[i + 1] > c.close[i] }
        }
        val c = Synth.candles(600, Timeframe.D1, 9)
        assertTrue(mismatches(peeking, c, cutsFor(c)).isNotEmpty(), "a strategy reading tomorrow's close went unnoticed")
    }

    @Test
    fun everyStrategyHasAUniqueIdNamingItsChart() {
        val ids = Builtins.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate strategy ids")
        for (s in Builtins.all) assertTrue(s.id.endsWith("_" + s.timeframe.label), "${s.id} does not end with its chart")
    }
}
