package com.ikverse.signallab.catalog

import com.ikverse.signallab.engine.strategy.AnalystCall
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.dsl.CustomStrategy
import com.ikverse.signallab.engine.strategy.dsl.Rule
import com.ikverse.signallab.ledger.CallRow
import com.ikverse.signallab.ledger.CustomStrategyRow
import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A custom strategy as saved: its row and the strategy it reads as. */
class SavedStrategy(val row: CustomStrategyRow, val strategy: CustomStrategy) {
    val id: String get() = strategy.id

    /** Proposed by the analyst rather than written by hand. */
    val byAnalyst: Boolean get() = row.reportSyncId != null
}

/**
 * Every strategy whose calls can be in the ledger: the built-ins, the analyst's own, and each custom strategy ever saved,
 * running or not, so their history keeps its name. The runner runs the built-ins and the custom strategies set running.
 */
class StrategyCatalog(private val dao: LedgerDao, val builtins: List<Strategy>, scope: CoroutineScope) {
    val saved: StateFlow<List<SavedStrategy>> = dao.watchCustomRules().map { rows -> rows.mapNotNull(::read).distinctBy { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val all: StateFlow<List<Strategy>> = saved.map { list -> builtins + AnalystCall + list.map { it.strategy } }
        .stateIn(scope, SharingStarted.Eagerly, builtins + AnalystCall)

    /** The custom strategies set running, read from the ledger now (the runner calls this at the start of each pass). */
    suspend fun running(prefs: Preferences): List<Strategy> =
        dao.customRules().mapNotNull(::read).distinctBy { it.id }.filter { it.id in prefs.customLive }.map { it.strategy }

    companion object {
        fun read(row: CustomStrategyRow): SavedStrategy? {
            val text = row.definitionV2 ?: return null
            val definition = runCatching { Rule.parse(text) }.getOrNull() ?: return null
            return SavedStrategy(row, CustomStrategy(CustomStrategy.idFor(definition), row.title, definition, row.reason))
        }

        /**
         * The strategy a stored call ran under, as it ran: an analyst call with its own stop, target and time limit, any
         * other by its id in [byId].
         */
        fun forCall(row: CallRow, byId: Map<String, Strategy>): Strategy? =
            if (row.strategyId == AnalystCall.id) AnalystCall.forCall(row.entryPrice, row.atr ?: Double.NaN, row.stop, row.target, row.maxBars)
            else byId[row.strategyId]
    }
}
