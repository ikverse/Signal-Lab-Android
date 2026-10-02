package com.ikverse.signallab.scan

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The real alarm. Exact alarms need the "alarms & reminders" permission from Android 12, and it is
 * off by default from Android 13; without it the alarm is still set, but the system may deliver it
 * minutes late. The scan does not depend on being on time: it judges candles by Binance's clock, and
 * a signal is traded as long as the entry candle has not ended.
 */
class AndroidAlarms(
    private val context: Context,
    /** Turns an instant on Binance's clock into the phone's wall-clock time the alarm manager wants. */
    private val serverToLocal: (Long) -> Long,
) : AlarmScheduler {
    private val manager get() = context.getSystemService(AlarmManager::class.java)

    override val exact: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    override fun armAt(serverInstant: Long) {
        val at = serverToLocal(serverInstant)
        val pending = pending()
        if (exact) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return
            } catch (e: SecurityException) {
                // The permission was withdrawn between the check and the call; fall through to the inexact alarm.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    override fun cancel() {
        manager.cancel(pending())
    }

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, ScanAlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION = "com.ikverse.signallab.SCAN_ALARM"
        private const val REQUEST_CODE = 100
    }
}
