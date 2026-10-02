package com.ikverse.signallab.data

/** Numbers the data layer runs on. Where a value comes from the research, the reason is beside it. */
object DataConfig {
    const val HOST_COM = "https://api.binance.com"
    const val HOST_US = "https://api.binance.us"

    const val QUOTE = "USDT"

    /** Candles kept per coin and timeframe: a year of momentum history plus margin, as in the research. */
    const val LIVE_HISTORY_DAYS = 420

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
}
