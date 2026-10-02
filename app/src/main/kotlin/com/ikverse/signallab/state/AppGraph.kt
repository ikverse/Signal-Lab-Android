package com.ikverse.signallab.state

import android.content.Context
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.CandleSync
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.HistoryManager
import com.ikverse.signallab.data.RecordDatabase
import com.ikverse.signallab.data.SettingsStore
import com.ikverse.signallab.data.TradeLog
import com.ikverse.signallab.data.TradeStatus
import com.ikverse.signallab.data.UniverseRepository
import com.ikverse.signallab.data.WatchlistRepository
import com.ikverse.signallab.data.binance.BinanceClient
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.WatchlistRules
import com.ikverse.signallab.scan.AndroidAlarms
import com.ikverse.signallab.scan.Notifier
import com.ikverse.signallab.scan.PermissionFlow
import com.ikverse.signallab.scan.PriceFeed
import com.ikverse.signallab.scan.ScanService
import com.ikverse.signallab.scan.ScanController
import com.ikverse.signallab.scan.ScanHealth
import com.ikverse.signallab.scan.Scanner
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
class AppGraph(context: Context, scope: CoroutineScope) {
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

    // Background scanning: the notifier is where alerts go, the controller decides when to scan, and the
    // alarm wakes it. None of them depends on a screen being open.
    val notifier = Notifier(context)
    val health = ScanHealth()
    val alarms = AndroidAlarms(context) { server -> server - (market.nowMs() - System.currentTimeMillis()) }
    val scanner = Scanner(market, sync, candles, tradeLog, settings, { watchlists.lists.value }, notifier)
    val controller = ScanController(
        scanner, market, alarms, tradeLog, notifier, health,
        hasWork = ::scanWanted, skewMs = { market.clockSkewMs }, housekeeping = ::tidyIfDue,
        followFast = { settings.getBoolean(SettingsStore.FOLLOW_FAST_CHARTS, true) },
    )

    /** Which exchange host the app talks to: Binance.com, or Binance.US for networks that cannot reach it. */
    suspend fun useHost(url: String) {
        settings.set(SettingsStore.DATA_HOST, url)
        host = url
    }

    fun currentHost(): String = host

    /** Live prices, only while a screen is showing them. */
    val priceFeed = PriceFeed(BinanceClient.defaultHttp(), { DataConfig.streamFor(host) }, scope)

    /** Asks for alerts, exact alarms and a battery exemption, once each, after the first list is switched on. */
    val permissions = PermissionFlow(context, settings, { watchlists.load(); watchlists.activeCoins().isNotEmpty() }, scope)

    private val problem = MutableStateFlow<String?>(null)

    /**
     * Once a day, deletes candles that are no longer needed: older than each chart keeps, and every candle of a chart no
     * list is watched on (BTC's daily chart stays, for the market regime). Trades and scores are never touched.
     */
    private suspend fun tidyIfDue() {
        val now = market.nowMs()
        val last = settings.get(LAST_TIDY)?.toLongOrNull() ?: 0L
        if (now - last < DAY_MS) return
        candles.trim(now, DataConfig::historyDays, scanner.timeframesInUse(), DataConfig.REGIME_COIN to Timeframe.D1)
        settings.set(LAST_TIDY, now.toString())
    }

    /** Starts the background service when there is something to watch, and stops it and its alarm when there is not. */
    suspend fun syncService(context: Context) {
        if (scanWanted()) {
            ScanService.start(context)
        } else {
            ScanService.stop(context)
            alarms.cancel()
            health.armed(null)
        }
    }

    /**
     * Whether there is any reason to run in the background: the user has not switched it off, and a
     * list is active or a paper trade is still open (a trade keeps being followed after its list is switched off).
     */
    suspend fun scanWanted(): Boolean {
        watchlists.load()
        if (!settings.getBoolean(SettingsStore.SCAN_IN_BACKGROUND, true)) return false
        return watchlists.activeCoins().isNotEmpty() || tradeLog.trades(TradeStatus.OPEN, limit = 1).isNotEmpty()
    }

    /** The last thing that went wrong in the background, for the health readout. */
    val lastProblem: StateFlow<String?> = problem

    fun start(scope: CoroutineScope) {
        notifier.createChannels()
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
        // Whenever the coins in active lists, or the charts they are watched on, change, bring their history up to date.
        // A change cancels the run in progress and starts again; what was already downloaded is kept.
        scope.launch {
            watchlists.lists.map { WatchlistRules.timeframesByCoin(it) }.distinctUntilChanged().collectLatest { needs ->
                if (needs.isNotEmpty()) history.ensureHistory(needs)
            }
        }
    }

    companion object {
        private const val LAST_TIDY = "last_tidy"
        private const val DAY_MS = 86_400_000L
    }
}
