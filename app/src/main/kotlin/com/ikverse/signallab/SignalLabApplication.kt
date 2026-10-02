package com.ikverse.signallab

import android.app.Application
import com.ikverse.signallab.state.AppGraph
import com.ikverse.signallab.state.LiveDebugState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SignalLabApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var graph: AppGraph
        private set

    lateinit var debugState: LiveDebugState
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this, appScope)
        debugState = LiveDebugState(graph, appScope)
        graph.start(appScope)
    }
}
