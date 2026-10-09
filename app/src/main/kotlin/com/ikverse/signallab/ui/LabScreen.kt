package com.ikverse.signallab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long "Tap again to stop" waits for the second tap. */
private const val STOP_ARMED_MS = 4_000L

/** Where the Lab is: its home, one test, the builder, or the ideas Claude suggested. Kept as text so it survives rotation. */
private sealed interface LabView {
    data object Home : LabView
    data class Test(val id: Long) : LabView
    data object Build : LabView
    data object Ideas : LabView

    val text: String
        get() = when (this) {
            Home -> "home"
            is Test -> "test:$id"
            Build -> "build"
            Ideas -> "ideas"
        }

    companion object {
        fun of(text: String): LabView = when {
            text == "build" -> Build
            text == "ideas" -> Ideas
            text.startsWith("test:") -> text.removePrefix("test:").toLongOrNull()?.let(::Test) ?: Home
            else -> Home
        }
    }
}

/** A pattern's backtest row on one chart, said plainly. */
fun labRowWords(r: LabRowUi): String =
    if (r.coins == 0) {
        "${Fmt.chartAdjective(r.chart)} chart: no stored candles, because no active list watches this chart."
    } else if (r.trades == 0) {
        "${Fmt.chartAdjective(r.chart)} chart: it never matched on ${PlainWords.count(r.coins, "coin")}."
    } else {
        "${Fmt.chartAdjective(r.chart)} chart: ${PlainWords.count(r.trades, "trade")} over ${PlainWords.count(r.coins, "coin")}. " +
            "Won ${Fmt.percent(r.hitRate, 0)}, average ${Fmt.signedPercent(r.meanNet, 1)} per trade after fees."
    }

/**
 * The pattern lab: your own ideas, tested the same honest way as the built-in patterns. Home lists the live tests with how far each is
 * from a verdict; an idea is built from simple pieces, tried on past data, and started as a live test. A phone shows one page at a time; a
 * wider screen puts the tests, the idea and its chart side by side, and the side panes can be dragged narrower or hidden.
 */
@Composable
fun LabScreen(
    analyst: AnalystModel,
    scorecard: ScorecardModel,
    trades: TradesModel,
    markets: MarketsModel,
    panels: PanelPrefs,
    layout: LayoutClass,
    hasPage: (String) -> Boolean,
    onOpenLearn: (String) -> Unit,
    onShowTrade: (TradeUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tests by analyst.lab.collectAsStateWithLifecycle()
    val budget by analyst.budget.collectAsStateWithLifecycle()
    val reports by analyst.reports.collectAsStateWithLifecycle()
    val card by scorecard.scorecard.collectAsStateWithLifecycle()
    val all by trades.trades.collectAsStateWithLifecycle()
    var viewText by rememberSaveable { mutableStateOf(LabView.Home.text) }
    var draft by rememberSaveable(stateSaver = LabDraft.Saver) { mutableStateOf(LabDraft()) }
    var chartTrade by rememberSaveable { mutableStateOf<Long?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val view = LabView.of(viewText)
    val setView = { v: LabView -> viewText = v.text }
    val wide = layout != LayoutClass.Compact
    val scope = rememberCoroutineScope()

    BackHandler(enabled = !wide && view != LabView.Home) { setView(LabView.Home) }
    // A test that no longer exists (the record was cleared) is not shown.
    if (view is LabView.Test && tests.none { it.id == view.id }) {
        LaunchedEffect(view) { setView(LabView.Home) }
    }
    val progressOf = { id: Long -> LabProgress.of(id, card.rows) }
    val tradesOf = { id: Long -> all.filter { it.variant.startsWith("lab${id}_") }.sortedByDescending { it.openedAt } }
    val help = { id: String? -> if (id != null && hasPage(id)) { { onOpenLearn(id) } } else null }

    val home = @Composable {
        LabHome(
            tests, budget, progressOf, reports, view, hasPage, onOpenLearn, message,
            onBuild = { draft = LabDraft(); setView(LabView.Build) },
            onExample = { draft = it; setView(LabView.Build) },
            onIdeas = { setView(LabView.Ideas) },
            onOpen = { setView(LabView.Test(it)) },
            onAsk = { scope.launch { message = (analyst.ask("suggest-patterns") as? Outcome.Refused)?.message } },
        )
    }
    val page = @Composable {
        when (view) {
            LabView.Home -> if (wide) Hint("Pick a test on the left, or build a new idea.") else Unit
            is LabView.Test -> tests.firstOrNull { it.id == view.id }?.let { t ->
                TestPage(
                    analyst, t, progressOf(t.id), tradesOf(t.id), wide, scope, help, onOpenLearn,
                    onBack = { setView(LabView.Home) },
                    onCopy = { d -> draft = d; setView(LabView.Build) },
                    onShowTrade = { tr -> if (wide) chartTrade = tr.id else onShowTrade(tr) },
                    compactChart = if (wide) null else { { ChartForTrade(markets, tradesOf(t.id).firstOrNull(), Modifier.fillMaxWidth().height(260.dp)) } },
                )
            }
            LabView.Build -> BuilderPage(
                analyst, draft, { draft = it }, wide, scope, help,
                onBack = { setView(LabView.Home) },
                onStarted = { message = "Live test started. It trades from the next setup on."; setView(LabView.Home) },
            )
            LabView.Ideas -> IdeasPage(
                reports, wide, onBack = { setView(LabView.Home) },
                onOpenInBuilder = { d -> draft = d; setView(LabView.Build) },
                onAsk = { scope.launch { message = (analyst.ask("suggest-patterns") as? Outcome.Refused)?.message } },
            )
        }
    }
    val side = @Composable {
        // The chart beside the idea: the chosen trade of the open test, else the newest, else what the builder would have matched.
        val shown = (view as? LabView.Test)?.let { v -> tradesOf(v.id).let { list -> list.firstOrNull { it.id == chartTrade } ?: list.firstOrNull() } }
        when {
            view is LabView.Test && shown != null -> ChartForTrade(markets, shown, Modifier.fillMaxSize())
            view is LabView.Test -> Hint("Its trades will be drawn here as soon as it has one.")
            view == LabView.Build -> Hint("Try the idea on past data to see how it would have done. Its live trades will be drawn here once the test is running.")
            else -> Hint("Choose a test to see its trades on a chart.")
        }
    }

    if (!wide) {
        Box(modifier.fillMaxSize().testTag("lab")) { if (view == LabView.Home) home() else page() }
        return
    }
    SplitPane(
        panels, "lab", "tests", 330f, list = home, modifier = modifier.testTag("lab"),
        page = {
            if (layout == LayoutClass.Wide) {
                SplitPane(panels, "lab-idea", "the idea", 420f, list = { Column(Modifier.fillMaxSize()) { page() } }, page = side)
            } else {
                Column(Modifier.fillMaxSize()) { page() }
            }
        },
    )
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Type.Small, modifier = Modifier.testTag("lab-hint"))
    }
}

/** A trade's chart with its lines, or a line saying why there is none. */
@Composable
private fun ChartForTrade(markets: MarketsModel, trade: TradeUi?, modifier: Modifier = Modifier) {
    if (trade == null) {
        Hint("This test has no trade to draw yet.")
        return
    }
    val changes by markets.changes.collectAsStateWithLifecycle()
    val chart by produceState<ChartUi?>(null, trade.symbol, trade.timeframe, changes) { value = markets.chart(trade.symbol, trade.timeframe) }
    Column(modifier.testTag("lab-chart")) {
        Text(
            "${baseOf(trade.symbol)} on the ${Fmt.chartAdjective(trade.timeframe)} chart",
            style = Type.BodyStrong, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val c = chart
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                c == null -> Text("Loading…", style = Type.Small, modifier = Modifier.padding(16.dp))
                c.candles.isEmpty() -> EmptyState("No candles yet", "The history for this chart is still downloading.")
                else -> ChartView(c.copy(levels = levelsOf(c, setOf(trade.id))), null, Modifier.fillMaxSize())
            }
        }
    }
}

// --- Home ----------------------------------------------------------------------------------------------------------------

@Composable
private fun LabHome(
    tests: List<LabPatternUi>,
    budget: LabBudgetUi,
    progressOf: (Long) -> LabProgress,
    reports: List<ReportUi>,
    view: LabView,
    hasPage: (String) -> Boolean,
    onOpenLearn: (String) -> Unit,
    message: String?,
    onBuild: () -> Unit,
    onExample: (LabDraft) -> Unit,
    onIdeas: () -> Unit,
    onOpen: (Long) -> Unit,
    onAsk: () -> Unit,
) {
    val running = tests.filter { it.stoppedAt == null }
    val finished = tests.filter { it.stoppedAt != null }
    val ideas = reports.sumOf { r -> r.suggestions.count { it.definition != null && it.runningAs == null } }
    var examples by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("lab-home")) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("Pattern lab", Modifier.weight(1f))
                if (hasPage("pattern-lab")) IconAction(Glyphs.Info, "How the lab works", { onOpenLearn("pattern-lab") }, Modifier.padding(end = 4.dp).testTag("lab-learn"), tint = Palette.Muted)
            }
        }
        item(key = "room") {
            RaisedGroup {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "You can run ${PlainWords.count((budget.maxRunning - budget.running).coerceAtLeast(0), "more test")}. " +
                            "${PlainWords.count(budget.newLeft, "new idea")} left in the next ${budget.windowDays} days.",
                        style = Type.Body, modifier = Modifier.testTag("lab-budget"),
                    )
                    Bar(budget.running, budget.maxRunning)
                    Text("${budget.running} of ${budget.maxRunning} places in use", style = Type.Small)
                }
            }
        }
        message?.let { m -> item(key = "message") { Text(m, style = Type.Body.copy(color = Palette.Warn), modifier = Modifier.padding(16.dp).testTag("lab-message")) } }
        item(key = "new") {
            SectionLabel("New idea")
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("Build your own", onBuild, Modifier.fillMaxWidth().testTag("lab-build"))
                LineButton("Ask Claude for ideas", onAsk, Modifier.fillMaxWidth().testTag("lab-ask"))
                LineButton(if (examples) "Hide the examples" else "Start from an example", { examples = !examples }, Modifier.fillMaxWidth().testTag("lab-examples"))
            }
            if (examples) {
                for ((i, e) in LabDraft.examples.withIndex()) {
                    TouchRow({ onExample(e) }, modifier = Modifier.testTag("example-$i")) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = Type.BodyStrong)
                            Text(e.sentence, style = Type.Small, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HRule()
                }
            }
        }
        if (ideas > 0) item(key = "ideas") {
            TouchRow(onIdeas, selected = view == LabView.Ideas, modifier = Modifier.testTag("lab-ideas")) {
                Column(Modifier.weight(1f)) {
                    Text("Ideas from Claude", style = Type.BodyStrong)
                    Text("${PlainWords.count(ideas, "idea")} you have not tried yet", style = Type.Small)
                }
                CountBadge(ideas)
            }
            HRule()
        }
        item(key = "live") { SectionLabel("Live tests", count = running.size) }
        if (running.isEmpty()) {
            item(key = "none") {
                Text("None running. Build an idea, try it on past data, then start it.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("no-lab"))
            }
        }
        items(running, key = { "t-${it.id}" }) { t -> TestRow(t, progressOf(t.id), view == LabView.Test(t.id), { onOpen(t.id) }) }
        if (finished.isNotEmpty()) {
            item(key = "done") { SectionLabel("Stopped", count = finished.size) }
            items(finished, key = { "s-${it.id}" }) { t -> TestRow(t, progressOf(t.id), view == LabView.Test(t.id), { onOpen(t.id) }) }
        }
        item(key = "end") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun Bar(value: Int, of: Int, color: Color = Palette.Accent) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Rule)) {
        if (of > 0) Box(Modifier.fillMaxWidth((value.toFloat() / of).coerceIn(0f, 1f)).height(6.dp).background(color))
    }
}

private fun statusColor(p: LabProgress): Color = when (p.status) {
    "Working", "Slightly ahead so far" -> Palette.Up
    "Not working", "Behind so far" -> Palette.Down
    else -> Palette.Warn
}

@Composable
private fun TestRow(t: LabPatternUi, p: LabProgress, selected: Boolean, onClick: () -> Unit) {
    TouchRow(onClick, selected = selected, modifier = Modifier.testTag("lab-${t.id}"), minHeight = 72.dp) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (t.stoppedAt == null) {
                    Text(
                        p.status, style = Type.Label.copy(color = statusColor(p)), maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp).border(1.dp, statusColor(p), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                } else {
                    Text("Stopped", style = Type.Small)
                }
            }
            Text("${p.closed} of ${PlainWords.MIN_VERDICT} trades · ${p.result}", style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.stoppedAt == null) Bar(p.closed.coerceAtMost(PlainWords.MIN_VERDICT), PlainWords.MIN_VERDICT, statusColor(p))
        }
    }
    HRule()
}

// --- One test -------------------------------------------------------------------------------------------------------------

@Composable
private fun TestPage(
    analyst: AnalystModel,
    t: LabPatternUi,
    p: LabProgress,
    trades: List<TradeUi>,
    wide: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    help: (String?) -> (() -> Unit)?,
    onOpenLearn: (String) -> Unit,
    onBack: () -> Unit,
    onCopy: (LabDraft) -> Unit,
    onShowTrade: (TradeUi) -> Unit,
    compactChart: (@Composable () -> Unit)?,
) {
    val draft = remember(t.definition) { LabDraft.read(t.definition, t.title) }
    var code by rememberSaveable(t.id) { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var problem by remember(t.id) { mutableStateOf<String?>(null) }
    var past by remember(t.id) { mutableStateOf<LabBacktestUi?>(null) }
    var running by remember(t.id) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(STOP_ARMED_MS)
            armed = false
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("lab-test")) {
        if (!wide) BackRow("Pattern lab", onBack)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t.title, style = Type.Title, modifier = Modifier.testTag("lab-test-title"))
            Text(
                (if (t.reportId != null) "Suggested by Claude" else "Your idea") + " · started ${Fmt.dateTime(t.startedAt)}" +
                    (t.stoppedAt?.let { " · stopped ${Fmt.dateTime(it)}" } ?: ""),
                style = Type.Small,
            )
            t.reason?.let { Text(it, style = Type.Small) }
        }
        RaisedGroup {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(draft?.sentence ?: t.summary, style = Type.Body, modifier = Modifier.testTag("lab-sentence"))
                TextAction(if (code) "Hide the code" else "Show the code", { code = !code }, Modifier.testTag("lab-code-toggle"))
                if (code) Text(t.definition, style = Type.Small.copy(color = Palette.Muted), modifier = Modifier.testTag("lab-code"))
            }
        }
        Spacer(Modifier.height(10.dp))
        RaisedGroup {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (t.stoppedAt == null) "Live test" else "Result when stopped", style = Type.BodyStrong, modifier = Modifier.weight(1f))
                    Text(p.status, style = Type.Label.copy(color = statusColor(p)), modifier = Modifier.border(1.dp, statusColor(p), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 1.dp))
                }
                Text("${p.closed} of ${PlainWords.MIN_VERDICT} trades done. A verdict appears at ${PlainWords.MIN_VERDICT}.", style = Type.Body, modifier = Modifier.testTag("lab-progress"))
                Bar(p.closed.coerceAtMost(PlainWords.MIN_VERDICT), PlainWords.MIN_VERDICT, statusColor(p))
                Text(p.result, style = Type.Small)
                help("scorecard")?.let { open -> TextAction("How we tell if it is working", open) }
                unwatchedText(t.unwatched, t.neverTrades)?.let { Text(it, style = Type.Small.copy(color = Palette.Warn), modifier = Modifier.testTag("lab-unwatched")) }
                problem?.let { Text(it, style = Type.Body.copy(color = Palette.Warn), modifier = Modifier.testTag("lab-problem")) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (t.stoppedAt == null) {
                        LineButton(if (armed) "Tap again to stop" else "Stop this test", {
                            if (armed) {
                                armed = false
                                scope.launch { problem = (analyst.stopLab(t.id) as? Outcome.Refused)?.message }
                            } else {
                                armed = true
                            }
                        }, Modifier.testTag("stop-${t.id}"))
                    }
                    if (draft != null) LineButton("Copy and change", { onCopy(draft.copy(title = t.title + " (copy)")) }, Modifier.testTag("lab-copy"))
                }
            }
        }
        compactChart?.let { chart ->
            SectionLabel("Its latest trade")
            chart()
        }
        SectionLabel("Its trades", count = trades.size)
        if (trades.isEmpty()) Text("None yet. A trade starts the next time the idea matches on a coin you watch.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp))
        for (tr in trades.take(MAX_TRADES_SHOWN)) {
            TouchRow({ onShowTrade(tr) }, modifier = Modifier.testTag("lab-trade-${tr.id}")) {
                Column(Modifier.weight(1f)) {
                    Text("${baseOf(tr.symbol)} on the ${Fmt.chartAdjective(tr.timeframe)} chart", style = Type.BodyStrong)
                    Text(if (tr.closed == null) "Open" else "Finished ${Fmt.dateTime(tr.closed.exitTime)}", style = Type.Small)
                }
                tr.closed?.let { Text(Fmt.signedPercent(it.net, 1), style = Type.NumberStrong.copy(color = Fmt.changeColor(it.net))) }
            }
            HRule()
        }
        SectionLabel("Tried on past data")
        RaisedGroup {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LineButton(if (running) "Working…" else "Try it on past data", {
                    scope.launch {
                        running = true
                        past = analyst.backtest(t.definition)
                        running = false
                    }
                }, Modifier.testTag("lab-past"), enabled = !running)
                past?.let { r ->
                    for (row in r.rows) Text(labRowWords(row), style = Type.Small.copy(color = Palette.Text))
                    Text(r.note, style = Type.Small.copy(color = Palette.Warn))
                }
                help("past-data")?.let { open -> TextAction("Why past results do not decide", open) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private const val MAX_TRADES_SHOWN = 30

// --- The builder ----------------------------------------------------------------------------------------------------------

@Composable
private fun BuilderPage(
    analyst: AnalystModel,
    draft: LabDraft,
    onChange: (LabDraft) -> Unit,
    wide: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    help: (String?) -> (() -> Unit)?,
    onBack: () -> Unit,
    onStarted: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) } // "row:part", e.g. "0:2"
    var past by remember(draft.definition) { mutableStateOf<LabBacktestUi?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember(draft.definition) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("lab-builder")) {
        if (!wide) BackRow("Pattern lab", onBack)
        Text("Build your own idea", style = Type.Title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Text("Tap a piece to change it. The sentence at the bottom says what the app will do.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp))
        SectionLabel("Enter when")
        RaisedGroup {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                draft.conditions.forEachIndexed { i, c ->
                    if (i > 0) Text("and", style = Type.Small, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Pill(LabVocab.label(c.left), editing == "$i:0", Modifier.weight(1f, fill = false).testTag("part-$i-0")) { editing = if (editing == "$i:0") null else "$i:0" }
                        Pill(LabVocab.compareWords(c.compare), editing == "$i:1", Modifier.testTag("part-$i-1")) { editing = if (editing == "$i:1") null else "$i:1" }
                        Pill(LabVocab.label(c.right), editing == "$i:2", Modifier.weight(1f, fill = false).testTag("part-$i-2")) { editing = if (editing == "$i:2") null else "$i:2" }
                        if (draft.conditions.size > 1) IconAction(Glyphs.Close, "Remove condition ${i + 1}", {
                            editing = null
                            onChange(draft.copy(conditions = draft.conditions.filterIndexed { j, _ -> j != i }))
                        }, Modifier.testTag("remove-$i"), tint = Palette.Muted)
                    }
                    if (editing?.startsWith("$i:") == true) {
                        val part = editing!!.substringAfter(':').toInt()
                        PartEditor(part, c, { onChange(draft.withCondition(i, it)) }, help)
                    }
                }
                if (draft.conditions.size < LabVocab.MAX_CONDITIONS) {
                    TextAction("+ Add another condition", {
                        onChange(draft.copy(conditions = draft.conditions + DraftCondition("volume_ratio(20)", "above", "1.5")))
                    }, Modifier.testTag("add-condition"))
                }
            }
        }
        SectionLabel("On these charts")
        Row(Modifier.padding(horizontal = 12.dp)) {
            for (c in LabVocab.charts) {
                TickChip(c, c in draft.charts, {
                    onChange(draft.copy(charts = if (c in draft.charts) draft.charts - c else draft.charts + c))
                }, Modifier.testTag("chart-$c"))
            }
        }
        SectionLabel("How a trade ends")
        val holdN = LabVocab.holdOf(draft.exit)
        Row(Modifier.padding(horizontal = 12.dp)) {
            ChoiceText("Trailing stop", draft.exit == "trail", { onChange(draft.copy(exit = "trail")) }, Modifier.testTag("exit-trail"))
            ChoiceText("Learned goal", draft.exit == "learned", { onChange(draft.copy(exit = "learned")) }, Modifier.testTag("exit-learned"))
            ChoiceText("After N candles", holdN != null, { onChange(draft.copy(exit = "hold(${LabVocab.DEFAULT_HOLD})")) }, Modifier.testTag("exit-hold"))
        }
        if (holdN != null) {
            Stepper("Candles to hold", holdN, 1, LabVocab.MAX_HOLD, { onChange(draft.copy(exit = "hold($it)")) }, Modifier.padding(horizontal = 16.dp))
        }
        help("trailing")?.let { open -> TextAction("What do these mean?", open, Modifier.padding(horizontal = 4.dp)) }
        SectionLabel("In plain words")
        RaisedGroup {
            Text(draft.sentence, style = Type.Body, modifier = Modifier.padding(14.dp).testTag("builder-sentence"))
        }
        val flaw = draft.problem
        flaw?.let { Text(it, style = Type.Body.copy(color = Palette.Warn), modifier = Modifier.padding(16.dp).testTag("builder-flaw")) }
        problem?.let { Text(it, style = Type.Body.copy(color = Palette.Warn), modifier = Modifier.padding(16.dp).testTag("builder-problem")) }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LineButton(if (busy) "Working…" else "Try it on past data", {
                scope.launch {
                    busy = true
                    past = analyst.backtest(draft.definition)
                    busy = false
                }
            }, Modifier.fillMaxWidth().testTag("builder-past"), enabled = flaw == null && !busy)
            TonalButton("Start live test", {
                scope.launch {
                    when (val r = analyst.startLab(draft.definition, draft.name, null, null)) {
                        Outcome.Done -> onStarted()
                        is Outcome.Refused -> problem = r.message
                    }
                }
            }, Modifier.fillMaxWidth().testTag("builder-start"), enabled = flaw == null)
        }
        past?.let { r ->
            SectionLabel("Tried on past data")
            RaisedGroup {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in r.rows) Text(labRowWords(row), style = Type.Small.copy(color = Palette.Text))
                    Text(r.note, style = Type.Small.copy(color = Palette.Warn))
                    help("past-data")?.let { open -> TextAction("Why past results do not decide", open) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Pill(text: String, open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text, style = Type.BodyStrong.copy(color = if (open) Palette.OnAccentTint else Palette.Strong), maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(2.dp).heightIn48().clip(shape).background(if (open) Palette.AccentTint else Palette.Background)
            .border(1.dp, if (open) Palette.AccentTint else Palette.Rule, shape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
    )
}

private fun Modifier.heightIn48(): Modifier = this.then(Modifier.height(MinTouch))

/** The choices for one piece of a condition, opened in place under it. */
@Composable
private fun PartEditor(part: Int, c: DraftCondition, onChange: (DraftCondition) -> Unit, help: (String?) -> (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("part-editor-$part")) {
        if (part == 1) {
            for ((id, words) in LabVocab.compares) {
                TouchRow({ onChange(c.copy(compare = id)) }, selected = c.compare == id, minHeight = 44.dp, modifier = Modifier.testTag("compare-$id")) {
                    Text(words.replaceFirstChar { it.uppercase() }, style = Type.Body)
                }
            }
            help("crosses")?.let { open -> TextAction("Crosses above or is above?", open) }
            return@Column
        }
        val current = if (part == 0) c.left else c.right
        val (block, n) = LabVocab.split(current)
        for (b in LabVocab.blocks) {
            TouchRow({
                val v = if (b.takesN) "${b.id}(${b.defaultN})" else b.id
                onChange(if (part == 0) c.copy(left = v) else c.copy(right = v))
            }, selected = block?.id == b.id, minHeight = 44.dp, modifier = Modifier.testTag("block-${b.id}")) {
                Text(b.menu.replace("N candles", "a number of candles").replace(" N ", " a number of "), style = Type.Body)
            }
        }
        if (part == 2) {
            var number by remember(current) { mutableStateOf(if (block == null) current else "") }
            TouchRow({ onChange(c.copy(right = if (number.toDoubleOrNull() != null) number else "30")) }, selected = block == null, minHeight = 44.dp, modifier = Modifier.testTag("block-number")) {
                Text("A plain number", style = Type.Body, modifier = Modifier.weight(1f))
                NumberField(
                    number, { v -> number = v; v.toDoubleOrNull()?.let { onChange(c.copy(right = v)) } }, "The number",
                    suffix = "", keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        }
        if (block != null && block.takesN) {
            Stepper("Number of candles", n ?: block.defaultN, LabVocab.MIN_N, LabVocab.MAX_N, { v ->
                val text = "${block.id}($v)"
                onChange(if (part == 0) c.copy(left = text) else c.copy(right = text))
            }, Modifier.padding(horizontal = 14.dp))
        }
        block?.learn?.let { id -> help(id)?.let { open -> TextAction("What is this?", open) } }
    }
}

/** A whole number changed with − and + buttons, held between [min] and [max]. */
@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().testTag("stepper"), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Body, modifier = Modifier.weight(1f))
        IconAction(Glyphs.Dash, "Fewer", { onChange((value - 1).coerceAtLeast(min)) }, Modifier.testTag("stepper-less"))
        Text("$value", style = Type.NumberStrong, modifier = Modifier.width(44.dp).testTag("stepper-value"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconAction(Glyphs.Plus, "More", { onChange((value + 1).coerceAtMost(max)) }, Modifier.testTag("stepper-more"))
    }
}

// --- Ideas from Claude ----------------------------------------------------------------------------------------------------

@Composable
private fun IdeasPage(reports: List<ReportUi>, wide: Boolean, onBack: () -> Unit, onOpenInBuilder: (LabDraft) -> Unit, onAsk: () -> Unit) {
    val ideas = reports.flatMap { r -> r.suggestions.map { r to it } }
    LazyColumn(Modifier.fillMaxSize().testTag("lab-ideas-page")) {
        item(key = "back") { if (!wide) BackRow("Pattern lab", onBack) }
        item(key = "title") {
            Text("Ideas from Claude", style = Type.Title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Text("Open one in the builder to try it on past data before it uses a place.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            LineButton("Ask Claude for new ideas", onAsk, Modifier.padding(12.dp).testTag("ideas-ask"))
        }
        if (ideas.isEmpty()) item(key = "none") { Text("None yet. Ask Claude, then paste its answer under Ask Claude.", style = Type.Small, modifier = Modifier.padding(16.dp)) }
        items(ideas.size, key = { "idea-$it" }) { i ->
            val (_, s) = ideas[i]
            val draft = s.definition?.let { LabDraft.read(it, s.title) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("idea-$i"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, style = Type.BodyStrong, modifier = Modifier.weight(1f))
                    if (s.runningAs != null) Text("Testing", style = Type.Label.copy(color = Palette.Up))
                }
                s.reason?.let { Text(it, style = Type.Small) }
                if (draft != null) Text(draft.sentence, style = Type.Body)
                s.problem?.let { Text("Cannot be tested: $it", style = Type.Body.copy(color = Palette.Warn)) }
                if (draft != null && s.runningAs == null) LineButton("Open in the builder", { onOpenInBuilder(draft) }, Modifier.testTag("idea-open-$i"))
            }
            HRule()
        }
    }
}
