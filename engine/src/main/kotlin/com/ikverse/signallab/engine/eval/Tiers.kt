package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.Tier
import kotlin.math.sqrt

/** A strategy's tier as its evidence sets it, and why, in a sentence. */
class TierVerdict(val tier: Tier, val reason: String)

/**
 * How a strategy earns its tier (docs/strategy-research.md §4), recomputed from three columns of evidence: the research
 * behind it, its backtest on the person's coins (at their fees and at double), and its live calls. The rules are fixed
 * numbers, written on the evidence page:
 *
 * - **Failing**: at least [MIN_LIVE_FAIL] live calls, or [MIN_BACKTEST] past calls that lose even at the person's fees, behind
 *   random entries with a t-statistic of [FAIL_T] or worse.
 * - **Proven**: the research supports it, at least [MIN_BACKTEST] past calls ahead of random entries at double the fees with a
 *   deflated Sharpe ratio above [CONFIDENCE] (allowing for every strategy ever tried), and live calls not behind.
 * - **Promising**: the research supports it and at least [MIN_BACKTEST] past calls ahead with a probabilistic Sharpe ratio above
 *   [CONFIDENCE]; or at least [MIN_BACKTEST] live calls with one above [CONFIDENCE].
 * - Otherwise **Watch** when the research supports it, **Testing** when it does not.
 */
object Tiers {
    const val MIN_BACKTEST = 100
    const val MIN_LIVE_FAIL = 30
    const val CONFIDENCE = 0.95
    const val FAIL_T = -2.0

    /** Whether the research supports [s]: its launch tier was set from published evidence, not a single source or a hunch. */
    fun researchSupports(s: Strategy): Boolean = s.launchTier == Tier.PROVEN || s.launchTier == Tier.PROMISING || s.launchTier == Tier.WATCH

    private fun t(m: Moments): Double = if (m.n < 2 || !(m.sd > 0)) Double.NaN else m.mean / (m.sd / sqrt(m.n.toDouble()))

    /**
     * [backtest] and [backtestDouble] are the per-call excess over random entries in the backtest at the person's fees and at
     * double them; [live] the same for finished live calls; [trials] every strategy ever tried.
     */
    fun judge(s: Strategy, backtest: DoubleArray, backtestDouble: DoubleArray, live: DoubleArray, trials: Int): TierVerdict {
        val research = researchSupports(s)
        val b = Moments.of(backtest)
        val b2 = Moments.of(backtestDouble)
        val l = Moments.of(live)
        val liveT = t(l)
        val pastT = t(b)
        if (l.n >= MIN_LIVE_FAIL && liveT <= FAIL_T) return TierVerdict(Tier.FAILING, "Behind random entries over ${l.n} live calls.")
        if (b.n >= MIN_BACKTEST && pastT <= FAIL_T && b.mean < 0) return TierVerdict(Tier.FAILING, "Behind random entries over ${b.n} past calls on your coins.")
        val liveBehind = l.n >= MIN_LIVE_FAIL && l.mean < 0
        if (research && b2.n >= MIN_BACKTEST && b2.mean > 0 && Evidence.deflatedSharpe(b2, trials) > CONFIDENCE && !liveBehind) {
            return TierVerdict(Tier.PROVEN, "Ahead of random entries on your coins even at double the fees, allowing for the $trials strategies tried.")
        }
        if (research && b.n >= MIN_BACKTEST && b.mean > 0 && Evidence.probabilisticSharpe(b) > CONFIDENCE && !liveBehind) {
            return TierVerdict(Tier.PROMISING, "Ahead of random entries over ${b.n} past calls on your coins; not yet at double the fees.")
        }
        if (l.n >= MIN_BACKTEST && l.mean > 0 && Evidence.probabilisticSharpe(l) > CONFIDENCE) {
            return TierVerdict(Tier.PROMISING, "Ahead of random entries over ${l.n} live calls.")
        }
        return if (research) TierVerdict(Tier.WATCH, if (b.n < MIN_BACKTEST) "Too few past calls on your coins to confirm the research." else "The research supports it, but your coins do not show it clearly yet.")
        else TierVerdict(Tier.TESTING, "One study or none behind it; it has to earn its place with live calls.")
    }
}
