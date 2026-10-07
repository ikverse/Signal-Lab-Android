package com.ikverse.signallab.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** How many recent closed trades "Explain a trade" offers to choose from. */
const val ANALYST_TRADES_OFFERED = 20

/** How long "Tap again to stop" waits for the second tap. */
private const val STOP_CONFIRM_MS = 4_000L

/**
 * The warning for a lab pattern whose charts [unwatched] no active list watches; null when every chart is watched. [never] means none of
 * its charts is, so it could not trade at all.
 */
fun unwatchedText(unwatched: List<String>, never: Boolean): String? {
    if (unwatched.isEmpty()) return null
    val charts = if (unwatched.size == 1) unwatched[0] else unwatched.dropLast(1).joinToString(", ") + " or " + unwatched.last()
    return if (never) "No active list watches $charts, so it would never trade. Add $charts to a list first."
    else "No active list watches $charts, so it would not trade there."
}

/** One chart's backtest of a lab pattern, in a line. */
fun labRowText(r: LabRowUi): String =
    if (r.coins == 0) {
        "${r.chart}: no stored candles, because no active list watches this chart"
    } else {
        "${r.chart} · ${r.coins} coins · ${r.trades} trades · win ${Fmt.percent(r.hitRate, 0)} · average ${Fmt.signedPercent(r.meanNet)} · " +
            "vs random ${Fmt.signedPercent(r.excess)} · t ${r.tCluster?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"}"
    }

/**
 * The Analyst: questions that open the Claude app with a draft holding the record's data, answered on the user's own Claude plan, the
 * answers the user kept, and the lab patterns they suggested. A wide screen shows the list beside the open report, with a divider that can
 * be dragged or the list hidden.
 */
@Composable
fun AnalystScreen(
    model: AnalystModel,
    trades: TradesModel,
    panels: PanelPrefs,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    wide: Boolean,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier,
    onGo: (String) -> Unit = {},
) {
    val reports by model.reports.collectAsStateWithLifecycle()
    val current = reports.firstOrNull { it.id == selected } ?: if (wide) reports.firstOrNull() else null
    val index = @Composable { AnalystIndex(model, trades, reports, current?.id, onSelect, onOpenPage) }
    val content = @Composable {
        when {
            current != null -> ReportView(model, current, wide, onSelect, onOpenPage, onGo)
            else -> EmptyState("No reports yet", "Ask a question, send it in the Claude app, then share or paste the answer here.")
        }
    }
    Column(modifier.fillMaxSize().testTag("analyst-screen")) {
        when {
            wide -> SplitPane(panels, "analyst", "questions", 340f, index, content, Modifier.weight(1f))
            current == null -> Column(Modifier.weight(1f)) { index() }
            else -> Column(Modifier.weight(1f)) { content() }
        }
    }
}

/** A report: the answer itself, and when it suggests patterns, a second tab to backtest and start them. */
@Composable
private fun ReportView(model: AnalystModel, report: ReportUi, wide: Boolean, onSelect: (Long?) -> Unit, onOpenPage: (String) -> Unit, onGo: (String) -> Unit) {
    var patterns by rememberSaveable(report.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (!wide) TextAction("‹ Analyst", { onSelect(null) }, color = Palette.Muted)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(report.title, style = Type.Heading, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("report-title"))
            Text("Kept ${Fmt.dateTime(report.receivedAt)}", style = Type.Small)
        }
        if (report.suggestions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                ChoiceText("Answer", !patterns, { patterns = false }, Modifier.testTag("tab-answer"))
                ChoiceText("Patterns (${report.suggestions.size})", patterns, { patterns = true }, Modifier.testTag("tab-patterns"))
            }
        }
        HRule()
        if (patterns && report.suggestions.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f).testTag("suggestions")) {
                itemsIndexed(report.suggestions, key = { i, s -> "$i-${s.definition}" }) { i, s ->
                    SuggestionCard(model, report.id, i, s, onOpenPage)
                    HRule()
                }
            }
        } else {
            LearnView(LearnPageUi("report-${report.id}", "Reports", report.title, report.markdown), onOpenPage = onOpenPage, modifier = Modifier.weight(1f), onGo = onGo)
        }
    }
}

@Composable
private fun SuggestionCard(model: AnalystModel, reportId: Long, index: Int, s: LabSuggestionUi, onOpenPage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember(s.definition) { mutableStateOf<LabBacktestUi?>(null) }
    var testing by remember(s.definition) { mutableStateOf(false) }
    var message by remember(s.definition) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("suggestion-$index")) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(s.title, style = Type.BodyStrong)
            s.reason?.let { Text(it, style = Type.Small) }
            s.summary?.let { Text(it, style = Type.Body, modifier = Modifier.padding(top = 4.dp).testTag("summary-$index")) }
            s.problem?.let { Text("Cannot be tested: $it", style = Type.Body.copy(color = Palette.Warn), modifier = Modifier.padding(top = 4.dp).testTag("problem-$index")) }
            unwatchedText(s.unwatched, s.neverTrades)?.let {
                Text(it, style = Type.Small.copy(color = Palette.Warn), modifier = Modifier.padding(top = 4.dp).testTag("unwatched-$index"))
            }
        }
        val definition = s.definition ?: return@Column
        Row {
            TextAction(if (testing) "Backtesting…" else "Backtest", {
                scope.launch {
                    testing = true
                    result = model.backtest(definition)
                    testing = false
                }
            }, enabled = !testing, modifier = Modifier.testTag("backtest-$index"))
            if (s.runningAs != null) {
                TextAction("Forward-testing as lab ${s.runningAs}", { onOpenPage("pattern-lab") }, color = Palette.Muted, modifier = Modifier.testTag("running-$index"))
            } else {
                TextAction("Start forward-testing", {
                    scope.launch { message = (model.startLab(definition, s.title, s.reason, reportId) as? Outcome.Refused)?.message }
                }, modifier = Modifier.testTag("start-$index"))
            }
        }
        result?.let { r ->
            Column(Modifier.padding(horizontal = 16.dp).testTag("backtest-result-$index")) {
                for (row in r.rows) Text(labRowText(row), style = Type.Small.copy(color = Palette.Text))
                Text(r.note, style = Type.Small)
            }
        }
        message?.let { Text(it, style = Type.Body.copy(color = Palette.Warn), modifier = Modifier.padding(horizontal = 16.dp).testTag("start-problem-$index")) }
    }
}

@Composable
private fun AnalystIndex(
    model: AnalystModel,
    trades: TradesModel,
    reports: List<ReportUi>,
    currentId: Long?,
    onSelect: (Long?) -> Unit,
    onOpenPage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notice by model.notice.collectAsStateWithLifecycle()
    val all by trades.trades.collectAsStateWithLifecycle()
    val lab by model.lab.collectAsStateWithLifecycle()
    val budget by model.budget.collectAsStateWithLifecycle()
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var stopArmed by remember { mutableStateOf<Long?>(null) }
    val closed = remember(all) { all.filter { it.closed != null }.sortedByDescending { it.closed!!.exitTime }.take(ANALYST_TRADES_OFFERED) }

    LaunchedEffect(stopArmed) {
        if (stopArmed != null) {
            delay(STOP_CONFIRM_MS)
            stopArmed = null
        }
    }

    fun ask(card: String, trade: Long? = null) {
        scope.launch {
            problem = (model.ask(card, trade) as? Outcome.Refused)?.message
            if (problem == null) picking = false
        }
    }

    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            problem = "There is no text to paste. In Claude, tap Copy under the answer first."
            return
        }
        problem = null
        scope.launch { model.keep(text)?.let(onSelect) }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("analyst-index")) {
        item(key = "title") { ScreenTitle("Analyst") }
        item(key = "intro") {
            Text(
                if (model.claudeInstalled) {
                    "Each question opens the Claude app with a draft: the question, with your record's data below it, answered on your own Claude " +
                        "plan. Nothing is sent until you tap Send there. To keep an answer, tap Share under it and choose Signal Lab."
                } else {
                    "The Claude app is not on this phone, so a question opens Android's share menu instead. With the Claude app installed, it " +
                        "opens there directly, answered on your own Claude plan."
                },
                style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp).testTag("analyst-intro"),
            )
            TextAction("How this works", { onOpenPage("analyst") }, modifier = Modifier.testTag("analyst-learn"))
        }
        notice?.let { text ->
            item(key = "notice") {
                ProblemState(text)
                TextAction("OK", model::noticeSeen, modifier = Modifier.testTag("notice-ok"))
            }
        }
        problem?.let { text -> item(key = "problem") { ProblemState(text) } }
        item(key = "ask") { SectionLabel("Ask Claude") }
        items(model.cards, key = { "card-${it.id}" }) { c ->
            TouchRow({ if (c.needsTrade) picking = !picking else ask(c.id) }, modifier = Modifier.testTag("card-${c.id}")) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = Type.BodyStrong)
                    Text(c.description, style = Type.Small)
                }
                Text(
                    when {
                        !c.needsTrade -> "Ask"
                        picking -> "Close"
                        else -> "Pick a trade"
                    },
                    style = Type.Small.copy(color = Palette.Accent),
                )
            }
            HRule()
            if (c.needsTrade && picking) {
                if (closed.isEmpty()) {
                    Text("No closed trades yet.", style = Type.Small, modifier = Modifier.padding(16.dp).testTag("pick-none"))
                }
                for (t in closed) {
                    TouchRow({ ask(c.id, t.id) }, modifier = Modifier.testTag("pick-${t.id}")) {
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                "${t.symbol.removeSuffix("USDT")} · ${t.timeframe} · ${Fmt.signedPercent(t.closed?.net)}",
                                style = Type.BodyStrong.copy(color = Fmt.changeColor(t.closed?.net)),
                            )
                            Text("${t.label} · closed ${Fmt.dateTime(t.closed!!.exitTime)}", style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                HRule()
            }
        }
        item(key = "lab") {
            SectionLabel("Lab patterns")
            Text(
                "${budget.running} of ${budget.maxRunning} running · ${budget.newLeft} of ${budget.maxNew} new left in ${budget.windowDays} days. " +
                    "Every pattern tested raises the bar for every verdict.",
                style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp).testTag("lab-budget"),
            )
            TextAction("About the lab", { onOpenPage("pattern-lab") }, modifier = Modifier.testTag("lab-learn"))
            if (lab.isEmpty()) {
                Text(
                    "None yet. Ask \"Suggest 3 patterns\", keep the answer, then start one from its Patterns tab.",
                    style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("no-lab"),
                )
            }
        }
        items(lab, key = { "lab-${it.id}" }) { p ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp).testTag("lab-${p.id}")) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text("Lab ${p.id}: ${p.title}", style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.summary, style = Type.Small, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        p.stoppedAt?.let { "Stopped ${Fmt.dateTime(it)}" } ?: "Forward-testing since ${Fmt.dateTime(p.startedAt)}",
                        style = Type.Small,
                    )
                    if (p.stoppedAt == null) {
                        unwatchedText(p.unwatched, p.neverTrades)?.let {
                            Text(it, style = Type.Small.copy(color = Palette.Warn), modifier = Modifier.testTag("lab-unwatched-${p.id}"))
                        }
                    }
                }
                if (p.stoppedAt == null) {
                    TextAction(if (stopArmed == p.id) "Tap again to stop" else "Stop", {
                        if (stopArmed == p.id) {
                            stopArmed = null
                            scope.launch { problem = (model.stopLab(p.id) as? Outcome.Refused)?.message }
                        } else {
                            stopArmed = p.id
                        }
                    }, color = if (stopArmed == p.id) Palette.Down else Palette.Accent, modifier = Modifier.testTag("stop-${p.id}"))
                }
            }
            HRule()
        }
        item(key = "reports") {
            SectionLabel("Reports")
            TextAction("Paste an answer", ::paste, modifier = Modifier.testTag("paste"))
        }
        if (reports.isEmpty()) {
            item(key = "no-reports") {
                Text(
                    "None yet. In Claude, tap Share under an answer and choose Signal Lab, or tap Copy and then Paste an answer.",
                    style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("no-reports"),
                )
            }
        }
        items(reports, key = { "report-${it.id}" }) { r ->
            TouchRow({ onSelect(r.id) }, selected = r.id == currentId, modifier = Modifier.testTag("report-${r.id}")) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Fmt.dateTime(r.receivedAt) + if (r.suggestions.isNotEmpty()) " · ${r.suggestions.size} patterns suggested" else "",
                        style = Type.Small,
                    )
                }
            }
            HRule()
        }
    }
}
