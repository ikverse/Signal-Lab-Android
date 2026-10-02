package com.ikverse.signallab.ui

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How numbers and times read on screen. Plain functions, so a test can hold every one of them to its wording. */
object Fmt {
    /** A price with the precision its size calls for: cents above 100, then more digits as the price shrinks. */
    fun price(p: Double?): String = when {
        p == null || p.isNaN() -> "—"
        p >= 100 -> "%.2f"
        p >= 1 -> "%.3f"
        p >= 0.01 -> "%.5f"
        else -> "%.8f"
    }.let { if (p == null || p.isNaN()) it else it.format(Locale.ROOT, p) }

    /** 0.0123 as "1.23%". */
    fun percent(fraction: Double?, digits: Int = 2): String =
        if (fraction == null || fraction.isNaN()) "—" else "%.${digits}f%%".format(Locale.ROOT, fraction * 100)

    /** 0.0123 as "+1.23%", -0.01 as "-1.00%". */
    fun signedPercent(fraction: Double?, digits: Int = 2): String =
        if (fraction == null || fraction.isNaN()) "—" else "%+.${digits}f%%".format(Locale.ROOT, fraction * 100)

    /** A big number short: 1_250_000_000 as "1.25B". */
    fun compact(v: Double): String = when {
        v >= 1e9 -> "%.2fB".format(Locale.ROOT, v / 1e9)
        v >= 1e6 -> "%.1fM".format(Locale.ROOT, v / 1e6)
        v >= 1e3 -> "%.1fK".format(Locale.ROOT, v / 1e3)
        else -> "%.0f".format(Locale.ROOT, v)
    }

    /** A date and time such as "3 Oct 14:05", in [zone] (the phone's by default). */
    fun dateTime(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).also { it.timeZone = zone }.format(Date(millis))

    fun time(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("HH:mm", Locale.ENGLISH).also { it.timeZone = zone }.format(Date(millis))

    /** A fee as the user thinks of it: 0.001 as "0.10%". */
    fun fee(fraction: Double): String = "%.3f%%".format(Locale.ROOT, fraction * 100).replace(Regex("(\\.\\d\\d)0%$"), "$1%")

    /** Parses what the user typed in a percent box ("0.075", "0,075 %") into a fraction (0.00075), or null. */
    fun parsePercent(text: String): Double? =
        text.trim().removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.div(100)

    /** The colour of a change: green up, red down, muted when there is none to show. */
    fun changeColor(fraction: Double?): Color = when {
        fraction == null || fraction.isNaN() || fraction == 0.0 -> Palette.Muted
        fraction > 0 -> Palette.Up
        else -> Palette.Down
    }

    /** "1h" as "1 hour", for sentences; the labels themselves are what the lists use. */
    fun chartName(label: String): String = when (label) {
        "1m" -> "1 minute"
        "5m" -> "5 minutes"
        "15m" -> "15 minutes"
        "30m" -> "30 minutes"
        "1h" -> "1 hour"
        "4h" -> "4 hours"
        "1d" -> "1 day"
        else -> label
    }
}
