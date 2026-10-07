package com.ikverse.signallab.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class TradeFilter(val label: String) { All("All"), Open("Open"), Closed("Closed") }

/**
 * Which trades pass the filters: the status, the chart size ([timeframe], or null for any), and a search over the coin, the pattern and the
 * chart. Pure, so a test can hold it to its word.
 */
fun filterTrades(trades: List<TradeUi>, status: TradeFilter, query: String, timeframe: String? = null): List<TradeUi> {
    val q = query.trim().lowercase()
    return trades.filter { t ->
        when (status) {
            TradeFilter.All -> true
            TradeFilter.Open -> t.closed == null
            TradeFilter.Closed -> t.closed != null
        } && (timeframe == null || t.timeframe == timeframe) &&
            (q.isEmpty() || q in t.symbol.lowercase() || q in t.label.lowercase() || q in t.short.lowercase() || q in t.variant.lowercase() || q == t.timeframe.lowercase())
    }
}

private val ChartSizes = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d")

/** The chart sizes [trades] were taken on, shortest first, then any size not known by name; [keep] is included even when no trade has it. */
fun tradeTimeframes(trades: List<TradeUi>, keep: String? = null): List<String> =
    (trades.map { it.timeframe } + listOfNotNull(keep)).distinct()
        .sortedWith(compareBy<String> { ChartSizes.indexOf(it).let { i -> if (i < 0) ChartSizes.size else i } }.thenBy { it })

/** One line over a set of trades: how many, and the average result of the closed ones. Also what a screen reader says for the totals. */
fun tradesSummary(trades: List<TradeUi>): String {
    val closed = trades.mapNotNull { it.closed }
    val open = trades.size - closed.size
    val mean = if (closed.isEmpty()) null else closed.sumOf { it.net } / closed.size
    return "$open open · ${closed.size} closed" + (mean?.let { " · average ${Fmt.signedPercent(it)} after costs" } ?: "")
}

/** The average result of the closed trades among [trades], or null when none has closed. */
fun closedMean(trades: List<TradeUi>): Double? = trades.mapNotNull { it.closed?.net }.takeIf { it.isNotEmpty() }?.average()

/** [trades] (newest first) split by pattern. The pattern that fired last comes first; each group keeps its trades newest first. */
fun groupTrades(trades: List<TradeUi>): List<Pair<String, List<TradeUi>>> {
    val newestFirst = trades.sortedByDescending { it.openedAt }
    return newestFirst.groupBy { it.label }.toList()
}

/** How far a closed trade did better (above zero) or worse than random entries over the same stretch, in percentage points; null when unknown. */
fun gapToRandom(c: ClosedUi): Double? = c.randomMean?.takeIf { !it.isNaN() }?.let { (c.net - it) * 100 }

/**
 * Every paper trade, grouped by pattern and newest first within each group, with what each one did. The filter, the search and the opened
 * trade live in [nav], so a notification can set them and they are still there after another tab has been on show. On a phone held upright
 * a trade opens out under its row; with room for two panels it opens beside the list. [prices] are the coins' latest prices, for where an
 * open trade stands.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TradesScreen(
    model: TradesModel,
    nav: NavState,
    onOpenCoin: (String, String, Long) -> Unit,
    onOpenLearn: (String) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    prices: Map<String, Double> = emptyMap(),
) {
    val all by model.trades.collectAsStateWithLifecycle()
    val status = nav.tradesStatus
    val query = nav.tradesQuery
    val timeframe = nav.tradesTimeframe
    val expanded = nav.tradesExpanded
    val shown = filterTrades(all, status, query, timeframe)
    val sizes = tradeTimeframes(all, keep = timeframe)
    val groups = groupTrades(shown)
    var folded by rememberSaveable { mutableStateOf(listOf<String>()) }
    val list = rememberLazyListState()
    // A trade a link asked to be opened is brought into view once it has arrived in the list; one already on screen (a row just touched) stays put.
    LaunchedEffect(expanded, shown.size) {
        // Each group adds a header row ahead of its trades.
        var at = -1
        var index = 0
        for ((pattern, trades) in groups) {
            index++
            if (pattern in folded) continue
            val within = trades.indexOfFirst { it.id == expanded }
            if (within >= 0) { at = index + within; break }
            index += trades.size
        }
        if (expanded != null && at >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == at }) list.animateScrollToItem(at)
    }
    val toggle = { t: TradeUi -> nav.tradesExpanded = if (expanded == t.id) null else t.id }
    val listPane = @Composable { m: Modifier ->
        Column(m.testTag("trades")) {
            if (wide) {
                // Beside the trade's panel the height is short: the title and the filters share a row, and the totals are one line.
                Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScreenTitle("Paper trades", Modifier.weight(1f))
                    for (f in TradeFilter.entries) ChoiceText(f.label, status == f, { nav.tradesStatus = f })
                }
                SearchField(query, { nav.tradesQuery = it }, "Coin, pattern or chart", description = "Filter by coin, pattern or chart")
                if (sizes.size > 1) TimeframeChips(sizes, timeframe) { nav.tradesTimeframe = it }
                Totals(shown, oneLine = true)
            } else {
                ScreenTitle("Paper trades")
                SearchField(query, { nav.tradesQuery = it }, "Coin, pattern or chart", description = "Filter by coin, pattern or chart")
                Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp)) {
                    for (f in TradeFilter.entries) ChoiceText(f.label, status == f, { nav.tradesStatus = f })
                }
                if (sizes.size > 1) TimeframeChips(sizes, timeframe) { nav.tradesTimeframe = it }
                Totals(shown)
            }
            HRule()
            when {
                all.isEmpty() -> EmptyState("No paper trades yet", "When a pattern appears on a coin you are watching, a pretend trade is recorded here. No real money is used.")
                shown.isEmpty() -> EmptyState("Nothing matches", "Change the filters or the search.")
                else -> LazyColumn(Modifier.weight(1f), state = list) {
                    for ((pattern, trades) in groups) {
                        val isFolded = pattern in folded
                        stickyHeader(key = "group-$pattern") {
                            GroupHeader(trades, isFolded, Modifier.testTag("trades-group-$pattern")) {
                                folded = if (isFolded) folded - pattern else folded + pattern
                            }
                        }
                        if (!isFolded) items(trades, key = { it.id }) { t ->
                            TradeRow(t, selected = wide && expanded == t.id) { toggle(t) }
                            if (!wide && expanded == t.id) {
                                TradeDetail(t, prices[t.symbol], { onOpenCoin(t.symbol, t.timeframe, t.id) }, { onOpenLearn(t.variant) }, Modifier.padding(top = 4.dp, bottom = 12.dp))
                            }
                            HRule()
                        }
                    }
                }
            }
        }
    }
    if (!wide) {
        listPane(modifier.fillMaxSize())
        return
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val listWidth = (maxWidth * 0.48f).coerceIn(300.dp, 440.dp)
        Row(Modifier.fillMaxSize()) {
            listPane(Modifier.width(listWidth).fillMaxHeight())
            VRule()
            val chosen = all.firstOrNull { it.id == expanded }
            Box(Modifier.weight(1f).fillMaxHeight().testTag("trade-pane")) {
                if (chosen == null) {
                    EmptyState("Choose a trade", "Touch a trade on the left to see what it did.")
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(chosen.symbol.removeSuffix("USDT"), style = Type.Title)
                            Spacer(Modifier.width(8.dp))
                            ChartTag(chosen.timeframe)
                            Spacer(Modifier.weight(1f))
                            chosen.closed?.let { Text(Fmt.signedPercent(it.net), style = Type.Big.copy(color = Fmt.changeColor(it.net))) }
                        }
                        Text(
                            "${chosen.short} · opened ${Fmt.dateTime(chosen.openedAt)}", style = Type.Small,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
                        )
                        TradeDetail(chosen, prices[chosen.symbol], { onOpenCoin(chosen.symbol, chosen.timeframe, chosen.id) }, { onOpenLearn(chosen.variant) })
                    }
                }
            }
        }
    }
}

/** "Any chart" and one choice per chart size the trades were taken on; a row that scrolls sideways when the sizes do not fit. */
@Composable
private fun TimeframeChips(sizes: List<String>, chosen: String?, onChoose: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp).testTag("trades-charts")) {
        ChoiceText("Any chart", chosen == null, { onChoose(null) })
        for (s in sizes) ChoiceText(s, chosen == s, { onChoose(s) })
    }
}

/**
 * The totals over the trades on show: how many are open, how many closed, and the average after costs, each under its name, or all on
 * [oneLine] where height is short. Read aloud as one line.
 */
@Composable
private fun Totals(trades: List<TradeUi>, oneLine: Boolean = false) {
    val closed = trades.count { it.closed != null }
    val mean = closedMean(trades)
    val semantics = Modifier.testTag("trades-summary").clearAndSetSemantics { contentDescription = tradesSummary(trades) }
    if (oneLine) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).then(semantics), verticalAlignment = Alignment.CenterVertically) {
            for ((name, value, color) in listOf(
                Triple("Open", "${trades.size - closed}", Palette.Strong), Triple("Closed", "$closed", Palette.Strong),
                Triple("Average", Fmt.signedPercent(mean), Fmt.changeColor(mean)),
            )) {
                Text(name, style = Type.Small)
                Text(value, style = Type.NumberStrong.copy(color = color), modifier = Modifier.padding(start = 4.dp, end = 14.dp))
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp).then(semantics),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Stat("Open", "${trades.size - closed}", Modifier.weight(1f), valueColor = Palette.Strong, big = true)
        Stat("Closed", "$closed", Modifier.weight(1f), valueColor = Palette.Strong, big = true)
        Stat("Average after costs", Fmt.signedPercent(mean), Modifier.weight(1.8f), valueColor = Fmt.changeColor(mean), big = true)
    }
}

/** A pattern's group: raised, its short name, how many closed (and open) and their average; touching it folds the group away or back. */
@Composable
private fun GroupHeader(trades: List<TradeUi>, folded: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val closed = trades.count { it.closed != null }
    val open = trades.size - closed
    val mean = closedMean(trades)
    TouchRow(onToggle, modifier.background(Palette.Raised), minHeight = 52.dp) {
        Text(trades.first().short, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            Text("$closed closed" + if (open > 0) " · $open open" else "", style = Type.Small)
            if (mean != null) Text("avg ${Fmt.signedPercent(mean)}", style = Type.Small.copy(color = Fmt.changeColor(mean), fontFeatureSettings = "tnum"))
        }
        Icon(
            if (folded) Glyphs.ChevronDown else Glyphs.ChevronUp, contentDescription = if (folded) "Show the group" else "Fold the group",
            tint = Palette.Muted, modifier = Modifier.padding(start = 10.dp).size(18.dp),
        )
    }
}

/** A trade in its group: the coin and its chart size, when it opened, and its result in a column of its own. */
@Composable
private fun TradeRow(t: TradeUi, selected: Boolean, onToggle: () -> Unit) {
    TouchRow(onToggle, modifier = Modifier.testTag("trade-${t.id}"), selected = selected, minHeight = 58.dp) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.symbol.removeSuffix("USDT"), style = Type.BodyStrong, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                ChartTag(t.timeframe)
            }
            Text(Fmt.dateTime(t.openedAt), style = Type.Small.copy(fontFeatureSettings = "tnum"))
        }
        val c = t.closed
        Box(Modifier.widthIn(min = scaledWithText(86.dp)), contentAlignment = Alignment.CenterEnd) {
            if (c == null) Text("open", style = Type.Small)
            else Text(Fmt.signedPercent(c.net), style = Type.NumberStrong.copy(color = Fmt.changeColor(c.net), fontSize = 17.sp), textAlign = TextAlign.End)
        }
    }
}

/** How a closed trade ended, as a word and a mark. */
private fun outcome(reason: String): Triple<String, ImageVector, Color> = when (reason) {
    "target" -> Triple("Target hit", Glyphs.Check, Palette.Up)
    "stop" -> Triple("Stopped out", Glyphs.Close, Palette.Down)
    "time" -> Triple("Time limit", Glyphs.Clock, Palette.Muted)
    else -> Triple(exitWords(reason).replaceFirstChar { it.uppercase() }, Glyphs.Dash, Palette.Muted)
}

/**
 * What a trade did, on a raised panel: for a closed one, how it ended, a bar from its worst dip to its best with a tick at the entry and
 * a dot where it closed, the four numbers and the gap to random entries; for an open one, its stop and target with a dot at the price
 * now. Then the way to its chart and to what the pattern is. Shared by the phone (under the row) and wider screens (beside the list).
 */
@Composable
fun TradeDetail(t: TradeUi, price: Double?, onShowOnChart: () -> Unit, onAbout: () -> Unit, modifier: Modifier = Modifier) {
    RaisedGroup(modifier.animateContentSize(tween(Motion.ENTER_MS, easing = Motion.EaseOut)).testTag("trade-detail")) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
            val c = t.closed
            if (c != null) {
                val (word, mark, color) = outcome(c.reason)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Icon(mark, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                    }
                    Text(word, style = Type.BodyStrong, modifier = Modifier.padding(start = 10.dp))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${Fmt.dateTime(c.exitTime)} · ${c.barsHeld} ${if (c.barsHeld == 1) "candle" else "candles"}",
                        style = Type.Small.copy(fontFeatureSettings = "tnum"),
                    )
                }
                val worst = c.maxDown
                val best = c.maxUp
                if (worst != null && best != null) {
                    val exitMove = c.exitPrice / t.entryPrice - 1
                    RangeBar(
                        rangeFraction(worst, best, 0.0) ?: 0.5f, rangeFraction(worst, best, exitMove), Fmt.changeColor(c.net),
                        Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text("Worst dip ${Fmt.signedPercent(worst)}", style = Type.Small.copy(fontFeatureSettings = "tnum"), modifier = Modifier.weight(1f))
                        Text("Best ${Fmt.signedPercent(best)}", style = Type.Small.copy(fontFeatureSettings = "tnum"))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Stat("Entry", Fmt.price(t.entryPrice), Modifier.weight(1f))
                    Stat("Exit", Fmt.price(c.exitPrice), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Stat("Result after costs", Fmt.signedPercent(c.net), Modifier.weight(1f), valueColor = Fmt.changeColor(c.net), big = true)
                    Stat("Random entries", Fmt.signedPercent(c.randomMean), Modifier.weight(1f), big = true)
                }
                gapToRandom(c)?.let { gap ->
                    Text(
                        "%.2f pts %s than random entries over the same stretch.".format(java.util.Locale.ROOT, kotlin.math.abs(gap), if (gap >= 0) "better" else "worse"),
                        style = Type.Small, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                val now = price?.let { it / t.entryPrice - 1 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Open", style = Type.BodyStrong)
                    Spacer(Modifier.weight(1f))
                    if (now != null) Text("now ${Fmt.signedPercent(now)}", style = Type.NumberStrong.copy(color = Fmt.changeColor(now)))
                }
                val stop = t.stop
                val target = t.target
                when {
                    t.exitMode == "trail" && stop != null -> {
                        RangeBar(0.5f, rangeFraction(stop, t.entryPrice + (t.entryPrice - stop), price), Fmt.changeColor(now), Modifier.padding(top = 14.dp, bottom = 4.dp), openEnded = true)
                        Row(Modifier.fillMaxWidth()) {
                            Stat("Safety stop", Fmt.price(stop), Modifier.weight(1f))
                            Stat("Entry", Fmt.price(t.entryPrice), Modifier.weight(1f), align = Alignment.CenterHorizontally)
                            Stat("Then", "trails up", Modifier.weight(1f), align = Alignment.End)
                        }
                    }
                    stop != null && target != null -> {
                        RangeBar(rangeFraction(stop, target, t.entryPrice) ?: 0.5f, rangeFraction(stop, target, price), Fmt.changeColor(now), Modifier.padding(top = 14.dp, bottom = 4.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Stat("Stop", Fmt.price(stop), Modifier.weight(1f))
                            Stat("Entry", Fmt.price(t.entryPrice), Modifier.weight(1f), align = Alignment.CenterHorizontally)
                            Stat("Target", Fmt.price(target), Modifier.weight(1f), align = Alignment.End)
                        }
                    }
                    else -> {
                        Stat("Entry", Fmt.price(t.entryPrice), Modifier.padding(top = 8.dp))
                        Text(exitText(t), style = Type.Small, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TonalButton("Show on chart", onShowOnChart, Modifier.weight(1f), stretch = true)
            LineButton("About this pattern", onAbout, Modifier.weight(1f), stretch = true)
        }
    }
}

fun exitWords(reason: String) = when (reason) {
    "target" -> "target hit"
    "stop" -> "stopped out"
    "time" -> "time limit"
    else -> reason
}
