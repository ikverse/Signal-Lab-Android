package com.ikverse.signallab.runner

import android.content.Context
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.CandleSync
import com.ikverse.signallab.data.UniverseRepository
import com.ikverse.signallab.data.binance.BinanceClient
import com.ikverse.signallab.data.futures.DerivativesStore
import com.ikverse.signallab.data.futures.DerivativesSync
import com.ikverse.signallab.data.futures.FuturesClient
import com.ikverse.signallab.data.history.DailyHistory
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.LegacyImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Signal Lab 2's pieces, built on the same candle store, Binance client and pair list as the rest of the app, so one file
 * is never opened twice. While Signal Lab 1's screens and scanner remain (until milestone 6), this runs beside them and
 * writes only to its own ledger.
 */
class V2Graph(
    context: Context,
    market: BinanceClient,
    pairs: UniverseRepository,
    candles: CandleStore,
    sync: CandleSync,
    /** False on Binance.US, which has no futures market. */
    futuresAvailable: () -> Boolean,
) {
    val ledger: LedgerDatabase = LedgerDatabase.open(context)
    val derivativesStore = DerivativesStore(context)
    val derivatives = DerivativesSync(FuturesClient(market), derivativesStore, market::nowMs, futuresAvailable)
    val runner = StrategyRunner(market, pairs, candles, DailyHistory(sync), ledger, AndroidCallAlerts(context), derivatives)
    val importer = LegacyImporter(context, ledger)
    val scheduler = DailyCloseScheduler(context, offsetMs = { market.nowMs() - System.currentTimeMillis() })

    private val importState = MutableStateFlow<LegacyImporter.Result?>(null)

    /** What the one-time import of Signal Lab 1's record did, once it has run. */
    val lastImport: StateFlow<LegacyImporter.Result?> = importState

    /** Imports Signal Lab 1's record if it has not been, then arms the daily alarm and the backstop. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            try {
                importState.value = importer.run()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left for the next start: the import is all-or-nothing, so a failure leaves nothing half done.
            }
            runCatching {
                scheduler.arm()
                scheduler.ensureBackstop()
            }
        }
    }
}
