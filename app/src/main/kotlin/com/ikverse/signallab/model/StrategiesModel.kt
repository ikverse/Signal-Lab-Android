package com.ikverse.signallab.model

import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.eval.Backtest
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.Tier
import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.PreferenceStore
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.runner.StrategyRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StrategyRowUi(val id: String, val name: String, val tier: Tier, val on: Boolean, val summary: String)

data class StrategiesUi(
    val loaded: Boolean = false,
    val rows: List<StrategyRowUi> = emptyList(),
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
) {
    val state: StateFlow<StrategiesUi> = combine(prefs.preferences, dao.watchCustoms(), dao.watchStrategiesTried()) { p, customs, tried ->
        StrategiesUi(true, strategies.map { StrategyRowUi(it.id, it.name, it.launchTier, p.isOn(it), StrategyTexts.summary(it)) }, customs, tried)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), StrategiesUi())

    fun switch(id: String, on: Boolean) {
        scope.launch { prefs.switch(id, on) }
    }

    private val evidence = HashMap<String, MutableStateFlow<EvidenceUi?>>()
    private val computed = HashMap<String, Long>()

    /** The evidence for strategy [id]; its backtest runs in the background the first time each day it is asked for. */
    fun evidence(id: String): StateFlow<EvidenceUi?> {
        val s = strategies.first { it.id == id }
        val flow = synchronized(evidence) { evidence.getOrPut(id) { MutableStateFlow(null) } }
        scope.launch { refresh(s, flow) }
        return flow
    }

    private suspend fun refresh(s: Strategy, flow: MutableStateFlow<EvidenceUi?>) {
        val p = prefs.preferences.first()
        val tried = dao.strategiesTried()
        val live = liveFacts(s)
        val base = EvidenceUi(s.id, s.name, s.launchTier, p.isOn(s), StrategyTexts.summary(s), StrategyTexts.rationale(s),
            StrategyTexts.research(s), StrategyTexts.sources(s), StrategyTexts.rule(s), flow.value?.backtest, computing = true, live, tried)
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
        for (sym in coins) candles.window(sym, Timeframe.D1)?.let { panel[sym] = it }
        val facts = withContext(Dispatchers.Default) { backtest(s, panel, btc, p) }
        computed[s.id] = day
        flow.value = base.copy(backtest = facts, computing = false)
    }

    private suspend fun liveFacts(s: Strategy): LiveFacts {
        val calls = dao.allCalls().filter { it.strategyId == s.id }
        val exits = calls.mapNotNull { dao.exitOf(it.id) }
        val excess = exits.mapNotNull { it.excess }
        val mean = if (excess.isEmpty()) null else excess.average()
        return LiveFacts(exits.size, mean, LedgerModel.standing(exits.size, mean))
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
