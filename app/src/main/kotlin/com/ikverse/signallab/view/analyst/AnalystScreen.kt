package com.ikverse.signallab.view.analyst

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikverse.signallab.analyst.ClaudeModel
import com.ikverse.signallab.analyst.RunLog
import com.ikverse.signallab.analyst.Turn
import com.ikverse.signallab.model.AnalystUi
import com.ikverse.signallab.view.common.Overline
import com.ikverse.signallab.view.common.Panel
import com.ikverse.signallab.view.common.PrimaryButton
import com.ikverse.signallab.view.common.QuietButton
import com.ikverse.signallab.view.common.RowsPanel
import com.ikverse.signallab.view.common.ScreenTitle
import com.ikverse.signallab.view.common.SwitchRow
import com.ikverse.signallab.view.common.Words
import com.ikverse.signallab.view.theme.Ink
import com.ikverse.signallab.view.theme.Type
import java.util.Locale

/** What the Analyst tab can ask its model to do. */
interface AnalystActions {
    fun send(text: String)
    fun stop()
    fun saveKey(key: String): String?
    fun removeKey()
    fun setModel(id: String)
    fun setCap(usd: Double)
    fun setBrief(on: Boolean)
    fun setReview(on: Boolean)
    fun clearChat()
    fun openKeyPage()

    /** Without a key: hands the question and a snapshot of the data to the Claude app through the share sheet. */
    fun shareToClaude(question: String)

    fun pairPc(address: String, code: String)
    fun confirmPairing()
    fun cancelPairing()
    fun forgetPc()
    fun checkPc()
}

private val starters = listOf(
    "How are my strategies doing against random entries?",
    "What in the news could move my open calls this week?",
    "Look for a new strategy worth testing and backtest it.",
)

/**
 * The analyst: Claude with Signal Lab's data, on the owner's own API key. The conversation, the latest report, and below
 * them the key, the model, the monthly budget and the scheduled runs.
 */
@Composable
fun AnalystScreen(state: AnalystUi, actions: AnalystActions, onRuns: () -> Unit, modifier: Modifier = Modifier) {
    if (!state.loaded) {
        Box(modifier.fillMaxSize().testTag("analyst-loading"))
        return
    }
    var draft by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(state.turns.size, state.streaming.length / 200) {
        val last = list.layoutInfo.totalItemsCount - 1
        if (last >= 0 && (state.running || state.turns.isNotEmpty())) list.animateScrollToItem(last)
    }
    Column(modifier.fillMaxSize().imePadding().testTag("analyst")) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("analyst-list"), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ScreenTitle("Analyst")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.spendLine, style = Type.Small, modifier = Modifier.weight(1f).testTag("spend"))
                        TextButton(onRuns, Modifier.testTag("to-runs")) { Text("What was sent", color = Ink.Accent, fontSize = 13.sp) }
                    }
                }
            }
            if (!state.canAsk) item(key = "pc-setup") { PcSetup(state, actions) }
            if (!state.canAsk) item(key = "setup") { KeySetup(actions) }
            state.reports.firstOrNull()?.let { r ->
                item(key = "report-${r.id}") { ReportPanel(r.title, r.at, r.body) }
            }
            if (state.canAsk && state.turns.isEmpty() && !state.running) {
                item(key = "starters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ask about your calls, your strategies or the market. It reads the app's own numbers and the news.", style = Type.Small)
                        for (s in starters) {
                            Panel(Modifier.testTag("starter"), onClick = { actions.send(s) }, outlined = true, ground = Ink.Ground, padding = PaddingValues(12.dp)) {
                                Text(s, style = Type.Body.copy(color = Ink.Text))
                            }
                        }
                    }
                }
            }
            items(state.turns, key = { "${it.role}-${it.at}" }) { TurnBubble(it) }
            if (state.running) {
                item(key = "streaming") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.streaming.isNotBlank()) Bubble(Turn.ASSISTANT) { Rich(state.streaming) }
                        state.working?.let { Text(it, style = Type.Small.copy(color = Ink.Accent), modifier = Modifier.testTag("working")) }
                    }
                }
            }
            state.problem?.let { p -> item(key = "problem") { Text(p, style = Type.Small.copy(color = Ink.Loss), modifier = Modifier.testTag("problem")) } }
            if (state.canAsk) item(key = "settings") { AnalystSettings(state, actions) }
            item(key = "note") {
                Text(
                    "Runs on your PC's Claude Code or your Claude API key. What leaves the phone is what each run sent: your question, your recent messages and the data its tools returned, to your PC or to Anthropic. Research, not financial advice.",
                    style = Type.Caption.copy(color = Ink.Faint),
                )
            }
        }
        if (state.canAsk) {
            Row(
                Modifier.fillMaxWidth().background(Ink.Bar).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    draft, { draft = it }, modifier = Modifier.weight(1f).testTag("ask"), placeholder = { Text("Ask the analyst", color = Ink.Faint) },
                    maxLines = 5, textStyle = Type.Body.copy(color = Ink.Text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (!state.running && draft.isNotBlank()) { actions.send(draft); draft = "" } }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink.Accent, unfocusedBorderColor = Ink.Outline),
                )
                if (state.running) QuietButton("Stop", actions::stop, Modifier.testTag("stop"))
                else PrimaryButton("Send", { actions.send(draft); draft = "" }, Modifier.testTag("send"), enabled = draft.isNotBlank())
            }
        }
    }
}

/** Pairing with the analyst host on the owner's PC: its address and code, then the fingerprint check. */
@Composable
private fun PcSetup(state: AnalystUi, actions: AnalystActions) {
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Panel(Modifier.testTag("pc-setup")) {
        Text("Use your PC", style = Type.Subhead)
        val probe = state.pairing
        if (probe == null) {
            Text(
                "The analyst can run in Claude Code on your PC, on your own Claude plan. Start the analyst host there (tools\\start-analyst-host.cmd in the Signal Lab folder) and type what its window shows. The phone must be on the same network.",
                style = Type.Body.copy(color = Ink.Body),
            )
            OutlinedTextField(
                address, { address = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pc-address"),
                placeholder = { Text("Address, such as 192.168.1.20", color = Ink.Faint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink.Accent, unfocusedBorderColor = Ink.Outline),
            )
            OutlinedTextField(
                code, { code = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pc-code"),
                placeholder = { Text("Pairing code", color = Ink.Faint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink.Accent, unfocusedBorderColor = Ink.Outline),
            )
            state.pairingProblem?.let { Text(it, style = Type.Small.copy(color = Ink.Loss), modifier = Modifier.testTag("pc-problem")) }
            PrimaryButton(if (state.pairingBusy) "Connecting…" else "Connect", { actions.pairPc(address, code) }, Modifier.testTag("pc-connect"),
                enabled = !state.pairingBusy && address.isNotBlank() && code.isNotBlank())
        } else {
            Text("Does the PC's window say the fingerprint ends in", style = Type.Body.copy(color = Ink.Body))
            Text(probe.shortFingerprint, style = Type.BigNumber, modifier = Modifier.testTag("pc-fingerprint"))
            Text("Pair only if it does: then this phone trusts that PC and no other." + (probe.claude?.let { " It runs $it." } ?: ""), style = Type.Small)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("It matches", actions::confirmPairing, Modifier.testTag("pc-confirm"))
                QuietButton("It doesn't", actions::cancelPairing, Modifier.testTag("pc-cancel"))
            }
        }
    }
}

@Composable
private fun KeySetup(actions: AnalystActions) {
    var key by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.testTag("key-setup")) {
        Text("Or use a Claude API key", style = Type.Subhead)
        Text(
            "The analyst runs on an API key from your own Claude account. In the Claude Console, open API keys, create one, copy it and paste it here. Paid Claude plans include a monthly API credit that covers it first. The key is sealed on this phone and is never backed up or synced.",
            style = Type.Body.copy(color = Ink.Body),
        )
        TextButton(actions::openKeyPage) { Text("Open the Claude Console", color = Ink.Accent) }
        OutlinedTextField(
            key, { key = it; problem = null }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("key-field"),
            placeholder = { Text("sk-ant-…", color = Ink.Faint) }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink.Accent, unfocusedBorderColor = Ink.Outline),
        )
        problem?.let { Text(it, style = Type.Small.copy(color = Ink.Loss), modifier = Modifier.testTag("key-problem")) }
        PrimaryButton("Save key", { problem = actions.saveKey(key); if (problem == null) key = "" }, Modifier.testTag("save-key"), enabled = key.isNotBlank())
    }
    Panel(Modifier.testTag("handoff")) {
        Text("No key? Ask in the Claude app", style = Type.Subhead)
        Text(
            "Shares a question with a snapshot of the market, your ledger and your strategies to the Claude app. Claude can read it there but cannot test rules, propose strategies or suggest calls back into Signal Lab.",
            style = Type.Small,
        )
        var question by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            question, { question = it }, modifier = Modifier.fillMaxWidth().testTag("handoff-question"), maxLines = 4,
            placeholder = { Text("Your question (optional)", color = Ink.Faint) }, textStyle = Type.Body.copy(color = Ink.Text),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink.Accent, unfocusedBorderColor = Ink.Outline),
        )
        QuietButton("Share to the Claude app", { actions.shareToClaude(question) }, Modifier.testTag("share-claude"))
    }
}

@Composable
private fun AnalystSettings(state: AnalystUi, actions: AnalystActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp).testTag("analyst-settings"), verticalAlignment = Alignment.CenterVertically) {
            Overline("Key, model and budget", Modifier.weight(1f))
            Text(if (open) "Hide" else "Show", style = Type.Small.copy(color = Ink.Accent))
        }
        if (!open) return@Column
        var cap by rememberSaveable(state.capUsd) { mutableStateOf(String.format(Locale.US, "%.0f", state.capUsd)) }
        RowsPanel(rows = listOfNotNull<@Composable () -> Unit>(
            state.pcAddress?.let { address ->
                @Composable {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val status = when (state.pcAnswering) {
                            true -> "answering"
                            false -> "not answering"
                            null -> "not checked yet"
                        }
                        Text("Your PC at $address · $status", style = Type.Body.copy(color = Ink.Text), modifier = Modifier.testTag("pc-line"))
                        Text("Questions go to your PC first, on your Claude plan, while it runs the analyst host.", style = Type.Caption)
                        state.pairingProblem?.let { Text(it, style = Type.Caption.copy(color = Ink.Loss)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(actions::checkPc, Modifier.testTag("pc-check")) { Text("Check now", color = Ink.Accent) }
                            TextButton(actions::forgetPc, Modifier.testTag("pc-forget")) { Text("Unpair", color = Ink.Loss) }
                        }
                    }
                }
            },
            (@Composable { PcSetup(state, actions) }).takeIf { state.pcAddress == null },
            (@Composable {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Key ending ${state.keyHint ?: "…"}", style = Type.Body.copy(color = Ink.Text), modifier = Modifier.weight(1f))
                    TextButton(actions::removeKey, Modifier.testTag("remove-key")) { Text("Remove", color = Ink.Loss) }
                }
            }).takeIf { state.keySet },
            @Composable {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Model", style = Type.Body.copy(color = Ink.Text))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in ClaudeModel.entries) {
                            val on = m.id == state.modelId
                            Text(
                                m.label + if (m == ClaudeModel.SONNET) " · half the price" else "",
                                style = Type.Small.copy(color = if (on) Ink.OnAccent else Ink.Text),
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Ink.Accent else Ink.Raised)
                                    .clickable { actions.setModel(m.id) }.padding(horizontal = 10.dp, vertical = 8.dp).testTag("model-${m.name.lowercase()}"),
                            )
                        }
                    }
                }
            },
            @Composable {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Monthly budget, USD", style = Type.Body.copy(color = Ink.Text))
                        Text("Spent this month: ${Words.usd(state.monthUsd)}", style = Type.Caption)
                    }
                    OutlinedTextField(
                        cap, { cap = it }, singleLine = true, modifier = Modifier.width(96.dp).testTag("cap"), textStyle = Type.Number,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { cap.toDoubleOrNull()?.takeIf { it >= 0 }?.let(actions::setCap) }),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink.Accent, unfocusedBorderColor = Ink.Outline),
                    )
                }
            },
            @Composable { SwitchRow("Daily brief", "A short brief after each daily close, about 20 cents.", state.brief, actions::setBrief) },
            @Composable { SwitchRow("Weekly review", "A research review on Sundays, about a dollar.", state.review, actions::setReview) },
            @Composable {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextButton(actions::clearChat, Modifier.testTag("clear-chat"), enabled = !state.running) { Text("Clear the conversation", color = Ink.Muted) }
                }
            },
        ))
    }
}

@Composable
private fun ReportPanel(title: String, at: Long, body: String) {
    var open by rememberSaveable(at) { mutableStateOf(false) }
    Panel(Modifier.testTag("report"), onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline(title, Modifier.weight(1f))
            Text(Words.day(at), style = Type.Caption)
        }
        if (open) Rich(body) else Text(body.lineSequence().firstOrNull { it.isNotBlank() }?.let(::plain).orEmpty(), style = Type.Body.copy(color = Ink.Body), maxLines = 3)
    }
}

@Composable
private fun TurnBubble(t: Turn) {
    Bubble(t.role) {
        if (t.role == Turn.USER) Text(t.text, style = Type.Body.copy(color = Ink.Text)) else Rich(t.text)
        t.costUsd?.takeIf { it > 0 }?.let { Text(Words.usd(it), style = Type.Caption.copy(color = Ink.Faint)) }
    }
}

@Composable
private fun Bubble(role: String, content: @Composable () -> Unit) {
    val mine = role == Turn.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        if (mine) Spacer(Modifier.width(40.dp))
        Column(
            Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(14.dp)).background(if (mine) Ink.Raised else Ink.Surface)
                .padding(12.dp).testTag(if (mine) "turn-user" else "turn-analyst"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

/** The answer's light formatting: **bold**, `code`, headings and bullet lines; everything else as written. */
@Composable
private fun Rich(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (raw in text.trim().lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.width(1.dp))
                line.startsWith("#") -> Text(inline(line.trimStart('#', ' ')), style = Type.Subhead)
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                    Text(inline("•  " + line.trimStart().drop(2)), style = Type.Body.copy(color = Ink.Strong), modifier = Modifier.padding(start = 4.dp))
                else -> Text(inline(line), style = Type.Body.copy(color = Ink.Strong))
            }
        }
    }
}

private fun plain(s: String) = s.replace("**", "").replace("`", "").trimStart('#', ' ')

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end < 0) { append(s.substring(i)); return@buildAnnotatedString }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Ink.Text)) { append(s.substring(i + 2, end)) }
                i = end + 2
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end < 0) { append(s.substring(i)); return@buildAnnotatedString }
                withStyle(SpanStyle(fontFamily = Type.Number.fontFamily, color = Ink.Text)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            else -> {
                append(s[i])
                i++
            }
        }
    }
}

/** Every recent run: what went with it, which tools ran with what, what came back, and what it cost. */
@Composable
fun RunsScreen(runs: List<RunLog>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize().testTag("runs"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ScreenTitle("What was sent")
                Text("Each run sent your question, your recent messages and what its tools returned, to Anthropic's API and nowhere else. Web searches run on Anthropic's servers.", style = Type.Small)
            }
        }
        if (runs.isEmpty()) item(key = "none") { Text("Nothing yet.", style = Type.Body.copy(color = Ink.Muted)) }
        items(runs, key = { it.id }) { RunPanel(it) }
    }
}

@Composable
private fun RunPanel(r: RunLog) {
    var open by rememberSaveable(r.id) { mutableStateOf(false) }
    Panel(Modifier.testTag("run"), onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${r.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · ${Words.utc(r.startedAt)}", style = Type.Subhead, modifier = Modifier.weight(1f))
            Text(Words.usd(r.costUsd), style = Type.Number)
        }
        Text(
            "${r.requests} ${if (r.requests == 1) "request" else "requests"} · ${r.usage.input + r.usage.cacheRead + r.usage.cacheWrite} tokens in, ${r.usage.output} out" +
                (if (r.usage.searches > 0) " · ${r.usage.searches} searches" else "") + " · ${r.ending}",
            style = Type.Caption,
        )
        Text(r.prompt, style = Type.Body.copy(color = Ink.Body), maxLines = if (open) Int.MAX_VALUE else 2)
        if (open) {
            Text("With ${r.historyTurns} earlier messages of the conversation.", style = Type.Caption)
            for (s in r.steps) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ink.Raised).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text((if (s.kind == "tool") "Tool " else "Server ") + s.name + if (s.isError) " · error" else "", style = Type.Small.copy(color = if (s.isError) Ink.Loss else Ink.Text))
                    Text("in: " + s.input, style = Type.Caption.copy(fontFamily = Type.Number.fontFamily), maxLines = 6)
                    Text("out: " + s.output, style = Type.Caption.copy(fontFamily = Type.Number.fontFamily), maxLines = 12)
                }
            }
        }
    }
}
