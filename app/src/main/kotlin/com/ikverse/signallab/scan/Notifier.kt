package com.ikverse.signallab.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ikverse.signallab.MainActivity
import com.ikverse.signallab.R
import com.ikverse.signallab.data.Alert

/**
 * Turns saved alerts into notifications. The alert row is the record and this is the nudge: with
 * notifications switched off nothing is lost, the alert is still in the inbox.
 *
 * Several alerts for one coin on one timeframe (a breakout often trips a handful of variants at once)
 * become one notification, and several notifications of a kind are grouped under a summary.
 */
class Notifier(
    private val context: Context,
    /** The user's own switch for alert notifications; the test alert, the resume prompt and the scanning status ignore it. */
    private val alertsOn: suspend () -> Boolean = { true },
) : AlertSink {
    /** One notification's worth of alerts. */
    class Composed(val channel: String, val key: String, val title: String, val body: String, val link: String?, val ids: List<Long>)

    private val manager get() = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(NotificationChannel(CH_SIGNALS, "Paper trades opened", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A signal fired and a paper trade was opened."
        })
        system.createNotificationChannel(NotificationChannel(CH_RESULTS, "Paper trades closed", NotificationManager.IMPORTANCE_LOW).apply {
            description = "A paper trade reached its target, its stop or its time limit."
        })
        system.createNotificationChannel(NotificationChannel(CH_STATUS, "Scanning status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "The quiet notification that shows Signal Lab is watching your coins."
            setShowBadge(false)
        })
        system.createNotificationChannel(NotificationChannel(CH_WARNINGS, "Market warnings", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A pump in progress, or a day of unusually heavy volume. Never a paper trade."
        })
        system.createNotificationChannel(NotificationChannel(CH_PROBLEMS, "Problems", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Scanning cannot work: Binance is unreachable or blocked, the clock is off, or scans stopped."
        })
    }

    /** Whether the system will show anything at all. */
    fun enabled(): Boolean = manager.areNotificationsEnabled()

    override suspend fun deliver(alerts: List<Alert>) {
        if (alerts.isEmpty() || !enabled() || !alertsOn()) return
        val posted = HashMap<String, Int>()
        for (c in compose(alerts)) {
            val id = notificationId(c.ids.first())
            val n = NotificationCompat.Builder(context, c.channel)
                .setSmallIcon(R.drawable.ic_stat_signal)
                .setContentTitle(c.title)
                .setContentText(c.body.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(c.body))
                .setContentIntent(open(c.link, id))
                .setAutoCancel(true)
                .setGroup(c.channel)
                .setOnlyAlertOnce(true)
                .build()
            notify(id, n)
            posted.merge(c.channel, 1, Int::plus)
        }
        for ((channel, count) in posted) {
            if (channel == CH_PROBLEMS || channel == CH_WARNINGS || count < 2) continue
            val id = if (channel == CH_SIGNALS) SUMMARY_SIGNALS else SUMMARY_RESULTS
            // Touching the summary shows the trades it counts: those opened, or those closed.
            val link = AlertText.tradesLink(status = if (channel == CH_SIGNALS) "open" else "closed")
            val n = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_signal)
                .setContentTitle(if (channel == CH_SIGNALS) "$count paper trades opened" else "$count paper trades closed")
                .setGroup(channel)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(open(link, id))
                .build()
            notify(id, n)
        }
    }

    /** The test alert: goes through the same channel and permission checks as a real one, and is not saved. */
    fun postTest(): Boolean {
        if (!enabled()) return false
        val n = NotificationCompat.Builder(context, CH_SIGNALS)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("Test alert")
            .setContentText("If you can read this, Signal Lab can reach you when a paper trade opens.")
            .setContentIntent(open(null, TEST_ID))
            .setAutoCancel(true)
            .build()
        return notify(TEST_ID, n)
    }

    /** Posted when the system refuses to restart scanning in the background; tapping it is the user's action that allows it. */
    fun postResume(): Boolean {
        if (!enabled()) return false
        val n = NotificationCompat.Builder(context, CH_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("Signal Lab stopped scanning")
            .setContentText("Tap to resume watching your coins.")
            .setContentIntent(open(null, RESUME_ID))
            .setAutoCancel(true)
            .build()
        return notify(RESUME_ID, n)
    }

    /** The quiet notification a foreground service has to show. */
    fun status(text: String): Notification =
        NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("Signal Lab")
            .setContentText(text)
            .setContentIntent(open(null, STATUS_ID))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun updateStatus(text: String) {
        notify(STATUS_ID, status(text))
    }

    @Suppress("MissingPermission")
    private fun notify(id: Int, n: Notification): Boolean = try {
        manager.notify(id, n)
        true
    } catch (e: SecurityException) {
        false
    }

    private fun open(link: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_LINK, link)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CH_SIGNALS = "signals"
        const val CH_RESULTS = "results"
        const val CH_STATUS = "status"
        const val CH_PROBLEMS = "problems"
        const val CH_WARNINGS = "warnings"
        const val EXTRA_LINK = "link"
        const val STATUS_ID = 1
        const val TEST_ID = 2
        const val RESUME_ID = 3
        const val SUMMARY_SIGNALS = 4
        const val SUMMARY_RESULTS = 5

        /**
         * Alert notifications are numbered from here, so none can ever share a number (or a PendingIntent request code, which is the
         * same number) with the scanning status, the test, the resume prompt or the summaries, which hold 1 to 5.
         */
        const val ALERT_ID_BASE = 100

        fun notificationId(alertId: Long): Int = ALERT_ID_BASE + alertId.toInt()

        private fun channelOf(kind: String) = when (kind) {
            AlertText.KIND_SIGNAL -> CH_SIGNALS
            AlertText.KIND_EXIT -> CH_RESULTS
            AlertText.KIND_PROBLEM -> CH_PROBLEMS
            AlertText.KIND_WARNING -> CH_WARNINGS
            else -> null
        }

        /** Where a grouped notification leads: several closed at once go to the Trades tab for the coin, not to one trade opened out of them. */
        private fun groupLink(first: Alert): String? =
            if (first.kind == AlertText.KIND_EXIT && first.symbol != null) AlertText.tradesLink(first.symbol) else first.link

        /**
         * Folds alerts into notifications: one per coin, timeframe and kind, in the order they first
         * appear. A "missed" alert is inbox-only and produces nothing.
         */
        fun compose(alerts: List<Alert>): List<Composed> {
            val groups = LinkedHashMap<String, MutableList<Alert>>()
            for (a in alerts) {
                val channel = channelOf(a.kind) ?: continue
                // A problem is a single message; the others fold per coin and timeframe.
                val key = if (channel == CH_PROBLEMS || channel == CH_WARNINGS) "$channel:${a.id}" else "$channel:${a.symbol}:${a.tf}"
                groups.getOrPut(key) { ArrayList() }.add(a)
            }
            return groups.map { (key, list) ->
                val first = list.first()
                val channel = channelOf(first.kind)!!
                if (list.size == 1) {
                    Composed(channel, key, first.title, first.body, first.link, listOf(first.id))
                } else {
                    val coin = AlertText.coin(first.symbol ?: "")
                    val verb = if (first.kind == AlertText.KIND_SIGNAL) "opened" else "closed"
                    Composed(channel, key, "${list.size} paper trades $verb: $coin ${first.tf}",
                        list.joinToString("\n") { it.body }, groupLink(first), list.map { it.id })
                }
            }
        }
    }
}
