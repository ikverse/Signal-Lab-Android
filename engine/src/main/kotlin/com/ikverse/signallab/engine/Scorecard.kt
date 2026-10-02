package com.ikverse.signallab.engine

import kotlin.math.sqrt

/** What one trade did over a fixed holding period, and what random entries held as long did. NaN = none. */
class HorizonResult(val net: Double, val random: Double)

class Trade(
    val symbol: String,
    /** Open time of the candle the signal fired on. */
    val barTime: Long,
    val detectedAt: Long,
    val regime: Int,
    val entryTime: Long,
    val entryPrice: Double,
    val exitTime: Long,
    val exitPrice: Double,
    val exitReason: ExitReason,
    val barsHeld: Int,
    val gross: Double,
    val net: Double,
    /** Average net result of the matched random entries; NaN when there were none to draw from. */
    val randomMean: Double,
    val excess: Double,
    val horizons: Map<Int, HorizonResult>,
)

/** Every net result of every random entry drawn for a variant, for hit rates and means. */
class PooledRandom(val exit: DoubleArray, val horizons: Map<Int, DoubleArray>)

class VariantRun(val trades: List<Trade>, val pooled: PooledRandom)

class HorizonScore(
    val n: Int, val hit: Double, val mean: Double, val randomHit: Double, val randomMean: Double,
    val excess: Double, val tCluster: Double,
)

class RegimeScore(val n: Int, val meanExcess: Double)

class ScoreDetails(
    val tier: String,
    val exits: Map<ExitReason, Int>,
    val avgBarsHeld: Double,
    val excessCi95: Pair<Double, Double>?,
    val horizons: Map<Int, HorizonScore>,
    val byRegime: Map<String, RegimeScore>,
    val weeks: Int,
)

/** One variant's scorecard line. NaN means "not available" (for example, no trades). */
class Score(
    val variant: String,
    val family: String,
    val tf: Timeframe,
    val n: Int,
    val hit: Double,
    val randomHit: Double,
    val mean: Double,
    val median: Double,
    val randomMean: Double,
    val excess: Double,
    val t: Double,
    val tCluster: Double,
    val p: Double,
    val profitFactor: Double,
    val details: ScoreDetails,
)

private class DoubleBuffer {
    var data = DoubleArray(256)
    var size = 0
    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun toArray(): DoubleArray = data.copyOf(size)
}

/**
 * Paper trades, random baselines and verdicts.
 *
 * Every signal becomes a paper trade (see [PaperTrading]). Each trade is also compared with random
 * entries on the same coin, within the same 90 days and the same market regime, with identical
 * exits. A signal only counts as an edge when it beats those random entries after fees, by a
 * margin that survives correction for every variant ever tried.
 */
object Scorecard {
    private const val WEEK_MS = 7 * DAY_MS

    private fun mean(a: DoubleArray): Double = if (a.isEmpty()) Double.NaN else a.sum() / a.size

    private fun share(a: DoubleArray): Double = if (a.isEmpty()) Double.NaN else a.count { it > 0 }.toDouble() / a.size

    /**
     * Turns signal flags into independent paper trades (one open per coin at a time) with matched
     * random baselines. [regimes] holds each coin's BTC-regime series (see [PaperTrading.regimeSeries]).
     */
    fun runVariant(
        key: SignalKey,
        flagsBySymbol: Map<String, BooleanArray>,
        panel: Map<String, Candles>,
        tf: Timeframe,
        regimes: Map<String, IntArray>,
    ): VariantRun {
        val hold = key.holdBars
        val horizons = if (hold != null) intArrayOf(hold) else EngineConfig.horizonBars(tf)
        val windowBars = EngineConfig.RANDOM_WINDOW_DAYS * 24 / tf.hours
        val draws = EngineConfig.RANDOM_DRAWS_PER_TRADE
        val limit = hold ?: EngineConfig.timeLimitBars(tf)
        val trades = ArrayList<Trade>()
        val randomExit = DoubleBuffer()
        val randomH = horizons.associateWith { DoubleBuffer() }

        for ((sym, flags) in flagsBySymbol) {
            val c = panel.getValue(sym)
            val idx = flags.indices.filter { flags[it] }.toIntArray()
            if (idx.isEmpty()) continue
            val atr = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)
            val ex = PaperTrading.simulateExits(c, idx, tf, atr, hold)
            val cost = EngineConfig.costFor(sym)

            // Only trades that start after the previous one on this coin has closed.
            val keep = ArrayList<Int>()
            var busyUntil = -1
            for (j in idx.indices) {
                if (ex.valid[j] && idx[j] + 1 > busyUntil) {
                    keep.add(j)
                    busyUntil = ex.exitIdx[j]
                }
            }
            if (keep.isEmpty()) continue
            val sig = IntArray(keep.size) { idx[keep[it]] }
            val hz = PaperTrading.horizonReturns(c, sig, horizons, cost)

            // Matched random entries: same coin, +/- window, same regime, same exits and horizons.
            val reg = regimes.getValue(sym)
            val usable = BooleanArray(c.size) { it + limit < c.size && !atr[it].isNaN() }
            val randMean = DoubleArray(sig.size) { Double.NaN }
            val randH = horizons.associateWith { DoubleArray(sig.size) { Double.NaN } }
            val seed = Rng.seedOf(key.name, sym)
            val pools = HashMap<Int, IntArray>()
            for (jj in sig.indices) {
                val r = reg[sig[jj]]
                val pool = pools.getOrPut(r) { (0 until c.size).filter { usable[it] && reg[it] == r }.toIntArray() }
                val lo = lowerBound(pool, sig[jj] - windowBars)
                val hi = upperBound(pool, sig[jj] + windowBars)
                if (hi <= lo) continue
                val picked = IntArray(draws) { d -> pool[Rng.intIn(seed, sig[jj].toLong(), d.toLong(), lo, hi)] }
                val rx = PaperTrading.simulateExits(c, picked, tf, atr, hold)
                var total = 0.0
                var count = 0
                for (d in picked.indices) {
                    if (rx.valid[d]) {
                        val net = rx.gross[d] - cost
                        total += net
                        count++
                        randomExit.add(net)
                    }
                }
                if (count > 0) randMean[jj] = total / count
                for ((h, values) in PaperTrading.horizonReturns(c, picked, horizons, cost)) {
                    var ht = 0.0
                    var hc = 0
                    for (v in values) {
                        if (!v.isNaN()) {
                            ht += v
                            hc++
                            randomH.getValue(h).add(v)
                        }
                    }
                    if (hc > 0) randH.getValue(h)[jj] = ht / hc
                }
            }

            for (jj in sig.indices) {
                val j = keep[jj]
                val s = sig[jj]
                val net = ex.gross[j] - cost
                val rm = randMean[jj]
                trades.add(Trade(
                    symbol = sym, barTime = c.t[s], detectedAt = c.closeTime[s], regime = reg[s],
                    entryTime = c.t[s + 1], entryPrice = ex.entryPrice[j],
                    exitTime = c.closeTime[ex.exitIdx[j]], exitPrice = ex.exitPrice[j],
                    exitReason = ex.reason[j]!!, barsHeld = ex.exitIdx[j] - s, gross = ex.gross[j], net = net,
                    randomMean = rm, excess = if (rm.isNaN()) Double.NaN else net - rm,
                    horizons = horizons.associateWith { h -> HorizonResult(hz.getValue(h)[jj], randH.getValue(h)[jj]) },
                ))
            }
        }
        return VariantRun(trades, PooledRandom(randomExit.toArray(), randomH.mapValues { it.value.toArray() }))
    }

    /** One variant's scorecard line from its trades and the random entries pooled with them. */
    fun score(key: SignalKey, family: String, tf: Timeframe, run: VariantRun): Score {
        val trades = run.trades
        val n = trades.size
        val nets = DoubleArray(n) { trades[it].net }
        val paired = trades.filter { !it.excess.isNaN() }
        val ex = DoubleArray(paired.size) { paired[it].excess }
        val weeks = LongArray(paired.size) { paired[it].entryTime / WEEK_MS }

        if (n == 0) {
            return Score(key.name, family, tf, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                ScoreDetails("No verdict", emptyMap(), Double.NaN, null, emptyMap(), emptyMap(), 0))
        }
        val wins = nets.filter { it > 0 }.sum()
        val losses = -nets.filter { it < 0 }.sum()
        val exMean = mean(ex)
        var tPlain = Double.NaN
        if (ex.size > 1) {
            var ss = 0.0
            for (v in ex) ss += (v - exMean) * (v - exMean)
            val sd = sqrt(ss / (ex.size - 1))
            if (sd > 0) tPlain = exMean / (sd / sqrt(ex.size.toDouble()))
        }
        val tCluster = if (ex.size > 1) Statistics.clusterT(ex, weeks) else Double.NaN
        val sorted = nets.sortedArray()
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2

        val horizonScores = LinkedHashMap<Int, HorizonScore>()
        for (h in trades[0].horizons.keys) {
            val both = trades.filter { !it.horizons.getValue(h).net.isNaN() && !it.horizons.getValue(h).random.isNaN() }
            if (both.isEmpty()) continue
            val v = DoubleArray(both.size) { both[it].horizons.getValue(h).net }
            val hx = DoubleArray(both.size) { v[it] - both[it].horizons.getValue(h).random }
            val rp = run.pooled.horizons[h] ?: DoubleArray(0)
            horizonScores[h] = HorizonScore(
                n = v.size, hit = share(v), mean = mean(v), randomHit = share(rp), randomMean = mean(rp),
                excess = mean(hx), tCluster = Statistics.clusterT(hx, LongArray(both.size) { both[it].entryTime / WEEK_MS }),
            )
        }
        val byRegime = LinkedHashMap<String, RegimeScore>()
        for ((r, label) in listOf(1 to "btc_above_200d", 0 to "btc_below_200d")) {
            val v = paired.filter { it.regime == r }.map { it.excess }.toDoubleArray()
            if (v.isNotEmpty()) byRegime[label] = RegimeScore(v.size, mean(v))
        }
        val ci = if (ex.size > 1) {
            Statistics.clusterBootstrapCi(ex, weeks, Rng.seedOf(key.name, "bootstrap"), EngineConfig.BOOTSTRAP_RESAMPLES)
        } else null
        return Score(
            variant = key.name, family = family, tf = tf, n = n,
            hit = share(nets), randomHit = share(run.pooled.exit), mean = mean(nets), median = median,
            randomMean = mean(run.pooled.exit), excess = exMean, t = tPlain, tCluster = tCluster,
            p = if (tCluster.isNaN()) Double.NaN else Statistics.normalSurvival(tCluster),
            profitFactor = if (losses > 0) wins / losses else Double.NaN,
            details = ScoreDetails(
                tier = Statistics.tier(n),
                exits = ExitReason.entries.associateWith { r -> trades.count { it.exitReason == r } },
                avgBarsHeld = trades.sumOf { it.barsHeld }.toDouble() / n,
                excessCi95 = ci, horizons = horizonScores, byRegime = byRegime, weeks = weeks.distinct().size,
            ),
        )
    }

    /** Verdicts across a batch of scores. [trials] is every variant ever tried, scored now or not. */
    fun judge(scores: List<Score>, trials: Int): List<Statistics.Judgement> =
        Statistics.judge(scores.map { Statistics.Judged(it.variant, it.n, it.tCluster, it.p) }, trials)
}
