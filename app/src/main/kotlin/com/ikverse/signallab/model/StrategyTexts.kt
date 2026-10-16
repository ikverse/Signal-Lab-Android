package com.ikverse.signallab.model

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.strategy.Breakout
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.TrendTimer
import com.ikverse.signallab.view.common.Words

/** A study a strategy rests on, with where to read it. */
data class Source(val title: String, val url: String)

/**
 * What each strategy says in plain words: why a call fired, the level an open call is watching, what the research found,
 * and the rule exactly. The words for a new strategy are written here when it is added (docs/strategy-research.md).
 */
object StrategyTexts {
    /** One sentence on why the signal on candle [i] of [c] fired. */
    fun why(s: Strategy, c: Candles, i: Int): String = when (s) {
        is Breakout -> {
            val high = Indicators.rollingMaxPrior(c.high, s.lookback)[i]
            "Closed at ${Words.price(c.close[i])}, above its highest price of the prior ${s.lookback} days (${Words.price(high)})${volumeNote(c, i)}."
        }
        is TrendTimer -> {
            val avg = Indicators.sma(c.close, s.days)[i]
            "Closed at ${Words.price(c.close[i])}, back above its ${s.days}-day average (${Words.price(avg)})."
        }
        else -> "${s.name} fired on the close of ${Words.day(c.t[i])}."
    }

    /** " on volume 1.6× its usual" when the signal day traded well above its 30-day norm. */
    private fun volumeNote(c: Candles, i: Int): String {
        if (i < 1) return ""
        val usual = Indicators.sma(c.volume, 30)[i - 1]
        if (!(usual > 0)) return ""
        val ratio = c.volume[i] / usual
        return if (ratio >= 1.2) ", on volume ${String.format(java.util.Locale.US, "%.1f", ratio)}× its usual" else ""
    }

    /** The exit in a few words, for a call's card. */
    fun exitShort(s: Strategy): String = when (s) {
        is Breakout -> "exits on a close below its ${s.exitLookback}-day low"
        is TrendTimer -> "exits on a close below its ${s.days}-day average"
        else -> "exits after ${s.exit.maxBars} days"
    }

    /**
     * The price a daily close must stay above for an open call to stay open, as of the newest candle in [c]; null when the
     * strategy has no such level.
     */
    fun exitLevel(s: Strategy, c: Candles): Double? = when (s) {
        is Breakout -> if (c.size >= s.exitLookback) (c.size - s.exitLookback until c.size).minOf { c.low[it] } else null
        is TrendTimer -> Indicators.sma(c.close, s.days).lastOrNull()?.takeUnless { it.isNaN() }
        else -> null
    }

    /** What to do now with an open call: hold, and the line that would end it. */
    fun holdInstruction(s: Strategy, c: Candles): String {
        val level = exitLevel(s, c) ?: return "Hold until its time limit."
        return when (s) {
            is TrendTimer -> "Hold. Exit if a daily close is below its ${s.days}-day average, ${Words.price(level)}."
            else -> "Hold. Exit if a daily close is below ${Words.price(level)}."
        }
    }

    /** A paragraph on why the strategy should work, from docs/strategy-research.md. */
    fun rationale(s: Strategy): String = when (s) {
        is Breakout -> "Crypto trends tend to persist for weeks. A new ${s.lookback}-day high is one of the few simple rules that kept " +
            "working out of sample once researchers corrected for having tried thousands of rules, though only on altcoins, not on Bitcoin. " +
            "Fewer than half the calls end up; a few long runs pay for many small exits."
        is TrendTimer -> "Staying in only while the price is above its ${s.days}-day average has mostly helped by sitting out long falls, " +
            "not by earning more in the good times. It is tested on Bitcoin and the largest coins."
        else -> ""
    }

    fun summary(s: Strategy): String = when (s) {
        is Breakout -> "New ${s.lookback}-day high · daily · rides it with a wide exit"
        is TrendTimer -> "Above the ${s.days}-day average · daily"
        else -> s.name
    }

    fun research(s: Strategy): String = when (s) {
        is Breakout -> "Supports on altcoins, not on Bitcoin"
        is TrendTimer -> "Supports, mostly as drawdown protection"
        else -> "Not yet reviewed"
    }

    fun sources(s: Strategy): List<Source> = when (s) {
        is Breakout -> listOf(
            Source("Hudson and Urquhart, 2021, Annals of Operations Research", "https://ideas.repec.org/a/spr/annopr/v297y2021i1d10.1007_s10479-019-03357-1.html"),
            Source("Gerritsen and others, 2020, Finance Research Letters", "https://research-portal.uu.nl/en/publications/the-profitability-of-technical-trading-rules-in-the-bitcoin-marke/"),
        )
        is TrendTimer -> listOf(
            Source("Detzel and others, 2021, Financial Management", "https://ideas.repec.org/a/bla/finmgt/v50y2021i1p107-137.html"),
            Source("Grayscale, 2023, The trend is your friend", "https://research.grayscale.com/reports/the-trend-is-your-friend-managing-bitcoins-volatility-with-momentum-signals"),
        )
        else -> emptyList()
    }

    /** The rule exactly, one line each. */
    fun rule(s: Strategy): List<String> {
        val stop = s.exit.safetyStopAtr?.let { "safety stop: entry − $it × ATR(14)" }
        return when (s) {
            is Breakout -> listOfNotNull(
                "entry: close > highest(high, ${s.lookback}, prior)",
                "exit: close < lowest(low, ${s.exitLookback}, prior), or ${s.exit.maxBars} days", stop,
                "size: 1 ATR = 1% of the account, at most 20%",
            )
            is TrendTimer -> listOfNotNull(
                "entry: close crosses above SMA(${s.days})", "exit: close < SMA(${s.days}), or ${s.exit.maxBars} days", stop,
                "size: 1 ATR = 1% of the account, at most 20%",
            )
            else -> listOfNotNull("${s.exit.maxBars} days at most", stop)
        }
    }
}
