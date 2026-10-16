package com.ikverse.signallab.model

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.strategy.Breakout
import com.ikverse.signallab.engine.strategy.HourlyTrend
import com.ikverse.signallab.engine.strategy.Leaders
import com.ikverse.signallab.engine.strategy.Momentum
import com.ikverse.signallab.engine.strategy.NearHigh
import com.ikverse.signallab.engine.strategy.QuietDip
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
        is Momentum -> {
            val r = Indicators.pctReturn(c.close, 7 * s.weeks)[i]
            "Up ${Words.pct(r).removePrefix("+")} in ${s.weeks} weeks, more than usual for it, while the market is rising."
        }
        is NearHigh -> "Closed at ${Words.price(c.close[i])}, within 1% of its highest price of the last seven days, while the market is rising."
        is QuietDip -> {
            val r = Indicators.pctReturn(c.close, 1)[i]
            "Fell ${Words.pct(-r).removePrefix("+")} in a day on less volume than usual: few sold, so it tends to bounce."
        }
        is Leaders -> {
            val r = Indicators.pctReturn(c.close, 7 * s.weeks)[i]
            "Among the strongest fifth of the watched coins over ${s.weeks} weeks (${Words.pct(r)}), at this week's ranking."
        }
        is HourlyTrend -> "An hourly close back above its ${s.hours}-hour average while the daily trend is rising."
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
        is HourlyTrend -> "exits on an hourly close below its ${s.hours}-hour average, or after two days"
        else -> "exits after ${s.exit.maxBars} days"
    }

    /**
     * The price a close must stay above for an open call to stay open, as of the newest candle in [c]; null when the
     * strategy has no such level.
     */
    fun exitLevel(s: Strategy, c: Candles): Double? = when (s) {
        is Breakout -> if (c.size >= s.exitLookback) (c.size - s.exitLookback until c.size).minOf { c.low[it] } else null
        is TrendTimer -> Indicators.sma(c.close, s.days).lastOrNull()?.takeUnless { it.isNaN() }
        else -> null
    }

    /** What to do now with an open call: hold, and the line that would end it. */
    fun holdInstruction(s: Strategy, c: Candles): String {
        if (s is HourlyTrend) return "Hold. Exit on an hourly close below its ${s.hours}-hour average."
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
        is Momentum -> "A coin's last one to two weeks tend to carry on for the next one to four, in large coins and in rising markets. " +
            "Held longer, the effect reverses, so it holds two weeks."
        is Leaders -> "Ranking the coins by their last three weeks and holding the top fifth was the strongest pattern in the academic data. " +
            "Most of it came from shorting tiny coins, though, and what is left long-only has faded since 2022."
        is NearHigh -> "Large, liquid coins near their weekly high tend to keep going, while small ones near theirs tend to fall back. One working paper."
        is QuietDip -> "A coin that falls on low volume tends to bounce back within days; one that falls on heavy volume does not. " +
            "There is no price stop, because a stop lowers what a bounce earns."
        is HourlyTrend -> "Requiring the daily trend to agree halved the trades of an hourly rule and doubled its quality. The only test " +
            "is before fees, and fees decide most hourly rules."
        else -> ""
    }

    fun summary(s: Strategy): String = when (s) {
        is Breakout -> "New ${s.lookback}-day high · daily · rides it with a wide exit"
        is TrendTimer -> "Above the ${s.days}-day average · BTC and the top 10"
        is Momentum -> "Strong ${s.weeks} weeks keeps going · rising market only"
        is Leaders -> "Top fifth by ${s.weeks}-week gain · weekly · fading since 2022"
        is NearHigh -> "Big coins near their weekly high · one study"
        is QuietDip -> "A drop on low volume bounces · mid-size coins"
        is HourlyTrend -> "Hourly entry when the daily trend is up · fee-fragile"
        else -> s.name
    }

    fun research(s: Strategy): String = when (s) {
        is Breakout -> "Supports on altcoins, not on Bitcoin"
        is TrendTimer -> "Supports, mostly as drawdown protection"
        is Momentum -> "Supports, in large coins and rising markets"
        is Leaders -> "Supported before 2022, fading since"
        is NearHigh, is QuietDip -> "One study"
        is HourlyTrend -> "One test, before fees"
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
        is Momentum -> listOf(
            Source("Liu and Tsyvinski, 2021, Review of Financial Studies", "https://www.nber.org/papers/w24877"),
            Source("Han, Kang and Ryu, 2023, SSRN 4675565", "https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4675565"),
        )
        is Leaders -> listOf(
            Source("Liu, Tsyvinski and Wu, 2022, Journal of Finance", "https://www.nber.org/papers/w25882"),
            Source("Fieberg, Liedtke and Zaremba, 2024, International Review of Financial Analysis", "https://open.icm.edu.pl/handle/123456789/25544"),
        )
        is NearHigh -> listOf(
            Source("Fičura, 2023, working paper", "https://wp.ffu.vse.cz/artkey/wps-202301-0003_impact-of-size-and-volume-on-cryptocurrency-momentum-and-reversal.php"),
        )
        is QuietDip -> listOf(
            Source("Bianchi, Babiak and Dickerson, 2022, Journal of Banking and Finance", "https://ideas.repec.org/a/eee/jbfina/v142y2022ics0378426622001418.html"),
        )
        is HourlyTrend -> listOf(
            Source("Quantpedia, 2025, a multi-timeframe trend strategy on Bitcoin", "https://quantpedia.com/how-to-design-a-simple-multi-timeframe-trend-strategy-on-bitcoin/"),
        )
        else -> emptyList()
    }

    /** The rule exactly, one line each. */
    fun rule(s: Strategy): List<String> {
        val stop = s.exit.safetyStopAtr?.let { "safety stop: entry − $it × ATR(14)" }
        val size = "size: 1 ATR = 1% of the account, at most 20%"
        return when (s) {
            is Breakout -> listOfNotNull(
                "entry: close > highest(high, ${s.lookback}, prior)",
                "exit: close < lowest(low, ${s.exitLookback}, prior), or ${s.exit.maxBars} days", stop, size,
            )
            is TrendTimer -> listOfNotNull(
                "entry: close crosses above SMA(${s.days}); BTC and the top 10 by volume",
                "exit: close < SMA(${s.days}), or ${s.exit.maxBars} days", stop, size,
            )
            is Momentum -> listOfNotNull(
                "entry: return(${7 * s.weeks} days) > 0 and above its median of the last ${s.medianDays} days; BTC above SMA(200); top 30 by volume",
                "exit: after ${s.exit.maxBars} days", stop, size,
            )
            is Leaders -> listOfNotNull(
                "entry: Monday's close, top 20% of the watched coins by return(${7 * s.weeks} days), at least ${s.minCoins} coins; BTC above SMA(200)",
                "exit: after ${s.exit.maxBars} days", stop, size,
            )
            is NearHigh -> listOfNotNull(
                "entry: close ≥ 99% of highest(high, 7); BTC above SMA(200); top 30 by volume",
                "exit: after ${s.exit.maxBars} days", stop, size,
            )
            is QuietDip -> listOfNotNull(
                "entry: return(1 day) < −5% on volume below its 30-day median; ranks 10 to 80 by volume",
                "exit: after ${s.exit.maxBars} days, no price stop", stop, size,
            )
            is HourlyTrend -> listOfNotNull(
                "entry: hourly close crosses above EMA(${s.hours}) while the daily SMA(${s.days}) is rising",
                "exit: hourly close < EMA(${s.hours}), or ${s.exit.maxBars} hours", stop, size,
            )
            else -> listOfNotNull("${s.exit.maxBars} days at most", stop)
        }
    }
}
