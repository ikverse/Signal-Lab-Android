package com.ikverse.signallab.engine

/**
 * When candles close. Binance aligns every timeframe to the Unix epoch, which is a UTC midnight: 1h
 * candles close on the hour, 4h candles at 00, 04, 08, 12, 16 and 20 UTC, and daily candles at 00:00
 * UTC. Times are milliseconds, and callers pass Binance's clock rather than the phone's.
 */
object CandleClock {
    /** The next instant after [now] at which a [tf] candle closes. */
    fun nextClose(tf: Timeframe, now: Long): Long = (Math.floorDiv(now, tf.ms) + 1) * tf.ms

    /** The next instant after [now] at which a candle of any timeframe closes. */
    fun nextClose(now: Long): Long = Timeframe.entries.minOf { nextClose(it, now) }

    /** The latest instant at or before [now] at which a [tf] candle closed. */
    fun lastClose(tf: Timeframe, now: Long): Long = Math.floorDiv(now, tf.ms) * tf.ms

    /** The timeframes that have a candle closing at exactly [instant], shortest first. */
    fun closingAt(instant: Long): List<Timeframe> = Timeframe.entries.filter { Math.floorMod(instant, it.ms) == 0L }

    /** Every instant in (after, upTo] at which a [tf] candle closed, oldest first. */
    fun closesBetween(tf: Timeframe, after: Long, upTo: Long): List<Long> {
        val out = ArrayList<Long>()
        var t = nextClose(tf, after)
        while (t <= upTo) {
            out.add(t)
            t += tf.ms
        }
        return out
    }
}
