package com.ikverse.signallab.engine

/**
 * A counter-based random source (SplitMix64): every draw is a pure function of a seed and a few
 * counters, so a draw never depends on what was drawn before it or in what order trades were
 * processed. That is what makes a backtest and a live run, or a rerun on another phone, agree.
 */
object Rng {
    private const val GAMMA = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

    /** SplitMix64's output function. */
    fun mix(z0: Long): Long {
        var z = z0 + GAMMA
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** A stable 64-bit hash of text (FNV-1a), for seeding by variant and coin name. */
    fun seedOf(vararg parts: String): Long {
        var h = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
        for (p in parts) {
            for (ch in p) {
                h = (h xor ch.code.toLong()) * 0x100000001b3L
            }
            h = (h xor 0x1fL) * 0x100000001b3L // a separator, so ("ab","c") differs from ("a","bc")
        }
        return mix(h)
    }

    /** A uniform value in [0, 1) from a seed and two counters. */
    fun unit(seed: Long, a: Long, b: Long): Double =
        (mix(mix(seed + a) + b) ushr 11).toDouble() / (1L shl 53).toDouble()

    /** An integer in [lo, hi) from a seed and two counters. */
    fun intIn(seed: Long, a: Long, b: Long, lo: Int, hi: Int): Int {
        require(hi > lo) { "empty range [$lo, $hi)" }
        val v = lo + (unit(seed, a, b) * (hi - lo)).toInt()
        return if (v >= hi) hi - 1 else v
    }
}
