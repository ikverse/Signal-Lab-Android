package com.ikverse.signallab.view.strategies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.model.EvidenceUi
import com.ikverse.signallab.model.StrategiesUi
import com.ikverse.signallab.model.StrategyRowUi
import com.ikverse.signallab.view.common.Figure
import com.ikverse.signallab.view.common.Num
import com.ikverse.signallab.view.common.Overline
import com.ikverse.signallab.view.common.Panel
import com.ikverse.signallab.view.common.RowsPanel
import com.ikverse.signallab.view.common.ScreenTitle
import com.ikverse.signallab.view.common.Section
import com.ikverse.signallab.view.common.Signed
import com.ikverse.signallab.view.common.TierPill
import com.ikverse.signallab.view.common.Words
import com.ikverse.signallab.model.CustomRowUi
import com.ikverse.signallab.view.common.SwitchRow
import com.ikverse.signallab.view.theme.Ink
import com.ikverse.signallab.view.theme.Type

/** Every strategy, its tier, and whether it puts its calls on Today. */
@Composable
fun StrategiesScreen(
    state: StrategiesUi,
    onOpen: (String) -> Unit,
    onSwitch: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onRun: (String, Boolean) -> Unit = { _, _ -> },
) {
    if (!state.loaded) {
        Box(modifier.fillMaxSize().testTag("strategies-loading"))
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("strategies"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Strategies") }
        item {
            Text(
                "Each one is a fixed rule with its evidence. Switched on, it puts its calls on Today and notifies you; switched off, it still runs quietly into the ledger, so its record keeps growing.",
                style = Type.Small,
            )
        }
        item { RowsPanel(rows = state.rows.map { row -> @Composable { StrategyRow(row, onOpen, onSwitch) } }) }
        item { Section("Your own", state.custom.size.takeIf { it > 0 }) }
        if (state.custom.isEmpty()) {
            item {
                Panel {
                    Text("None yet.", style = Type.Body)
                    Text("Ask the analyst to look for a strategy worth testing: it writes the rule, backtests it, and proposes it here. Nothing runs until you start it.", style = Type.Small)
                }
            }
        } else {
            item { RowsPanel(rows = state.custom.map { c -> @Composable { CustomRow(c, onOpen, onRun) } }) }
        }
        val legacy = state.customs - state.custom.size
        if (legacy > 0) item { Text("${Words.count(legacy, "lab pattern")} from Signal Lab 1, kept as history.", style = Type.Caption) }
        item {
            Text("Strategies tried so far: ${state.tried}, Signal Lab 1's included. Every new try raises the bar for the others a little.",
                style = Type.Caption.copy(color = Ink.Faint))
        }
    }
}

@Composable
private fun StrategyRow(row: StrategyRowUi, onOpen: (String) -> Unit, onSwitch: (String, Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("strategy-${row.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(role = Role.Button) { onOpen(row.id) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.name, style = Type.Subhead)
                TierPill(row.tier)
            }
            Text(row.summary, style = Type.Small)
        }
        Switch(
            row.on, { onSwitch(row.id, it) },
            modifier = Modifier.semantics { contentDescription = "${row.name} on Today" }.testTag("switch-${row.id}"),
            colors = SwitchDefaults.colors(checkedThumbColor = Ink.OnAccent, checkedTrackColor = Ink.Accent, uncheckedThumbColor = Ink.Faint, uncheckedTrackColor = Ink.Raised, uncheckedBorderColor = Ink.Outline),
        )
    }
}

@Composable
private fun CustomRow(c: CustomRowUi, onOpen: (String) -> Unit, onRun: (String, Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("custom-${c.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(role = Role.Button) { onOpen(c.id) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.name, style = Type.Subhead)
                TierPill(c.tier)
            }
            Text(if (c.byAnalyst) "Proposed by the analyst" + if (c.running) " · running" else " · not started" else if (c.running) "Running" else "Not started",
                style = Type.Caption.copy(color = if (c.running) Ink.Gain else Ink.Muted))
            c.thesis?.let { Text(it, style = Type.Small, maxLines = 2) }
        }
        Switch(
            c.running, { onRun(c.id, it) },
            modifier = Modifier.semantics { contentDescription = "${c.name} running" }.testTag("run-${c.id}"),
            colors = SwitchDefaults.colors(checkedThumbColor = Ink.OnAccent, checkedTrackColor = Ink.Accent, uncheckedThumbColor = Ink.Faint, uncheckedTrackColor = Ink.Raised, uncheckedBorderColor = Ink.Outline),
        )
    }
}

/** One strategy's evidence: the research, its history on your coins, and its live record, side by side. */
@Composable
fun EvidenceScreen(e: EvidenceUi?, modifier: Modifier = Modifier, onRun: (Boolean) -> Unit = {}) {
    if (e == null) {
        Box(modifier.fillMaxSize().testTag("evidence-loading"))
        return
    }
    val uri = LocalUriHandler.current
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp).testTag("evidence"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(e.name, style = Type.Display, modifier = Modifier.weight(1f))
                TierPill(e.tier)
            }
            Text(e.summary, style = Type.Small)
        }
        Panel(Modifier.testTag("verdict")) {
            Text(e.verdict, style = Type.Body.copy(color = Ink.Text))
            e.tierReason?.let { Text("${e.tier.label}: $it", style = Type.Small, modifier = Modifier.testTag("tier-reason")) }
            e.running?.let { running ->
                SwitchRow("Running", if (running) "It makes calls into the ledger at each daily close." else "Start it to collect live calls. Only live calls can give it a verdict.", running, onRun)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Research", style = Type.Caption)
                    Text(e.research, style = Type.Small.copy(color = Ink.Gain))
                }
                Figure("Your coins", e.backtest?.let { Words.pct(it.meanExcess) } ?: if (e.computing) "…" else "–", Modifier.weight(1f),
                    valueColor = e.backtest?.let { if (it.meanExcess > 0) Ink.Gain else Ink.Loss } ?: Ink.Muted,
                    note = e.backtest?.let { "per call vs random, ${it.calls} calls" })
                Figure("Live", e.live.meanExcess?.let { Words.pct(it) } ?: Words.count(e.live.closed, "call"), Modifier.weight(1f),
                    note = if (e.live.closed < 100) "${e.live.closed} finished" else "vs random")
            }
        }
        e.backtest?.let { b ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tested on your coins", style = Type.Heading)
                Text("${b.coins} coins, daily, from ${Words.day(b.from)}, at your fees.", style = Type.Small)
                RowsPanel(rows = listOf(
                    @Composable { Fact("Calls", { Num("${b.calls}") }) },
                    @Composable { Fact("Ahead of random, per call", { Signed(b.meanExcess) }) },
                    @Composable { Fact("Likely range (95%)", { Num(b.ci?.let { "${Words.pct(it.first)} to ${Words.pct(it.second)}" } ?: "–", size = 14) }) },
                    @Composable { Fact("At double the fees", { Signed(b.excessAtDoubleCost) }) },
                    @Composable { Fact("When Bitcoin was rising", { Signed(b.bull) }) },
                    @Composable { Fact("When Bitcoin was falling", { Signed(b.bear) }) },
                    @Composable { Fact("Calls that ended up", { Num(Words.share(b.hit)) }) },
                    @Composable { Fact("Average hold", { Num("${Math.round(b.avgHoldDays)} days") }) },
                ))
                Text("A wide range means the past alone cannot tell yet. Live calls narrow it.", style = Type.Small)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Why it should work", style = Type.Heading)
            Text(e.rationale, style = Type.Body)
        }
        if (e.sources.isNotEmpty()) {
            Panel {
                Overline("Sources")
                for (s in e.sources) {
                    Text(s.title, style = Type.Body.copy(color = Ink.Accent), modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { uri.openUri(s.url) }.padding(vertical = 6.dp))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Overline("The exact rule")
            for (line in e.rule) Num(line, color = Ink.Muted, size = 13)
        }
    }
}

@Composable
private fun Fact(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Body.copy(color = Ink.Body), modifier = Modifier.weight(1f))
        value()
    }
}
