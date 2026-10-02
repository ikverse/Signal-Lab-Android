package com.ikverse.signallab.scan

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.ikverse.signallab.SignalLabApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.DateFormat
import java.util.Date

/**
 * Keeps the app alive between candle closes, so it can scan the moment one happens. It is a
 * "special use" foreground service: Android 15 limits the data-sync type to six hours in every
 * twenty-four, which a scanner that wakes every hour would run into.
 *
 * It shows one quiet notification. The alarm (see [AndroidAlarms]) wakes the work for charts of an hour and
 * longer. Charts under an hour close too often for an alarm, so while a list is watched on one the service also
 * holds a partial wake lock and follows those charts itself: that costs battery, so it is best on a charger.
 */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scanning = Mutex()
    private var fastJob: Job? = null
    private val graph get() = (application as SignalLabApplication).graph

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        if (!enterForeground(graph.notifier.status("Starting"))) return
        scope.launch {
            // Keep the notification truthful: what is watched, and when the next scan is due.
            combine(graph.health.snapshot, graph.watchlists.lists) { health, lists ->
                val coins = lists.filter { it.active }.flatMap { it.symbols }.distinct().size
                val next = health.nextScanAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
                buildString {
                    append("Watching $coins ${if (coins == 1) "coin" else "coins"}")
                    if (health.scanning.isNotEmpty()) append(" · scanning ${health.scanning.joinToString { it.label }}")
                    else if (next != null) append(" · next scan $next")
                    if (health.fastScanning) append(" · following short charts")
                    if (!graph.alarms.exact) append(" (alarms may run late)")
                }
            }.collect { graph.notifier.updateStatus(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start has to be answered with a foreground notification, even one that is about to stop.
        if (!enterForeground(graph.notifier.status("Checking candles"))) return START_NOT_STICKY
        scope.launch {
            if (!graph.scanWanted()) {
                fastJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            ensureFastLoop()
            scanning.withLock {
                val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signallab:scan")
                lock.acquire(WAKE_LOCK_MS)
                try {
                    graph.controller.scanDue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graph.health.problem(e.message ?: e.javaClass.simpleName)
                } finally {
                    if (lock.isHeld) lock.release()
                }
            }
        }
        return START_STICKY
    }

    /**
     * While any list is watched on a chart under an hour, follows those charts: holds the CPU awake (renewing a short wake lock
     * so a hang can never keep it on for ever) and scans each one as it closes. Starts at most once; ends by itself when no list
     * is watched on such a chart any more.
     */
    private fun ensureFastLoop() {
        if (fastJob?.isActive == true) return
        fastJob = scope.launch {
            if (!graph.settings.getBoolean(com.ikverse.signallab.data.SettingsStore.FOLLOW_FAST_CHARTS, true)) return@launch
            if (graph.scanner.timeframesInUse().none { it.isFast }) return@launch
            val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signallab:fast")
            val renew = launch {
                while (true) {
                    lock.acquire(FAST_WAKE_LOCK_MS)
                    delay(FAST_WAKE_LOCK_RENEW_MS)
                }
            }
            try {
                graph.controller.runFast()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.health.problem(e.message ?: e.javaClass.simpleName)
            } finally {
                renew.cancel()
                if (lock.isHeld) lock.release()
            }
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Becomes a foreground service. Android can refuse this when the system restarts the service in the
     * background; then the user is asked to resume (their tap is an action Android accepts) and the
     * service ends, instead of crashing.
     */
    private fun enterForeground(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifier.STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.STATUS_ID, n)
        }
        true
    } catch (e: IllegalStateException) {
        graph.notifier.postResume()
        stopSelf()
        false
    } catch (e: SecurityException) {
        graph.notifier.postResume()
        stopSelf()
        false
    }

    companion object {
        /** A scan of every timeframe at midnight is the longest job; this is far more than it needs and releases itself if the work hangs. */
        private const val WAKE_LOCK_MS = 10 * 60_000L

        /** The fast loop renews its lock every few minutes, so a hang can hold the CPU awake for ten minutes at most. */
        private const val FAST_WAKE_LOCK_MS = 10 * 60_000L
        private const val FAST_WAKE_LOCK_RENEW_MS = 5 * 60_000L

        @Volatile
        var running = false
            private set

        /**
         * Starts the service, or asks the user to when Android will not let the app do it from the
         * background: the notification's tap is an action the system accepts.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
            } catch (e: IllegalStateException) {
                (context.applicationContext as SignalLabApplication).graph.notifier.postResume()
            } catch (e: SecurityException) {
                (context.applicationContext as SignalLabApplication).graph.notifier.postResume()
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScanService::class.java))
        }
    }
}
