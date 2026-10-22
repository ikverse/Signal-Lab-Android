package com.ikverse.signallab.view.ledger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.model.LedgerUi
import com.ikverse.signallab.model.Period
import com.ikverse.signallab.model.Point
import com.ikverse.signallab.model.StrategyLine
import com.ikverse.signallab.view.common.Empty
import com.ikverse.signallab.view.common.Num
import com.ikverse.signallab.view.common.Overline
import com.ikverse.signallab.view.common.Panel
import com.ikverse.signallab.view.common.Pill
import com.ikverse.signallab.view.common.QuietButton
import com.ikverse.signallab.view.common.RowsPanel
import com.ikverse.signallab.view.common.ScreenTitle
import com.ikverse.signallab.view.common.Section
import com.ikverse.signallab.view.common.Signed
import com.ikverse.signallab.view.common.TapRow
import com.ikverse.signallab.view.common.Words
import com.ikverse.signallab.view.theme.Ink
import com.ikverse.signallab.view.theme.Type

/** The paper account and how each strategy has done live. */
@Composable
fun LedgerScreen(
    state: LedgerUi,
    onPeriod: (Period) -> Unit,
    onStrategy: (String) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    if (!state.loaded) {
        Box(modifier.fillMaxSize().testTag("ledger-loading"))
        return
    }
    LazyColumn(
        modifier.fillMaxSize().testTag("ledger"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Ledger") }
        item { AccountPanel(state, onPeriod, wide) }
        if (state.strategies.isEmpty()) {
            item { Empty("No live calls yet", "Each strategy's live record builds up here from its first call. Until then its evidence is the research and its history on your coins, under Strategies.") }
        } else {
            item { Section("By strategy") { Text("live calls · after fees", style = Type.Caption) } }
            item { RowsPanel(rows = state.strategies.map { line -> @Composable { StrategyLineRow(line, onStrategy) } }) }
            if (state.copies.isNotEmpty()) {
                item { CopiesPanel(state) }
            }
        }
        if (state.coins.isNotEmpty()) {
            item { Section("By coin") }
            item {
                RowsPanel(rows = state.coins.take(12).map { c ->
                    @Composable {
                        TapRow(null) {
                            Text(Words.base(c.symbol), style = Type.Subhead)
                            Text("${Words.count(c.closed, "call")} · ${c.up} up", style = Type.Caption, modifier = Modifier.weight(1f))
                            Num(Words.usd(c.netUsd, signed = true), color = if (c.netUsd >= 0) Ink.Gain else Ink.Loss, size = 14)
                        }
                    }
                })
            }
        }
        if (state.imported > 0) {
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Imported from Signal Lab 1", style = Type.Subhead, modifier = Modifier.weight(1f))
                        Num(Words.count(state.imported, "trade"), color = Ink.Muted, size = 14)
                    }
                    Text("Kept as history. Their old patterns are retired, but they still count as tries, so new strategies are judged fairly.", style = Type.Small)
                }
            }
        }
        item { QuietButton("Save as CSV", onExport, Modifier.fillMaxWidth().testTag("export-csv")) }
    }
}

@Composable
private fun AccountPanel(s: LedgerUi, onPeriod: (Period) -> Unit, wide: Boolean) {
    Panel(Modifier.testTag("account")) {
        Text(s.since?.let { "Paper account since ${Words.day(it)}" } ?: "Paper account", style = Type.Small)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(Words.usd(s.value), style = Type.BigNumber)
            Signed(s.change, size = 16, bold = true)
        }
        val beat = (s.change - s.randomChange) * 100
        Text(
            "The same exits on random days would have made ${Words.pct(s.randomChange)}. " +
                if (beat >= 0) "The calls are ahead of chance by ${String.format(java.util.Locale.US, "%.1f", beat)} points, after fees."
                else "The calls are behind chance by ${String.format(java.util.Locale.US, "%.1f", -beat)} points, after fees.",
            style = Type.Body.copy(color = Ink.Body),
        )
        Curve(s.curve, s.randomCurve, Modifier.fillMaxWidth().height(if (wide) 200.dp else 120.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(Ink.Gain, "Your calls", dashed = false)
            Legend(Ink.Faint, "Random entries", dashed = true)
            if (s.deepestDrop < 0) Text("deepest drop ${Words.pct(s.deepestDrop)}", style = Type.Caption)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in Period.entries) {
                FilterChip(
                    selected = p == s.period, onClick = { onPeriod(p) }, label = { Text(p.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Ink.Text, selectedLabelColor = Ink.Ground, labelColor = Ink.Body),
                )
            }
        }
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, text: String, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.width(14.dp).height(3.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f, 4f)) else null)
        }
        Text(text, style = Type.Caption)
    }
}

/** The account curve over the random one, on one scale. */
@Composable
private fun Curve(curve: List<Point>, random: List<Point>, modifier: Modifier) {
    val all = curve + random
    val desc = if (curve.size < 2) "No finished calls to draw yet." else "Account from ${Words.usd(curve.first().value)} to ${Words.usd(curve.last().value)}; random entries to ${Words.usd(random.last().value)}"
    Canvas(modifier.semantics { contentDescription = desc }) {
        if (curve.size < 2) return@Canvas
        val t0 = all.minOf { it.time }.toFloat()
        val t1 = all.maxOf { it.time }.toFloat().coerceAtLeast(t0 + 1)
        val lo = all.minOf { it.value }
        val hi = all.maxOf { it.value }.coerceAtLeast(lo + 1)
        fun path(points: List<Point>) = Path().apply {
            points.forEachIndexed { i, p ->
                val x = (p.time - t0) / (t1 - t0) * size.width
                val y = size.height - ((p.value - lo) / (hi - lo)).toFloat() * size.height
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path(random), Ink.Faint, style = Stroke(width = 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
        drawPath(path(curve), Ink.Gain, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun StrategyLineRow(line: StrategyLine, onStrategy: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onStrategy(line.id) }
            .padding(horizontal = 16.dp, vertical = 14.dp).testTag("line-${line.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line.name, style = Type.Subhead)
            if (line.testing) Pill("Off Today", Ink.Muted, line = Ink.Outline)
            Spacer(Modifier.weight(1f))
            Num(Words.usd(line.netUsd, signed = true), color = if (line.netUsd >= 0) Ink.Gain else Ink.Loss, size = 14)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Words.count(line.closed, "call"), style = Type.Caption)
            Text("${line.up} up", style = Type.Caption)
            if (line.open > 0) Text("${line.open} open", style = Type.Caption)
            line.meanExcess?.let { Text("${Words.pct(it)} vs random", style = Type.Caption) }
        }
        Text(line.standing, style = Type.Small.copy(color = Ink.Body))
    }
}

/** "My copies": the calls the owner took on Binance, their real result beside the paper one, and what the fills cost. */
@Composable
private fun CopiesPanel(s: LedgerUi) {
    Panel(Modifier.testTag("my-copies")) {
        Overline("My copies")
        val sold = s.copiesSold.size
        val summary = buildList {
            add("${Words.count(s.copies.size, "call")} copied on Binance, $sold sold.")
            s.copiesNet?.let { real -> add("Your average result ${Words.pct(real)} after fees" + (s.copiesPaperNet?.let { ", paper ${Words.pct(it)} on the same calls." } ?: ".")) }
            s.copiesEntryGap?.let { add("Your buys sat ${com.ikverse.signallab.model.CopyUi.gapWords(it)} the paper entries on average.") }
        }
        for (line in summary) Text(line, style = Type.Body.copy(color = Ink.Text))
        for (c in s.copies.take(12)) {
            Row(Modifier.fillMaxWidth().testTag("copy-${c.callId}"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${c.base} · ${c.strategyName}", style = Type.Small, modifier = Modifier.weight(1f))
                Text(c.net?.let { "you ${Words.pct(it)}" } ?: "open", style = Type.Small.copy(color = Ink.Text))
                Text(c.paperNet?.let { "paper ${Words.pct(it)}" } ?: "", style = Type.Small)
            }
        }
    }
}
