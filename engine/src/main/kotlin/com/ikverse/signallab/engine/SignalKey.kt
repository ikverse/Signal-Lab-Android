package com.ikverse.signallab.engine

/**
 * One signal variant: a name like `donchian20_1d`, the family it belongs to, and the settings that
 * distinguish it from its siblings. Equal keys are the same variant.
 */
data class SignalKey(val name: String, val family: String, val params: Map<String, Double>) {
    fun param(key: String): Double? = params[key]
    /** Candles to hold a trade for, when the variant has no target and stop. */
    val holdBars: Int? get() = params["hold_bars"]?.toInt()
}
