package com.ikverse.signallab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** The trades still open, newest first: each is a setup that has not played out yet. */
internal fun setupsOf(trades: List<TradeUi>): List<TradeUi> = trades.filter { it.closed == null }.sortedByDescending { it.openedAt }

/** The coin's short name from a symbol: "SOLUSDT" as "SOL". */
internal fun baseOf(symbol: String): String = symbol.removeSuffix("USDT")

/** The scorecard row that belongs to a trade's pattern on its chart, or null when the pattern has no row yet. */
internal fun rowOf(trade: TradeUi, rows: List<ScoreRowUi>): ScoreRowUi? = rows.firstOrNull { it.variant == trade.variant && it.timeframe == trade.timeframe }

/**
 * Today: the coins with a setup right now, one plain card each. A phone shows the cards in a list; a wider screen puts the list beside
 * the selected setup's chart and plan, and the list can be dragged narrower or hidden.
 */
@Composable
fun TodayScreen(
    markets: MarketsModel,
    trades: TradesModel,
    scorecard: ScorecardModel,
    panels: PanelPrefs,
    layout: LayoutClass,
    nav: NavState,
    onOpenLearn: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPage: (String) -> Unit = {},
) {
    val all by trades.trades.collectAsStateWithLifecycle()
    val card by scorecard.scorecard.collectAsStateWithLifecycle()
    val coins by markets.coins.collectAsStateWithLifecycle()
    val live by markets.prices.collectAsStateWithLifecycle()
    val setups = setupsOf(all)
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val symbols = setups.map { it.symbol }.toSet()
    DisposableEffect(symbols) {
        val handle = markets.watchPrices(symbols)
        onDispose { handle.close() }
    }
    val stored = coins.mapNotNull { c -> c.price?.let { c.symbol to it } }.toMap()
    val priceOf = { symbol: String -> live[symbol] ?: stored[symbol] }
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    val chosen = setups.firstOrNull { it.id == picked } ?: setups.firstOrNull()
    val openChart = { t: TradeUi -> nav.openCoin(t.symbol, t.timeframe, fromApp = true, trade = t.id) }

    if (setups.isEmpty()) {
        Column(modifier.fillMaxSize().testTag("today")) {
            ScreenTitle("Today")
            EmptyState(
                "Nothing worth looking at right now",
                "When a coin on your lists matches a pattern, it shows up here and you get a notification. Until then there is nothing to do.",
                Modifier.testTag("today-empty"),
            )
        }
        return
    }
    if (layout == LayoutClass.Compact) {
        LazyColumn(modifier.fillMaxSize().testTag("today")) {
            item(key = "title") {
                ScreenTitle("Today")
                Text(
                    "Coins with a setup right now, and the plan for each.",
                    style = Type.Small, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            items(setups, key = { it.id }) { t ->
                SetupCard(t, priceOf(t.symbol), rowOf(t, card.rows), now, onOpenLearn, onOpenPage, { openChart(t) }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            item(key = "end") { EndSpace() }
        }
        return
    }
    SplitPane(
        panels, "today", "setups", 340f,
        list = {
            LazyColumn(Modifier.fillMaxSize().testTag("today")) {
                item(key = "title") { ScreenTitle("Today") }
                items(setups, key = { it.id }) { t ->
                    SetupRow(t, t.id == chosen?.id, now) { picked = t.id }
                }
                item(key = "end") { EndSpace() }
            }
        },
        page = {
            if (chosen != null) SetupDetail(markets, chosen, priceOf(chosen.symbol), rowOf(chosen, card.rows), onOpenLearn, onOpenPage)
        },
        modifier = modifier,
    )
}

/** One setup in the list beside the detail: the coin, when, and the sentence. */
@Composable
private fun SetupRow(t: TradeUi, selected: Boolean, now: Long, onClick: () -> Unit) {
    TouchRow(onClick, selected = selected, modifier = Modifier.testTag("setup-${t.id}")) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(baseOf(t.symbol), style = Type.Heading, modifier = Modifier.weight(1f))
                Text(PlainWords.ago(t.openedAt, now), style = Type.Small)
            }
            Text(PlainWords.headline(baseOf(t.symbol), PlainWords.isLab(t.variant)), style = Type.Body)
            Text("${t.short} · ${Fmt.chartAdjective(t.timeframe)} chart", style = Type.Small)
        }
    }
    HRule()
}

/** The full card, as a phone shows it: the sentence, why, the three prices, how the pattern has done, and a button to the chart. */
@Composable
private fun SetupCard(
    t: TradeUi,
    price: Double?,
    row: ScoreRowUi?,
    now: Long,
    onOpenLearn: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    onChart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Raised).padding(14.dp).testTag("setup-${t.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The coin and its chart size: two setups on the same coin are told apart by the chart they are on.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(baseOf(t.symbol), style = Type.Heading)
            Spacer(Modifier.width(8.dp))
            ChartTag(t.timeframe, Modifier.testTag("setup-chart-size-${t.id}"))
            Spacer(Modifier.weight(1f))
            Text(PlainWords.ago(t.openedAt, now), style = Type.Small)
        }
        Text(PlainWords.headline(baseOf(t.symbol), PlainWords.isLab(t.variant)), style = Type.Title)
        Text(PlainWords.why(t.variant), style = Type.Body)
        PlanGrid(t)
        PlanFacts(t, price, row, onOpenLearn, onOpenPage)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TonalButton("See chart", onChart, Modifier.testTag("setup-chart-${t.id}"))
            Spacer(Modifier.weight(1f))
            Text("Practice trade started", style = Type.Small)
        }
    }
}

/** The detail beside the list on a wide screen: the chart with the trade's three lines, then the plan and the reasons. */
@Composable
private fun SetupDetail(markets: MarketsModel, t: TradeUi, price: Double?, row: ScoreRowUi?, onOpenLearn: (String) -> Unit, onOpenPage: (String) -> Unit) {
    val changes by markets.changes.collectAsStateWithLifecycle()
    val chart by produceState<ChartUi?>(null, t.symbol, t.timeframe, changes) { value = markets.chart(t.symbol, t.timeframe) }
    Column(Modifier.fillMaxSize().testTag("setup-detail")) {
        Text(
            PlainWords.headline(baseOf(t.symbol), PlainWords.isLab(t.variant)), style = Type.Title,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        )
        val c = chart
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                c == null -> Text("Loading…", style = Type.Small, modifier = Modifier.padding(16.dp))
                c.candles.isEmpty() -> EmptyState("No candles yet", "The history for this chart is still downloading.")
                else -> ChartView(c.copy(levels = levelsOf(c, setOf(t.id))), price, Modifier.fillMaxSize())
            }
        }
        HRule()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanGrid(t)
            Text(PlainWords.why(t.variant), style = Type.Body)
            PlanFacts(t, price, row, onOpenLearn, onOpenPage)
        }
    }
}

/**
 * Entry price / Profit goal / Loss limit. A level the trade does not have (a trailing exit has no fixed exit price) says so. The three boxes
 * are always the same height: when one needs a second line, all three grow.
 */
@Composable
private fun PlanGrid(t: TradeUi) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlanCell("Entry price", Fmt.price(t.entryPrice), null, Palette.Strong, Modifier.weight(1f))
        PlanCell(
            "Profit goal", t.target?.let { Fmt.price(it) } ?: "—", PlainWords.move(t.entryPrice, t.target)?.let(PlainWords::signed) ?: trailNote(t),
            Palette.Up, Modifier.weight(1f),
        )
        PlanCell(
            "Loss limit", t.stop?.let { Fmt.price(it) } ?: "—", PlainWords.move(t.entryPrice, t.stop)?.let(PlainWords::signed),
            Palette.Down, Modifier.weight(1f),
        )
    }
}

private fun trailNote(t: TradeUi): String? = if (t.target == null && t.exitMode == "trail") "follows the price" else null

@Composable
private fun PlanCell(label: String, value: String, note: String?, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).border(1.dp, Palette.Rule, RoundedCornerShape(8.dp)).background(Palette.Background).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = Type.Label, textAlign = TextAlign.Center)
        FitText(value, Type.NumberStrong.copy(color = color), textAlign = TextAlign.Center)
        Text(note ?: " ", style = Type.Label.copy(color = color), textAlign = TextAlign.Center)
    }
}

/** Where the price stands now, how the pattern has done, and the pattern's name with a "?" to its Learn page. */
@Composable
private fun PlanFacts(t: TradeUi, price: Double?, row: ScoreRowUi?, onOpenLearn: (String) -> Unit, onOpenPage: (String) -> Unit) {
    PlainWords.since(t.entryPrice, price)?.let { Text(it, style = Type.Small) }
    Text(PlainWords.record(row?.closed ?: 0, row?.hitRate), style = Type.Small)
    Row(Modifier.clickable { onOpenLearn(t.variant) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Pattern: ${t.short}", style = Type.Small.copy(color = Palette.Accent))
        Text("  What is this?", style = Type.Small)
    }
    Row(Modifier.clickable { onOpenPage("plan") }.padding(vertical = 4.dp).testTag("plan-help")) {
        Text("How the profit goal and loss limit work", style = Type.Small.copy(color = Palette.Accent))
    }
}
