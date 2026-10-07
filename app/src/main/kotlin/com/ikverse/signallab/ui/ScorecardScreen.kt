package com.ikverse.signallab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs

/** The colour of a verdict: green for an edge, red for a loser, quiet for everything that is not yet known. */
fun verdictColor(verdict: String): Color = when (verdict) {
    "Edge" -> Palette.Up
    "Losing" -> Palette.Down
    else -> Palette.Muted
}

/** How firm a verdict is, as a count of filled dots out of three: early read 1, provisional 2, meaningful 3, none yet 0. */
fun firmnessDots(firmness: String): Int = when (firmness) {
    "Early read" -> 1
    "Provisional" -> 2
    "Meaningful" -> 3
    else -> 0
}

/** Rows that match the chosen chart (or all), the ones with trades first, then by how many they have. */
fun scorecardRows(rows: List<ScoreRowUi>, chart: String?): List<ScoreRowUi> =
    rows.filter { chart == null || it.timeframe == chart }
        .sortedWith(compareByDescending<ScoreRowUi> { it.closed }.thenByDescending { it.open }.thenBy { it.label }.thenBy { it.timeframe })

/**
 * The scorecard: each pattern on each chart, judged against random entries after costs. A verdict needs many trades and a result
 * that random entries rarely match, and the more patterns are tested the higher that bar, so most rows will say "No verdict" for a long time.
 * [onOpenPage] opens a Learn page: the info button opens how the scorecard judges.
 */
@Composable
fun ScorecardScreen(model: ScorecardModel, onOpenLearn: (String) -> Unit, wide: Boolean, modifier: Modifier = Modifier, onOpenPage: (String) -> Unit = {}) {
    val card by model.scorecard.collectAsStateWithLifecycle()
    var chart by rememberSaveable { mutableStateOf<String?>(null) }
    val charts = card.rows.map { it.timeframe }.distinct().sortedBy { listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d").indexOf(it) }
    val rows = scorecardRows(card.rows, chart)
    // One scale for every bar on screen, so the bars compare with each other.
    val scale = rows.mapNotNull { r -> r.excess?.let { abs(it) } }.maxOrNull()?.takeIf { it > 0 }
    Column(modifier.fillMaxSize().testTag("scorecard")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Scorecard", Modifier.weight(1f))
            IconAction(Glyphs.Info, "How the scorecard judges", { onOpenPage("scorecard") }, Modifier.padding(end = 4.dp), tint = Palette.Muted)
        }
        Text("Does each pattern beat random entries, after costs?", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp))
        if (charts.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 2.dp)) {
            ChoiceText("All", chart == null, { chart = null })
            for (c in charts) ChoiceText(c, chart == c, { chart = c })
        }
        if (wide) HeaderRow() else NarrowCaptions()
        when {
            card.rows.isEmpty() -> EmptyState("Nothing scored yet", "A pattern appears here after the first scan, and gets a verdict once enough of its trades have closed.")
            rows.isEmpty() -> EmptyState("No patterns on this chart", "Choose another chart.")
            else -> LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.variant }) { r ->
                    if (wide) WideRow(r, scale, onOpenLearn) else NarrowRow(r, scale, onOpenLearn)
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

/** The four figures of a narrow row, in the same columns as the captions over the list. */
private val NARROW_COLUMNS = listOf(0.9f, 0.8f, 1f, 1.45f)

@Composable
private fun NarrowCaptions() {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        for ((i, name) in listOf("Trades", "Win", "Avg", "vs random").withIndex()) Text(name, style = Type.Label, modifier = Modifier.weight(NARROW_COLUMNS[i]))
    }
    HRule()
}

@Composable
private fun HeaderRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Pattern", style = Type.Label, modifier = Modifier.weight(2.6f))
        Text("Chart", style = Type.Label, modifier = Modifier.weight(0.8f))
        Text("Trades", style = Type.Label, modifier = Modifier.weight(1f))
        Text("Win", style = Type.Label, modifier = Modifier.weight(0.8f))
        Text("Avg", style = Type.Label, modifier = Modifier.weight(1f))
        Text("vs random", style = Type.Label, modifier = Modifier.weight(1.5f))
        Text("Verdict", style = Type.Label, modifier = Modifier.weight(1.8f))
    }
    HRule()
}

@Composable
private fun WideRow(r: ScoreRowUi, scale: Double?, onOpenLearn: (String) -> Unit) {
    TouchRow({ onOpenLearn(r.variant) }, modifier = Modifier.testTag("score-${r.variant}"), minHeight = 52.dp) {
        Text(r.short, style = Type.BodyStrong, modifier = Modifier.weight(2.6f).padding(end = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(0.8f)) { ChartTag(r.timeframe) }
        TradeCount(r, Modifier.weight(1f))
        Text(Fmt.percent(r.hitRate, 0), style = Type.Number, modifier = Modifier.weight(0.8f))
        Text(Fmt.signedPercent(r.meanNet), style = Type.Number.copy(color = Fmt.changeColor(r.meanNet)), modifier = Modifier.weight(1f))
        VsRandom(r.excess, scale, Modifier.weight(1.5f))
        Verdict(r, Modifier.weight(1.8f))
    }
}

@Composable
private fun NarrowRow(r: ScoreRowUi, scale: Double?, onOpenLearn: (String) -> Unit) {
    TouchRow({ onOpenLearn(r.variant) }, modifier = Modifier.testTag("score-${r.variant}")) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.short, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                ChartTag(r.timeframe)
                Spacer(Modifier.weight(1f))
                Verdict(r)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TradeCount(r, Modifier.weight(NARROW_COLUMNS[0]))
                Text(Fmt.percent(r.hitRate, 0), style = Type.Number, modifier = Modifier.weight(NARROW_COLUMNS[1]))
                Text(Fmt.signedPercent(r.meanNet), style = Type.Number.copy(color = Fmt.changeColor(r.meanNet)), modifier = Modifier.weight(NARROW_COLUMNS[2]))
                VsRandom(r.excess, scale, Modifier.weight(NARROW_COLUMNS[3]))
            }
        }
    }
}

/** Trades closed, with the open ones after them as "+3". */
@Composable
private fun TradeCount(r: ScoreRowUi, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("${r.closed}", style = Type.Number)
        if (r.open > 0) Text(" +${r.open}", style = Type.Small.copy(fontFeatureSettings = "tnum"))
    }
}

/** The gap to random entries: a small bar either side of a centre line (left and red when worse), then the figure. */
@Composable
private fun VsRandom(excess: Double?, scale: Double?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(36.dp, 10.dp)) {
            val mid = size.width / 2
            drawRect(Palette.Faint, Offset(mid - 0.5f, 0f), Size(1f, size.height))
            if (excess != null && scale != null && excess != 0.0) {
                val w = (mid * (abs(excess) / scale)).toFloat().coerceAtLeast(1.5f)
                val color = if (excess > 0) Palette.Up else Palette.Down
                val left = if (excess > 0) mid else mid - w
                drawRect(color, Offset(left, 2.dp.toPx()), Size(w, size.height - 4.dp.toPx()))
            }
        }
        Text(Fmt.signedPercent(excess), style = Type.Number.copy(color = Fmt.changeColor(excess)), modifier = Modifier.padding(start = 6.dp))
    }
}

/** The verdict in its colour, and three dots for how firm it is. */
@Composable
private fun Verdict(r: ScoreRowUi, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(r.verdict, style = Type.Small.copy(color = if (verdictColor(r.verdict) == Palette.Muted) Palette.Text else verdictColor(r.verdict)), maxLines = 1)
        val filled = firmnessDots(r.firmness)
        Canvas(Modifier.padding(start = 6.dp).size(24.dp, 6.dp).semantics { contentDescription = r.firmness }) {
            for (i in 0 until 3) {
                drawCircle(if (i < filled) Palette.Text else Palette.ChipEdge, 3.dp.toPx(), Offset(3.dp.toPx() + i * 9.dp.toPx(), size.height / 2))
            }
        }
    }
}
