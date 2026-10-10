package com.ikverse.signallab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The most coins a list holds. The same number the engine's rules enforce; a test keeps the two equal. */
const val MAX_COINS_PER_LIST = 30

/** The most coins a list may hold while it is watched on 1-minute charts, which cost the most data and battery. Mirrors the engine's cap; a test keeps the two equal. */
const val MAX_COINS_ON_1M = 10

/** How many coins a list watched on [charts] may hold: the 1-minute chart takes fewer than the rest. */
internal fun coinLimit(charts: Collection<String>): Int = if ("1m" in charts) MAX_COINS_ON_1M else MAX_COINS_PER_LIST

/** The "N of M chosen" line, which says why the limit is lower than usual when it is. */
internal fun chosenLine(chosen: Int, charts: Collection<String>): String {
    val limit = coinLimit(charts)
    return if (limit < MAX_COINS_PER_LIST) "$chosen of $limit chosen (1-minute charts allow $limit)" else "$chosen of $limit chosen"
}

/** Why the list cannot be started yet, in a sentence, or null when it can. */
internal fun startBlocker(name: String, chosen: Int, charts: Collection<String>): String? {
    val limit = coinLimit(charts)
    return when {
        chosen == 0 -> "Choose at least one coin."
        charts.isEmpty() -> "Choose at least one chart."
        chosen > limit -> "1-minute charts allow $limit coins: remove ${chosen - limit}, or turn 1m off."
        name.isBlank() -> "Give the list a name."
        else -> null
    }
}

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
        SearchField(query, { query = it }, "Search coins")
        // The ways to rank coins wrap onto a second line rather than running off the edge, so every one of them can be seen.
        ChipRow(Modifier.testTag("sources")) {
            for (s in PickSource.entries) ChoiceText(s.label, s == source, { sourceName = s.name }, Modifier.testTag("source-${s.name}"))
        }
        if (source == PickSource.GAINERS || source == PickSource.LOSERS) {
            ChipRow(Modifier.testTag("windows")) {
                Text("Over", style = Type.Small, modifier = Modifier.padding(start = 3.dp, end = 8.dp))
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
/** Pick the chart sizes a list is watched on: any number of the seven, with what each is for. A null [label] leaves out the heading. */
@Composable
fun ChartPicker(all: List<Pair<String, String>>, chosen: Set<String>, onChange: (Set<String>) -> Unit, modifier: Modifier = Modifier, label: String? = "Charts to watch") {
    Column(modifier) {
        if (label != null) SectionLabel(label)
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 13.dp)) {
            for ((label, _) in all) {
                TickChip(label, label in chosen, { onChange(if (label in chosen) chosen - label else chosen + label) })
            }
        }
        // What each chosen chart is for, one to a line, so they can be read down the page.
        val notes = all.filter { it.first in chosen }
        if (notes.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                for ((label, what) in notes) Text("$label: $what", style = Type.Small)
            }
        }
    }
}

/** The coins are downloading: how far along, and anything that failed. Shows nothing when there is nothing to say. */
@Composable
fun DownloadBanner(download: DownloadUi, modifier: Modifier = Modifier) {
    val text = when {
        download.running -> "Downloading history: coin ${download.ready + 1} of ${download.total}" + (download.current?.let { " ($it)" } ?: "")
        download.failures.isNotEmpty() -> "Could not download ${download.failures.size} ${if (download.failures.size == 1) "coin" else "coins"}: ${download.failures.values.first()}"
        else -> null
    }
    // The last thing said is kept, so the banner has its words to show while it closes.
    var last by remember { mutableStateOf("" to false) }
    val now = text?.let { it to download.running }
    LaunchedEffect(now) { if (now != null) last = now }
    val (shown, running) = now ?: last
    AnimatedVisibility(
        visible = text != null,
        enter = expandVertically(tween(Motion.ENTER_MS, easing = Motion.EaseOut)) + fadeIn(tween(Motion.ENTER_MS, easing = Motion.EaseOut)),
        exit = shrinkVertically(tween(Motion.EXIT_MS, easing = Motion.EaseOut)) + fadeOut(tween(Motion.EXIT_MS, easing = Motion.EaseOut)),
    ) {
        Column(modifier.fillMaxWidth().testTag("download-banner")) {
            Text(shown, style = Type.Small.copy(color = if (running) Palette.Muted else Palette.Warn), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            HRule()
        }
    }
}

/**
 * The first thing a new install shows, and what "New list" opens: name the list, choose its charts and coins, switch it on. On a
 * phone held upright it is one column, a short header (name, charts) over a coin picker that takes the rest of the screen. On a
 * wide screen, or an unfolded foldable, it is two panes: the list being made on the left, the coin picker on the right.
 */
@Composable
fun SetupScreen(
    lists: ListsModel,
    panels: PanelPrefs,
    wide: Boolean,
    modifier: Modifier = Modifier,
    title: String = "Choose your coins",
    intro: Boolean = true,
    onDone: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    defaultName: String = "My coins",
    backLabel: String? = null,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    var charts by rememberSaveable { mutableStateOf(listOf("15m", "1h", "4h")) }
    var chartsOpen by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val limit = coinLimit(charts)
    val order = lists.allTimeframes.map { it.first }
    val form = SetupForm(
        title = title, intro = intro, backLabel = backLabel, onCancel = onCancel,
        name = name, onName = { name = it },
        allCharts = lists.allTimeframes, charts = charts.sortedBy { order.indexOf(it) }, onCharts = { charts = it.toList() },
        chartsOpen = chartsOpen, onChartsOpen = { chartsOpen = it },
        chosen = chosen,
        // A coin past the limit is not added; the count line says what the limit is.
        onToggle = { s -> chosen = if (s in chosen) chosen - s else if (chosen.size < limit) chosen + s else chosen },
        countText = chosenLine(chosen.size, charts),
    )
    BoxWithConstraints(modifier.fillMaxSize().testTag("setup")) {
        val screenHeight = maxHeight
        val headerMax = screenHeight * 0.5f
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (wide) WideSetup(lists, panels, form, screenHeight) else PhoneSetup(lists, form, headerMax = headerMax)
            }
            SetupActions(lists, name, chosen, charts, message, { message = it }, scope, onDone)
        }
    }
}

/** What the setup screen is showing and how it changes, so the phone and the wide layout draw the same form. */
private class SetupForm(
    val title: String,
    val intro: Boolean,
    val backLabel: String?,
    val onCancel: (() -> Unit)?,
    val name: String,
    val onName: (String) -> Unit,
    val allCharts: List<Pair<String, String>>,
    val charts: List<String>,
    val onCharts: (Set<String>) -> Unit,
    val chartsOpen: Boolean,
    val onChartsOpen: (Boolean) -> Unit,
    val chosen: List<String>,
    val onToggle: (String) -> Unit,
    val countText: String,
)

/** Below this height the coin picker is given a fixed, scrolling pane of [SHORT_PICKER] rather than what is left over. */
private val SHORT_SCREEN = 480.dp
private val SHORT_PICKER = 380.dp

private const val INTRO_TEXT = "Signal Lab watches the coins you choose. When a pattern appears on one, it records a pretend trade: no real money is ever used. " +
    "Pick up to $MAX_COINS_PER_LIST coins, and the charts to watch them on."

/** A phone held upright: a short header, the coins you have picked as chips, and the coin picker with the rest of the screen. */
@Composable
private fun PhoneSetup(lists: ListsModel, form: SetupForm, headerMax: Dp) {
    Column(Modifier.fillMaxSize()) {
        // Capped at half the screen and scrolling inside that, so a short screen with the charts open still leaves room for coins.
        Column(Modifier.heightIn(max = headerMax).verticalScroll(rememberScrollState())) {
            if (form.backLabel != null && form.onCancel != null) BackRow(form.backLabel, form.onCancel)
            ScreenTitle(form.title)
            if (form.intro) Text(INTRO_TEXT, style = Type.Body.copy(color = Palette.Muted), modifier = Modifier.padding(horizontal = 16.dp))
            NameField(form.name, form.onName)
            ChartSummary(form.charts, form.chartsOpen, { form.onChartsOpen(!form.chartsOpen) })
            if (form.chartsOpen) ChartPicker(form.allCharts, form.charts.toSet(), form.onCharts, label = null)
        }
        HRule()
        if (form.chosen.isNotEmpty()) PickedChips(form.chosen, form.onToggle)
        CoinPicker(lists, form.chosen.toSet(), coinLimit(form.charts), form.onToggle, Modifier.weight(1f), countText = form.countText)
    }
}

/**
 * A wide screen or an unfolded foldable: the list being made on the left (name, charts with what each is for, the coins so far, each
 * with a way to remove it) and the coin picker on the right, with a divider that drags and a button that hides the left pane.
 */
@Composable
private fun WideSetup(lists: ListsModel, panels: PanelPrefs, form: SetupForm, height: Dp) {
    SplitPane(
        panels, "setup", "your list", 340f,
        list = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (form.backLabel != null && form.onCancel != null) BackRow(form.backLabel, form.onCancel)
                ScreenTitle(form.title)
                if (form.intro) Text(INTRO_TEXT, style = Type.Body.copy(color = Palette.Muted), modifier = Modifier.padding(horizontal = 16.dp))
                NameField(form.name, form.onName)
                ChartPicker(form.allCharts, form.charts.toSet(), form.onCharts)
                HRule()
                SectionLabel("Your coins", count = form.chosen.size)
                Column(Modifier.testTag("picked")) {
                    if (form.chosen.isEmpty()) Text("None yet. Choose them on the right.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp))
                    for (s in form.chosen) {
                        val base = s.removeSuffix("USDT")
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(base, style = Type.BodyStrong, modifier = Modifier.weight(1f))
                            IconAction(Glyphs.Close, "Remove $base", { form.onToggle(s) }, tint = Palette.Muted)
                        }
                        HRule()
                    }
                }
                EndSpace()
            }
        },
        page = {
            if (height >= SHORT_SCREEN) {
                CoinPicker(lists, form.chosen.toSet(), coinLimit(form.charts), form.onToggle, Modifier.fillMaxSize(), countText = form.countText)
            } else {
                // A phone held sideways: the picker's own header would leave the coins no room, so the pane scrolls and the picker keeps a usable height.
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    CoinPicker(lists, form.chosen.toSet(), coinLimit(form.charts), form.onToggle, Modifier.height(SHORT_PICKER), countText = form.countText)
                }
            }
        },
    )
}

/** The list's name, in a box that looks like a place to type, with its label over it. */
@Composable
private fun NameField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("List name", style = Type.Small, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = value, onValueChange = onChange, textStyle = Type.Body.copy(color = Palette.Strong), singleLine = true,
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "List name" },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = MinTouch).clip(shape).background(Palette.Raised).border(1.dp, Palette.Rule, shape).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text("Name this list", style = Type.Body.copy(color = Palette.Muted))
                    inner()
                }
            },
        )
    }
}

/** The charts a list is watched on in one line, with a button that opens the chips to change them. */
@Composable
private fun ChartSummary(charts: List<String>, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Charts: ${charts.joinToString(" · ").ifEmpty { "none" }}", style = Type.Body, modifier = Modifier.weight(1f).testTag("chart-summary"))
        TextAction(if (open) "Done" else "Change", onToggle, Modifier.testTag("charts-toggle"))
    }
}

/** The coins picked so far, in one row that scrolls sideways; touching one takes it off the list. */
@Composable
private fun PickedChips(coins: List<String>, onRemove: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().testTag("picked"), contentPadding = PaddingValues(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        items(coins, key = { it }) { symbol ->
            val base = symbol.removeSuffix("USDT")
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier.heightIn(min = MinTouch).clickable(role = Role.Button, onClick = { onRemove(symbol) }).padding(horizontal = 3.dp)
                    .semantics { contentDescription = "Remove $base" },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.heightIn(min = 32.dp).clip(shape).background(Palette.AccentTint).padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(base, style = Type.BodyStrong.copy(fontSize = 14.sp, color = Palette.OnAccentTint))
                    Icon(Glyphs.Close, contentDescription = null, tint = Palette.OnAccentTint, modifier = Modifier.size(14.dp))
                }
            }
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
) {
    val blocker = startBlocker(name, chosen.size, charts)
    Column {
        HRule()
        message?.let { ProblemState(it) }
        // The reason the button is grey, so it never sits there unexplained.
        blocker?.let { Text(it, style = Type.Small.copy(color = Palette.Muted), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("start-blocker")) }
        TonalButton(
            "Start watching ${chosen.size} ${if (chosen.size == 1) "coin" else "coins"}", enabled = blocker == null, stretch = true,
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("start"),
        )
    }
}
