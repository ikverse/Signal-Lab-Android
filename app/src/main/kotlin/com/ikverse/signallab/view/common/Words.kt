package com.ikverse.signallab.view.common

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** How numbers and times read on screen. Plain functions, so tests hold them to their words. */
object Words {
    private val day = DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneOffset.UTC)
    private val dayLong = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK).withZone(ZoneOffset.UTC)
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.UK).withZone(ZoneOffset.UTC)

    /** A true minus sign rather than a hyphen. */
    private fun minus(s: String) = s.replace('-', '−')

    /** A price with as many digits as its size needs. */
    fun price(p: Double?): String {
        if (p == null || p.isNaN()) return "–"
        val a = abs(p)
        return when {
            a >= 1000 -> String.format(Locale.US, "%,.2f", p)
            a >= 1 -> String.format(Locale.US, "%.2f", p)
            a >= 0.01 -> String.format(Locale.US, "%.4f", p)
            a == 0.0 -> "0"
            else -> String.format(Locale.US, "%.8f", p).trimEnd('0')
        }.let(::minus)
    }

    /** A signed percentage with one decimal: "+6.8%", "−1.2%". */
    fun pct(f: Double, decimals: Int = 1): String = minus(String.format(Locale.US, "%+.${decimals}f%%", f * 100))

    /** An unsigned share: "20%". */
    fun share(f: Double): String = "${Math.round(f * 100)}%"

    /** Dollars, signed when [signed]: "$2,000", "+$61", "−$300". */
    fun usd(v: Double, signed: Boolean = false): String {
        val body = String.format(Locale.US, "$%,.0f", abs(v))
        return when {
            !signed -> if (v < 0) "−$body" else body
            v > 0.5 -> "+$body"
            v < -0.5 -> "−$body"
            else -> body
        }
    }

    /** A price to edit: eight significant digits, no grouping, no trailing zeros: "142.3", "0.00004521". */
    fun plain(p: Double): String =
        java.math.BigDecimal(p).round(java.math.MathContext(8)).stripTrailingZeros().toPlainString()

    /** An amount to type into Binance's order screen: "412 USDT". */
    fun usdt(v: Double): String = String.format(Locale.US, "%,.0f USDT", v)

    /** A coin quantity as Binance shows it, with no trailing zeros: "2.89", "0.00052", "1250". */
    fun quantity(q: Double): String = java.math.BigDecimal(q.toString()).stripTrailingZeros().toPlainString()

    fun day(t: Long): String = day.format(Instant.ofEpochMilli(t))

    fun dayLong(t: Long): String = dayLong.format(Instant.ofEpochMilli(t))

    fun utc(t: Long): String = clock.format(Instant.ofEpochMilli(t)) + " UTC"

    fun base(symbol: String): String = symbol.removeSuffix("USDT")

    /** "1 call" / "3 calls". */
    fun count(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"
}
