package com.ikverse.signallab.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * What the debug screen may see and do: a readout of the data layer on a real phone, and a few shortcuts. Only debug builds show it,
 * behind a long press on the version in Settings.
 */
interface DebugState {
    val lines: StateFlow<List<String>>

    /** Re-downloads the pair list now. */
    fun refreshPairList()

    /** Makes a list from the 30 biggest coins by volume and switches it on, so history starts downloading. */
    fun createTestList()

    /** Makes a list of the 10 biggest coins watched on 1m, 5m and 15m charts and switches it on, to try the fast scanning. */
    fun createFastTestList()

    /** Scans every timeframe's newest closed candle now: a real scan, exactly what the alarm runs. */
    fun scanNow()

    /** Posts a notification through the real channel and permission checks, to see that alerts reach the phone. */
    fun sendTestAlert()
}
