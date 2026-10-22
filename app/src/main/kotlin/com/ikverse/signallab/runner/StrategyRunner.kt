package com.ikverse.signallab.runner

import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.CandleSync
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.UniverseRepository
import com.ikverse.signallab.data.binance.MarketData
import com.ikverse.signallab.data.futures.DerivativesStore
import com.ikverse.signallab.data.futures.DerivativesSync
import com.ikverse.signallab.data.futures.NoticeKind
import com.ikverse.signallab.engine.eval.Exposure
import com.ikverse.signallab.engine.eval.Heat
import com.ikverse.signallab.engine.eval.Holding
import com.ikverse.signallab.engine.eval.Risk
import com.ikverse.signallab.model.FuturesContext
import com.ikverse.signallab.data.history.DailyHistory
import com.ikverse.signallab.data.universe.Universe
import com.ikverse.signallab.data.universe.UniverseCoin
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.eval.Backtest
import com.ikverse.signallab.engine.eval.Live
import com.ikverse.signallab.engine.eval.NewCall
import com.ikverse.signallab.engine.eval.Tiers
import com.ikverse.signallab.catalog.StrategyCatalog
import com.ikverse.signallab.engine.strategy.AnalystCall
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.engine.strategy.FundingSeries
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.ledger.CallRow
import com.ikverse.signallab.ledger.ExitRow
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.ledger.SettingRow
import com.ikverse.signallab.ledger.StrategyRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A call the owner recorded from the analyst's suggestion, waiting for the next daily open. */
class PendingCall(val id: String, val symbol: String, val stopPct: Double?, val targetPct: Double?, val days: Int, val acceptedAt: Long)

/** Where the runner finds the analyst's calls the owner has recorded, and tells what became of them. */
interface PendingCalls {
    suspend fun accepted(): List<PendingCall>

    suspend fun opened(id: String, callId: Long, now: Long)

    suspend fun expired(id: String, why: String, now: Long)
}

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
 * Signal Lab 2's pass, run just after every hourly close.
 *
 * Once per daily close, the daily pass: the pair list is refreshed if stale, every offered coin's daily history is brought
 * up to date (the universe and the cross-coin ranking need all of them), the universe is chosen, open daily calls are
 * checked for their exits, each daily strategy's signals on the newest closed candle become calls that enter at the open
 * of the candle now forming, and every strategy's tier is recomputed from its evidence. Every hour, the hourly pass does
 * the same for hourly strategies on the universe's hourly candles.
 *
 * A call or exit is notified when its strategy is on (by hand, or by its tier) and notifications are wanted; every call is
 * recorded either way. Running it twice changes nothing the second time: every call and exit is keyed.
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
    /** For the hourly pass; without it hourly strategies do not run. */
    private val hourly: CandleSync? = null,
    /** The custom strategies the owner has set running, read at the start of each run. */
    private val customs: suspend (Preferences) -> List<Strategy> = { emptyList() },
    /** The analyst's calls the owner has recorded; without it none are opened. */
    private val pending: PendingCalls? = null,
    /** Funding, basis, implied volatility and exchange notices, for the sizing engine and the vetoes; null leaves them out. */
    private val futures: DerivativesStore? = null,
) {
    private val lock = Mutex()
    private val dao get() = ledger.dao()

    /** The strategies of the run in progress: the built-ins and the custom strategies running. */
    private var active: List<Strategy> = strategies
    private var byId: Map<String, Strategy> = strategies.associateBy { it.id }

    suspend fun run(): RunReport = lock.withLock { runLocked() }

    /** [sized]: open calls that hold part of the account; a call tracked with no size takes no room. */
    private class Tally(var newCalls: Int = 0, var exits: Int = 0, var sized: Int = 0, var risk: RiskDay? = null)

    /**
     * What the sizing engine and the vetoes know during one daily pass: the leverage heat, the coins with a delisting
     * notice, Bitcoin's daily variance, the cap from the live record, and the open daily calls with how their coins move.
     */
    private class RiskDay(
        val heat: Heat,
        val delisted: Set<String>,
        val btcVar: Double,
        val kelly: Double?,
        val book: MutableMap<Long, Holding>,
        val btc: Candles,
    ) {
        private val exposures = HashMap<String, Exposure?>()

        fun exposureOf(symbol: String, c: Candles): Exposure? = exposures.getOrPut(symbol) { Risk.exposure(c.close, btc.close) }
    }

    private suspend fun runLocked(): RunReport {
        val prefs = preferences()
        market.serverTime()
        val now = market.nowMs()
        val custom = runCatching { customs(prefs) }.getOrDefault(emptyList())
        active = strategies + custom.filter { c -> strategies.none { it.id == c.id } }
        byId = active.associateBy { it.id } + (AnalystCall.id to AnalystCall)
        for (s in strategies) dao.insertStrategy(StrategyRow(s.id, s.name, s.timeframe.label, "{}", now, retired = false, source = "v2"))
        for (s in custom) dao.insertStrategy(StrategyRow(s.id, s.name, s.timeframe.label, "{}", now, retired = false, source = "custom"))
        val open = dao.openCalls()
        val tally = Tally(sized = open.count { it.holdsRoom() })
        val lastDay = Math.floorDiv(now, DAY) * DAY - DAY
        var report = RunReport(storedUniverse(), 0, 0, emptyMap())
        if (dao.setting(DAILY_DONE)?.toLongOrNull() != lastDay) {
            report = dailyPass(prefs, open, now, tally)
            if (report.problem != null) return report
            dao.putSetting(SettingRow(DAILY_DONE, lastDay.toString(), now))
        }
        hourlyPass(prefs, open, now, tally)
        dao.putSetting(SettingRow(LAST_PASS, now.toString(), now))
        return RunReport(report.universe, tally.newCalls, tally.exits, report.failures)
    }

    private suspend fun storedUniverse(): List<String> = dao.setting(UNIVERSE)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    private suspend fun dailyPass(prefs: Preferences, open: List<CallRow>, now: Long, tally: Tally): RunReport {
        pairs.refreshIfStale()
        val offered = pairs.top(OFFERED_LIMIT)
        val symbols = (listOf(DataConfig.REGIME_COIN) + offered.map { it.symbol }).distinct()
        val waiting = runCatching { pending?.accepted().orEmpty() }.getOrDefault(emptyList())
        val synced = history.ensure((symbols + open.map { it.symbol } + waiting.map { it.symbol }).distinct())
        if (synced.blocked) return RunReport(emptyList(), 0, 0, synced.failures, problem = "Binance refuses this network.")
        val btc = candles.window(DataConfig.REGIME_COIN, Timeframe.D1)
            ?: return RunReport(emptyList(), 0, 0, synced.failures, problem = "No Bitcoin daily history yet.")

        val pins = dao.pins()
        val universe: List<UniverseCoin> = Universe.select(
            offered, candles.quoteVolumeSince(symbols, now, 30), candles.firstOpens(Timeframe.D1, symbols), now,
            pins = pins.filter { it.kind == "pin" }.map { it.symbol }.toSet(),
            excludes = pins.filter { it.kind == "exclude" }.map { it.symbol }.toSet(),
        )
        val rankOf = universe.associate { it.symbol to it.rank }
        val panel = LinkedHashMap<String, Candles>()
        for (sym in (universe.map { it.symbol } + open.map { it.symbol } + waiting.map { it.symbol }).distinct()) {
            candles.window(sym, Timeframe.D1)?.let { panel[sym] = it }
        }
        // The futures context and notices first, so the calls of this close are sized and vetoed by them.
        derivatives?.let { d -> syncDerivatives(d, universe.map { it.symbol }) }
        loadFunding(panel.keys)
        tally.risk = riskDay(open, panel, btc, now)
        val daily = active.filter { it.timeframe == Timeframe.D1 && !it.retired }
        // A ranking compares the universe's coins at the same close, once for all of them.
        val ranked = daily.filter { it.crossSectional }.associate { s -> s.id to s.entriesAcross(panel.filterKeys { it in rankOf }) }

        // The candle now forming must be the one right after the newest stored one, or its open is not the entry price.
        fun formingOpenOf(symbol: String, c: Candles) = synced.forming[symbol]?.takeIf { it.openTime == c.t.last() + Timeframe.D1.ms }?.open
        // Every exit first, so a call leaving at this open frees its room before the new calls are sized.
        for ((symbol, c) in panel) {
            closeOpen(prefs, open.filter { it.symbol == symbol && it.tf == Timeframe.D1.label }, c, btc, formingOpenOf(symbol, c), now, tally)
        }
        for ((symbol, c) in panel) {
            val formingOpen = formingOpenOf(symbol, c)
            if (formingOpen != null) {
                for (p in waiting.filter { it.symbol == symbol }) openPending(prefs, p, c, btc, formingOpen, now, tally)
            }
            val rank = rankOf[symbol] ?: continue
            if (formingOpen == null) continue
            for (s in daily) {
                if (s.ranks != null && rank !in s.ranks!!) continue
                val fired = ranked[s.id]?.get(symbol)?.lastOrNull()
                openCall(prefs, s, symbol, c, btc, formingOpen, now, tally, fired)
            }
        }

        for (p in waiting.filter { it.symbol !in panel }) pending?.expired(p.id, "No daily history for ${p.symbol}.", now)
        dao.putSetting(SettingRow(UNIVERSE, universe.joinToString(",") { it.symbol }, now))
        runCatching { retier(prefs, panel.filterKeys { it in rankOf }, btc, now) }
        return RunReport(universe.map { it.symbol }, 0, 0, synced.failures)
    }

    /**
     * Futures and market context, once a day: funding for every watched coin (the funding-regime strategy and the market
     * line read it); open interest, basis, positioning and implied volatility for Bitcoin and Ether; and exchange notices.
     * Each piece fails alone: a refused feed never stops the pass.
     */
    private suspend fun syncDerivatives(d: DerivativesSync, watched: List<String>) {
        for (symbol in (DERIVATIVES_COINS + watched).distinct()) runCatching { d.syncFunding(symbol) }
        for (symbol in DERIVATIVES_COINS) {
            runCatching { d.syncOpenInterest(symbol) }
            runCatching { d.syncPositioning(symbol) }
            for (contract in BASIS_CONTRACTS) runCatching { d.syncBasis(symbol, contract) }
            runCatching { d.syncDvol(symbol.removeSuffix("USDT")) }
        }
        runCatching { d.syncNotices() }
    }

    /**
     * Hands the stored funding of [symbols] (default: the coins watched at the last pass) to the strategies that read it.
     * Without a store this does nothing, and they keep what they had.
     */
    suspend fun loadFunding(symbols: Collection<String>? = null) {
        val store = futures ?: return
        val coins = symbols ?: (storedUniverse() + DERIVATIVES_COINS).distinct()
        runCatching {
            Builtins.funding.update(coins.associateWith { sym ->
                val points = store.funding(sym)
                FundingSeries(LongArray(points.size) { points[it].time }, DoubleArray(points.size) { points[it].rate })
            }.filterValues { it.time.isNotEmpty() })
        }
    }

    /** The sizing engine's inputs for this daily pass (docs/strategy-research.md §7.1). */
    private suspend fun riskDay(open: List<CallRow>, panel: Map<String, Candles>, btc: Candles, now: Long): RiskDay {
        val context = runCatching { FuturesContext.read(futures, now) }.getOrNull()
        val delisted = runCatching { futures?.notices(now - NOTICE_DAYS * DAY).orEmpty() }.getOrDefault(emptyList())
            .filter { it.kind == NoticeKind.DELISTING }.map { it.coin }.toSet()
        val exits = dao.allExits().associateBy { it.callId }
        val liveNet = dao.allCalls().filter { c ->
            c.source == "v2" && (c.sizeFraction ?: 0.0) > 0 && byId[c.strategyId]?.retired != true
        }.mapNotNull { exits[it.id]?.net }.toDoubleArray()
        val day = RiskDay(context?.heat ?: Heat.NONE, delisted, Risk.btcVariance(btc.close), Risk.kellyCap(liveNet), HashMap(), btc)
        for (row in open) {
            if (row.tf != Timeframe.D1.label || !row.holdsRoom()) continue
            val c = panel[row.symbol] ?: continue
            day.exposureOf(row.symbol, c)?.let { day.book[row.id] = Holding(row.sizeFraction!!, it) }
        }
        return day
    }

    private suspend fun hourlyPass(prefs: Preferences, open: List<CallRow>, now: Long, tally: Tally) {
        val sync = hourly ?: return
        // A retired strategy opens nothing new, but its open calls are still followed to their exit.
        val hourlyStrategies = active.filter { it.timeframe == Timeframe.H1 && !it.retired }
        if (hourlyStrategies.isEmpty() && open.none { it.tf == Timeframe.H1.label }) return
        val btc = candles.window(DataConfig.REGIME_COIN, Timeframe.D1) ?: return
        val since = now - HOURLY_DAYS * DAY
        // With no hourly strategy making calls, only coins with an open hourly call are followed.
        val universe = if (hourlyStrategies.isEmpty()) emptyList() else storedUniverse()
        for (symbol in (universe + open.filter { it.tf == Timeframe.H1.label }.map { it.symbol }).distinct()) {
            val forming = runCatching { sync.syncCoin(symbol, Timeframe.H1, since).forming }.getOrNull()
            val c = candles.window(symbol, Timeframe.H1, since) ?: continue
            val formingOpen = forming?.takeIf { it.openTime == c.t.last() + Timeframe.H1.ms }?.open
            closeOpen(prefs, open.filter { it.symbol == symbol && it.tf == Timeframe.H1.label }, c, btc, formingOpen, now, tally)
            if (symbol !in universe || formingOpen == null) continue
            for (s in hourlyStrategies) openCall(prefs, s, symbol, c, btc, formingOpen, now, tally, null)
        }
    }

    private suspend fun closeOpen(prefs: Preferences, calls: List<CallRow>, c: Candles, btc: Candles, formingOpen: Double?, now: Long, tally: Tally) {
        for (row in calls) {
            val s = StrategyCatalog.forCall(row, byId) ?: continue
            val noticed = c.tf == Timeframe.D1 && formingOpen != null && CallWords.base(row.symbol) in tally.risk?.delisted.orEmpty()
            val closed = Live.close(s, row.toNewCall(), c, btc, formingOpen)
                ?: (if (noticed) Live.closeOnNotice(s, row.toNewCall(), c, btc, formingOpen) else null)
                ?: continue
            val inserted = dao.insertExit(ExitRow(
                callId = row.id, exitTime = closed.exitTime, exitPrice = closed.exitPrice, reason = closed.reason.label,
                barsHeld = closed.barsHeld, gross = closed.gross, net = closed.net,
                randomMean = closed.randomMean.takeUnless { it.isNaN() }, excess = closed.excess.takeUnless { it.isNaN() },
                maxUp = closed.maxUp, maxDown = closed.maxDown, barsToPeak = null, closedAt = now,
            ))
            if (inserted == -1L) continue
            tally.exits++
            if (row.holdsRoom()) tally.sized--
            tally.risk?.book?.remove(row.id)
            if (prefs.notifications && prefs.isOn(s)) alerts.post(CallWords.exit(row.id, s.name, row.symbol, closed.reason.label, closed.exitPrice, closed.net))
        }
    }

    private suspend fun openCall(
        prefs: Preferences, s: Strategy, symbol: String, c: Candles, btc: Candles, formingOpen: Double, now: Long, tally: Tally, fired: Boolean?,
    ) {
        val entryTime = c.t.last() + s.timeframe.ms
        val latest = dao.latestCall(s.id, symbol)
        // Busy while a call is open, or when the last one leaves at the very open a new one would enter at.
        val busy = latest != null && (dao.exitOf(latest.id)?.let { it.exitTime >= entryTime } ?: true)
        val call = Live.signal(s, c, btc, formingOpen, busy, prefs.costFor(symbol), fired) ?: return
        // Daily calls go through the sizing engine and the vetoes; an hourly call keeps its plain size.
        val risk = tally.risk.takeIf { s.timeframe == Timeframe.D1 }
        val veto = when {
            risk == null -> null
            CallWords.base(symbol) in risk.delisted -> VETO_DELISTING
            risk.heat == Heat.BLOCK -> Heat.BLOCK.words
            else -> null
        }
        val exposure = risk?.exposureOf(symbol, c)
        val engine = if (risk == null || veto != null) call.sizeFraction
        else Risk.size(call.sizeFraction, if (s.trend) Risk.trendFactor(c, c.size - 1) else 1.0, risk.heat, exposure, risk.book.values.toList(), risk.btcVar, risk.kelly).size
        val size = if (veto != null || tally.sized >= MAX_OPEN_CALLS) 0.0 else engine
        val id = dao.insertCall(CallRow(
            strategyId = call.strategyId, symbol = symbol, tf = s.timeframe.label, signalTime = call.signalTime,
            entryTime = call.entryTime, entryPrice = call.entryPrice, atr = call.atr, stop = call.stop, target = null,
            maxBars = call.maxBars, regime = call.regime, sizeFraction = size, cost = call.cost, source = "v2",
            archived = false, status = "live", openedAt = now, plainSize = call.sizeFraction, veto = veto,
        ))
        if (id == -1L) return
        tally.newCalls++
        if (size > 0) {
            tally.sized++
            if (risk != null && exposure != null) risk.book[id] = Holding(size, exposure)
        }
        // A skipped call is recorded quietly, so the ledger can say whether skipping helped.
        if (veto == null && prefs.notifications && prefs.isOn(s)) alerts.post(CallWords.newCall(id, s.name, symbol, call.entryPrice, call.stop, size, prefs.tradeMoney))
    }

    /**
     * Opens a call the owner recorded from the analyst at [formingOpen], the open of the candle now forming, if it was
     * recorded before that candle began: a call never enters at a price that was already known when it was made.
     */
    private suspend fun openPending(prefs: Preferences, p: PendingCall, c: Candles, btc: Candles, formingOpen: Double, now: Long, tally: Tally) {
        if (p.acceptedAt >= c.t.last() + Timeframe.D1.ms) return
        val call = runCatching { AnalystCall.open(c, btc, formingOpen, p.stopPct, p.targetPct, p.days, prefs.costFor(p.symbol)) }.getOrNull()
        if (call == null) {
            pending?.expired(p.id, "Its average true range was not known yet.", now)
            return
        }
        dao.insertStrategy(StrategyRow(AnalystCall.id, AnalystCall.name, AnalystCall.timeframe.label, "{}", now, retired = false, source = "analyst"))
        val size = if (tally.sized >= MAX_OPEN_CALLS) 0.0 else call.sizeFraction
        var id = dao.insertCall(CallRow(
            strategyId = AnalystCall.id, symbol = p.symbol, tf = Timeframe.D1.label, signalTime = call.signalTime,
            entryTime = call.entryTime, entryPrice = call.entryPrice, atr = call.atr, stop = call.stop, target = call.target,
            maxBars = call.maxBars, regime = call.regime, sizeFraction = size, cost = call.cost, scope = p.id, source = "v2",
            archived = false, status = "live", openedAt = now,
        ))
        if (id == -1L) {
            id = dao.callId(AnalystCall.id, p.symbol, call.signalTime, p.id) ?: return
        } else {
            tally.newCalls++
            if (size > 0) tally.sized++
            if (prefs.notifications && prefs.isOn(AnalystCall)) alerts.post(CallWords.newCall(id, AnalystCall.name, p.symbol, call.entryPrice, call.stop, size, prefs.tradeMoney))
        }
        pending?.opened(p.id, id, now)
    }

    /** Every strategy's tier, recomputed from its backtest on the universe and its live calls (docs/strategy-research.md §4). */
    private suspend fun retier(prefs: Preferences, dailyPanel: Map<String, Candles>, btc: Candles, now: Long) {
        val trials = dao.strategiesTried()
        val exits = dao.allExits().associateBy { it.callId }
        val calls = dao.allCalls().filter { it.source == "v2" }
        for (s in active + AnalystCall) {
            val panel = if (s.timeframe == Timeframe.D1) dailyPanel
            else dailyPanel.keys.mapNotNull { sym -> candles.window(sym, s.timeframe, now - HOURLY_DAYS * DAY)?.let { sym to it } }.toMap()
            if (panel.isEmpty()) continue
            val past = Backtest.run(s, panel, btc, prefs::costFor).mapNotNull { it.excess.takeUnless(Double::isNaN) }.toDoubleArray()
            val doubled = Backtest.run(s, panel, btc) { 2 * prefs.costFor(it) }.mapNotNull { it.excess.takeUnless(Double::isNaN) }.toDoubleArray()
            val live = calls.filter { it.strategyId == s.id }.mapNotNull { exits[it.id]?.excess }.toDoubleArray()
            val verdict = Tiers.judge(s, past, doubled, live, trials)
            dao.putSetting(SettingRow(Preferences.TIER_PREFIX + s.id, verdict.tier.name, now))
            dao.putSetting(SettingRow(Preferences.TIER_REASON_PREFIX + s.id, verdict.reason, now))
        }
    }

    private fun CallRow.toNewCall() = NewCall(
        strategyId, symbol, signalTime, entryTime, entryPrice, atr ?: Double.NaN, stop, maxBars, regime, sizeFraction ?: 0.0, cost, target,
    )

    companion object {
        private const val DAY = 86_400_000L

        /** The pair list is a few hundred coins; this only bounds a read. */
        const val OFFERED_LIMIT = 2000

        /** The paper account holds at most this many sized calls at once; later calls are tracked with no size. */
        const val MAX_OPEN_CALLS = 8

        private fun CallRow.holdsRoom() = (sizeFraction ?: 0.0) > 0

        /** Hourly candles are kept and read this many days back (as Signal Lab 1 kept them). */
        const val HOURLY_DAYS = 100

        val DERIVATIVES_COINS = listOf("BTCUSDT", "ETHUSDT")

        /** The perpetual's basis and the nearest quarterly contract's, whose annualised premium is the leverage gauge. */
        val BASIS_CONTRACTS = listOf("PERPETUAL", "CURRENT_QUARTER")

        /** A delisting notice counts for this many days after it was published: past the halt, the coin is gone anyway. */
        const val NOTICE_DAYS = 30L

        const val VETO_DELISTING = "the exchange will delist the coin"

        /** Settings the runner leaves for the screens: when it last ran, and the coins it watched. */
        const val LAST_PASS = "last_pass_at"
        const val UNIVERSE = "universe"

        /** The open time of the last daily candle whose pass has run, so the daily pass runs once per close. */
        const val DAILY_DONE = "daily_pass_day"
    }
}
