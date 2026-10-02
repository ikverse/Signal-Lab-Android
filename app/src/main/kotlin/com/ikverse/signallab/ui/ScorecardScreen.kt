package com.ikverse.signallab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The colour of a verdict: green for an edge, red for a loser, quiet for everything that is not yet known. */
fun verdictColor(verdict: String): Color = when (verdict) {
    "Edge" -> Palette.Up
    "Losing" -> Palette.Down
    else -> Palette.Muted
}

/** Rows that match the chosen chart (or all), the ones with trades first, then by how many they have. */
fun scorecardRows(rows: List<ScoreRowUi>, chart: String?): List<ScoreRowUi> =
    rows.filter { chart == null || it.timeframe == chart }
        .sortedWith(compareByDescending<ScoreRowUi> { it.closed }.thenByDescending { it.open }.thenBy { it.label }.thenBy { it.timeframe })

/**
 * The scorecard: each pattern on each chart, judged against random entries after costs. A verdict needs many trades and a result
 * that random entries rarely match, and the more patterns are tested the higher that bar, so most rows will say "No verdict" for a long time.
 */
@Composable
fun ScorecardScreen(model: ScorecardModel, onOpenLearn: (String) -> Unit, wide: Boolean, modifier: Modifier = Modifier) {
    val card by model.scorecard.collectAsStateWithLifecycle()
    var chart by rememberSaveable { mutableStateOf<String?>(null) }
    val charts = card.rows.map { it.timeframe }.distinct().sortedBy { listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d").indexOf(it) }
    val rows = scorecardRows(card.rows, chart)
    Column(modifier.fillMaxSize().testTag("scorecard")) {
        ScreenTitle("Scorecard")
        Text(
            "Does a pattern beat random entries, after costs? Each row compares a pattern's pretend trades with trades entered at random times on the same coin, held the same way.",
            style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (charts.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            ChoiceText("All", chart == null, { chart = null })
            for (c in charts) ChoiceText(c, chart == c, { chart = c })
        }
        HRule()
        if (wide) HeaderRow()
        when {
            card.rows.isEmpty() -> EmptyState("Nothing scored yet", "A pattern appears here after the first scan, and gets a verdict once enough of its trades have closed.")
            rows.isEmpty() -> EmptyState("No patterns on this chart", "Choose another chart.")
            else -> LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.variant }) { r ->
                    if (wide) WideRow(r, onOpenLearn) else NarrowRow(r, onOpenLearn)
                    HRule()
                }
                item {
                    Text(
                        "${card.patternsTested} patterns have been tested so far. Test enough patterns and a few will look good by pure luck, " +
                            "so the bar for an “Edge” verdict rises with every pattern added. This is research, not advice.",
                        style = Type.Small, modifier = Modifier.padding(16.dp).testTag("multiple-tests-note"),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("PATTERN", style = Type.Label, modifier = Modifier.weight(3f))
        Text("CHART", style = Type.Label, modifier = Modifier.weight(1f))
        Text("TRADES", style = Type.Label, modifier = Modifier.weight(1f))
        Text("WIN RATE", style = Type.Label, modifier = Modifier.weight(1.2f))
        Text("AVERAGE", style = Type.Label, modifier = Modifier.weight(1.2f))
        Text("VS RANDOM", style = Type.Label, modifier = Modifier.weight(1.3f))
        Text("VERDICT", style = Type.Label, modifier = Modifier.weight(2.2f))
    }
    HRule()
}

@Composable
private fun WideRow(r: ScoreRowUi, onOpenLearn: (String) -> Unit) {
    TouchRow({ onOpenLearn(r.variant) }, modifier = Modifier.testTag("score-${r.variant}")) {
        Text(r.label, style = Type.BodyStrong, modifier = Modifier.weight(3f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.timeframe, style = Type.Number, modifier = Modifier.weight(1f))
        Text("${r.closed}" + if (r.open > 0) " (+${r.open})" else "", style = Type.Number, modifier = Modifier.weight(1f))
        Text(Fmt.percent(r.hitRate, 0), style = Type.Number, modifier = Modifier.weight(1.2f))
        Text(Fmt.signedPercent(r.meanNet), style = Type.Number.copy(color = Fmt.changeColor(r.meanNet)), modifier = Modifier.weight(1.2f))
        Text(Fmt.signedPercent(r.excess), style = Type.Number.copy(color = Fmt.changeColor(r.excess)), modifier = Modifier.weight(1.3f))
        Column(Modifier.weight(2.2f)) {
            Text(r.verdict, style = Type.BodyStrong.copy(color = verdictColor(r.verdict)))
            if (r.firmness != r.verdict) Text(r.firmness, style = Type.Small)
        }
    }
}

@Composable
private fun NarrowRow(r: ScoreRowUi, onOpenLearn: (String) -> Unit) {
    TouchRow({ onOpenLearn(r.variant) }, modifier = Modifier.testTag("score-${r.variant}")) {
        Column(Modifier.weight(1f)) {
            Text("${r.label} · ${r.timeframe}", style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${r.closed} closed" + (if (r.open > 0) ", ${r.open} open" else "") + " · win ${Fmt.percent(r.hitRate, 0)} · average ${Fmt.signedPercent(r.meanNet)} · vs random ${Fmt.signedPercent(r.excess)}",
                style = Type.Small,
            )
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text(r.verdict, style = Type.BodyStrong.copy(color = verdictColor(r.verdict)))
            if (r.firmness != r.verdict) Text(r.firmness, style = Type.Small)
        }
    }
}
