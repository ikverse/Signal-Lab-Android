package com.ikverse.signallab.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The most coins a list holds. The same number the engine's rules enforce; a test keeps the two equal. */
const val MAX_COINS_PER_LIST = 30

/** What the picker is showing: the answer, and the source and window it was asked for, so a slow answer is never worded for a different list. */
private data class Shown(val source: PickSource, val window: PickWindow, val offers: OffersUi)

/**
 * Search the coins Binance trades, ranked by volume, gainers, losers, trades, volatility or listing date, and tick the ones to
 * watch. [selected] is what is ticked so far; [alreadyIn] are coins that can't be ticked again. [countText] replaces the
 * "N of M chosen" line where that is not what is being counted.
 */
@Composable
fun CoinPicker(
    lists: ListsModel,
    selected: Set<String>,
    max: Int,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    alreadyIn: Set<String> = emptySet(),
    countText: String? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sourceName by rememberSaveable { mutableStateOf(PickSource.VOLUME.name) }
    var windowName by rememberSaveable { mutableStateOf(PickWindow.H24.name) }
    val source = PickSource.entries.firstOrNull { it.name == sourceName } ?: PickSource.VOLUME
    val window = PickWindow.entries.firstOrNull { it.name == windowName } ?: PickWindow.H24
    val check by lists.listingCheck.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf<Shown?>(null) }

    LaunchedEffect(source) { if (source == PickSource.NEW) lists.checkListings() }
    // "New" asks again as the lookup of listing days finds more, ten coins at a time and when it starts, ends or fails.
    val lookupStep = if (source == PickSource.NEW) Triple(check.done / 10, check.running, check.failed) else null
    LaunchedEffect(query, source, window, lookupStep) {
        if (shown != null) delay(200)
        shown = Shown(source, window, lists.offers(query, source, window))
    }
    Column(modifier) {
        PlainField(query, { query = it }, "Search coins")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp).testTag("sources")) {
            for (s in PickSource.entries) ChoiceText(s.label, s == source, { sourceName = s.name }, Modifier.testTag("source-${s.name}"))
        }
        if (source == PickSource.GAINERS || source == PickSource.LOSERS) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).testTag("windows"), verticalAlignment = Alignment.CenterVertically) {
                Text("Over", style = Type.Small, modifier = Modifier.padding(horizontal = 10.dp))
                for (w in PickWindow.entries) ChoiceText(w.label, w == window, { windowName = w.name }, Modifier.testTag("window-${w.name}"))
            }
        }
        Text(Fmt.sourceNote(source, window), style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("source-note"))
        if (source == PickSource.NEW) ListingProgress(check, onRetry = { lists.checkListings() })
        Text(
            countText ?: "${selected.size} of $max chosen", style = Type.Small,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("chosen-count"),
        )
        HRule()
        val now = System.currentTimeMillis()
        val answer = shown
        when {
            answer == null -> Text("Loading the coin list…", style = Type.Small, modifier = Modifier.padding(16.dp))
            answer.offers.coins.isEmpty() -> when {
                answer.offers.problem != null -> EmptyState("Could not load this list", answer.offers.problem)
                query.isNotBlank() -> EmptyState("No coin matches “$query”", "Try the coin's short name, such as SOL.")
                answer.source == PickSource.VOLUME -> EmptyState("No coin list yet", "The list of Binance coins has not been downloaded. Check the connection; it is fetched once a day.")
                answer.source == PickSource.NEW && check.running -> EmptyState("Looking for new coins", "Checking when each coin was listed. New ones appear here as they are found.")
                else -> EmptyState("Nothing to show", "No coin fits this list right now.")
            }
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("offers")) {
                items(answer.offers.coins, key = { it.symbol }) { o ->
                    val locked = o.symbol in alreadyIn
                    CheckRow(
                        checked = o.symbol in selected || locked, title = o.base,
                        subtitle = if (locked) "already in this list" else Fmt.offerLine(o, answer.source, answer.window, now),
                        onToggle = { if (!locked) onToggle(o.symbol) },
                    )
                }
            }
        }
    }
}

/** How the lookup of listing days is going: how far it has got, or why it stopped, with a way to try again. */
@Composable
private fun ListingProgress(check: ListingCheckUi, onRetry: () -> Unit) {
    when {
        check.running -> Text(
            "Checking listing dates: ${check.done} of ${check.total}…", style = Type.Small.copy(color = Palette.Muted),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("listing-progress"),
        )
        check.failed != null -> Row(Modifier.padding(start = 16.dp).testTag("listing-failed"), verticalAlignment = Alignment.CenterVertically) {
            Text("Could not check every listing date: ${check.failed}", style = Type.Small.copy(color = Palette.Warn), modifier = Modifier.weight(1f))
            TextAction("Try again", onRetry)
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
    BoxWithConstraints(modifier.fillMaxSize().testTag("setup")) {
        // The name, the charts and the coin list share a page that scrolls, so a small phone never squeezes the coin list to nothing.
        val pickerHeight = maxOf(360.dp, maxHeight * 0.65f)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ScreenTitle(title)
                if (intro) Text(
                    "Signal Lab watches the coins you choose. When a pattern appears on one, it records a pretend trade: no real money is ever used. " +
                        "Pick up to $MAX_COINS_PER_LIST coins, and the charts to watch them on.",
                    style = Type.Body.copy(color = Palette.Muted), modifier = Modifier.padding(horizontal = 16.dp),
                )
                PlainField(name, { name = it }, "List name")
                ChartPicker(lists.allTimeframes, charts.toSet(), { charts = it.toList() })
                HRule()
                CoinPicker(lists, chosen.toSet(), MAX_COINS_PER_LIST, { s -> chosen = if (s in chosen) chosen - s else if (chosen.size < MAX_COINS_PER_LIST) chosen + s else chosen }, Modifier.height(pickerHeight))
            }
            SetupActions(lists, name, chosen, charts, message, { message = it }, scope, onDone, onCancel)
        }
    }
}

@Composable
private fun SetupActions(
    lists: ListsModel,
    name: String,
    chosen: List<String>,
    charts: List<String>,
    message: String?,
    setMessage: (String?) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: (() -> Unit)?,
    onCancel: (() -> Unit)?,
) {
    Column {
        HRule()
        message?.let { ProblemState(it) }
        if (onCancel != null) TextAction("Cancel", onCancel, color = Palette.Muted, modifier = Modifier.fillMaxWidth())
        TextAction(
            "Switch on and start (${chosen.size} ${if (chosen.size == 1) "coin" else "coins"})", enabled = chosen.isNotEmpty() && charts.isNotEmpty(),
            onClick = {
                scope.launch {
                    when (val r = lists.create(name, chosen, charts.toSet(), activate = true)) {
                        Outcome.Done -> {
                            setMessage(null)
                            onDone?.invoke()
                        }
                        is Outcome.Refused -> setMessage(r.message)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("start"),
        )
    }
}
