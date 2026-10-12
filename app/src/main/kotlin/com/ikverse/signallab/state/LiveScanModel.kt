package com.ikverse.signallab.state

import com.ikverse.signallab.ui.ScanModel
import com.ikverse.signallab.ui.ScanStatusUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The scan health as Today shows it, and "Check now", which runs the same scan the alarm does. */
class LiveScanModel(private val graph: AppGraph, private val scope: CoroutineScope) : ScanModel {
    private val busy = MutableStateFlow(false)

    override val status: StateFlow<ScanStatusUi> = combine(graph.health.snapshot, busy) { h, manual ->
        ScanStatusUi(
            on = h.nextScanAt != null || h.fastScanning || h.scanning.isNotEmpty(),
            lastCheckedAt = h.lastSuccess.values.maxOrNull(),
            nextCheckAt = h.nextScanAt,
            checking = manual || h.scanning.isNotEmpty(),
            problem = h.lastProblem,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ScanStatusUi())

    override fun checkNow() {
        if (!busy.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                graph.controller.scanAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.health.problem(e.message ?: e.javaClass.simpleName)
            } finally {
                busy.value = false
            }
        }
    }
}
