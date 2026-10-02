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

    /** A short description of what fired, e.g. "Breakout: close above the 20-candle high". Falls back to the name. */
    fun describe(variant: String): String {
        trend.matchEntire(variant)?.let { return "Trend: close crossed above its ${it.groupValues[1]}-candle average" }
        donchian.matchEntire(variant)?.let { return "Breakout: close above the ${it.groupValues[1]}-candle high" }
        tsMomentum.matchEntire(variant)?.let { return "Momentum: ${it.groupValues[1]}-week return in its own top fifth" }
        xsMomentum.matchEntire(variant)?.let { return "Ranking: top fifth of the list by ${it.groupValues[1]}-week return" }
        if (variant.startsWith("fade1h_hold24_")) return "Drop fade: 1h fall beyond 2.5 deviations, held 24 candles"
        fade.matchEntire(variant)?.let { return "Drop fade: ${it.groupValues[1]}h fall beyond 2.5 deviations" }
        if (variant == "intraday_mom_1h") return "Intraday momentum: strong first hour of the UTC day, held the last hour"
        return variant
    }
}
