package com.ikverse.signallab.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class TradeFilter(val label: String) { All("All"), Open("Open"), Closed("Closed") }

/** Which trades pass the filters: the status, and a search over the coin, the pattern and the chart. Pure, so a test can hold it to its word. */
fun filterTrades(trades: List<TradeUi>, status: TradeFilter, query: String): List<TradeUi> {
    val q = query.trim().lowercase()
    return trades.filter { t ->
        when (status) {
            TradeFilter.All -> true
            TradeFilter.Open -> t.closed == null
            TradeFilter.Closed -> t.closed != null
        } && (q.isEmpty() || q in t.symbol.lowercase() || q in t.label.lowercase() || q in t.variant.lowercase() || q == t.timeframe.lowercase())
    }
}

/** One line over a set of trades: how many, and the average result of the closed ones. */
fun tradesSummary(trades: List<TradeUi>): String {
    val closed = trades.mapNotNull { it.closed }
    val open = trades.size - closed.size
    val mean = if (closed.isEmpty()) null else closed.sumOf { it.net } / closed.size
    return "$open open · ${closed.size} closed" + (mean?.let { " · average ${Fmt.signedPercent(it)} after costs" } ?: "")
}

/** [trades] (newest first) split by pattern. The pattern that fired last comes first; each group keeps its trades newest first. */
fun groupTrades(trades: List<TradeUi>): List<Pair<String, List<TradeUi>>> {
    val newestFirst = trades.sortedByDescending { it.openedAt }
    return newestFirst.groupBy { it.label }.toList()
}

/**
 * Every paper trade, grouped by pattern and newest first within each group, with what each one did. The filter, the search and the opened
 * trade live in [nav], so a notification can set them and they are still there after another tab has been on show.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TradesScreen(model: TradesModel, nav: NavState, onOpenCoin: (String, String) -> Unit, onOpenLearn: (String) -> Unit, modifier: Modifier = Modifier) {
    val all by model.trades.collectAsStateWithLifecycle()
    val status = nav.tradesStatus
    val query = nav.tradesQuery
    val expanded = nav.tradesExpanded
    val shown = filterTrades(all, status, query)
    val groups = groupTrades(shown)
    val list = rememberLazyListState()
    // A trade a link asked to be opened is brought into view once it has arrived in the list; one already on screen (a row just touched) stays put.
    LaunchedEffect(expanded, shown.size) {
        // Each group adds a header row ahead of its trades.
        var at = -1
        var index = 0
        for ((_, trades) in groups) {
            index++
            val within = trades.indexOfFirst { it.id == expanded }
            if (within >= 0) { at = index + within; break }
            index += trades.size
        }
        if (expanded != null && at >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == at }) list.animateScrollToItem(at)
    }
    Column(modifier.fillMaxSize().testTag("trades")) {
        ScreenTitle("Paper trades")
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            for (f in TradeFilter.entries) ChoiceText(f.label, status == f, { nav.tradesStatus = f })
        }
        PlainField(query, { nav.tradesQuery = it }, "Filter by coin, pattern or chart")
        Text(tradesSummary(shown), style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("trades-summary"))
        HRule()
        when {
            all.isEmpty() -> EmptyState("No paper trades yet", "When a pattern appears on a coin you are watching, a pretend trade is recorded here. No real money is used.")
            shown.isEmpty() -> EmptyState("Nothing matches", "Change the filter or the search.")
            else -> LazyColumn(Modifier.weight(1f), state = list) {
                for ((pattern, trades) in groups) {
                    stickyHeader(key = "group-$pattern") {
                        Column(Modifier.fillMaxWidth().background(Palette.Background).testTag("trades-group-$pattern")) {
                            Text(pattern, style = Type.Heading, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
                            Text(tradesSummary(trades), style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            HRule()
                        }
                    }
                    items(trades, key = { it.id }) { t ->
                        TradeRow(t, expanded == t.id, { nav.tradesExpanded = if (expanded == t.id) null else t.id }, onOpenCoin, onOpenLearn)
                        HRule()
                    }
                }
            }
        }
    }
}

@Composable
private fun TradeRow(t: TradeUi, open: Boolean, onToggle: () -> Unit, onOpenCoin: (String, String) -> Unit, onOpenLearn: (String) -> Unit) {
    TouchRow(onToggle, modifier = Modifier.testTag("trade-${t.id}")) {
        Column(Modifier.weight(1f)) {
            Text(t.label, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.symbol.removeSuffix("USDT")} · ${t.timeframe} · ${Fmt.dateTime(t.openedAt)}", style = Type.Small)
        }
        val c = t.closed
        if (c == null) Text("open", style = Type.Small) else Text(Fmt.signedPercent(c.net), style = Type.NumberStrong.copy(color = Fmt.changeColor(c.net)))
    }
    if (open) {
        Column(Modifier.padding(bottom = 8.dp)) {
            LabelValue("Entry", Fmt.price(t.entryPrice))
            val c = t.closed
            if (c == null) {
                LabelValue("How it ends", exitText(t))
            } else {
                LabelValue("Exit", "${Fmt.price(c.exitPrice)} (${exitWords(c.reason)}, ${Fmt.dateTime(c.exitTime)})")
                LabelValue("Result after costs", Fmt.signedPercent(c.net), valueColor = Fmt.changeColor(c.net))
                LabelValue("Random entries averaged", Fmt.signedPercent(c.randomMean))
                LabelValue("Best it reached", Fmt.signedPercent(c.maxUp))
                LabelValue("Worst dip on the way", Fmt.signedPercent(c.maxDown))
                LabelValue("Held for", "${c.barsHeld} ${if (c.barsHeld == 1) "candle" else "candles"}")
            }
            Row {
                TextAction("Show on chart", { onOpenCoin(t.symbol, t.timeframe) })
                TextAction("What is this pattern?", { onOpenLearn(t.variant) }, color = Palette.Muted)
            }
        }
    }
}

fun exitWords(reason: String) = when (reason) {
    "target" -> "target hit"
    "stop" -> "stopped out"
    "time" -> "time limit"
    else -> reason
}
