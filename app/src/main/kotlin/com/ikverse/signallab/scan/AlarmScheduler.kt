package com.ikverse.signallab.scan

/** Wakes the phone at a candle close, even from Doze. */
interface AlarmScheduler {
    /** Wakes the app at [serverInstant], Binance's clock. Replaces any alarm already set. */
    fun armAt(serverInstant: Long)

    fun cancel()

    /** True when the system will deliver the alarm on time; false means it may run minutes late. */
    val exact: Boolean
}
