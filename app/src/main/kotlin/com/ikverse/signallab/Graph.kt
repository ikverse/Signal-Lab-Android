package com.ikverse.signallab

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.CandleSync
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.UniverseRepository
import com.ikverse.signallab.data.binance.BinanceClient
import com.ikverse.signallab.data.futures.DerivativesStore
import com.ikverse.signallab.data.futures.DerivativesSync
import com.ikverse.signallab.data.futures.FuturesClient
import com.ikverse.signallab.data.history.DailyHistory
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.LegacyImporter
import com.ikverse.signallab.ledger.PreferenceStore
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.model.CallModel
import com.ikverse.signallab.model.LedgerModel
import com.ikverse.signallab.model.MarketsModel
import com.ikverse.signallab.model.SettingsModel
import com.ikverse.signallab.model.StrategiesModel
import com.ikverse.signallab.model.TodayModel
import com.ikverse.signallab.runner.AndroidCallAlerts
import com.ikverse.signallab.runner.DailyCloseScheduler
import com.ikverse.signallab.runner.StrategyRunner
import com.ikverse.signallab.update.AndroidApkInspector
import com.ikverse.signallab.update.AndroidInstallLauncher
import com.ikverse.signallab.update.ApkDownloader
import com.ikverse.signallab.update.AppVersion
import com.ikverse.signallab.update.UpdateChecker
import com.ikverse.signallab.update.UpdateController
import com.ikverse.signallab.update.UpdateMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything Signal Lab 2 is made of, built once by the application: the market data, the ledger, the daily runner and
 * its schedule, the updater, and the models the screens read. It is the only place that knows how the pieces fit.
 */
class Graph(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext

    @Volatile
    private var host = DataConfig.HOST_COM

    val market = BinanceClient(host = { host })
    val candles = CandleStore(app)
    val sync = CandleSync(market, candles)
    val pairs = UniverseRepository(market, candles)
    val ledger: LedgerDatabase = LedgerDatabase.open(app)
    val prefsStore = PreferenceStore(ledger.dao())
    val preferences: StateFlow<Preferences> = prefsStore.preferences.stateIn(scope, SharingStarted.Eagerly, Preferences())
    val derivativesStore = DerivativesStore(app)
    val derivatives = DerivativesSync(FuturesClient(market), derivativesStore, market::nowMs) {
        host == DataConfig.HOST_COM && preferences.value.futuresData
    }
    val strategies = Builtins.all
    val runner = StrategyRunner(
        market, pairs, candles, DailyHistory(sync), ledger, AndroidCallAlerts(app), derivatives, strategies,
        preferences = { prefsStore.current() }, hourly = sync,
    )
    val importer = LegacyImporter(app, ledger)
    val scheduler = DailyCloseScheduler(app, offsetMs = { market.nowMs() - System.currentTimeMillis() })

    val updates = UpdateController(
        installed = AppVersion.parse(BuildConfig.VERSION_NAME) ?: AppVersion(0, 0, 0),
        source = UpdateChecker(BinanceClient.defaultHttp()),
        downloader = ApkDownloader(BinanceClient.defaultHttp(), AndroidInstallLauncher.downloadDir(app)),
        inspector = AndroidApkInspector(app),
        launcher = AndroidInstallLauncher(app),
        memory = object : UpdateMemory {
            override suspend fun lastCheck() = ledger.dao().setting(LAST_UPDATE_CHECK)?.toLongOrNull() ?: 0L
            override suspend fun saveLastCheck(time: Long) = prefsStore.set(LAST_UPDATE_CHECK, time.toString())
        },
    )

    private val importState = MutableStateFlow<LegacyImporter.Result?>(null)
    val lastImport: StateFlow<LegacyImporter.Result?> = importState

    val today = TodayModel(ledger.dao(), prefsStore.preferences, candles, derivativesStore, strategies, scope)
    val ledgerModel = LedgerModel(ledger.dao(), prefsStore.preferences, strategies, scope)
    val strategiesModel = StrategiesModel(ledger.dao(), prefsStore, candles, strategies, scope)
    val markets = MarketsModel(ledger.dao(), candles, pairs, derivativesStore, strategies, scope)
    val calls = CallModel(ledger.dao(), candles, strategies)
    val settings = SettingsModel(app, prefsStore, updates, lastImport, BuildConfig.VERSION_NAME, scope)

    /** The ledger as a spreadsheet, for "Save as CSV". */
    suspend fun ledgerCsv(): String {
        val dao = ledger.dao()
        return ledgerModel.csv(dao.allCalls().filter { it.source == "v2" }, dao.allExits(), preferences.value.accountSize)
    }

    /**
     * Brings Signal Lab 1's record over if it has not been, tidies away what Signal Lab 1 left with Android, arms the daily
     * alarm and its backstop, and runs a pass at once when none has run in the last day.
     */
    fun start() {
        scope.launch { preferences.collect { host = if (it.binanceUs) DataConfig.HOST_US else DataConfig.HOST_COM } }
        scope.launch {
            try {
                importState.value = importer.run()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left for the next start: the import is all-or-nothing, so a failure leaves nothing half done.
            }
            runCatching { forgetSignalLab1() }
            runCatching {
                scheduler.arm()
                scheduler.ensureBackstop()
                val last = ledger.dao().setting(StrategyRunner.LAST_PASS)?.toLongOrNull()
                if (last == null || System.currentTimeMillis() - last > DAY_MS) scheduler.runNow()
            }
        }
    }

    /** Signal Lab 1's alarm and notification channels, removed once: its scanner is gone. Its record file is never touched. */
    private suspend fun forgetSignalLab1() {
        if (ledger.dao().setting(LEGACY_CLEANED) != null) return
        val old = Intent().setClassName(app.packageName, "com.ikverse.signallab.scan.ScanAlarmReceiver").setAction("com.ikverse.signallab.SCAN_ALARM")
        PendingIntent.getBroadcast(app, 100, old, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { pi ->
            app.getSystemService(AlarmManager::class.java)?.cancel(pi)
            pi.cancel()
        }
        app.getSystemService(NotificationManager::class.java)?.let { nm ->
            for (id in listOf("signals", "results", "status", "problems", "warnings")) nm.deleteNotificationChannel(id)
        }
        prefsStore.set(LEGACY_CLEANED, "1")
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val LAST_UPDATE_CHECK = "last_update_check"
        const val LEGACY_CLEANED = "legacy_cleaned"
    }
}
