package com.ikverse.signallab

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ikverse.signallab.analyst.Analyst
import com.ikverse.signallab.analyst.AnalystStore
import com.ikverse.signallab.analyst.AnalystTools
import com.ikverse.signallab.analyst.AnalystTransport
import com.ikverse.signallab.analyst.AnalystWorker
import com.ikverse.signallab.analyst.ApiKeyStore
import com.ikverse.signallab.analyst.ClaudeApiTransport
import com.ikverse.signallab.analyst.KeystoreCipher
import com.ikverse.signallab.analyst.SpendMeter
import com.ikverse.signallab.analyst.Suggestions
import com.ikverse.signallab.catalog.StrategyCatalog
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
import com.ikverse.signallab.model.AnalystModel
import com.ikverse.signallab.model.CallModel
import com.ikverse.signallab.model.LedgerModel
import com.ikverse.signallab.model.MarketsModel
import com.ikverse.signallab.model.SettingsModel
import com.ikverse.signallab.model.StrategiesModel
import com.ikverse.signallab.model.TodayModel
import com.ikverse.signallab.runner.AndroidCallAlerts
import com.ikverse.signallab.runner.DailyCloseScheduler
import com.ikverse.signallab.runner.StrategyRunner
import com.ikverse.signallab.sync.DeviceSync
import com.ikverse.signallab.sync.DriveFolder
import com.ikverse.signallab.sync.GoogleAuthorizer
import com.ikverse.signallab.sync.LedgerSync
import com.ikverse.signallab.sync.SettingsSyncMemory
import com.ikverse.signallab.sync.SyncSettings
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

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
    val derivatives = DerivativesSync(
        FuturesClient(market), derivativesStore, market::nowMs,
        available = { host == DataConfig.HOST_COM && preferences.value.futuresData },
        deribit = com.ikverse.signallab.data.futures.DeribitClient(market),
        notices = com.ikverse.signallab.data.futures.NoticesClient(market),
    )
    val strategies = Builtins.all
    val catalog = StrategyCatalog(ledger.dao(), strategies, scope)
    private val allStrategies = { catalog.all.value }
    val suggestions = Suggestions(ledger.dao(), prefsStore)
    val runner = StrategyRunner(
        market, pairs, candles, DailyHistory(sync), ledger, AndroidCallAlerts(app), derivatives, strategies,
        preferences = { prefsStore.current() }, hourly = sync, customs = { catalog.running(it) }, pending = suggestions,
    )

    // The analyst: the owner's key sealed on this phone, Claude through Anthropic's SDK, and the app's tools.
    val apiKeys = ApiKeyStore(File(app.noBackupFilesDir, ApiKeyStore.FILE_NAME), KeystoreCipher())
    val analystStore = AnalystStore(File(app.filesDir, "analyst"))
    val spend = SpendMeter(ledger.dao())
    private var transport: Pair<String, AnalystTransport>? = null
    private fun transport(): AnalystTransport? {
        val key = apiKeys.load() ?: return null
        transport?.takeIf { it.first == key }?.let { return it.second }
        return ClaudeApiTransport(key).also { transport = key to it }
    }
    val analystTools = AnalystTools(ledger.dao(), candles, derivativesStore, catalog, prefsStore)
    val pcHost = com.ikverse.signallab.analyst.HostPairing(
        ledger.dao(), File(app.noBackupFilesDir, com.ikverse.signallab.analyst.HostPairing.FILE_NAME), KeystoreCipher(com.ikverse.signallab.analyst.KeystoreCipher.ALIAS),
    )
    val analyst = Analyst(::transport, analystTools.all, analystStore, spend, { prefsStore.current() }, pc = pcHost)
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

    val today = TodayModel(ledger.dao(), prefsStore.preferences, candles, derivativesStore, allStrategies, scope, suggestions = suggestions)
    val ledgerModel = LedgerModel(ledger.dao(), prefsStore.preferences, allStrategies, scope)
    val strategiesModel = StrategiesModel(ledger.dao(), prefsStore, candles, strategies, scope, allStrategies, catalog.saved)
    val markets = MarketsModel(ledger.dao(), candles, pairs, derivativesStore, allStrategies, scope)
    val calls = CallModel(ledger.dao(), candles, allStrategies) { prefsStore.current() }
    val analystModel = AnalystModel(analyst, analystStore, apiKeys, spend, prefsStore, ledger.dao(), scope, analystTools.all, pcHost)
    // Sync between the owner's devices, through the app's hidden folder in their Google Drive.
    val deviceSync = DeviceSync(
        SyncSettings(ledger.dao()), GoogleAuthorizer(app), { token -> DriveFolder(BinanceClient.defaultHttp(), token) }, LedgerSync(ledger, derivativesStore),
        SettingsSyncMemory(ledger.dao()), afterMerge = {},
    )
    val settings = SettingsModel(app, prefsStore, updates, lastImport, BuildConfig.VERSION_NAME, scope, deviceSync.status)

    /** After each pass: the analyst's brief and review, when the owner has asked for them and they are due. */
    suspend fun afterPass() {
        runCatching { AnalystWorker.queueDue(app, ledger.dao(), prefsStore.current(), System.currentTimeMillis()) }
        deviceSync.requestSync()
    }

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
            val dao = ledger.dao()
            val synced = dao.watchSettings().map { rows -> rows.filter { LedgerSync.isSynced(it.key) }.map { it.key to it.value } }.distinctUntilChanged()
            deviceSync.start(scope, listOf(dao.watchLiveCalls(), dao.watchLiveExits(), dao.watchCustomRules(), dao.watchPins(), synced))
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
