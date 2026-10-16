package com.ikverse.signallab.model

import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.ledger.CallRow
import com.ikverse.signallab.ledger.ExitRow
import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.view.common.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class Period(val label: String, val days: Int?) { MONTH("1 month", 30), QUARTER("3 months", 90), ALL("All", null) }

/** One point of an account curve: the time and the account's value then. */
data class Point(val time: Long, val value: Double)

/** One strategy's line in the ledger. */
data class StrategyLine(
    val id: String,
    val name: String,
    val closed: Int,
    val up: Int,
    val open: Int,
    /** The average result after fees minus what random entries did, per call. */
    val meanExcess: Double?,
    val netUsd: Double,
    /** Where it stands, in a sentence. */
    val standing: String,
    val testing: Boolean,
)

data class CoinLine(val symbol: String, val closed: Int, val up: Int, val netUsd: Double)

data class LedgerUi(
    val loaded: Boolean = false,
    val period: Period = Period.QUARTER,
    val start: Double = 0.0,
    val value: Double = 0.0,
    /** The same calls with each one's result replaced by its random entries' average. */
    val randomValue: Double = 0.0,
    val curve: List<Point> = emptyList(),
    val randomCurve: List<Point> = emptyList(),
    val deepestDrop: Double = 0.0,
    val strategies: List<StrategyLine> = emptyList(),
    val coins: List<CoinLine> = emptyList(),
    val imported: Int = 0,
    val since: Long? = null,
) {
    val change: Double get() = if (start > 0) value / start - 1 else 0.0
    val randomChange: Double get() = if (start > 0) randomValue / start - 1 else 0.0
}

/**
 * The paper account: every finished Signal Lab 2 call at its size, added up in the order they finished, beside the same
 * calls valued at their random entries. A call recorded with no size (the account was full) counts in the strategy lines
 * but not in the account.
 */
class LedgerModel(
    dao: LedgerDao,
    preferences: Flow<Preferences>,
    private val strategies: List<Strategy>,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val period = MutableStateFlow(Period.QUARTER)

    fun choose(p: Period) {
        period.value = p
    }

    val state: StateFlow<LedgerUi> = combine(dao.watchLiveCalls(), dao.watchLiveExits(), preferences, period, dao.watchImportedCalls()) { calls, exits, prefs, p, imported ->
        build(calls, exits, prefs, p, imported)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), LedgerUi())

    /** The ledger as a spreadsheet: one row per finished call. */
    fun csv(calls: List<CallRow>, exits: List<ExitRow>, account: Double): String = buildString {
        appendLine("strategy,symbol,signal_day,entry_time,entry_price,size_fraction,size_usd,exit_time,exit_price,reason,net,random_mean,excess,net_usd")
        val by = exits.associateBy { it.callId }
        for (c in calls) {
            val e = by[c.id] ?: continue
            val size = (c.sizeFraction ?: 0.0) * account
            appendLine(listOf(c.strategyId, c.symbol, Words.day(c.signalTime), c.entryTime, c.entryPrice, c.sizeFraction ?: 0.0, size,
                e.exitTime, e.exitPrice, e.reason, e.net, e.randomMean ?: "", e.excess ?: "", e.net * size).joinToString(","))
        }
    }

    private fun build(calls: List<CallRow>, exits: List<ExitRow>, prefs: Preferences, p: Period, imported: Int): LedgerUi {
        val from = p.days?.let { now() - it * DAY } ?: Long.MIN_VALUE
        val callBy = calls.associateBy { it.id }
        val done = exits.filter { it.exitTime >= from }.sortedBy { it.exitTime }
        val start = prefs.accountSize
        var value = start
        var random = start
        var peak = start
        var drop = 0.0
        val first = done.firstOrNull()?.let { callBy[it.callId]?.entryTime } ?: calls.firstOrNull()?.entryTime
        val curve = ArrayList<Point>().apply { first?.let { add(Point(it, start)) } }
        val randomCurve = ArrayList<Point>().apply { first?.let { add(Point(it, start)) } }
        for (e in done) {
            val c = callBy[e.callId] ?: continue
            val size = (c.sizeFraction ?: 0.0) * start
            value += e.net * size
            random += (e.randomMean ?: e.net) * size
            peak = maxOf(peak, value)
            drop = minOf(drop, value / peak - 1)
            curve.add(Point(e.exitTime, value))
            randomCurve.add(Point(e.exitTime, random))
        }
        val exitBy = exits.associateBy { it.callId }
        val lines = strategies.map { s ->
            val mine = calls.filter { it.strategyId == s.id }
            val fin = mine.mapNotNull { c -> exitBy[c.id]?.takeIf { it.exitTime >= from }?.let { c to it } }
            val excess = fin.mapNotNull { it.second.excess }
            val mean = if (excess.isEmpty()) null else excess.average()
            val net = fin.sumOf { (c, e) -> e.net * (c.sizeFraction ?: 0.0) * start }
            StrategyLine(s.id, s.name, fin.size, fin.count { it.second.net > 0 }, mine.count { exitBy[it.id] == null }, mean, net,
                standing(fin.size, mean), testing = !prefs.isOn(s))
        }.filter { it.closed > 0 || it.open > 0 }
        val coins = calls.mapNotNull { c -> exitBy[c.id]?.takeIf { it.exitTime >= from }?.let { c to it } }.groupBy { it.first.symbol }
            .map { (sym, list) -> CoinLine(sym, list.size, list.count { it.second.net > 0 }, list.sumOf { (c, e) -> e.net * (c.sizeFraction ?: 0.0) * start }) }
            .sortedByDescending { it.netUsd }
        return LedgerUi(true, p, start, value, random, curve, randomCurve, drop, lines, coins, imported, first)
    }

    companion object {
        private const val DAY = 86_400_000L

        /** No live verdict below this many finished calls (docs/strategy-research.md §4). */
        const val MIN_CALLS = 100

        /** Where a strategy stands live, in a sentence. */
        fun standing(closed: Int, meanExcess: Double?): String {
            val more = MIN_CALLS - closed
            return when {
                closed < 10 || meanExcess == null -> "Too few calls to say anything yet."
                more > 0 && meanExcess > 0 -> "Ahead so far. About $more more calls before a live verdict."
                more > 0 -> "Behind so far. About $more more calls before a live verdict."
                meanExcess > 0 -> "Ahead of random entries over $closed calls."
                else -> "Behind random entries over $closed calls."
            }
        }
    }
}
