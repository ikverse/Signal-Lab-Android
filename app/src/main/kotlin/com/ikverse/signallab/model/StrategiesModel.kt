package com.ikverse.signallab.model

import com.ikverse.signallab.catalog.SavedStrategy
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.eval.Backtest
import com.ikverse.signallab.engine.strategy.AnalystCall
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.Tier
import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.PreferenceStore
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.runner.StrategyRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StrategyRowUi(val id: String, val name: String, val tier: Tier, val on: Boolean, val summary: String)

/** A custom strategy in the library: the owner's own, or one the analyst proposed. */
data class CustomRowUi(val id: String, val name: String, val thesis: String?, val rule: String, val running: Boolean, val byAnalyst: Boolean, val tier: Tier)

data class StrategiesUi(
    val loaded: Boolean = false,
    val rows: List<StrategyRowUi> = emptyList(),
    /** Custom strategies written in the rule language, newest first. */
    val custom: List<CustomRowUi> = emptyList(),
    /** Strategies of the person's own: Signal Lab 1's lab patterns, kept as history, and new ones. */
    val customs: Int = 0,
    /** Every strategy ever tried, Signal Lab 1's included: the bar every new one has to clear. */
    val tried: Int = 0,
)

/** A backtest on the person's own coins, in the numbers the evidence page shows. */
data class BacktestFacts(
    val coins: Int,
    val calls: Int,
    val hit: Double,
    val meanExcess: Double,
    val ci: Pair<Double, Double>?,
    val t: Double,
    val excessAtDoubleCost: Double,
    val bull: Double?,
    val bear: Double?,
    val avgHoldDays: Double,
    val from: Long,
)

data class LiveFacts(val closed: Int, val meanExcess: Double?, val standing: String)

data class EvidenceUi(
    val id: String,
    val name: String,
    val tier: Tier,
    val on: Boolean,
    val summary: String,
    val rationale: String,
    val research: String,
    val sources: List<Source>,
    val rule: List<String>,
    val backtest: BacktestFacts?,
    val computing: Boolean,
    val live: LiveFacts,
    val tried: Int,
    /** Why the strategy has its tier, from the last daily pass; null before its first. */
    val tierReason: String? = null,
    /** For a custom strategy, whether it is running; null for the others, which always run. */
    val running: Boolean? = null,
) {
    /** The verdict in a sentence, from the three columns. */
    val verdict: String
        get() {
            val b = backtest ?: return if (computing) "Testing it on your coins…" else "No history on your coins yet."
            val past = when {
                b.calls < 30 -> "Too few past calls on your coins to judge"
                b.meanExcess > 0 && b.excessAtDoubleCost > 0 -> "Ahead of random entries on your coins, even at double the fees"
                b.meanExcess > 0 -> "Ahead of random entries on your coins, but not at double the fees"
                else -> "Not ahead of random entries on your coins"
            }
            val live = if (live.closed < LedgerModel.MIN_CALLS) "Not yet confirmed live." else live.standing
            return "$past, allowing for the $tried strategies tried. $live"
        }
}

/** The strategy library, each strategy's evidence, and switching one on or off. */
class StrategiesModel(
    private val dao: LedgerDao,
    private val prefs: PreferenceStore,
    private val candles: CandleStore,
    private val strategies: List<Strategy>,
    private val scope: CoroutineScope,
    /** Every strategy whose evidence can be asked for, as it is now: the built-ins, the analyst's and the custom ones. */
    private val all: () -> List<Strategy> = { strategies + AnalystCall },
    saved: Flow<List<SavedStrategy>> = flowOf(emptyList()),
) {
    val state: StateFlow<StrategiesUi> = combine(prefs.preferences, dao.watchCustoms(), dao.watchStrategiesTried(), saved) { p, customs, tried, mine ->
        val rows = (strategies + AnalystCall).map { StrategyRowUi(it.id, it.name, p.tierOf(it), p.isOn(it), StrategyTexts.summary(it)) }
        val custom = mine.sortedByDescending { it.row.createdAt }.map {
            CustomRowUi(it.id, it.row.title, it.row.reason, it.strategy.definition.print(), it.id in p.customLive, it.byAnalyst, p.tierOf(it.strategy))
        }
        StrategiesUi(true, rows, custom, customs, tried)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), StrategiesUi())

    fun switch(id: String, on: Boolean) {
        scope.launch { prefs.switch(id, on) }
    }

    /** Starts or stops a custom strategy; a running one makes calls into the ledger from the next daily pass. */
    fun run(id: String, on: Boolean) {
        scope.launch { prefs.setBool(Preferences.CUSTOM_LIVE_PREFIX + id, on) }
        synchronized(evidence) { evidence[id] }?.let { f -> f.value = f.value?.copy(running = on) }
    }

    private val evidence = HashMap<String, MutableStateFlow<EvidenceUi?>>()
    private val computed = HashMap<String, Long>()

    /** The evidence for strategy [id]; its backtest runs in the background the first time each day it is asked for. */
    fun evidence(id: String): StateFlow<EvidenceUi?> {
        val flow = synchronized(evidence) { evidence.getOrPut(id) { MutableStateFlow(null) } }
        val s = all().firstOrNull { it.id == id } ?: return flow
        scope.launch { refresh(s, flow) }
        return flow
    }

    private suspend fun refresh(s: Strategy, flow: MutableStateFlow<EvidenceUi?>) {
        val p = prefs.preferences.first()
        val tried = dao.strategiesTried()
        val live = liveFacts(s)
        val base = EvidenceUi(s.id, s.name, p.tierOf(s), p.isOn(s), StrategyTexts.summary(s), StrategyTexts.rationale(s),
            StrategyTexts.research(s), StrategyTexts.sources(s), StrategyTexts.rule(s), flow.value?.backtest, computing = true, live, tried,
            tierReason = p.tierReasons[s.id], running = if (s is com.ikverse.signallab.engine.strategy.dsl.CustomStrategy) s.id in p.customLive else null)
        flow.value = base
        val btc = candles.window(DataConfig.REGIME_COIN, Timeframe.D1)
        val day = btc?.t?.lastOrNull() ?: 0L
        if (btc == null) {
            flow.value = base.copy(computing = false)
            return
        }
        if (computed[s.id] == day && base.backtest != null) {
            flow.value = base.copy(computing = false)
            return
        }
        val coins = (dao.setting(StrategyRunner.UNIVERSE)?.split(',')?.filter { it.isNotBlank() }.orEmpty().ifEmpty { listOf(DataConfig.REGIME_COIN) })
        val panel = LinkedHashMap<String, Candles>()
        val since = if (s.timeframe == Timeframe.D1) 0L else System.currentTimeMillis() - 100 * 86_400_000L
        for (sym in coins) candles.window(sym, s.timeframe, since)?.let { panel[sym] = it }
        val facts = withContext(Dispatchers.Default) { backtest(s, panel, btc, p) }
        computed[s.id] = day
        flow.value = base.copy(backtest = facts, computing = false)
    }

    private suspend fun liveFacts(s: Strategy): LiveFacts {
        val calls = dao.allCalls().filter { it.strategyId == s.id }
        val exits = calls.mapNotNull { dao.exitOf(it.id) }
        val excess = exits.mapNotNull { it.excess }
        val mean = if (excess.isEmpty()) null else excess.average()
        return LiveFacts(exits.size, mean, LedgerModel.standing(excess))
    }

    companion object {
        /** The strategy's backtest on [panel], at the person's costs and at twice them. */
        fun backtest(s: Strategy, panel: Map<String, Candles>, btc: Candles, p: Preferences): BacktestFacts? {
            val trades = Backtest.run(s, panel, btc, p::costFor)
            if (trades.isEmpty()) return null
            val sum = Backtest.summarize(trades, s.id)
            val doubled = Backtest.run(s, panel, btc) { 2 * p.costFor(it) }
            val dx = doubled.mapNotNull { it.excess.takeUnless(Double::isNaN) }
            return BacktestFacts(
                coins = panel.size, calls = sum.n, hit = sum.hit, meanExcess = sum.meanExcess, ci = sum.excessCi95, t = sum.tCluster,
                excessAtDoubleCost = if (dx.isEmpty()) Double.NaN else dx.average(),
                bull = sum.byRegime[1]?.second, bear = sum.byRegime[0]?.second,
                avgHoldDays = trades.sumOf { it.barsHeld }.toDouble() / trades.size, from = trades.minOf { it.signalTime },
            )
        }
    }
}
