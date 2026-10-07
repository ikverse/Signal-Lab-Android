package com.ikverse.signallab.engine

import kotlin.math.exp
import kotlin.math.sqrt

/** The statistics the scorecard stands on. Plain functions of their arguments. */
object Statistics {
    private val SQRT_PI = sqrt(Math.PI)

    /**
     * The complementary error function, accurate to about 1e-12 relative error. The standard
     * library has none. Below 3 it sums a series with no cancelling terms; above, a continued fraction.
     */
    fun erfc(x: Double): Double {
        if (x.isNaN()) return Double.NaN
        if (x < 0) return 2 - erfc(-x)
        if (x < 3.0) {
            var term = x
            var sum = x
            val x2 = x * x
            for (n in 1..400) {
                term *= 2 * x2 / (2 * n + 1)
                sum += term
                if (term < 1e-18 * sum) break
            }
            val erf = 2 / SQRT_PI * exp(-x2) * sum
            return 1 - erf
        }
        var f = x
        for (k in 80 downTo 1) f = x + (k / 2.0) / f
        return exp(-x * x) / (SQRT_PI * f)
    }

    /** The chance of a standard normal value above [z]. */
    fun normalSurvival(z: Double): Double = 0.5 * erfc(z / sqrt(2.0))

    /**
     * t-statistic of the mean of [x], with standard errors clustered by [clusters] (calendar week):
     * ten coins breaking out on the same day count as one piece of evidence, not ten. NaN when there
     * are fewer than two observations or two clusters, or no variance.
     */
    fun clusterT(x: DoubleArray, clusters: LongArray): Double {
        val n = x.size
        if (n < 2) return Double.NaN
        val sums = HashMap<Long, Double>()
        val mean = x.sum() / n
        for (i in x.indices) sums.merge(clusters[i], x[i] - mean, Double::plus)
        val g = sums.size
        if (g < 2) return Double.NaN
        var ss = 0.0
        for (s in sums.values) ss += s * s
        val variance = g.toDouble() / (g - 1) * ss / (n.toDouble() * n)
        return if (variance > 0) mean / sqrt(variance) else Double.NaN
    }

    /** 95% interval of the mean of [x], resampling whole clusters. Null with fewer than two clusters. */
    fun clusterBootstrapCi(x: DoubleArray, clusters: LongArray, seed: Long, resamples: Int): Pair<Double, Double>? {
        val groups = LinkedHashMap<Long, MutableList<Double>>()
        for (i in x.indices) groups.getOrPut(clusters[i]) { ArrayList() }.add(x[i])
        val g = groups.size
        if (g < 2) return null
        val lists = groups.values.toList()
        val means = DoubleArray(resamples)
        for (r in 0 until resamples) {
            var total = 0.0
            var count = 0
            for (d in 0 until g) {
                for (v in lists[Rng.intIn(seed, r.toLong(), d.toLong(), 0, g)]) {
                    total += v
                    count++
                }
            }
            means[r] = total / count
        }
        return Indicators.quantile(means, 0.025) to Indicators.quantile(means, 0.975)
    }

    /** How firm a verdict is, by the number of independent trades behind it. */
    fun tier(n: Int): String {
        for ((limit, label) in EngineConfig.VERDICT_TIERS) if (n < limit) return label
        return EngineConfig.VERDICT_TOP
    }

    class Judgement(val pHolm: Double, val qBh: Double, val verdict: String)

    class Judged(val variant: String, val n: Int, val tCluster: Double, val p: Double)

    /**
     * Holm and Benjamini-Hochberg corrections across every variant ever tried, then a verdict each.
     * [trials] counts variants tried in earlier runs too: they are trials with p = 1.
     * Missing p-values (NaN) count as 1.
     */
    fun judge(scores: List<Judged>, trials: Int): List<Judgement> {
        val ps = DoubleArray(scores.size) { if (scores[it].p.isNaN()) 1.0 else scores[it].p }
        val m = maxOf(trials, ps.size)
        val order = ps.indices.sortedBy { ps[it] }
        val holm = DoubleArray(ps.size) { 1.0 }
        var running = 0.0
        for ((rank, i) in order.withIndex()) {
            running = maxOf(running, minOf(1.0, (m - rank) * ps[i]))
            holm[i] = running
        }
        val bh = DoubleArray(ps.size) { 1.0 }
        running = 1.0
        for (rank in order.indices.reversed()) {
            val i = order[rank]
            running = minOf(running, ps[i] * m / (rank + 1))
            bh[i] = minOf(1.0, running)
        }
        return scores.indices.map { i ->
            val s = scores[i]
            val verdict = when {
                EngineConfig.isForwardOnly(s.variant) -> EngineConfig.FORWARD_ONLY_VERDICT
                s.n < EngineConfig.VERDICT_TIERS.first().first || s.tCluster.isNaN() -> "No verdict"
                s.tCluster >= EngineConfig.EDGE_T && holm[i] < EngineConfig.ALPHA -> "Edge"
                s.tCluster <= EngineConfig.LOSING_T -> "Losing"
                else -> "No edge"
            }
            Judgement(holm[i], bh[i], verdict)
        }
    }
}
