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

    /** 0.0123 as "+1.23%", -0.01 as "−1.00%": a true minus sign, as wide as the plus, so signed columns line up. */
    fun signedPercent(fraction: Double?, digits: Int = 2): String =
        if (fraction == null || fraction.isNaN()) "—" else "%+.${digits}f%%".format(Locale.ROOT, fraction * 100).replace('-', MINUS)

    /** The minus sign used on screen. Notifications keep the plain hyphen. */
    const val MINUS = '−'

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

    /** A day as a list's heading: "Today · 7 Oct", "Yesterday · 6 Oct", or "5 Oct" (with the year when it is not this one). */
    fun day(millis: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val cal = java.util.Calendar.getInstance(zone)
        cal.timeInMillis = now
        val today = cal.get(java.util.Calendar.YEAR) to cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.get(java.util.Calendar.YEAR) to cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = millis
        val that = cal.get(java.util.Calendar.YEAR) to cal.get(java.util.Calendar.DAY_OF_YEAR)
        val date = SimpleDateFormat(if (that.first == today.first) "d MMM" else "d MMM yyyy", Locale.ENGLISH).also { it.timeZone = zone }.format(Date(millis))
        return when (that) {
            today -> "Today · $date"
            yesterday -> "Yesterday · $date"
            else -> date
        }
    }

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

    /** How long ago a coin was listed, in whole days: "listed today", "listed 1 day ago", "listed 12 days ago". */
    fun listedAgo(listedAt: Long, now: Long): String {
        val days = ((now - listedAt) / 86_400_000L).coerceAtLeast(0).toInt()
        return when (days) {
            0 -> "listed today"
            1 -> "listed 1 day ago"
            else -> "listed $days days ago"
        }
    }

    /** The line under a coin in the picker: the number it was ranked by, then its volume. [now] is the phone's clock, for "new". */
    fun offerLine(offer: OfferUi, source: PickSource, window: PickWindow, now: Long): String {
        val volume = "volume ${compact(offer.quoteVolume)} USDT"
        val v = offer.value
        return when {
            v == null || source == PickSource.VOLUME -> "24h $volume"
            source == PickSource.GAINERS || source == PickSource.LOSERS -> "${signedPercent(v)} in ${window.label} · $volume"
            source == PickSource.ACTIVE -> "${compact(v)} trades in 24h · $volume"
            source == PickSource.VOLATILE -> "${percent(v, 1)} range in 24h · $volume"
            else -> "${listedAgo(v.toLong(), now)} · $volume"
        }
    }

    /** What a picker source means, in one sentence, shown under its chips. */
    fun sourceNote(source: PickSource, window: PickWindow): String = when (source) {
        PickSource.VOLUME -> "Coins with the most trading in the last 24 hours."
        PickSource.GAINERS -> "Biggest rises over ${window.label}, among coins that traded at least 1M USDT in 24 hours."
        PickSource.LOSERS -> "Biggest falls over ${window.label}, among coins that traded at least 1M USDT in 24 hours."
        PickSource.ACTIVE -> "Coins with the most separate trades in the last 24 hours."
        PickSource.VOLATILE -> "Widest high-to-low swing in the last 24 hours, among coins that traded at least 1M USDT."
        PickSource.NEW -> "Coins Binance listed in the last 30 days, newest first."
    }

    /** "1h" as "1-hour", for a chart named before a noun: "the 1-hour chart". */
    fun chartAdjective(label: String): String = when (label) {
        "1m" -> "1-minute"
        "5m" -> "5-minute"
        "15m" -> "15-minute"
        "30m" -> "30-minute"
        "1h" -> "1-hour"
        "4h" -> "4-hour"
        "1d" -> "1-day"
        else -> label
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
