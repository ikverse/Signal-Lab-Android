package com.ikverse.signallab

import android.app.Application
import com.ikverse.signallab.runner.RunnerHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SignalLabApplication : Application(), RunnerHost {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this, appScope)
        graph.start()
    }
}
