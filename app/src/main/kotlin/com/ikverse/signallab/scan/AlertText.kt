package com.ikverse.signallab.scan

import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.PatternLabels
import com.ikverse.signallab.engine.ExitMode
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.LiveScan
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.Warnings
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

    /** A market warning: shown, never traded. */
    const val KIND_WARNING = "warning"

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

    /** The in-app address of a coin's chart. The screens read it (see `parseLink`); it travels with the alert and is what a touch on it follows. */
    fun link(symbol: String, tf: Timeframe): String = "signallab://coin/$symbol?tf=${tf.label}"

    /** The address of a coin's Details tab, where its warnings are shown. */
    fun detailsLink(symbol: String, tf: Timeframe): String = "signallab://coin/$symbol?tf=${tf.label}&show=details"

    /** The address of the Trades tab: optionally for one coin, with one trade opened, or showing only "open" or "closed" trades. */
    fun tradesLink(symbol: String? = null, tradeId: Long? = null, status: String? = null): String {
        val query = listOfNotNull(symbol?.let { "coin=$it" }, tradeId?.let { "id=$it" }, status?.let { "status=$it" })
        return "signallab://trades" + if (query.isEmpty()) "" else query.joinToString("&", "?")
    }

    /** The address of the Alerts tab on its Problems filter. */
    const val PROBLEMS_LINK = "signallab://alerts?group=problems"

    /** The address of Settings. */
    const val SETTINGS_LINK = "signallab://settings"

    /** The line added to alerts about a coin Binance listed less than a month ago. */
    fun newCoinLine(symbol: String): String =
        "${coin(symbol)} was listed on Binance less than ${DataConfig.NEW_COIN_DAYS} days ago. New coins fell on average in their first month."

    fun opened(p: LiveScan.Plan, newCoin: Boolean = false): Text {
        val levels = when {
            p.mode == ExitMode.TRAIL ->
                "Entry ${price(p.entryPrice)}, safety stop ${price(p.stop!!)}, then a stop that follows the price up." +
                    if (p.limit < com.ikverse.signallab.engine.EngineConfig.trailCapBars(p.tf)) " Closed by the end of the UTC day at the latest." else ""
            p.mode == ExitMode.LEARNED && p.target != null ->
                "Entry ${price(p.entryPrice)}, target ${price(p.target!!)} (${percent(p.target!! / p.entryPrice - 1)}, from this pattern's earlier signals), " +
                    "stop ${price(p.stop!!)}, within ${p.limit} ${if (p.limit == 1) "candle" else "candles"}."
            p.mode == ExitMode.LEARNED ->
                "Entry ${price(p.entryPrice)}, held ${p.limit} candles: too few earlier signals to learn a target from."
            p.target != null && p.stop != null ->
                "Entry ${price(p.entryPrice)}, target ${price(p.target!!)}, stop ${price(p.stop!!)}."
            else -> "Entry ${price(p.entryPrice)}, held ${p.limit} ${if (p.limit == 1) "candle" else "candles"}."
        }
        val note = if (newCoin) " ${newCoinLine(p.symbol)}" else ""
        return Text("Paper trade opened: ${coin(p.symbol)} ${p.tf.label}", "${PatternLabels.describe(p.variant)}. $levels$note", link(p.symbol, p.tf))
    }

    fun closed(variant: String, symbol: String, tf: Timeframe, reason: ExitReason, net: Double, randomMean: Double, tradeId: Long? = null): Text {
        val how = when (reason) {
            ExitReason.TARGET -> "target hit"
            ExitReason.STOP -> "stopped out"
            ExitReason.TIME -> "time limit"
        }
        val baseline = if (randomMean.isNaN()) "" else "; random entries averaged ${percent(randomMean)}"
        return Text(
            "Paper trade closed: ${coin(symbol)} ${tf.label}, $how",
            "${PatternLabels.describe(variant)}. Net ${percent(net)} after costs$baseline.",
            tradesLink(symbol, tradeId),
        )
    }

    fun missed(variant: String, symbol: String, tf: Timeframe): Text = Text(
        "Signal missed: ${coin(symbol)} ${tf.label}",
        "${PatternLabels.describe(variant)} fired, but the phone only noticed after its entry candle had ended, so no paper trade was opened.",
        link(symbol, tf),
    )

    fun pump(symbol: String, tf: Timeframe, pump: Warnings.Pump, newCoin: Boolean): Text = Text(
        "Pump warning: ${coin(symbol)}",
        "${coin(symbol)} rose ${"%.1f".format(Locale.ROOT, pump.rise * 100)}% in ${pump.minutes} minutes on ${"%.0f".format(Locale.ROOT, pump.volumeMultiple)} times its usual volume. " +
            "Pumps like this usually peak within about a minute, and late buyers lose. No paper trade is opened." +
            if (newCoin) " ${newCoinLine(symbol)}" else "",
        detailsLink(symbol, tf),
    )

    fun volumeSpike(symbol: String, multiple: Double, newCoin: Boolean): Text = Text(
        "Volume spike: ${coin(symbol)}",
        "${coin(symbol)} traded ${"%.1f".format(Locale.ROOT, multiple)} times its usual daily volume. " +
            "On Binance a day like this has usually been followed by lower prices the next day. No paper trade is opened." +
            if (newCoin) " ${newCoinLine(symbol)}" else "",
        detailsLink(symbol, Timeframe.D1),
    )

    fun blocked(): Text = Text(
        "Binance is not available from this network",
        "Binance refused the connection (HTTP 451), so nothing can be scanned. Choose Binance.US in settings, or use another network.",
        SETTINGS_LINK,
    )

    fun unreachable(failed: Int, total: Int, why: String): Text = Text(
        "Could not update $failed of $total coins",
        "The latest candles could not be downloaded ($why). The next close will try again.",
        PROBLEMS_LINK,
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
            PROBLEMS_LINK,
        )
    }
}
