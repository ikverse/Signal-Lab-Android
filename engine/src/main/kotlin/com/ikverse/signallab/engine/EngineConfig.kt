package com.ikverse.signallab.engine

/**
 * Every number the engine's rules depend on, in one place. The Learn pages read their "Settings"
 * sections from here, and a test holds these equal to the values the research was run with.
 */
object EngineConfig {
    // Costs per round trip: taker fees plus a slippage allowance.
    const val COST_MAJORS = 0.0025
    const val COST_OTHERS = 0.0040
    val MAJORS: Set<String> = setOf("BTCUSDT", "ETHUSDT")

    fun costFor(symbol: String): Double = if (symbol in MAJORS) COST_MAJORS else COST_OTHERS

    // Signals
    val TREND_MA_WINDOWS = intArrayOf(20, 50)
    val DONCHIAN_LOOKBACKS = intArrayOf(10, 20, 55, 100)
    val TS_MOMENTUM_WEEKS = intArrayOf(1, 2, 4)
    const val TS_MOMENTUM_TOP_QUANTILE = 0.80
    const val TS_MOMENTUM_HISTORY_DAYS = 365
    val XS_MOMENTUM_WEEKS = intArrayOf(2, 3)
    const val XS_MOMENTUM_TOP_FRACTION = 0.20
    const val XS_MOMENTUM_MIN_COINS = 8
    val FADE_HOURS = intArrayOf(1, 2, 4)
    const val FADE_SIGMA = 2.5
    const val FADE_VOL_DAYS = 30
    const val INTRADAY_MOM_TOP_QUANTILE = 0.80
    const val INTRADAY_MOM_HISTORY_DAYS = 90

    // Exits and scoring
    const val ATR_WINDOW = 14
    const val TARGET_ATR = 2.0
    const val STOP_ATR = 1.0

    fun timeLimitBars(tf: Timeframe): Int = when (tf) {
        Timeframe.H1 -> 24
        Timeframe.H4 -> 42
        Timeframe.D1 -> 7
    }

    /** Fixed holding periods checked besides the target-and-stop trade, in candles. */
    fun horizonBars(tf: Timeframe): IntArray = when (tf) {
        Timeframe.H1 -> intArrayOf(4, 12, 24)
        Timeframe.H4 -> intArrayOf(6, 18, 42)
        Timeframe.D1 -> intArrayOf(3, 7, 14, 30)
    }

    const val RANDOM_DRAWS_PER_TRADE = 20
    const val RANDOM_WINDOW_DAYS = 90
    const val REGIME_MA_DAYS = 200

    const val EDGE_T = 3.0
    const val LOSING_T = -2.0
    const val ALPHA = 0.05
    val VERDICT_TIERS: List<Pair<Int, String>> =
        listOf(30 to "No verdict", 100 to "Early read", 300 to "Provisional")
    const val VERDICT_TOP = "Meaningful"
    const val BOOTSTRAP_RESAMPLES = 2000

    /**
     * Variants defined after looking at a backtest. History can't promote them: their backtest is
     * shown for reference, but only live paper trades can give them a verdict.
     */
    val FORWARD_ONLY_VARIANTS: Set<String> = setOf("fade1h_hold24_1h")
    const val FORWARD_ONLY_VERDICT = "Judged on live trades only"
}
