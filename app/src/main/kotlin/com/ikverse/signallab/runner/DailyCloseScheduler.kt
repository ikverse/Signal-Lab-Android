package com.ikverse.signallab.runner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * When Signal Lab 2's pass runs: an exact alarm a little after each hourly close (by Binance's clock) starts it; the
 * first pass after 00:00 UTC does the daily work. A job every [BACKSTOP_HOURS] hours runs it too, so a missed alarm (a phone
 * Samsung put to sleep, a reboot) costs hours, not days. Both start the same unique work, so they never run twice at once. Nothing runs as a foreground service.
 */
class DailyCloseScheduler(
    private val context: Context,
    /** Binance's clock minus the phone's, in milliseconds. */
    private val offsetMs: () -> Long,
    private val phoneNow: () -> Long = System::currentTimeMillis,
) {
    /** Arms the alarm for the next daily close. Exact when Android allows it, otherwise as close as Android will. */
    fun arm() {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextRunAt(phoneNow() + offsetMs()) - offsetMs()
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, Intent(context, DailyCloseReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** The periodic backstop; asking again keeps the one already scheduled. */
    fun ensureBackstop() {
        val request = PeriodicWorkRequestBuilder<ScanWorker>(BACKSTOP_HOURS, TimeUnit.HOURS).setConstraints(networked).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(BACKSTOP_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Runs the pass as soon as there is a network; a pass already queued or running is kept, not doubled. */
    fun runNow() {
        val request = OneTimeWorkRequestBuilder<ScanWorker>().setConstraints(networked).build()
        WorkManager.getInstance(context).enqueueUniqueWork(DAILY_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private val networked = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        private const val HOUR_MS = 3_600_000L

        /** After the close, wait this long so Binance has published the closed candle and the next one's first trade. */
        const val SETTLE_MS = 90_000L
        const val BACKSTOP_HOURS = 6L
        const val DAILY_WORK = "v2-daily-pass"
        const val BACKSTOP_WORK = "v2-backstop"
        const val ACTION = "com.ikverse.signallab.V2_DAILY_CLOSE"
        private const val REQUEST_CODE = 200

        /**
         * The next run time by Binance's clock: the next hourly close after [serverNow], plus the settling wait. The daily close
         * at 00:00 UTC is one of them, and the runner does its daily work only on the first pass after it.
         */
        fun nextRunAt(serverNow: Long): Long = (Math.floorDiv(serverNow, HOUR_MS) + 1) * HOUR_MS + SETTLE_MS
    }
}

/** The daily-close alarm: starts the pass and arms the next one. */
class DailyCloseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = context.applicationContext as? RunnerHost ?: return
        runCatching {
            host.graph.scheduler.runNow()
            host.graph.scheduler.arm()
        }
    }
}

/** After a reboot or an app update, the alarm and the backstop are set again, and a pass runs in case one was missed. */
class V2BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val host = context.applicationContext as? RunnerHost ?: return
        runCatching {
            host.graph.scheduler.arm()
            host.graph.scheduler.ensureBackstop()
            host.graph.scheduler.runNow()
        }
    }
}
