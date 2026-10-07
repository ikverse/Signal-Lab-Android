package com.ikverse.signallab.engine

/**
 * Plain-language names for signal variants, for notifications and lists. The variant's own name
 * (`donchian20_1d`) stays the identifier everywhere; this only describes it. A test holds every
 * variant the engine produces to a real description, so a new variant cannot ship unlabelled.
 */
object VariantLabels {
    private val trend = Regex("""trend_ma(\d+)_\w+""")
    private val donchian = Regex("""donchian(\d+)_\w+""")
    private val tsMomentum = Regex("""tsmom(\d+)w_\w+""")
    private val xsMomentum = Regex("""xsmom(\d+)w_\w+""")
    private val fade = Regex("""fade(\d+)h_\w+""")
    private val intradayMomentum = Regex("""intraday_mom_(\w+)""")

    /** A short description of what fired, e.g. "Breakout: close above the 20-candle high". Falls back to the name. */
    fun describe(variant: String): String {
        trend.matchEntire(variant)?.let { return "Trend: close crossed above its ${it.groupValues[1]}-candle average" }
        donchian.matchEntire(variant)?.let { return "Breakout: close above the ${it.groupValues[1]}-candle high" }
        tsMomentum.matchEntire(variant)?.let { return "Momentum: ${it.groupValues[1]}-week return in its own top fifth" }
        xsMomentum.matchEntire(variant)?.let { return "Ranking: top fifth of the list by ${it.groupValues[1]}-week return" }
        if (variant.startsWith("fade1h_hold24_")) return "Drop fade: 1h fall beyond 2.5 deviations, held 24 candles"
        fade.matchEntire(variant)?.let { return "Drop fade: ${it.groupValues[1]}h fall beyond 2.5 deviations" }
        intradayMomentum.matchEntire(variant)?.let {
            val first = if (it.groupValues[1] == "30m") "half-hour" else "hour"
            return "Intraday momentum: strong first $first of the UTC day, held the last $first"
        }
        if (variant.startsWith("bullish_harami_")) return "Bullish harami: a small candle inside the body of a big red one"
        if (variant.startsWith("bullish_hikkake_")) return "Bullish hikkake: a false break down out of an inside candle"
        if (variant.startsWith("intraday_breakout_")) return "Intraday breakout: closed above the usual range around the day's open"
        return variant
    }

    /**
     * The name of what fired, short enough for a row: "Breakout · 20 high", "Bullish hikkake". Two variants share a short name only
     * when they share a description (the same pattern on different chart sizes). Falls back to the variant's own name.
     */
    fun short(variant: String): String {
        trend.matchEntire(variant)?.let { return "Trend · ${it.groupValues[1]} avg" }
        donchian.matchEntire(variant)?.let { return "Breakout · ${it.groupValues[1]} high" }
        tsMomentum.matchEntire(variant)?.let { return "Momentum · ${it.groupValues[1]} wk" }
        xsMomentum.matchEntire(variant)?.let { return "Ranking · ${it.groupValues[1]} wk" }
        if (variant.startsWith("fade1h_hold24_")) return "Drop fade · 1h, held 24"
        fade.matchEntire(variant)?.let { return "Drop fade · ${it.groupValues[1]}h" }
        intradayMomentum.matchEntire(variant)?.let { return "Intraday momentum · ${if (it.groupValues[1] == "30m") "30m" else "1h"}" }
        if (variant.startsWith("bullish_harami_")) return "Bullish harami"
        if (variant.startsWith("bullish_hikkake_")) return "Bullish hikkake"
        if (variant.startsWith("intraday_breakout_")) return "Intraday breakout"
        return variant
    }
}
