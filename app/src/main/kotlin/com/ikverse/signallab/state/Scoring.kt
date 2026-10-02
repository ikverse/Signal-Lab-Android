package com.ikverse.signallab.state

import com.ikverse.signallab.data.LiveTrade
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Statistics
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.VariantLabels
import com.ikverse.signallab.ui.ScoreRowUi
import com.ikverse.signallab.ui.ScorecardUi

/**
 * The scorecard, from what the live scan has recorded. Each pattern on each chart is one row: how many trades are open and closed,
 * how often they won, their average after costs, and by how much they beat the random entries drawn for them. The verdicts come
 * from the engine's own [Statistics.judge], and the bar rises with [trials], every pattern ever tried, scored or not.
 */
object Scoring {
    private const val WEEK_MS = 7 * 86_400_000L

    /** [registered] are the patterns the scan has run, so a pattern with no trades yet still has its row. */
    fun build(trades: List<LiveTrade>, registered: List<Pair<String, Timeframe>>, trials: Int): ScorecardUi {
        class Group(val variant: String, val tf: Timeframe) {
            val open = ArrayList<LiveTrade>()
            val closed = ArrayList<LiveTrade>()
        }
        val groups = LinkedHashMap<String, Group>()
        for ((v, tf) in registered) groups.getOrPut("$v/${tf.label}") { Group(v, tf) }
        for (t in trades) {
            val g = groups.getOrPut("${t.trade.variant}/${t.trade.tf.label}") { Group(t.trade.variant, t.trade.tf) }
            if (t.exit == null) g.open.add(t) else g.closed.add(t)
        }
        val all = groups.values.toList()

        // Per row: the results of closed trades, the part that beat random, and the weeks they fell in (trades in the same week are one piece of evidence).
        class Stats(val n: Int, val hit: Double?, val mean: Double?, val random: Double?, val excess: Double?, val t: Double, val p: Double)
        val stats = all.map { g ->
            val nets = g.closed.map { it.exit!!.net }
            val paired = g.closed.filter { it.exit!!.excess != null && !it.exit.excess!!.isNaN() }
            val ex = DoubleArray(paired.size) { paired[it].exit!!.excess!! }
            val weeks = LongArray(paired.size) { paired[it].trade.entryTime / WEEK_MS }
            val tCluster = if (ex.size > 1) Statistics.clusterT(ex, weeks) else Double.NaN
            val randoms = g.closed.mapNotNull { it.exit!!.randomMean }.filter { !it.isNaN() }
            Stats(
                n = nets.size,
                hit = if (nets.isEmpty()) null else nets.count { it > 0 }.toDouble() / nets.size,
                mean = if (nets.isEmpty()) null else nets.average(),
                random = if (randoms.isEmpty()) null else randoms.average(),
                excess = if (ex.isEmpty()) null else ex.average(),
                t = tCluster,
                p = if (tCluster.isNaN()) Double.NaN else Statistics.normalSurvival(tCluster),
            )
        }
        // The engine withholds a verdict from a pattern defined after a backtest, because history cannot promote it. These are
        // live trades, the one kind of evidence it may be judged on, so the name is passed on unmarked.
        val judged = Statistics.judge(all.indices.map { Statistics.Judged("live:" + all[it].variant, stats[it].n, stats[it].t, stats[it].p) }, trials)
        val rows = all.indices.map { i ->
            val g = all[i]
            val s = stats[i]
            ScoreRowUi(
                variant = g.variant, label = VariantLabels.describe(g.variant), timeframe = g.tf.label,
                open = g.open.size, closed = s.n, hitRate = s.hit, meanNet = s.mean, randomMean = s.random, excess = s.excess,
                tCluster = s.t.takeIf { !it.isNaN() },
                verdict = judged[i].verdict, firmness = Statistics.tier(s.n),
                forwardOnly = g.variant in EngineConfig.FORWARD_ONLY_VARIANTS,
            )
        }
        return ScorecardUi(rows, trials)
    }
}
