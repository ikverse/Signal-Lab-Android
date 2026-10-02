package com.ikverse.signallab

import android.app.Application
import com.ikverse.signallab.state.AppGraph
import com.ikverse.signallab.state.LiveAppModel
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

    /** What the screens see; made after the graph, and shared by every activity. */
    lateinit var model: LiveAppModel
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this, appScope)
        debugState = LiveDebugState(graph, appScope)
        model = LiveAppModel(graph, this, appScope, debugState)
        graph.start(appScope)
    }
}
