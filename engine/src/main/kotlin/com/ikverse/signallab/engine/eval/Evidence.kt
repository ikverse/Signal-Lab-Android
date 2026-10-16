package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Rng
import com.ikverse.signallab.engine.Statistics
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** A series of per-call results summarised the way the evidence tests need them. */
class Moments(val n: Int, val mean: Double, val sd: Double, val skew: Double, val kurtosis: Double) {
    /** Mean over standard deviation, per call; NaN when it cannot be said. */
    val sharpe: Double get() = if (n < 2 || !(sd > 0)) Double.NaN else mean / sd

    companion object {
        fun of(x: DoubleArray): Moments {
            val n = x.size
            if (n == 0) return Moments(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
            val m = x.average()
            var m2 = 0.0
            var m3 = 0.0
            var m4 = 0.0
            for (v in x) {
                val d = v - m
                m2 += d * d
                m3 += d * d * d
                m4 += d * d * d * d
            }
            m2 /= n
            m3 /= n
            m4 /= n
            val sd = sqrt(m2 * n / maxOf(1, n - 1))
            val skew = if (m2 > 0) m3 / m2.pow(1.5) else 0.0
            val kurt = if (m2 > 0) m4 / (m2 * m2) else 3.0
            return Moments(n, m, sd, skew, kurt)
        }
    }
}

/**
 * How sure the evidence lets Signal Lab be, in closed forms cheap enough for a phone (docs/strategy-research.md §4).
 *
 * The probabilistic Sharpe ratio, the deflated Sharpe ratio and the minimum track record length follow Bailey and López de
 * Prado (2014): they allow for few observations and for results that are skewed or fat-tailed, as crypto calls are. The
 * deflated version raises the bar to the best Sharpe ratio expected by luck alone among every strategy ever tried.
 */
object Evidence {
    /** Euler–Mascheroni constant. */
    private const val EULER = 0.5772156649015329

    fun normalCdf(z: Double): Double = 1.0 - Statistics.normalSurvival(z)

    /** The inverse of [normalCdf] (Acklam's rational approximation, relative error under 1.2e-9). */
    fun normalQuantile(p: Double): Double {
        require(p > 0 && p < 1) { "p must be in (0, 1)" }
        val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
        val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
        val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
        val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
        val low = 0.02425
        return when {
            p < low -> {
                val q = sqrt(-2 * ln(p))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
            p <= 1 - low -> {
                val q = p - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
            }
            else -> {
                val q = sqrt(-2 * ln(1 - p))
                -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
        }
    }

    /** The spread of a Sharpe ratio estimate's error, per unit, given its skew and kurtosis. */
    private fun spread(sr: Double, skew: Double, kurt: Double): Double = 1 - skew * sr + (kurt - 1) / 4 * sr * sr

    /** The probability that the true Sharpe ratio is above [benchmark], from [m]. NaN with fewer than three results. */
    fun probabilisticSharpe(m: Moments, benchmark: Double = 0.0): Double {
        val sr = m.sharpe
        if (m.n < 3 || sr.isNaN()) return Double.NaN
        val v = spread(sr, m.skew, m.kurtosis)
        if (!(v > 0)) return Double.NaN
        return normalCdf((sr - benchmark) * sqrt((m.n - 1).toDouble()) / sqrt(v))
    }

    /**
     * The Sharpe ratio the best of [trials] strategies would reach by luck alone, when each is measured over [n] results and
     * none has any skill: the bar the deflated Sharpe ratio sets.
     */
    fun expectedMaxSharpe(trials: Int, n: Int): Double {
        if (trials < 2 || n < 2) return 0.0
        val sd = sqrt(1.0 / (n - 1))
        return sd * ((1 - EULER) * normalQuantile(1 - 1.0 / trials) + EULER * normalQuantile(1 - 1.0 / (trials * E)))
    }

    /** The probabilistic Sharpe ratio against the best expected by luck among [trials] strategies. */
    fun deflatedSharpe(m: Moments, trials: Int): Double = probabilisticSharpe(m, expectedMaxSharpe(trials, m.n))

    /**
     * How many results are needed before a Sharpe ratio like [m]'s is above [benchmark] with [confidence]: the minimum track
     * record length. Null when the observed ratio is not above the benchmark, so no number of calls would do.
     */
    fun minTrackRecord(m: Moments, benchmark: Double = 0.0, confidence: Double = 0.95): Int? {
        val sr = m.sharpe
        if (sr.isNaN() || sr <= benchmark) return null
        val z = normalQuantile(confidence)
        return ceil(1 + spread(sr, m.skew, m.kurtosis) * (z / (sr - benchmark)).pow(2)).toInt()
    }

    /**
     * A 95% interval for the mean of [x] from the stationary block bootstrap (Politis and Romano, 1994): resampled in blocks
     * of random length, mean length [meanBlock], so results that cluster in time are resampled together. Reproducible for a
     * given [seed]. Null with fewer than two results.
     */
    fun bootstrapMeanCi(x: DoubleArray, seed: Long, resamples: Int = 2000, meanBlock: Int = maxOf(1, ceil(x.size.toDouble().pow(1.0 / 3)).toInt())): Pair<Double, Double>? {
        val n = x.size
        if (n < 2) return null
        val p = 1.0 / meanBlock
        val means = DoubleArray(resamples)
        for (r in 0 until resamples) {
            var i = Rng.intIn(seed, r.toLong(), -1, 0, n)
            var total = 0.0
            for (k in 0 until n) {
                total += x[i]
                i = if (Rng.unit(seed, r.toLong(), k.toLong()) < p) Rng.intIn(seed, r.toLong(), n.toLong() + k, 0, n) else (i + 1) % n
            }
            means[r] = total / n
        }
        means.sort()
        return means[(0.025 * (resamples - 1)).toInt()] to means[(0.975 * (resamples - 1)).toInt()]
    }

    /**
     * The probability of backtest overfitting (Bailey, Borwein, López de Prado and Zhu, 2014) by combinatorially symmetric
     * cross-validation. [trials] holds each tried variant's per-period results over the same periods. The periods are cut into
     * [blocks] (even) equal parts; for every way of choosing half of them as the in-sample set, the variant best in sample is
     * ranked out of sample. The answer is the share of splits where it lands below the median out of sample. NaN with fewer
     * than two variants or too few periods.
     */
    fun overfitProbability(trials: List<DoubleArray>, blocks: Int = 8): Double {
        require(blocks % 2 == 0 && blocks >= 2) { "blocks must be even" }
        if (trials.size < 2) return Double.NaN
        val t = trials.minOf { it.size }
        if (t < blocks) return Double.NaN
        val size = t / blocks
        fun score(trial: DoubleArray, parts: List<Int>): Double {
            var s = 0.0
            var k = 0
            for (b in parts) for (i in b * size until (b + 1) * size) {
                s += trial[i]
                k++
            }
            return s / k
        }
        var below = 0
        var splits = 0
        for (combo in combinations(blocks, blocks / 2)) {
            val rest = (0 until blocks).filter { it !in combo }
            val inSample = trials.map { score(it, combo) }
            val best = inSample.indices.maxBy { inSample[it] }
            val out = trials.map { score(it, rest) }
            val rank = out.count { it < out[best] } + 0.5 * (out.count { it == out[best] } - 1)
            val omega = (rank + 1) / (trials.size + 1)
            if (ln(omega / (1 - omega)) <= 0) below++
            splits++
        }
        return below.toDouble() / splits
    }

    private fun combinations(n: Int, k: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        fun rec(start: Int, acc: ArrayList<Int>) {
            if (acc.size == k) {
                out.add(acc.toList())
                return
            }
            for (i in start until n) {
                acc.add(i)
                rec(i + 1, acc)
                acc.removeAt(acc.size - 1)
            }
        }
        rec(0, ArrayList())
        return out
    }

    /** True when [a] and [b] agree to [tol]: for tests and for comparing figures. */
    fun close(a: Double, b: Double, tol: Double = 1e-9) = abs(a - b) <= tol * maxOf(1.0, abs(a), abs(b))
}
