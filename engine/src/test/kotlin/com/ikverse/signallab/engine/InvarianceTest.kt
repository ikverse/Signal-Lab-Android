package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two properties that make the scorecard honest: a signal never uses data from after the moment
 * it claims to know it, and it never changes when more data arrives.
 */
class InvarianceTest {
    private fun truncate(panel: Map<String, Candles>, cutTime: Long): Map<String, Candles> =
        panel.mapNotNull { (sym, c) ->
            val k = upperBound(c.t, cutTime)
            if (k > 0) sym to c.head(k) else null
        }.toMap()

    /**
     * Recomputes [compute] on history cut at [cuts] times; every flag up to each cut must equal the
     * full-history run. Returns the mismatches, so a test can assert none and a mutation test can
     * assert the check really catches a cheat.
     */
    private fun repaintMismatches(
        compute: (Map<String, Candles>) -> Map<SignalKey, Map<String, BooleanArray>>,
        panel: Map<String, Candles>,
        cuts: Int,
    ): List<String> {
        val full = compute(panel)
        val times = panel.values.first().t
        val bad = ArrayList<String>()
        for (n in 1..cuts) {
            val cut = times[(times.size * (0.1 + 0.85 * Rng.unit(77, n.toLong(), 0))).toInt()]
            val part = compute(truncate(panel, cut))
            for ((key, bySymbol) in part) {
                for ((sym, flags) in bySymbol) {
                    val want = full.getValue(key).getValue(sym).copyOf(flags.size)
                    if (!want.contentEquals(flags)) bad.add("${key.name} $sym cut at $cut")
                }
            }
        }
        return bad
    }

    private fun hasSignals(panel: Map<String, Candles>) =
        Signals.compute(panel).values.any { by -> by.values.any { f -> f.any { it } } }

    @Test
    fun syntheticDailyIncludingCrossSectionalMomentum() {
        val panel = Synth.panel(9, 900, Timeframe.D1)
        assertTrue(hasSignals(panel), "test data produced no signals")
        assertEquals(emptyList(), repaintMismatches(Signals::compute, panel, 12))
        assertTrue(Signals.compute(panel).keys.any { it.family == "xs_momentum" })
    }

    @Test
    fun syntheticHourlyIncludingIntradaySignals() {
        val panel = Synth.panel(2, 9500, Timeframe.H1)
        assertTrue(hasSignals(panel))
        assertEquals(emptyList(), repaintMismatches(Signals::compute, panel, 4))
    }

    @Test
    fun syntheticFourHour() {
        val panel = Synth.panel(3, 3000, Timeframe.H4)
        assertEquals(emptyList(), repaintMismatches(Signals::compute, panel, 5))
    }

    @Test
    fun realCandlesOnEveryTimeframe() {
        for ((tf, cuts) in listOf(Timeframe.D1 to 10, Timeframe.H4 to 6, Timeframe.H1 to 4)) {
            assertEquals(emptyList(), repaintMismatches(Signals::compute, Golden.panel(tf), cuts), tf.label)
        }
    }

    @Test
    fun theRepaintCheckCatchesASignalThatPeeksAhead() {
        val cheating = { panel: Map<String, Candles> ->
            val honest = Signals.compute(panel)
            val peek = panel.mapValues { (_, c) ->
                BooleanArray(c.size) { i -> i + 1 < c.size && c.close[i + 1] > c.close[i] }
            }
            honest + (SignalKey("cheat", "cheat", emptyMap()) to peek)
        }
        val bad = repaintMismatches(cheating, Synth.panel(4, 600, Timeframe.D1), 5)
        assertTrue(bad.any { it.startsWith("cheat") }, "the cheat went unnoticed")
    }

    @Test
    fun changingTheFutureNeverChangesPastSignals() {
        for ((tf, coins, n) in listOf(Triple(Timeframe.D1, 9, 900), Triple(Timeframe.H1, 2, 9500))) {
            val panel = Synth.panel(coins, n, tf)
            val k = (n * 0.7).toInt()
            val cut = panel.values.first().t[k]
            val scrambled = panel.mapValues { (sym, c) ->
                val open = c.open.copyOf()
                val high = c.high.copyOf()
                val low = c.low.copyOf()
                val close = c.close.copyOf()
                for (i in k + 1 until c.size) {
                    val f = 0.2 + 4.8 * Rng.unit(Rng.seedOf(sym), i.toLong(), 9)
                    open[i] *= f; close[i] *= f; high[i] *= f * 1.5; low[i] *= f * 0.5
                }
                Candles(sym, tf, c.t, open, high, low, close, c.volume, c.closeTime)
            }
            val a = Signals.compute(panel)
            val b = Signals.compute(scrambled)
            for ((key, bySymbol) in a) {
                for ((sym, flags) in bySymbol) {
                    val upToCut = flags.copyOf(k + 1)
                    assertTrue(upToCut.contentEquals(b.getValue(key).getValue(sym).copyOf(k + 1)),
                        "${key.name} $sym changed before $cut (${tf.label})")
                }
            }
        }
    }
}
