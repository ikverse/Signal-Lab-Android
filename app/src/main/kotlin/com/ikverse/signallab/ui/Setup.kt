package com.ikverse.signallab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The most coins a list holds. The same number the engine's rules enforce; a test keeps the two equal. */
const val MAX_COINS_PER_LIST = 30

/** Search the coins Binance trades and tick the ones to watch. [selected] is what is ticked so far. */
@Composable
fun CoinPicker(lists: ListsModel, selected: Set<String>, max: Int, onToggle: (String) -> Unit, modifier: Modifier = Modifier, alreadyIn: Set<String> = emptySet()) {
    var query by rememberSaveable { mutableStateOf("") }
    var offers by remember { mutableStateOf<List<OfferUi>?>(null) }
    LaunchedEffect(query) {
        if (offers != null) delay(200)
        offers = lists.offers(query)
    }
    Column(modifier) {
        PlainField(query, { query = it }, "Search coins")
        Text("${selected.size} of $max chosen", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("chosen-count"))
        HRule()
        val shown = offers
        when {
            shown == null -> Text("Loading the coin list…", style = Type.Small, modifier = Modifier.padding(16.dp))
            shown.isEmpty() -> EmptyState(
                if (query.isBlank()) "No coin list yet" else "No coin matches “$query”",
                if (query.isBlank()) "The list of Binance coins has not been downloaded. Check the connection; it is fetched once a day." else "Try the coin's short name, such as SOL.",
            )
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(shown, key = { it.symbol }) { o ->
                    val locked = o.symbol in alreadyIn
                    CheckRow(
                        checked = o.symbol in selected || locked, title = o.base,
                        subtitle = if (locked) "already in this list" else "24h volume ${Fmt.compact(o.quoteVolume)} USDT",
                        onToggle = { if (!locked) onToggle(o.symbol) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
/** Pick the chart sizes a list is watched on: any number of the seven, with what each is for. */
@Composable
fun ChartPicker(all: List<Pair<String, String>>, chosen: Set<String>, onChange: (Set<String>) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionLabel("Charts to watch")
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            for ((label, _) in all) {
                ChoiceText(label, label in chosen, { onChange(if (label in chosen) chosen - label else chosen + label) })
            }
        }
        val notes = all.filter { it.first in chosen }.joinToString("  ·  ") { "${it.first}: ${it.second}" }
        if (notes.isNotEmpty()) Text(notes, style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

/** The coins are downloading: how far along, and anything that failed. Shows nothing when there is nothing to say. */
@Composable
fun DownloadBanner(download: DownloadUi, modifier: Modifier = Modifier) {
    val text = when {
        download.running -> "Downloading history: coin ${download.ready + 1} of ${download.total}" + (download.current?.let { " ($it)" } ?: "")
        download.failures.isNotEmpty() -> "Could not download ${download.failures.size} ${if (download.failures.size == 1) "coin" else "coins"}: ${download.failures.values.first()}"
        else -> return
    }
    Column(modifier.fillMaxWidth().testTag("download-banner")) {
        Text(text, style = Type.Small.copy(color = if (download.running) Palette.Muted else Palette.Warn), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        HRule()
    }
}

/** The first thing a new install shows, and what comes back if every list is deleted: choose coins, name the list, switch it on. */
@Composable
fun SetupScreen(
    lists: ListsModel,
    modifier: Modifier = Modifier,
    title: String = "Choose your coins",
    intro: Boolean = true,
    onDone: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    var name by rememberSaveable { mutableStateOf("My coins") }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    var charts by rememberSaveable { mutableStateOf(listOf("15m", "1h", "4h")) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().testTag("setup")) {
        ScreenTitle(title)
        if (intro) Text(
            "Signal Lab watches the coins you choose. When a pattern appears on one, it records a pretend trade: no real money is ever used. " +
                "Pick up to $MAX_COINS_PER_LIST coins, and the charts to watch them on.",
            style = Type.Body.copy(color = Palette.Muted), modifier = Modifier.padding(horizontal = 16.dp),
        )
        PlainField(name, { name = it }, "List name")
        ChartPicker(lists.allTimeframes, charts.toSet(), { charts = it.toList() })
        HRule()
        CoinPicker(lists, chosen.toSet(), MAX_COINS_PER_LIST, { s -> chosen = if (s in chosen) chosen - s else if (chosen.size < MAX_COINS_PER_LIST) chosen + s else chosen }, Modifier.weight(1f))
        HRule()
        message?.let { ProblemState(it) }
        if (onCancel != null) TextAction("Cancel", onCancel, color = Palette.Muted, modifier = Modifier.fillMaxWidth())
        TextAction(
            "Switch on and start (${chosen.size} ${if (chosen.size == 1) "coin" else "coins"})", enabled = chosen.isNotEmpty() && charts.isNotEmpty(),
            onClick = {
                scope.launch {
                    when (val r = lists.create(name, chosen, charts.toSet(), activate = true)) {
                        Outcome.Done -> {
                            message = null
                            onDone?.invoke()
                        }
                        is Outcome.Refused -> message = r.message
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("start"),
        )
    }
}
