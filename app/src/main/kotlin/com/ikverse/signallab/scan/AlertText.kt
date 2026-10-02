package com.ikverse.signallab.scan

import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.LiveScan
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.VariantLabels
import java.util.Locale

/**
 * The words of every alert. They always say "paper trade" and never tell anyone to buy or sell:
 * this app records what a rule would have done, it does not advise.
 */
object AlertText {
    const val KIND_SIGNAL = "signal"
    const val KIND_EXIT = "exit"
    const val KIND_MISSED = "missed"
    const val KIND_PROBLEM = "problem"

    class Text(val title: String, val body: String, val link: String?)

    fun coin(symbol: String): String = symbol.removeSuffix("USDT")

    /** A price with the precision its size calls for: cents above 100, then more digits as the price shrinks. */
    fun price(p: Double): String = when {
        p >= 100 -> "%.2f"
        p >= 1 -> "%.3f"
        p >= 0.01 -> "%.5f"
        else -> "%.8f"
    }.format(Locale.ROOT, p)

    fun percent(fraction: Double): String = "%+.2f%%".format(Locale.ROOT, fraction * 100)

    /** The in-app address of a coin's chart. M5's screens resolve it; until then it only travels with the alert. */
    fun link(symbol: String, tf: Timeframe): String = "signallab://coin/$symbol?tf=${tf.label}"

    fun opened(p: LiveScan.Plan): Text {
        val levels = if (p.target != null && p.stop != null) {
            "Entry ${price(p.entryPrice)}, target ${price(p.target!!)}, stop ${price(p.stop!!)}."
        } else {
            "Entry ${price(p.entryPrice)}, held ${p.limit} ${if (p.limit == 1) "candle" else "candles"}."
        }
        return Text("Paper trade opened: ${coin(p.symbol)} ${p.tf.label}", "${VariantLabels.describe(p.variant)}. $levels", link(p.symbol, p.tf))
    }

    fun closed(variant: String, symbol: String, tf: Timeframe, reason: ExitReason, net: Double, randomMean: Double): Text {
        val how = when (reason) {
            ExitReason.TARGET -> "target hit"
            ExitReason.STOP -> "stopped out"
            ExitReason.TIME -> "time limit"
        }
        val baseline = if (randomMean.isNaN()) "" else "; random entries averaged ${percent(randomMean)}"
        return Text(
            "Paper trade closed: ${coin(symbol)} ${tf.label}, $how",
            "${VariantLabels.describe(variant)}. Net ${percent(net)} after costs$baseline.",
            link(symbol, tf),
        )
    }

    fun missed(variant: String, symbol: String, tf: Timeframe): Text = Text(
        "Signal missed: ${coin(symbol)} ${tf.label}",
        "${VariantLabels.describe(variant)} fired, but the phone only noticed after its entry candle had ended, so no paper trade was opened.",
        link(symbol, tf),
    )

    fun blocked(): Text = Text(
        "Binance is not available from this network",
        "Binance refused the connection (HTTP 451), so nothing can be scanned. Choose Binance.US in settings, or use another network.",
        null,
    )

    fun unreachable(failed: Int, total: Int, why: String): Text = Text(
        "Could not update $failed of $total coins",
        "The latest candles could not be downloaded ($why). The next close will try again.",
        null,
    )

    fun clockSkew(skewMs: Long): Text = Text(
        "The phone's clock is off",
        "It differs from Binance's by ${"%.1f".format(Locale.ROOT, kotlin.math.abs(skewMs) / 1000.0)} seconds. Closed candles are judged by Binance's clock, but alarms use the phone's, so scans may run late. Turn on automatic time in the phone's settings.",
        null,
    )

    fun stalled(tf: Timeframe, sinceMs: Long): Text {
        val hours = sinceMs / 3_600_000.0
        return Text(
            "Scanning has stalled on ${tf.label}",
            "No ${tf.label} scan has completed for ${"%.1f".format(Locale.ROOT, hours)} hours. Open the app to resume, and check that Signal Lab is allowed to run in the background.",
            null,
        )
    }
}
