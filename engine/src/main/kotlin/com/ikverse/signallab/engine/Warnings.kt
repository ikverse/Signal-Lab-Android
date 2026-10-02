package com.ikverse.signallab.engine

/**
 * Market warnings: things worth knowing that are never paper trades. Research found that chasing a pump loses (the price
 * peaks about a minute in and late buyers are left holding it), and that a day of heavy volume tends to be followed by
 * lower prices, not higher. Both only look at the candles up to the one that just closed.
 */
object Warnings {
    /** A pump in progress: how far the price rose and how many times its usual volume traded. */
    class Pump(val rise: Double, val volumeMultiple: Double, val minutes: Int)

    /**
     * Whether the candle at [i] ends a pump: up [EngineConfig.PUMP_RISE] or more across the last [EngineConfig.PUMP_MINUTES]
     * minutes, on [EngineConfig.PUMP_VOLUME_MULTIPLE] times the volume that coin normally trades in such a stretch, measured over
     * the [EngineConfig.PUMP_NORMAL_MINUTES] minutes before. Only 1-minute and 5-minute charts are fine enough to see one.
     */
    fun pump(c: Candles, i: Int = c.size - 1): Pump? {
        if (c.tf != Timeframe.M1 && c.tf != Timeframe.M5) return null
        val window = maxOf(1, EngineConfig.PUMP_MINUTES / c.tf.minutes)
        val normal = EngineConfig.PUMP_NORMAL_MINUTES / c.tf.minutes
        if (i < 0 || i >= c.size || i - window + 1 < normal) return null
        val start = i - window + 1
        val rise = c.close[i] / c.open[start] - 1
        if (!(rise >= EngineConfig.PUMP_RISE)) return null
        var recent = 0.0
        for (k in start..i) recent += c.volume[k]
        var before = 0.0
        for (k in start - normal until start) before += c.volume[k]
        val usual = before / normal * window
        if (!(usual > 0)) return null
        val multiple = recent / usual
        return if (multiple >= EngineConfig.PUMP_VOLUME_MULTIPLE) Pump(rise, multiple, EngineConfig.PUMP_MINUTES) else null
    }

    /**
     * How many times its average over the previous [EngineConfig.VOLUME_SPIKE_DAYS] days the daily candle at [i] traded, when that is
     * more than [EngineConfig.VOLUME_SPIKE_MULTIPLE]; null otherwise. Only meaningful on the daily chart.
     */
    fun volumeSpike(c: Candles, i: Int = c.size - 1): Double? {
        if (c.tf != Timeframe.D1) return null
        val days = EngineConfig.VOLUME_SPIKE_DAYS
        if (i < days || i >= c.size) return null
        var total = 0.0
        for (k in i - days until i) total += c.volume[k]
        val average = total / days
        if (!(average > 0)) return null
        val multiple = c.volume[i] / average
        return if (multiple > EngineConfig.VOLUME_SPIKE_MULTIPLE) multiple else null
    }
}
