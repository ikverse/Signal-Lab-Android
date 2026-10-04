package com.ikverse.signallab.state

import android.content.Context
import com.ikverse.signallab.BuildConfig
import com.ikverse.signallab.data.Alert
import com.ikverse.signallab.data.CostModel
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.LiveTrade
import com.ikverse.signallab.data.SettingsStore
import com.ikverse.signallab.data.TradeStatus
import com.ikverse.signallab.data.WatchlistResult
import com.ikverse.signallab.engine.Refusal
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.VariantLabels
import com.ikverse.signallab.engine.Watchlist
import com.ikverse.signallab.engine.WatchlistRules
import com.ikverse.signallab.ui.AlertUi
import com.ikverse.signallab.ui.AlertsModel
import com.ikverse.signallab.ui.CandleUi
import com.ikverse.signallab.ui.ChartUi
import com.ikverse.signallab.ui.ClosedUi
import com.ikverse.signallab.ui.CoinUi
import com.ikverse.signallab.ui.DownloadUi
import com.ikverse.signallab.ui.LevelKind
import com.ikverse.signallab.ui.LevelUi
import com.ikverse.signallab.ui.DimLevel
import com.ikverse.signallab.ui.ListUi
import com.ikverse.signallab.ui.ListingCheckUi
import com.ikverse.signallab.ui.ListsModel
import com.ikverse.signallab.ui.MarketsModel
import com.ikverse.signallab.ui.OfferUi
import com.ikverse.signallab.ui.OffersUi
import com.ikverse.signallab.ui.PanelPrefs
import com.ikverse.signallab.ui.Outcome
import com.ikverse.signallab.ui.PermissionPrompt
import com.ikverse.signallab.ui.PermissionsUi
import com.ikverse.signallab.ui.PickSource
import com.ikverse.signallab.ui.PickWindow
import com.ikverse.signallab.ui.ScorecardModel
import com.ikverse.signallab.ui.ScorecardUi
import com.ikverse.signallab.ui.SettingsModel
import com.ikverse.signallab.ui.SettingsUi
import com.ikverse.signallab.ui.TradeUi
import com.ikverse.signallab.ui.TradesModel
import com.ikverse.signallab.ui.UpdateUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/** At most one value every [ms]; the newest one wins. Keeps a burst of database writes from redrawing a screen for each. */
internal fun <T> Flow<T>.throttled(ms: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(ms)
}

/** What a refused change says, in plain words. */
fun refusalText(r: Refusal): String = when (r) {
    Refusal.NAME_BLANK -> "Give the list a name."
    Refusal.NAME_TOO_LONG -> "Use a name of ${WatchlistRules.MAX_NAME_LENGTH} characters or fewer."
    Refusal.NAME_TAKEN -> "You already have a list with that name."
    Refusal.LIST_NOT_FOUND -> "That list no longer exists."
    Refusal.LIST_FULL -> "A list holds at most ${WatchlistRules.MAX_COINS_PER_LIST} coins."
    Refusal.DUPLICATE_COIN -> "That coin is already in this list."
    Refusal.UNKNOWN_COIN -> "Binance does not offer that coin here."
    Refusal.OVER_ACTIVE_CAP -> "That would watch more than ${WatchlistRules.MAX_ACTIVE_COINS} coins at once. Switch another list off first."
    Refusal.OVER_FAST_CAP ->
        "Too many coins would be on 1-minute or 5-minute charts. Across the lists that are on, at most " +
            "${WatchlistRules.maxCoinsOn(Timeframe.M1)} coins can use 1-minute charts and ${WatchlistRules.maxCoinsOn(Timeframe.M5)} can use 5-minute charts."
    Refusal.NO_TIMEFRAME -> "Choose at least one chart."
}

private fun PickSource.toData() = when (this) {
    PickSource.VOLUME -> com.ikverse.signallab.data.PickerSource.VOLUME
    PickSource.GAINERS -> com.ikverse.signallab.data.PickerSource.GAINERS
    PickSource.LOSERS -> com.ikverse.signallab.data.PickerSource.LOSERS
    PickSource.ACTIVE -> com.ikverse.signallab.data.PickerSource.ACTIVE
    PickSource.VOLATILE -> com.ikverse.signallab.data.PickerSource.VOLATILE
    PickSource.NEW -> com.ikverse.signallab.data.PickerSource.NEW
}

private fun PickWindow.toData() = when (this) {
    PickWindow.H1 -> com.ikverse.signallab.data.MoveWindow.H1
    PickWindow.H24 -> com.ikverse.signallab.data.MoveWindow.H24
    PickWindow.D7 -> com.ikverse.signallab.data.MoveWindow.D7
}

private fun WatchlistResult<*>.outcome(): Outcome = when (this) {
    is WatchlistResult.Ok -> Outcome.Done
    is WatchlistResult.Refused -> Outcome.Refused(refusalText(reason))
}

class LiveListsModel(private val graph: AppGraph, private val scope: CoroutineScope) : ListsModel {
    override val lists: StateFlow<List<ListUi>> = graph.watchlists.lists
        .map { all -> all.map { it.toUi() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val ready = MutableStateFlow(false)
    override val loaded: StateFlow<Boolean> = ready

    override val download: StateFlow<DownloadUi> = graph.history.progress
        .map { DownloadUi(it.total, it.ready, it.current, it.running, it.failures) }
        .stateIn(scope, SharingStarted.Eagerly, DownloadUi())

    init {
        scope.launch {
            try {
                graph.watchlists.load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The lists could not be read; the screens will show what there is, which is nothing.
            }
            ready.value = true
        }
    }

    override val listingCheck: StateFlow<ListingCheckUi> = graph.universe.listingLookup
        .map { ListingCheckUi(it.running, it.done, it.total, it.failed) }
        .stateIn(scope, SharingStarted.Eagerly, ListingCheckUi())

    override fun checkListings() {
        scope.launch {
            try {
                graph.universe.lookUpListings()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The lookup reports its own failures through listingCheck; anything else leaves "New" as it was.
            }
        }
    }

    override suspend fun offers(query: String, source: PickSource, window: PickWindow): OffersUi {
        val universe = graph.universe
        var problem: String? = null
        try {
            // The day's figures behind gainers, losers and trade counts go stale in minutes; the pair list itself changes slowly.
            universe.refreshIfStale(maxAgeMs = DataConfig.PICKER_REFRESH_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.message ?: "Binance did not answer."
        }
        val found = try {
            universe.offers(query.trim(), source.toData(), window.toData(), 50).map { OfferUi(it.coin.symbol, it.coin.base, it.coin.quoteVolume, it.value) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.message ?: "Binance did not answer."
            emptyList()
        }
        // Stale figures are shown quietly; only an empty answer needs the reason.
        return OffersUi(found, problem.takeIf { found.isEmpty() })
    }

    /** One step, all or nothing: see [com.ikverse.signallab.data.WatchlistRepository.createWith]. */
    override suspend fun create(name: String, symbols: List<String>, timeframes: Set<String>, activate: Boolean): Outcome =
        graph.watchlists.createWith(name, symbols, timeframes.map(Timeframe::of).toSet(), activate).outcome()

    override suspend fun rename(id: Long, name: String) = graph.watchlists.rename(id, name).outcome()
    override suspend fun delete(id: Long) = graph.watchlists.delete(id).outcome()
    override suspend fun setActive(id: Long, active: Boolean) = graph.watchlists.setActive(id, active).outcome()
    override suspend fun addCoin(id: Long, symbol: String) = graph.watchlists.add(id, symbol).outcome()
    override suspend fun removeCoin(id: Long, symbol: String) = graph.watchlists.remove(id, symbol).outcome()
    override suspend fun setTimeframes(id: Long, timeframes: Set<String>) = graph.watchlists.setTimeframes(id, timeframes.map(Timeframe::of).toSet()).outcome()

    override val allTimeframes: List<Pair<String, String>> = listOf(
        "1m" to "Most signals and the quickest results. Uses the most data and battery; at most ${WatchlistRules.maxCoinsOn(Timeframe.M1)} coins.",
        "5m" to "Fast. At most ${WatchlistRules.maxCoinsOn(Timeframe.M5)} coins.",
        "15m" to "Moves over a few hours.",
        "30m" to "Moves over several hours.",
        "1h" to "Moves over a day or two.",
        "4h" to "Moves over several days.",
        "1d" to "Slow: moves over weeks.",
    )

    private fun Watchlist.toUi() = ListUi(id, name, active, symbols, timeframes.sorted().map { it.label })
}

class LiveMarketsModel(private val graph: AppGraph, scope: CoroutineScope) : MarketsModel {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val coins: StateFlow<List<CoinUi>> =
        combine(graph.watchlists.lists, graph.tradeLog.version, graph.candles.version.throttled(5_000)) { lists, _, _ -> lists }
            .mapLatest { build(it) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val prices: StateFlow<Map<String, Double>> = graph.priceFeed.prices

    override val changes: StateFlow<Long> =
        combine(graph.tradeLog.version, graph.candles.version.throttled(2_000)) { a, b -> a * 1_000_003 + b }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0L)

    private suspend fun build(lists: List<Watchlist>): List<CoinUi> {
        val charts = LinkedHashMap<String, java.util.TreeSet<Timeframe>>()
        for (l in lists) if (l.active) for (s in l.symbols) charts.getOrPut(s) { java.util.TreeSet() }.addAll(l.timeframes)
        val open = graph.tradeLog.trades(TradeStatus.OPEN, limit = Int.MAX_VALUE).groupingBy { it.trade.symbol }.eachCount()
        return charts.map { (symbol, tfs) ->
            val shortest = tfs.first()
            val latest = graph.candles.latest(symbol, shortest, 1)
            val price = latest?.close?.lastOrNull()
            val change = if (latest == null || price == null) null else {
                graph.candles.closeAt(symbol, shortest, latest.t.last() - DAY_MS)?.takeIf { it > 0 }?.let { price / it - 1 }
            }
            CoinUi(symbol, symbol.removeSuffix(DataConfig.QUOTE), price, change, open[symbol] ?: 0, tfs.map { it.label })
        }
    }

    override suspend fun chart(symbol: String, timeframe: String): ChartUi? {
        val tf = Timeframe.of(timeframe)
        val c = graph.candles.latest(symbol, tf, CHART_CANDLES) ?: return null
        val candles = List(c.size) { CandleUi(c.t[it], c.open[it], c.high[it], c.low[it], c.close[it], c.volume[it]) }
        val levels = graph.tradeLog.trades(TradeStatus.OPEN, symbol = symbol, limit = 50).filter { it.trade.tf == tf }.flatMap { t ->
            buildList {
                add(LevelUi(LevelKind.ENTRY, "Entry", t.trade.entryPrice))
                t.trade.stop?.let { add(LevelUi(LevelKind.STOP, "Stop", it)) }
                t.trade.target?.let { add(LevelUi(LevelKind.TARGET, "Target", it)) }
            }
        }
        return ChartUi(symbol, timeframe, candles, levels)
    }

    override fun watchPrices(symbols: Set<String>): AutoCloseable = graph.priceFeed.watch(symbols)

    private companion object {
        const val DAY_MS = 86_400_000L
        const val CHART_CANDLES = 600
    }
}

internal fun LiveTrade.toUi() = TradeUi(
    id = id, variant = trade.variant, label = VariantLabels.describe(trade.variant), symbol = trade.symbol, timeframe = trade.tf.label,
    openedAt = trade.entryTime, entryPrice = trade.entryPrice, stop = trade.stop, target = trade.target, exitMode = trade.exitMode,
    closed = exit?.let { ClosedUi(it.exitTime, it.exitPrice, it.reason.label, it.net, it.randomMean, it.maxUp, it.maxDown, it.barsHeld) },
)

class LiveTradesModel(private val graph: AppGraph, scope: CoroutineScope) : TradesModel {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val trades: StateFlow<List<TradeUi>> = graph.tradeLog.version
        .mapLatest { graph.tradeLog.trades(TradeStatus.ALL, limit = 1_000).map { it.toUi() } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class LiveScorecardModel(private val graph: AppGraph, scope: CoroutineScope) : ScorecardModel {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val scorecard: StateFlow<ScorecardUi> = combine(graph.tradeLog.version, graph.watchlists.lists) { _, lists -> lists }
        .mapLatest { lists ->
            val inUse = lists.filter { it.active }.flatMap { it.timeframes }.toSet()
            val registered = graph.tradeLog.registeredVariants().filter { it.tf in inUse }.map { it.name to it.tf }
            Scoring.build(graph.tradeLog.trades(TradeStatus.ALL, limit = Int.MAX_VALUE), registered, graph.tradeLog.variantCount())
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ScorecardUi())
}

internal fun Alert.toUi() = AlertUi(id, ts, kind, title, body, symbol, tf)

class LiveAlertsModel(private val graph: AppGraph, scope: CoroutineScope) : AlertsModel {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val alerts: StateFlow<List<AlertUi>> = graph.tradeLog.version
        .mapLatest { graph.tradeLog.alerts(300).map { it.toUi() } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class LiveSettingsModel(
    private val graph: AppGraph,
    private val context: Context,
    private val scope: CoroutineScope,
    /** Where a request to open one of Android's own screens goes; the activity listens and opens it. */
    private val systemScreens: MutableSharedFlow<PermissionPrompt>,
) : SettingsModel {
    private val state = MutableStateFlow(read(CostModel(), scanning = true, followFast = true, us = false, dim = false, level = DimLevel.DIM, railRight = false))
    override val settings: StateFlow<SettingsUi> = state

    init {
        scope.launch { refresh() }
    }

    private fun read(costs: CostModel, scanning: Boolean, followFast: Boolean, us: Boolean, dim: Boolean, level: DimLevel, railRight: Boolean): SettingsUi {
        val g = graph.permissions.grants()
        return SettingsUi(
            feePerSide = costs.feePerSide, extraMajors = costs.extraMajors, extraOthers = costs.extraOthers,
            backgroundScanning = scanning, followFastCharts = followFast, binanceUs = us,
            permissions = PermissionsUi(g.notifications, g.exactAlarms, g.batteryExempt),
            version = BuildConfig.VERSION_NAME,
            dataNote = "Everything Signal Lab records stays on this phone. It downloads prices from Binance and sends nothing about you anywhere.",
            dimScreen = dim, dimLevel = level, railOnRight = railRight,
        )
    }

    /** Reads everything again: after a change, and when the app comes back from one of Android's own screens. */
    suspend fun refresh() {
        val s = graph.settings
        state.value = read(
            CostModel.load(s), s.getBoolean(SettingsStore.SCAN_IN_BACKGROUND, true), s.getBoolean(SettingsStore.FOLLOW_FAST_CHARTS, true),
            graph.currentHost() == DataConfig.HOST_US,
            s.getBoolean(SettingsStore.DIM_SCREEN, false),
            s.get(SettingsStore.DIM_LEVEL)?.let { name -> DimLevel.entries.firstOrNull { it.name == name } } ?: DimLevel.DIM,
            s.getBoolean(SettingsStore.RAIL_ON_RIGHT, false),
        )
    }

    override suspend fun setRailOnRight(on: Boolean) {
        graph.settings.setBoolean(SettingsStore.RAIL_ON_RIGHT, on)
        refresh()
    }

    override suspend fun setDimScreen(on: Boolean) {
        graph.settings.setBoolean(SettingsStore.DIM_SCREEN, on)
        refresh()
    }

    override suspend fun setDimLevel(level: DimLevel) {
        graph.settings.set(SettingsStore.DIM_LEVEL, level.name)
        refresh()
    }

    override suspend fun setFee(feePerSide: Double): Outcome {
        if (!feePerSide.isFinite() || feePerSide < 0 || feePerSide > MAX_FEE) return Outcome.Refused("The fee should be between 0% and ${MAX_FEE * 100}% each way.")
        graph.settings.setDouble(SettingsStore.FEE_PER_SIDE, feePerSide)
        refresh()
        return Outcome.Done
    }

    override suspend fun setExtraCosts(majors: Double, others: Double): Outcome {
        if (listOf(majors, others).any { !it.isFinite() || it < 0 || it > MAX_EXTRA }) {
            return Outcome.Refused("An extra cost should be between 0% and ${MAX_EXTRA * 100}%.")
        }
        graph.settings.setDouble(SettingsStore.EXTRA_COST_MAJORS, majors)
        graph.settings.setDouble(SettingsStore.EXTRA_COST_OTHERS, others)
        refresh()
        return Outcome.Done
    }

    override suspend fun setBackgroundScanning(on: Boolean) {
        graph.settings.setBoolean(SettingsStore.SCAN_IN_BACKGROUND, on)
        graph.syncService(context)
        refresh()
    }

    override suspend fun setFollowFastCharts(on: Boolean) {
        graph.settings.setBoolean(SettingsStore.FOLLOW_FAST_CHARTS, on)
        graph.syncService(context)
        refresh()
    }

    override val update: StateFlow<UpdateUi> =
        graph.updates.state.map { it.toUi() }.stateIn(scope, SharingStarted.Eagerly, graph.updates.state.value.toUi())

    override suspend fun checkForUpdate() = graph.updates.checkNow()

    override suspend fun installUpdate() = graph.updates.install()

    override suspend fun setBinanceUs(on: Boolean) {
        graph.useHost(if (on) DataConfig.HOST_US else DataConfig.HOST_COM)
        try {
            // The pairs on offer differ between the two, so the picker's list is fetched again from the new one.
            graph.universe.refreshIfStale(force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or refused: the old list stays until the next daily refresh.
        }
        refresh()
    }

    override fun openSystemScreen(prompt: PermissionPrompt) {
        systemScreens.tryEmit(prompt)
    }

    private companion object {
        const val MAX_FEE = 0.01
        const val MAX_EXTRA = 0.05
    }
}

/** Panel sizes kept in the settings, one entry per screen, so they are still there the next time the app opens. */
class LivePanelPrefs(private val graph: AppGraph, private val scope: CoroutineScope, private val keys: List<String> = listOf("markets", "lists", "learn", "chart")) : PanelPrefs {
    private val state = MutableStateFlow<Map<String, String>?>(null)
    override val saved: StateFlow<Map<String, String>?> = state

    init {
        scope.launch {
            val found = HashMap<String, String>()
            for (k in keys) graph.settings.get(PREFIX + k)?.let { found[k] = it }
            // A change made before the read finished wins over what was read.
            state.value = found + (state.value ?: emptyMap())
        }
    }

    override fun save(key: String, text: String) {
        state.value = (state.value ?: emptyMap()) + (key to text)
        scope.launch { graph.settings.set(PREFIX + key, text) }
    }

    private companion object {
        const val PREFIX = "panels_"
    }
}
