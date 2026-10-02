package com.ikverse.signallab.scan

import com.ikverse.signallab.data.Alert

/**
 * Where alerts go after they are saved. The saved row is the record; this is only the nudge, so
 * a sink that fails or is switched off loses nothing.
 */
fun interface AlertSink {
    suspend fun deliver(alerts: List<Alert>)

    companion object {
        val NONE = AlertSink { }
    }
}
