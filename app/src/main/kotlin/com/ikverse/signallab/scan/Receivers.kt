package com.ikverse.signallab.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ikverse.signallab.SignalLabApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A candle has closed: hand over to the service, which does the scan. */
class ScanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        wake(context, goAsync())
    }
}

/**
 * The phone restarted or the app was updated. Either one clears the alarm, so scanning is started
 * again here, if there is anything to watch.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        wake(context, goAsync())
    }
}

/** [pending] is null only when a receiver is called directly, as tests do; the system always supplies one. */
private fun wake(context: Context, pending: BroadcastReceiver.PendingResult?) {
    val app = context.applicationContext as SignalLabApplication
    CoroutineScope(Dispatchers.Default).launch {
        try {
            // Reading the lists and the setting takes a moment, so a service is only started if it has work.
            if (app.graph.scanWanted()) ScanService.start(context.applicationContext)
        } finally {
            pending?.finish()
        }
    }
}
