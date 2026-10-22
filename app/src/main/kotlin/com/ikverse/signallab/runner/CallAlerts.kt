package com.ikverse.signallab.runner

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ikverse.signallab.R
import java.util.Locale

/** A new call or an exit, in the words a notification shows. */
data class CallAlert(val id: Long, val title: String, val text: String)

/** Where the runner reports calls and exits. Android shows them as notifications; tests collect them. */
interface CallAlerts {
    fun post(alert: CallAlert)
}

/** Words for calls and exits: what to do, with the numbers. Research, not advice: the app trades on paper only. */
object CallWords {
    private fun price(p: Double): String = when {
        p >= 1000 -> String.format(Locale.US, "%,.2f", p)
        p >= 1 -> String.format(Locale.US, "%.2f", p)
        p >= 0.01 -> String.format(Locale.US, "%.4f", p)
        else -> String.format(Locale.US, "%.8f", p).trimEnd('0')
    }

    private fun pct(f: Double): String = String.format(Locale.US, "%+.1f%%", f * 100).replace('-', '−')

    fun base(symbol: String) = symbol.removeSuffix("USDT")

    fun newCall(id: Long, strategy: String, symbol: String, entry: Double, stop: Double?, sizeFraction: Double): CallAlert {
        val stopPart = stop?.let { ", safety stop ${price(it)} (${pct(it / entry - 1)})" } ?: ""
        val size = if (sizeFraction > 0) " Size ${Math.round(sizeFraction * 100)}% of the paper account." else " The paper account is full; tracked only."
        return CallAlert(id, "${base(symbol)} long · $strategy", "Enter at ${price(entry)}$stopPart.$size")
    }

    fun exit(id: Long, strategy: String, symbol: String, reason: String, exitPrice: Double, net: Double): CallAlert {
        val why = when (reason) {
            "stop" -> "safety stop hit"
            "signal" -> "the strategy's exit signal"
            "notice" -> "the exchange will delist the coin"
            else -> "time limit reached"
        }
        return CallAlert(id, "Exit ${base(symbol)} · $strategy", "Leave at ${price(exitPrice)}: $why. Result ${pct(net)} after fees.")
    }
}

/** Calls and exits as Android notifications, on their own channel. */
class AndroidCallAlerts(private val context: Context) : CallAlerts {
    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Calls and exits", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A strategy's new call, and when an open call should be left."
            },
        )
    }

    override fun post(alert: CallAlert) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(
                context, alert.id.toInt(), it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_CALL, alert.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(TAG, alert.id.toInt(), n)
    }

    companion object {
        const val CHANNEL = "v2_calls"

        /** The call a tapped notification opens. */
        const val EXTRA_CALL = "call_id"
        private const val TAG = "call"
    }
}
