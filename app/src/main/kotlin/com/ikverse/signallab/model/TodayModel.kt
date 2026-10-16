package com.ikverse.signallab.model

import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.futures.DerivativesStore
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.Tier
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
)

/** Builds what Today shows from the ledger, the candles and the person's choices. */
class TodayModel(
    private val dao: LedgerDao,
    preferences: Flow<Preferences>,
    private val candles: CandleStore,
    private val derivatives: DerivativesStore?,
    private val strategies: List<Strategy>,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val byId = strategies.associateBy { it.id }

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

        val shown = i.calls.filter { c -> byId[c.strategyId]?.let(i.prefs::isOn) == true }
        val newest = shown.filter { exitBy[it.id] == null }.maxOfOrNull { it.entryTime }
        val newCalls = ArrayList<CallUi>()
        val open = ArrayList<OpenUi>()
        val closed = ArrayList<ClosedUi>()
        var todayUsd = 0.0
        for (row in shown) {
            val s = byId.getValue(row.strategyId)
            val c = window(row.symbol)
            val ui = callUi(row, s, c, i.prefs.accountSize)
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
            if (row.entryTime == newest && newest != null && i.t - row.openedAt < DAY) {
                newCalls.add(ui)
                continue
            }
            val price = c?.close?.lastOrNull()
            val change = price?.let { it / row.entryPrice - 1 }
            if (c != null && c.size >= 2 && c.t.last() >= row.entryTime) todayUsd += (c.close[c.size - 1] / c.close[c.size - 2] - 1) * ui.sizeUsd
            open.add(OpenUi(ui, price, change, change?.let { it * ui.sizeUsd },
                (c?.let { StrategyTexts.holdInstruction(s, it) } ?: "Hold.") + " Time limit ${Words.day(ui.lastDay)}.",
                leaveNow = false, daysHeld = c?.let { cc -> cc.t.count { it >= row.entryTime } } ?: 0))
        }
        val lastPass = dao.setting(StrategyRunner.LAST_PASS)?.toLongOrNull()
        return TodayUi(
            loaded = true,
            market = MarketLine.of(btc, funding, strategies),
            newCalls = newCalls.sortedByDescending { it.sizeFraction },
            open = open.sortedWith(compareByDescending<OpenUi> { it.leaveNow }.thenBy { it.call.entryTime }),
            closedThisWeek = closed.sortedByDescending { it.exitTime },
            todayUsd = todayUsd,
            lastPass = lastPass,
            nextPass = DailyCloseScheduler.nextRunAt(i.t),
        )
    }

    companion object {
        const val DAY = 86_400_000L

        /** A ledger row as a card, with the words its strategy gives it. */
        fun callUi(row: CallRow, s: Strategy, c: Candles?, account: Double): CallUi {
            val i = c?.t?.indexOfFirst { it == row.signalTime } ?: -1
            val size = row.sizeFraction ?: 0.0
            return CallUi(
                id = row.id, symbol = row.symbol, strategyId = s.id, strategyName = s.name, tier = s.launchTier,
                signalTime = row.signalTime, entryTime = row.entryTime, entry = row.entryPrice, stop = row.stop,
                sizeFraction = size, sizeUsd = size * account,
                why = if (c != null && i >= 0) StrategyTexts.why(s, c, i) else "${s.name} fired on ${Words.day(row.signalTime)}.",
                exitShort = StrategyTexts.exitShort(s), lastDay = row.entryTime + (row.maxBars - 1) * DAY,
            )
        }
    }
}
