package com.ikverse.signallab.data

/** Numbers the data layer runs on. Where a value comes from the research, the reason is beside it. */
object DataConfig {
    const val HOST_COM = "https://api.binance.com"
    const val HOST_US = "https://api.binance.us"

    /** The matching price streams. Binance.com's lives on port 9443; Binance.US's is the same shape. */
    const val STREAM_COM = "wss://stream.binance.com:9443"
    const val STREAM_US = "wss://stream.binance.us:9443"

    fun streamFor(host: String): String = if (host == HOST_US) STREAM_US else STREAM_COM

    /** The coin whose daily candles give the market regime. It is kept up to date whether or not a list holds it. */
    const val REGIME_COIN = "BTCUSDT"

    const val QUOTE = "USDT"

    /**
     * How many days of candles are kept for each chart. Enough for the patterns that run on it to warm up and for the
     * random comparison to look around a trade, and no more: a minute chart has 1,440 candles a day. The 4-hour and
     * daily charts keep a year plus four weeks, which is what 1-4 week momentum needs to give the same answer as it does
     * on all of history.
     */
    fun historyDays(tf: com.ikverse.signallab.engine.Timeframe): Int = when (tf) {
        com.ikverse.signallab.engine.Timeframe.M1 -> 7
        com.ikverse.signallab.engine.Timeframe.M5 -> 30
        com.ikverse.signallab.engine.Timeframe.M15, com.ikverse.signallab.engine.Timeframe.M30 -> 60
        com.ikverse.signallab.engine.Timeframe.H1 -> 100
        com.ikverse.signallab.engine.Timeframe.H4, com.ikverse.signallab.engine.Timeframe.D1 -> 400
    }

    /** Binance serves at most this many candles per request. */
    const val KLINE_PAGE = 1000

    /** A polite gap between requests, about 8 a second: far under Binance's 6,000 weight a minute. */
    const val REQUEST_PAUSE_MS = 120L

    /** Back off when Binance reports more than this much weight used in the last minute. */
    const val MAX_USED_WEIGHT = 4800
    const val MAX_ATTEMPTS = 6

    /** More than this between the phone's clock and Binance's and "closed candle" can't be trusted. */
    const val CLOCK_SKEW_WARN_MS = 5_000L

    const val UNIVERSE_REFRESH_MS = 24 * 3_600_000L

    /** The picker's day figures (gainers, losers, trades) are refreshed when it opens if they are older than this. */
    const val PICKER_REFRESH_MS = 5 * 60_000L

    /** Gainers, losers and the most volatile skip coins that traded less than this in 24 hours (USDT): the top of those lists is otherwise thin junk. */
    const val MIN_MOVER_VOLUME = 1_000_000.0

    /** A 1-hour or 7-day mover list is kept this long before Binance is asked again. */
    const val ROLLING_CACHE_MS = 2 * 60_000L

    /** How many first-candle lookups for listing days run at once. */
    const val LISTING_LOOKUPS_AT_ONCE = 4

    /** After a candle closes, wait this long before scanning, so the exchange has published it and the next one's first trade. */
    const val SCAN_SETTLE_MS = 5_000L

    /** A signal that could not be entered yet (no first price, no fresh BTC regime) is retried this often during the entry candle. */
    const val SCAN_RETRY_MS = 30_000L
    const val SCAN_RETRIES = 5

    /** The same problem is raised again no sooner than this. */
    const val PROBLEM_COOLDOWN_MS = 6 * 3_600_000L

    /** No completed scan for this many candles of a timeframe is called a stall. */
    const val STALL_CANDLES = 2

    /** The price stream closes this long after the last app screen leaves, and Binance cuts a connection at 24 hours. */
    const val PRICE_FEED_GRACE_MS = 30_000L
    const val PRICE_FEED_MAX_AGE_MS = 23 * 3_600_000L

    // --- Market warnings: alerts only, never paper trades ---

    const val PUMP_COOLDOWN_MS = 30 * 60_000L

    const val VOLUME_SPIKE_COOLDOWN_MS = 20 * 3_600_000L

    /** A coin listed on Binance less than this long ago is "new": new coins fell, on average, in their first month. */
    const val NEW_COIN_DAYS = 30
}
