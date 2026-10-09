package com.ikverse.signallab.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** The patterns in the order a reader wants them: working first, then still being judged (most trades first), then the rest. */
fun worksOrder(rows: List<ScoreRowUi>): List<ScoreRowUi> =
    rows.sortedWith(compareBy<ScoreRowUi> { PlainWords.verdictRank(it.verdict) }.thenByDescending { it.closed }.thenBy { it.short }.thenBy { it.timeframe })

/** The colour of the plain verdict word: green when working, red when not, quiet for no better than guessing, warm while still being judged. */
private fun wordColor(verdict: String): Color = when (verdict) {
    "Edge" -> Palette.Up
    "Losing" -> Palette.Down
    "No edge" -> Palette.Muted
    else -> Palette.Warn
}

/**
 * Does it work?: the answer first, in words (how the practice trades did in all, and for each pattern whether it beats random entries),
 * with the full numbers one touch away. A phone shows one column; a wider screen puts the answers beside the curve and the numbers,
 * and the answers can be dragged narrower or hidden. [onOpenPage] opens a Learn page: the info button opens how the scorecard judges.
 */
@Composable
fun ScorecardScreen(
    model: ScorecardModel,
    trades: TradesModel,
    panels: PanelPrefs,
    onOpenLearn: (String) -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onOpenPage: (String) -> Unit = {},
    onOpenTrades: () -> Unit = {},
    onAsk: () -> Unit = {},
) {
    val card by model.scorecard.collectAsStateWithLifecycle()
    val all by trades.trades.collectAsStateWithLifecycle()
    var chart by rememberSaveable { mutableStateOf<String?>(null) }
    var numbers by rememberSaveable { mutableStateOf(false) }
    val account = PlainWords.account(all)
    val charts = card.rows.map { it.timeframe }.distinct().sortedBy { listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d").indexOf(it) }
    val rows = scorecardRows(card.rows, chart)
    // One scale for every bar on screen, so the bars compare with each other.
    val scale = rows.mapNotNull { r -> r.excess?.let { abs(it) } }.maxOrNull()?.takeIf { it > 0 }
    val answers = worksOrder(card.rows)

    val answerList = @Composable {
        LazyColumn(Modifier.fillMaxSize().testTag("scorecard")) {
            item(key = "title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScreenTitle("Does it work?", Modifier.weight(1f))
                    IconAction(Glyphs.Info, "How we tell if it is working", { onOpenPage("scorecard") }, Modifier.padding(end = 4.dp), tint = Palette.Muted)
                }
            }
            item(key = "hero") { Hero(account, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            if (card.rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState("Nothing scored yet", "A pattern appears here after the first scan, and gets a verdict once enough of its trades have finished.")
                }
            } else {
                item(key = "head") { SectionLabel("Which patterns work", count = answers.size) }
                items(answers, key = { it.variant }) { r -> PatternAnswer(r, onOpenLearn, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            }
            item(key = "note") { TestingNote(card.patternsTested) }
            if (!wide) {
                item(key = "numbers") {
                    TextAction(if (numbers) "Hide the numbers" else "Show the numbers", { numbers = !numbers }, Modifier.testTag("show-numbers"))
                }
                if (numbers) {
                    item(key = "chips") { ChartChips(charts, chart) { chart = it } }
                    item(key = "captions") { NarrowCaptions() }
                    items(rows, key = { "n-" + it.variant }) { r ->
                        NarrowRow(r, scale, onOpenLearn)
                        HRule()
                    }
                }
                item(key = "actions") { Actions(onOpenTrades, onAsk) }
            }
        }
    }
    if (!wide) {
        Box(modifier.fillMaxSize()) { answerList() }
        return
    }
    SplitPane(
        panels, "works", "results", 400f,
        list = answerList,
        page = {
            LazyColumn(Modifier.fillMaxSize().testTag("scorecard-numbers")) {
                item(key = "curve") { Curve(account, Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) }
                item(key = "head") { SectionLabel("The numbers") }
                item(key = "chips") { ChartChips(charts, chart) { chart = it } }
                item(key = "header") { HeaderRow() }
                items(rows, key = { "w-" + it.variant }) { r ->
                    WideRow(r, scale, onOpenLearn)
                    HRule()
                }
                item(key = "actions") { Actions(onOpenTrades, onAsk) }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun ChartChips(charts: List<String>, chart: String?, onChoose: (String?) -> Unit) {
    if (charts.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 2.dp)) {
        ChoiceText("All", chart == null, { onChoose(null) })
        for (c in charts) ChoiceText(c, chart == c, { onChoose(c) })
    }
}

/** The one-line answer: how the practice trades added up, after fees. */
@Composable
private fun Hero(account: PlainWords.Account?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Raised).padding(16.dp).testTag("works-hero"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (account == null) {
            Text("No finished practice trades yet", style = Type.Heading)
            Text("Results appear here once setups play out.", style = Type.Small)
            return@Column
        }
        Text(
            Fmt.signedPercent(account.total, 1), style = Type.Big.copy(fontSize = 38.sp, color = Fmt.changeColor(account.total)),
            modifier = Modifier.testTag("works-total"),
        )
        Text("All finished practice trades added up, after fees.", style = Type.Small, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Won ${account.wins} of ${account.n} · average ${Fmt.signedPercent(account.average, 2)} per trade",
            style = Type.Body, textAlign = TextAlign.Center, modifier = Modifier.testTag("works-record"),
        )
        if (account.n < PlainWords.MIN_VERDICT) {
            Spacer(Modifier.height(4.dp))
            Text("Only ${account.n} so far. That is too few to read much into.", style = Type.Small.copy(color = Palette.Warn), textAlign = TextAlign.Center)
        }
    }
}

/** One pattern's answer: its name, the verdict in a word, and the reason in a sentence. Touching it explains the pattern. */
@Composable
private fun PatternAnswer(r: ScoreRowUi, onOpenLearn: (String) -> Unit, modifier: Modifier = Modifier) {
    val color = wordColor(r.verdict)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Raised).clickable { onOpenLearn(r.variant) }.padding(14.dp)
            .testTag("score-${r.variant}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.short, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            ChartTag(r.timeframe)
            Spacer(Modifier.weight(1f))
            Text(
                PlainWords.verdictWord(r.verdict), style = Type.Label.copy(color = color), maxLines = 1,
                modifier = Modifier.border(1.dp, color, RoundedCornerShape(12.dp)).padding(horizontal = 9.dp, vertical = 2.dp),
            )
        }
        Text(PlainWords.verdictReason(r.verdict, r.closed), style = Type.Small)
    }
}

/** Why a pattern needs a lot of trades before it is called working. */
@Composable
private fun TestingNote(tested: Int) {
    Text(
        "$tested patterns have been tested so far. Test enough patterns and a few will look good by pure luck, " +
            "so the bar for “Working” rises with every pattern added. This is research, not advice.",
        style = Type.Small, modifier = Modifier.padding(16.dp).testTag("multiple-tests-note"),
    )
}

/** The buttons under the answers: every trade, and Claude. */
@Composable
private fun Actions(onOpenTrades: () -> Unit, onAsk: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LineButton("See all trades", onOpenTrades, Modifier.fillMaxWidth().testTag("see-all-trades"))
        TonalButton("Ask Claude what this means", onAsk, Modifier.fillMaxWidth().testTag("ask-claude"))
    }
}

/** The practice account over time: the running total of finished trades, with the starting line. */
@Composable
private fun Curve(account: PlainWords.Account?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Raised).padding(14.dp).testTag("works-curve")) {
        Text("Practice account over time", style = Type.BodyStrong)
        if (account == null || account.curve.size < 2) {
            Text("The curve appears after a few trades have finished.", style = Type.Small, modifier = Modifier.padding(top = 6.dp))
            return@Column
        }
        val curve = account.curve
        val lo = minOf(0.0, curve.min())
        val hi = maxOf(0.0, curve.max())
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val color = Fmt.changeColor(account.total)
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp)) {
            val pad = 6.dp.toPx()
            fun y(v: Double) = pad + (size.height - 2 * pad) * (1 - ((v - lo) / span)).toFloat()
            drawLine(Palette.Faint, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
            val path = Path()
            curve.forEachIndexed { i, v ->
                val x = size.width * i / (curve.size - 1)
                if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
            }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, 3.5.dp.toPx(), Offset(size.width, y(curve.last())))
        }
        Text("Each finished trade adds its result. The grey line is where you started.", style = Type.Small)
    }
}

/** The four figures of a narrow row, in the same columns as the captions over the list. */
private val NARROW_COLUMNS = listOf(0.9f, 0.8f, 1f, 1.45f)

@Composable
private fun NarrowCaptions() {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        for ((i, name) in listOf("Trades", "Won", "Average", "vs guessing").withIndex()) Text(name, style = Type.Label, modifier = Modifier.weight(NARROW_COLUMNS[i]))
    }
    HRule()
}

@Composable
private fun HeaderRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Pattern", style = Type.Label, modifier = Modifier.weight(2.6f))
        Text("Chart", style = Type.Label, modifier = Modifier.weight(0.8f))
        Text("Trades", style = Type.Label, modifier = Modifier.weight(1f))
        Text("Won", style = Type.Label, modifier = Modifier.weight(0.8f))
        Text("Average", style = Type.Label, modifier = Modifier.weight(1f))
        Text("vs guessing", style = Type.Label, modifier = Modifier.weight(1.5f))
        Text("Verdict", style = Type.Label, modifier = Modifier.weight(1.8f))
    }
    HRule()
}

@Composable
private fun WideRow(r: ScoreRowUi, scale: Double?, onOpenLearn: (String) -> Unit) {
    TouchRow({ onOpenLearn(r.variant) }, modifier = Modifier.testTag("numbers-${r.variant}"), minHeight = 52.dp) {
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
    TouchRow({ onOpenLearn(r.variant) }, modifier = Modifier.testTag("numbers-${r.variant}")) {
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

/** The scorecard's own verdict in its colour, and three dots for how firm it is. Only the numbers show it; the answers use plain words. */
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
