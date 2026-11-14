package com.ikverse.signallab.runner

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The morning plan: one notification at an hour the owner chose, listing what to sell and what to buy since the last plan,
 * instead of an alert for each call as it happens. The daily close is at 00:00 UTC, the middle of the night in Cairo; a plan
 * at breakfast is one thing to act on. Research, not advice: the app trades on paper only.
 */
object MorningPlan {
    /** Something to sell: an open call that has left on paper. */
    class Sell(val base: String, val strategy: String, val why: String)

    /** Something to buy: a new call, with the amount in USDT when the owner's money is set, and its safety stop if it has one. */
    class Buy(val base: String, val strategy: String, val usdt: Double?, val stop: String?)

    /** The notification's id, which no call has. */
    const val ID = -1L

    /** Settings: whether the plan replaces single alerts, its hour on the phone's clock, and when the last one was made. */
    const val ON = "plan_on"
    const val HOUR = "plan_hour"
    const val DEFAULT_HOUR = 8
    const val LAST_DAY = "plan_day"
    const val LAST_AT = "plan_at"

    /**
     * The local day (days since 1970) whose plan is due at [now]: today's once [hour] has come, unless [lastDay] says it
     * was already made; null when nothing is due.
     */
    fun dueDay(now: Long, zone: ZoneId, hour: Int, lastDay: Long?): Long? {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        if (local.hour < hour) return null
        val today = local.toLocalDate().toEpochDay()
        return if (lastDay == today) null else today
    }

    /** The plan in words, sells first; null when there is nothing to do. */
    fun words(sells: List<Sell>, buys: List<Buy>): CallAlert? {
        if (sells.isEmpty() && buys.isEmpty()) return null
        val title = "Today's plan: " + listOfNotNull(
            sells.size.takeIf { it > 0 }?.let { "$it to sell" }, buys.size.takeIf { it > 0 }?.let { "$it to buy" },
        ).joinToString(", ")
        val lines = sells.map { "Sell ${it.base} (${it.strategy}): ${it.why}." } + buys.map { b ->
            val amount = b.usdt?.let { String.format(Locale.US, "%,.0f USDT of ", Math.floor(it)) } ?: ""
            "Buy $amount${b.base} (${b.strategy})" + (b.stop?.let { ", then place a stop at $it" } ?: "") + "."
        }
        return CallAlert(ID, title, lines.joinToString("\n"), opensCall = false)
    }
}
