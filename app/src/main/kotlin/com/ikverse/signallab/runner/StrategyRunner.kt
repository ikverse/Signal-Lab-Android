package com.ikverse.signallab.runner

import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.UniverseRepository
import com.ikverse.signallab.data.binance.MarketData
import com.ikverse.signallab.data.futures.DerivativesSync
import com.ikverse.signallab.data.history.DailyHistory
import com.ikverse.signallab.data.universe.Universe
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.eval.Live
import com.ikverse.signallab.engine.eval.NewCall
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.ledger.CallRow
import com.ikverse.signallab.ledger.ExitRow
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.ledger.SettingRow
import com.ikverse.signallab.ledger.StrategyRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one run did. */
class RunReport(
    val universe: List<String>,
    val newCalls: Int,
    val exits: Int,
    /** Coins whose history could not be brought up to date, with why. */
    val failures: Map<String, String>,
    /** A sentence when the run could not do its job at all; null when it ran. */
    val problem: String? = null,
)

/**
 * Signal Lab 2's daily pass, run after each daily close. In order: the pair list is refreshed if stale, every offered
 * coin's daily history is brought up to date (the cross-coin rankings and the universe need all of them), the universe is
 * chosen, open calls are checked for their exits, and each strategy's signals on the newest closed candle become calls
 * that enter at the open of the candle now forming. Each call and exit is notified when its strategy is switched on
 * (by hand, or by being Proven or Promising) and notifications are wanted. Every call is recorded either way.
 *
 * Running it twice changes nothing the second time: every call and exit is keyed, so a repeat is ignored.
 */
class StrategyRunner(
    private val market: MarketData,
    private val pairs: UniverseRepository,
    private val candles: CandleStore,
    private val history: DailyHistory,
    private val ledger: LedgerDatabase,
    private val alerts: CallAlerts,
    private val derivatives: DerivativesSync? = null,
    private val strategies: List<Strategy> = Builtins.all,
    /** The person's costs, switches and notification choice, read at the start of each run. */
    private val preferences: suspend () -> Preferences = { Preferences() },
) {
    private val lock = Mutex()
    private val dao get() = ledger.dao()

    suspend fun run(): RunReport = lock.withLock { runLocked() }

    private suspend fun runLocked(): RunReport {
        val prefs = preferences()
        market.serverTime()
        pairs.refreshIfStale()
        val offered = pairs.top(OFFERED_LIMIT)
        val symbols = (listOf(DataConfig.REGIME_COIN) + offered.map { it.symbol }).distinct()
        val open = dao.openCalls()
        val synced = history.ensure((symbols + open.map { it.symbol }).distinct())
        if (synced.blocked) return RunReport(emptyList(), 0, 0, synced.failures, problem = "Binance refuses this network.")
        val now = market.nowMs()
        val btc = candles.window(DataConfig.REGIME_COIN, Timeframe.D1)
            ?: return RunReport(emptyList(), 0, 0, synced.failures, problem = "No Bitcoin daily history yet.")

        val pins = dao.pins()
        val universe = Universe.select(
            offered, candles.quoteVolumeSince(symbols, now, 30), candles.firstOpens(Timeframe.D1, symbols), now,
            pins = pins.filter { it.kind == "pin" }.map { it.symbol }.toSet(),
            excludes = pins.filter { it.kind == "exclude" }.map { it.symbol }.toSet(),
        ).map { it.symbol }

        for (s in strategies) {
            dao.insertStrategy(StrategyRow(s.id, s.name, s.timeframe.label, "{}", now, retired = false, source = "v2"))
        }
        val byId = strategies.associateBy { it.id }
        val openBy = open.groupBy { it.symbol }
        var openCount = open.size
        var newCalls = 0
        var exits = 0

        for (symbol in (universe + openBy.keys).distinct()) {
            val c = candles.window(symbol, Timeframe.D1) ?: continue
            // The candle now forming must be the one right after the newest stored one, or its open is not the entry price.
            val formingOpen = synced.forming[symbol]?.takeIf { it.openTime == c.t.last() + Timeframe.D1.ms }?.open

            for (row in openBy[symbol].orEmpty()) {
                val s = byId[row.strategyId] ?: continue
                val closed = Live.close(s, row.toNewCall(), c, btc, formingOpen) ?: continue
                val inserted = dao.insertExit(ExitRow(
                    callId = row.id, exitTime = closed.exitTime, exitPrice = closed.exitPrice, reason = closed.reason.label,
                    barsHeld = closed.barsHeld, gross = closed.gross, net = closed.net,
                    randomMean = closed.randomMean.takeUnless { it.isNaN() }, excess = closed.excess.takeUnless { it.isNaN() },
                    maxUp = closed.maxUp, maxDown = closed.maxDown, barsToPeak = null, closedAt = now,
                ))
                if (inserted == -1L) continue
                exits++
                openCount--
                if (prefs.notifications && prefs.isOn(s)) alerts.post(CallWords.exit(row.id, s.name, symbol, closed.reason.label, closed.exitPrice, closed.net))
            }

            if (symbol !in universe || formingOpen == null) continue
            val entryTime = c.t.last() + Timeframe.D1.ms
            for (s in strategies) {
                if (s.timeframe != Timeframe.D1) continue
                val latest = dao.latestCall(s.id, symbol)
                // Busy while a call is open, or when the last one leaves at the very open a new one would enter at.
                val busy = latest != null && (dao.exitOf(latest.id)?.let { it.exitTime >= entryTime } ?: true)
                val call = Live.signal(s, c, btc, formingOpen, busy, prefs.costFor(symbol)) ?: continue
                val size = if (openCount >= MAX_OPEN_CALLS) 0.0 else call.sizeFraction
                val id = dao.insertCall(CallRow(
                    strategyId = call.strategyId, symbol = symbol, tf = s.timeframe.label, signalTime = call.signalTime,
                    entryTime = call.entryTime, entryPrice = call.entryPrice, atr = call.atr, stop = call.stop, target = null,
                    maxBars = call.maxBars, regime = call.regime, sizeFraction = size, cost = call.cost, source = "v2",
                    archived = false, status = "live", openedAt = now,
                ))
                if (id == -1L) continue
                newCalls++
                openCount++
                if (prefs.notifications && prefs.isOn(s)) alerts.post(CallWords.newCall(id, s.name, symbol, call.entryPrice, call.stop, size))
            }
        }

        // Funding and open interest for the market line and the warnings; a failure here never stops the calls.
        derivatives?.let { d ->
            for (symbol in DERIVATIVES_COINS) {
                runCatching { d.syncFunding(symbol); d.syncOpenInterest(symbol) }
            }
        }
        dao.putSetting(SettingRow(LAST_PASS, now.toString(), now))
        dao.putSetting(SettingRow(UNIVERSE, universe.joinToString(","), now))
        return RunReport(universe, newCalls, exits, synced.failures)
    }

    private fun CallRow.toNewCall() = NewCall(
        strategyId, symbol, signalTime, entryTime, entryPrice, atr ?: Double.NaN, stop, maxBars, regime, sizeFraction ?: 0.0, cost,
    )

    companion object {
        /** The pair list is a few hundred coins; this only bounds a read. */
        const val OFFERED_LIMIT = 2000

        /** The paper account holds at most this many calls at once; later calls are tracked with no size. */
        const val MAX_OPEN_CALLS = 8

        val DERIVATIVES_COINS = listOf("BTCUSDT", "ETHUSDT")

        /** Settings the runner leaves for the screens: when it last ran, and the coins it watched. */
        const val LAST_PASS = "last_pass_at"
        const val UNIVERSE = "universe"
    }
}
