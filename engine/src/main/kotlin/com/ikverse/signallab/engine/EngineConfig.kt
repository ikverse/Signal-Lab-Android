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
    const val INTRADAY_BREAKOUT_DAYS = 14

    // Exits and scoring
    const val ATR_WINDOW = 14
    const val TARGET_ATR = 2.0
    const val STOP_ATR = 1.0

    fun timeLimitBars(tf: Timeframe): Int = when (tf) {
        Timeframe.M1 -> 60
        Timeframe.M5 -> 48
        Timeframe.M15 -> 48
        Timeframe.M30 -> 48
        Timeframe.H1 -> 24
        Timeframe.H4 -> 42
        Timeframe.D1 -> 7
    }

    /** Fixed holding periods checked besides the target-and-stop trade, in candles. */
    fun horizonBars(tf: Timeframe): IntArray = when (tf) {
        Timeframe.M1 -> intArrayOf(5, 15, 60)
        Timeframe.M5 -> intArrayOf(6, 12, 48)
        Timeframe.M15 -> intArrayOf(8, 16, 48)
        Timeframe.M30 -> intArrayOf(6, 12, 48)
        Timeframe.H1 -> intArrayOf(4, 12, 24)
        Timeframe.H4 -> intArrayOf(6, 18, 42)
        Timeframe.D1 -> intArrayOf(3, 7, 14, 30)
    }

    const val RANDOM_DRAWS_PER_TRADE = 20

    /** The research's comparison window; 4-hour and daily charts still use it. */
    const val RANDOM_WINDOW_DAYS = 90

    /** Days either side of a signal that random entries are drawn from. Shorter charts keep less history, so they look at less. */
    fun randomWindowDays(tf: Timeframe): Int = when (tf) {
        Timeframe.M1 -> 3
        Timeframe.M5, Timeframe.M15, Timeframe.M30 -> 10
        Timeframe.H1 -> 30
        Timeframe.H4, Timeframe.D1 -> RANDOM_WINDOW_DAYS
    }
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

    // --- Exits for the live scan (the classic target and stop above stay what the research used) ---

    /** Trend patterns: a safety stop this many candle sizes (ATR) below the entry... */
    const val TRAIL_STOP_ATR = 2.0

    /** ...which starts following the price once it has risen this many candle sizes... */
    const val TRAIL_ACTIVATE_ATR = 1.0

    /** ...staying this many candle sizes under the highest close, and never moving down. */
    const val TRAIL_DISTANCE_ATR = 2.0

    /** The longest a trailing trade may last, in candles: 1m 2h, 5m 6h, 15m and 30m 1 day, 1h 3 days, 4h 15 days, 1d 30 days. */
    fun trailCapBars(tf: Timeframe): Int = when (tf) {
        Timeframe.M1 -> 120
        Timeframe.M5 -> 72
        Timeframe.M15 -> 96
        Timeframe.M30 -> 48
        Timeframe.H1 -> 72
        Timeframe.H4 -> 90
        Timeframe.D1 -> 30
    }

    /** Fast patterns: a safety stop this many candle sizes below the entry, with a target and a time limit learned from past signals. */
    const val LEARNED_STOP_ATR = 3.0

    /** Past finished signals needed before a coin's own history is trusted; below it the whole list's is used, and below that, a plain hold. */
    const val LEARN_MIN_SIGNALS = 20

    /** With too little to learn from: hold this many candles, with no target or stop. */
    const val LEARN_FALLBACK_HOLD_BARS = 6

    /** How many candles after a signal its best rise is looked for. */
    fun learnWindowBars(tf: Timeframe): Int = when (tf) {
        Timeframe.M1 -> 60
        Timeframe.M5 -> 48
        Timeframe.M15 -> 48
        Timeframe.M30 -> 48
        Timeframe.H1 -> 24
        Timeframe.H4 -> 18
        Timeframe.D1 -> 7
    }

    /** How far back finished signals are learned from, in days: inside the history kept, leaving room for the indicators to warm up. */
    fun learnLookbackDays(tf: Timeframe): Int = when (tf) {
        Timeframe.M1 -> 4
        Timeframe.M5 -> 20
        Timeframe.M15, Timeframe.M30 -> 40
        Timeframe.H1 -> 60
        Timeframe.H4, Timeframe.D1 -> 300
    }

    /** What the exchange fee is on Binance's entry tier, one way. The live scan charges two of these, plus any extra cost the user turns on. */
    const val DEFAULT_FEE_PER_SIDE = 0.001

    // --- Market warnings (alerts only) ---

    /** A pump: up this much within [PUMP_MINUTES] minutes... */
    const val PUMP_RISE = 0.05
    const val PUMP_MINUTES = 5

    /** ...on this many times the volume that coin normally trades in such a stretch, measured over the [PUMP_NORMAL_MINUTES] before. */
    const val PUMP_VOLUME_MULTIPLE = 10.0
    const val PUMP_NORMAL_MINUTES = 240

    /** A volume spike: a day's volume more than this many times its average over the previous [VOLUME_SPIKE_DAYS] days. */
    const val VOLUME_SPIKE_MULTIPLE = 3.0
    const val VOLUME_SPIKE_DAYS = 30
}
