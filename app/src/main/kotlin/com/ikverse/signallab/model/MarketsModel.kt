package com.ikverse.signallab.model

import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.UniverseRepository
import com.ikverse.signallab.data.futures.DerivativesStore
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.PinRow
import com.ikverse.signallab.runner.StrategyRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One coin in the markets list. */
data class CoinUi(
    val symbol: String,
    val price: Double?,
    /** The last daily close against the one before it. */
    val change: Double?,
    /** What is going on with it, in a few words: a call today, an open call, or nothing firing. */
    val note: String,
    val newCall: Boolean,
    val open: Boolean,
    val pinned: Boolean,
    val excluded: Boolean,
    val watched: Boolean,
)

data class MarketsUi(
    val loaded: Boolean = false,
    val market: MarketLine = MarketLine(null, null, null, null, 0, 0),
    val watched: List<CoinUi> = emptyList(),
    /** Coins found by the search, watched or not. */
    val found: List<CoinUi> = emptyList(),
    val query: String = "",
    val pins: Int = 0,
    val excludes: Int = 0,
)

/** One coin's page: its candles for the chart, and the calls it has had. */
data class CoinPageUi(val coin: CoinUi, val candles: Candles?, val calls: List<CallUi>)

/** The coins Signal Lab 2 watches, the market's state, and pinning or excluding a coin. */
class MarketsModel(
    private val dao: LedgerDao,
    private val candles: CandleStore,
    private val pairs: UniverseRepository,
    private val derivatives: DerivativesStore?,
    private val strategies: List<Strategy>,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val query = MutableStateFlow("")
    private val byId = strategies.associateBy { it.id }

    fun search(text: String) {
        query.value = text
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<MarketsUi> = combine(dao.watchLiveCalls(), dao.watchLiveExits(), dao.watchPins(), candles.version, query) { calls, exits, pins, _, q ->
        Triple(calls to exits, pins, q)
    }.mapLatest { (ce, pins, q) -> build(ce.first, ce.second, pins, q) }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), MarketsUi())

    private suspend fun build(calls: List<com.ikverse.signallab.ledger.CallRow>, exits: List<com.ikverse.signallab.ledger.ExitRow>, pins: List<PinRow>, q: String): MarketsUi {
        val universe = dao.setting(StrategyRunner.UNIVERSE)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val pinned = pins.filter { it.kind == "pin" }.map { it.symbol }.toSet()
        val excluded = pins.filter { it.kind == "exclude" }.map { it.symbol }.toSet()
        val closedIds = exits.map { it.callId }.toSet()
        val openBy = calls.filter { it.id !in closedIds }.groupBy { it.symbol }
        val latestEntry = openBy.values.flatten().maxOfOrNull { it.entryTime }
        val symbols = (universe + q.trim().uppercase().takeIf { it.length >= 2 }?.let { s -> pairs.search(s, 20).map { it.symbol } }.orEmpty()).distinct()
        val closes = candles.closesSince(Timeframe.D1, symbols, now() - 3 * DAY)
        fun coin(symbol: String): CoinUi {
            val cl = closes[symbol].orEmpty()
            val price = cl.lastOrNull()?.close
            val change = if (cl.size >= 2) cl[cl.size - 1].close / cl[cl.size - 2].close - 1 else null
            val open = openBy[symbol].orEmpty()
            val fresh = open.filter { it.entryTime == latestEntry && now() - it.openedAt < DAY }
            val note = when {
                fresh.isNotEmpty() -> "${byId[fresh.first().strategyId]?.name ?: "A strategy"} fired today"
                open.isNotEmpty() -> open.joinToString(", ") { byId[it.strategyId]?.name ?: it.strategyId } + " open"
                symbol == DataConfig.REGIME_COIN -> "sets the market regime"
                symbol in excluded -> "excluded"
                else -> "nothing firing"
            }
            return CoinUi(symbol, price, change, note, fresh.isNotEmpty(), open.isNotEmpty(), symbol in pinned, symbol in excluded, symbol in universe)
        }
        val btc = candles.window(DataConfig.REGIME_COIN, Timeframe.D1)
        val funding = derivatives?.funding(DataConfig.REGIME_COIN, now() - 2 * DAY)?.lastOrNull()?.rate
        val text = q.trim().uppercase()
        val watched = universe.map(::coin)
        val found = if (text.length < 2) emptyList() else symbols.filter { it.contains(text) }.map(::coin)
        return MarketsUi(true, MarketLine.of(btc, funding, strategies), watched, found, q, pinned.size, excluded.size)
    }

    /** One coin's page. */
    suspend fun page(symbol: String, account: Double): CoinPageUi {
        val c = candles.window(symbol, Timeframe.D1)
        val calls = dao.allCalls().filter { it.symbol == symbol && it.source == "v2" }.mapNotNull { row ->
            byId[row.strategyId]?.let { TodayModel.callUi(row, it, c, account) }
        }
        val s = state.value
        val coin = (s.watched + s.found).firstOrNull { it.symbol == symbol }
            ?: CoinUi(symbol, c?.close?.lastOrNull(), null, "", newCall = false, open = false, pinned = false, excluded = false, watched = false)
        return CoinPageUi(coin, c, calls.sortedByDescending { it.signalTime })
    }

    /** Always watch [symbol], or stop treating it specially. */
    fun pin(symbol: String, on: Boolean) {
        scope.launch { if (on) dao.putPin(PinRow(symbol, "pin", now())) else dao.removePin(symbol) }
    }

    /** Never watch [symbol], or stop excluding it. */
    fun exclude(symbol: String, on: Boolean) {
        scope.launch { if (on) dao.putPin(PinRow(symbol, "exclude", now())) else dao.removePin(symbol) }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
