package com.ikverse.signallab.model

import com.ikverse.signallab.analyst.Suggestions
import com.ikverse.signallab.catalog.StrategyCatalog
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.futures.DerivativesStore
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.strategy.AnalystCall
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.Tier
import com.ikverse.signallab.engine.strategy.dsl.CustomStrategy
import com.ikverse.signallab.ledger.AnalystNotes
import com.ikverse.signallab.ledger.SuggestionState
import com.ikverse.signallab.ledger.CallRow
import com.ikverse.signallab.ledger.ExitRow
import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.runner.DailyCloseScheduler
import com.ikverse.signallab.runner.StrategyRunner
import com.ikverse.signallab.view.common.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One call as a card shows it. */
data class CallUi(
    val id: Long,
    val symbol: String,
    val strategyId: String,
    val strategyName: String,
    val tier: Tier,
    val signalTime: Long,
    val entryTime: Long,
    val entry: Double,
    val stop: Double?,
    val sizeFraction: Double,
    val sizeUsd: Double,
    /** Why it fired, in a sentence. */
    val why: String,
    /** Its exit in a few words. */
    val exitShort: String,
    /** The last day it may be held. */
    val lastDay: Long,
    /** Its strategy reads hourly candles rather than daily ones. */
    val hourly: Boolean = false,
) {
    val base get() = Words.base(symbol)
    val stopFraction: Double? get() = stop?.let { it / entry - 1 }
}

/** An open call, or one that has to be left now. */
data class OpenUi(
    val call: CallUi,
    val price: Double?,
    val change: Double?,
    val pnlUsd: Double?,
    /** What to do, in a sentence: hold and the line to watch, or leave now and at what price. */
    val instruction: String,
    /** It has to be left now. */
    val leaveNow: Boolean,
    val daysHeld: Int,
)

/** A call that ended. */
data class ClosedUi(val call: CallUi, val exitTime: Long, val exitPrice: Double, val reason: String, val net: Double, val netUsd: Double) {
    val reasonWords: String
        get() = when (reason) {
            "stop" -> "safety stop"
            "signal" -> "exit signal"
            else -> "time limit"
        }
}

/** A call the analyst suggested, waiting for the owner, or recorded and waiting for the next daily open. */
data class SuggestionUi(
    val id: String,
    val symbol: String,
    val stopPct: Double?,
    val targetPct: Double?,
    val days: Int,
    val thesis: String,
    val createdAt: Long,
    /** Recorded by the owner: it enters at the next daily open. */
    val recorded: Boolean,
    val price: Double?,
) {
    val base get() = Words.base(symbol)

    /** "Stop −6% · target +12% · up to 20 days". */
    val plan: String
        get() = listOfNotNull(
            stopPct?.let { "stop ${Words.pct(-it, 0)}" }, targetPct?.let { "target ${Words.pct(it, 0)}" }, "up to ${Words.count(days, "day")}",
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
}

data class TodayUi(
    val loaded: Boolean = false,
    val market: MarketLine = MarketLine(null, null, null, null, 0, 0),
    val newCalls: List<CallUi> = emptyList(),
    val open: List<OpenUi> = emptyList(),
    val closedThisWeek: List<ClosedUi> = emptyList(),
    /** Today's move of the open calls, in dollars of the paper account. */
    val todayUsd: Double = 0.0,
    val lastPass: Long? = null,
    val nextPass: Long = 0,
    val context: String? = null,
    /** The analyst's suggested calls waiting for the owner, and recorded ones waiting for the next daily open. */
    val suggestions: List<SuggestionUi> = emptyList(),
    /** Dated lines of context from the analyst, such as "Wed 21 Oct · US inflation figures". */
    val contextLines: List<String> = emptyList(),
)

/** Builds what Today shows from the ledger, the candles and the person's choices. */
class TodayModel(
    private val dao: LedgerDao,
    preferences: Flow<Preferences>,
    private val candles: CandleStore,
    private val derivatives: DerivativesStore?,
    /** Every strategy a call in the ledger can belong to, as it is now. */
    private val strategies: () -> List<Strategy>,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val suggestions: Suggestions? = null,
) {
    /** The owner records a suggested call: it enters at the next daily open. */
    fun record(id: String) {
        scope.launch { suggestions?.record(id) }
    }

    fun dismiss(id: String) {
        scope.launch { suggestions?.dismiss(id) }
    }

    /** A minute's tick, so "today" and the next pass time move on while the screen is open. */
    private val minute = flow {
        while (true) {
            emit(now())
            delay(60_000)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TodayUi> = combine(dao.watchLiveCalls(), dao.watchLiveExits(), preferences, candles.version, minute) { calls, exits, prefs, _, t ->
        Inputs(calls, exits, prefs, t)
    }.mapLatest { build(it) }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), TodayUi())

    private class Inputs(val calls: List<CallRow>, val exits: List<ExitRow>, val prefs: Preferences, val t: Long)

    private suspend fun build(i: Inputs): TodayUi {
        val btc = candles.window(DataConfig.REGIME_COIN, Timeframe.D1)
        val funding = derivatives?.funding(DataConfig.REGIME_COIN, i.t - 2 * DAY)?.lastOrNull()?.rate
        val exitBy = i.exits.associateBy { it.callId }
        val windows = HashMap<String, Candles?>()
        suspend fun window(symbol: String) = windows.getOrPut(symbol) { candles.window(symbol, Timeframe.D1) }

        val all = strategies()
        val byId = all.associateBy { it.id }
        val theses = i.prefs.suggestions.filter { it.callId != null }.associate { it.callId to it.thesis }
        val shown = i.calls.filter { c -> StrategyCatalog.forCall(c, byId)?.let(i.prefs::isOn) == true }
        val newest = shown.filter { exitBy[it.id] == null }.maxOfOrNull { it.entryTime }
        val newCalls = ArrayList<CallUi>()
        val open = ArrayList<OpenUi>()
        val closed = ArrayList<ClosedUi>()
        var todayUsd = 0.0
        for (row in shown) {
            val s = StrategyCatalog.forCall(row, byId) ?: continue
            val c = window(row.symbol)
            val ui = callUi(row, s, c, i.prefs.accountSize, i.prefs.tierOf(s), theses[row.id])
            val exit = exitBy[row.id]
            if (exit != null) {
                val item = ClosedUi(ui, exit.exitTime, exit.exitPrice, exit.reason, exit.net, exit.net * ui.sizeUsd)
                if (i.t - exit.closedAt < DAY) {
                    open.add(OpenUi(ui, exit.exitPrice, exit.net, exit.net * ui.sizeUsd,
                        "Leave now: ${item.reasonWords}. Paper exit at ${Words.price(exit.exitPrice)}, ${Words.pct(exit.net)} after fees.",
                        leaveNow = true, daysHeld = exit.barsHeld))
                } else if (i.t - exit.exitTime < 7 * DAY) closed.add(item)
                continue
            }
            if (row.entryTime == newest && i.t - row.openedAt < DAY) {
                newCalls.add(ui)
                continue
            }
            val price = c?.close?.lastOrNull()
            val change = price?.let { it / row.entryPrice - 1 }
            if (c != null && c.size >= 2 && c.t.last() >= row.entryTime) todayUsd += (c.close[c.size - 1] / c.close[c.size - 2] - 1) * ui.sizeUsd
            open.add(OpenUi(ui, price, change, change?.let { it * ui.sizeUsd },
                (if (s.id == AnalystCall.id) analystHold(row.stop, row.target) else c?.let { StrategyTexts.holdInstruction(s, it) } ?: "Hold.") +
                    " Time limit ${Words.day(ui.lastDay)}.",
                leaveNow = false, daysHeld = c?.let { cc -> cc.t.count { it >= row.entryTime } } ?: 0))
        }
        val lastPass = dao.setting(StrategyRunner.LAST_PASS)?.toLongOrNull()
        val waiting = i.prefs.suggestions.filter {
            it.state == SuggestionState.ACCEPTED || it.state == SuggestionState.SUGGESTED && i.t - it.createdAt < AnalystNotes.SUGGESTION_LIFETIME_MS
        }
        val suggested = waiting.map {
            SuggestionUi(it.id, it.symbol, it.stopPct, it.targetPct, it.days, it.thesis, it.createdAt, it.state == SuggestionState.ACCEPTED,
                window(it.symbol)?.close?.lastOrNull())
        }
        val lines = i.prefs.contextLines.filter { it.until >= Math.floorDiv(i.t, DAY) * DAY }.map { "${Words.day(it.until)} · ${it.text}" }
        return TodayUi(
            loaded = true,
            market = MarketLine.of(btc, funding, all.filter { it !is CustomStrategy && it.id != AnalystCall.id }),
            newCalls = newCalls.sortedByDescending { it.sizeFraction },
            open = open.sortedWith(compareByDescending<OpenUi> { it.leaveNow }.thenBy { it.call.entryTime }),
            closedThisWeek = closed.sortedByDescending { it.exitTime },
            todayUsd = todayUsd,
            lastPass = lastPass,
            nextPass = DailyCloseScheduler.nextRunAt(i.t),
            suggestions = suggested,
            contextLines = lines,
        )
    }

    companion object {
        const val DAY = 86_400_000L

        /** A ledger row as a card, with the words its strategy gives it. */
        /** What to do with an open call of the analyst's: the levels it leaves at. */
        fun analystHold(stop: Double?, target: Double?): String = listOfNotNull(
            stop?.let { "stop ${Words.price(it)}" }, target?.let { "target ${Words.price(it)}" },
        ).let { if (it.isEmpty()) "Hold until its time limit." else "Hold. It leaves at its ${it.joinToString(" or ")}." }

        fun callUi(row: CallRow, s: Strategy, c: Candles?, account: Double, tier: Tier = s.launchTier, note: String? = null): CallUi {
            val i = c?.t?.indexOfFirst { it == row.signalTime } ?: -1
            val size = row.sizeFraction ?: 0.0
            return CallUi(
                id = row.id, symbol = row.symbol, strategyId = s.id, strategyName = s.name, tier = tier,
                signalTime = row.signalTime, entryTime = row.entryTime, entry = row.entryPrice, stop = row.stop,
                sizeFraction = size, sizeUsd = size * account,
                why = note ?: if (c != null && i >= 0) StrategyTexts.why(s, c, i) else "${s.name} fired on ${Words.day(row.signalTime)}.",
                exitShort = StrategyTexts.exitShort(s), lastDay = row.entryTime + (row.maxBars - 1) * s.timeframe.ms,
                hourly = s.timeframe == Timeframe.H1,
            )
        }
    }
}
