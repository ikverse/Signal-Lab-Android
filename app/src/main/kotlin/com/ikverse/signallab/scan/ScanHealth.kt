package com.ikverse.signallab.scan

import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the background scanning has been doing, for the status notification and the health readout. */
data class ScanSnapshot(
    /** The last scan of each timeframe. */
    val last: Map<Timeframe, ScanResult> = emptyMap(),
    /** When each timeframe last completed a scan cleanly (Binance's clock). */
    val lastSuccess: Map<Timeframe, Long> = emptyMap(),
    /** The next time a scan is due, by Binance's clock; null when nothing is armed. */
    val nextScanAt: Long? = null,
    val scanning: Timeframe? = null,
    val lastProblem: String? = null,
)

class ScanHealth {
    private val state = MutableStateFlow(ScanSnapshot())
    val snapshot: StateFlow<ScanSnapshot> = state.asStateFlow()

    fun started(tf: Timeframe) = state.update { it.copy(scanning = tf) }

    fun finished(r: ScanResult) = state.update {
        it.copy(
            last = it.last + (r.tf to r),
            lastSuccess = if (r.completed) it.lastSuccess + (r.tf to r.at) else it.lastSuccess,
            scanning = null,
        )
    }

    fun failed(tf: Timeframe, why: String) = state.update { it.copy(scanning = null, lastProblem = why) }

    fun armed(at: Long?) = state.update { it.copy(nextScanAt = at) }

    fun problem(text: String?) = state.update { it.copy(lastProblem = text) }

    private inline fun MutableStateFlow<ScanSnapshot>.update(f: (ScanSnapshot) -> ScanSnapshot) {
        while (true) {
            val old = value
            if (compareAndSet(old, f(old))) return
        }
    }
}
