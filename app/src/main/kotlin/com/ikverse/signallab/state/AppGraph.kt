package com.ikverse.signallab.state

import android.content.Context
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.CandleSync
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.HistoryManager
import com.ikverse.signallab.data.RecordDatabase
import com.ikverse.signallab.data.SettingsStore
import com.ikverse.signallab.data.TradeLog
import com.ikverse.signallab.data.UniverseRepository
import com.ikverse.signallab.data.WatchlistRepository
import com.ikverse.signallab.data.binance.BinanceClient
import com.ikverse.signallab.engine.WatchlistRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Everything the data layer is made of, built once by the application. It is the only place that
 * knows how the pieces fit; screens get what they need through interfaces in the `ui` package.
 */
class AppGraph(context: Context) {
    val recordDb = RecordDatabase(context)
    val candles = CandleStore(context)
    val settings = SettingsStore(recordDb)
    val tradeLog = TradeLog(recordDb)

    @Volatile
    private var host = DataConfig.HOST_COM

    val market = BinanceClient(host = { host })
    val universe = UniverseRepository(market, candles)
    val watchlists = WatchlistRepository(recordDb, universe)
    val sync = CandleSync(market, candles)
    val history = HistoryManager(sync, candles, market)

    private val problem = MutableStateFlow<String?>(null)

    /** The last thing that went wrong in the background, for the health readout. */
    val lastProblem: StateFlow<String?> = problem

    fun start(scope: CoroutineScope) {
        scope.launch {
            try {
                settings.get(SettingsStore.DATA_HOST)?.let { host = it }
                watchlists.load()
                market.serverTime()
                universe.refreshIfStale()
                problem.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem.value = e.message ?: e.javaClass.simpleName
            }
        }
        // Whenever the set of coins in active lists changes, bring their history up to date. A change
        // cancels the run in progress and starts again; what was already downloaded is kept.
        scope.launch {
            watchlists.lists.map { WatchlistRules.activeCoins(it).toSet() }.distinctUntilChanged().collectLatest { coins ->
                if (coins.isNotEmpty()) history.ensureHistory(coins)
            }
        }
    }
}
