package com.ikverse.signallab.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * What the temporary debug screen may see and do. It exists to show the data layer working on a
 * real phone before the real screens arrive in M5, and only debug builds show it.
 */
interface DebugState {
    val lines: StateFlow<List<String>>

    /** Re-downloads the pair list now. */
    fun refreshPairList()

    /** Makes a list from the 30 biggest coins by volume and switches it on, so history starts downloading. */
    fun createTestList()
}
