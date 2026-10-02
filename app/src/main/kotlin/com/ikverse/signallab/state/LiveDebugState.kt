package com.ikverse.signallab.state

import com.ikverse.signallab.BuildConfig
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.ui.DebugState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LiveDebugState(private val graph: AppGraph, private val scope: CoroutineScope) : DebugState {
    private val state = MutableStateFlow(listOf("Signal Lab ${BuildConfig.VERSION_NAME}"))
    override val lines: StateFlow<List<String>> = state
    private var note: String? = null

    /** The SQLite this phone really has. Android 10 ships 3.22, which is why the data layer avoids UPSERT and VACUUM INTO. */
    private val sqliteVersion: String by lazy {
        android.database.sqlite.SQLiteDatabase.create(null).use { db ->
            db.rawQuery("select sqlite_version()", null).use { it.moveToFirst(); it.getString(0) }
        }
    }

    init {
        if (BuildConfig.DEBUG) scope.launch {
            while (true) {
                try {
                    state.value = render()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.value = listOf("Signal Lab ${BuildConfig.VERSION_NAME}", "readout failed: ${e.message}")
                }
                delay(2_000)
            }
        }
    }

    private suspend fun render(): List<String> {
        val now = System.currentTimeMillis()
        val (coins, offered) = graph.candles.coinCount()
        val refreshed = graph.candles.lastPairListRefresh()
        val lists = graph.watchlists.lists.value
        val progress = graph.history.progress.value
        val rows = graph.candles.rowsByTimeframe()
        return buildList {
            add("Signal Lab ${BuildConfig.VERSION_NAME}  (debug readout, M3)")
            add("Binance clock: phone is ${"%+d".format(graph.market.clockSkewMs)} ms behind  ·  SQLite $sqliteVersion  ·  Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            add("Pair list: $coins pairs, $offered offered" + (refreshed?.let { ", refreshed ${(now - it) / 60_000} min ago" } ?: ", not downloaded yet"))
            if (lists.isEmpty()) add("Lists: none yet") else for (l in lists) add("List \"${l.name}\": ${if (l.active) "active" else "off"}, ${l.symbols.size} coins")
            add(
                if (progress.running) "History: ${progress.ready} of ${progress.total} coins ready, downloading ${progress.current ?: "..."}"
                else if (progress.total > 0) "History: ${progress.ready} of ${progress.total} coins ready, idle"
                else "History: nothing to download",
            )
            add("Candles stored: " + Timeframe.entries.joinToString("  ") { "${it.label} ${"%,d".format(rows[it] ?: 0L)}" } + "  (total ${"%,d".format(rows.values.sum())})")
            progress.failures.entries.take(3).forEach { add("failed ${it.key}: ${it.value}") }
            graph.lastProblem.value?.let { add("problem: $it") }
            note?.let { add(it) }
        }
    }

    override fun refreshPairList() {
        scope.launch {
            note = "refreshing pair list..."
            note = try {
                graph.universe.refreshIfStale(force = true)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "refresh failed: ${e.message}"
            }
        }
    }

    override fun createTestList() {
        scope.launch {
            try {
                note = "creating test list..."
                graph.universe.refreshIfStale()
                val top = graph.universe.top(30)
                val created = graph.watchlists.create("Top 30 by volume (test)")
                val list = (created as? com.ikverse.signallab.data.WatchlistResult.Ok)?.value
                    ?: graph.watchlists.lists.value.first { it.name.startsWith("Top 30") }
                for (c in top) graph.watchlists.add(list.id, c.symbol)
                graph.watchlists.setActive(list.id, true)
                note = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = "could not create the list: ${e.message}"
            }
        }
    }
}
