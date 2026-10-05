package com.ikverse.signallab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The chart sizes in the order the app lists them. */
private val CHART_ORDER = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d")

/** A coin shown because it was asked for, not because a switched-on list has it: its open trades are counted, its chart sizes are the one asked for and those its trades used. */
internal fun unwatchedCoin(symbol: String, requested: String?, trades: List<TradeUi>): CoinUi {
    val mine = trades.filter { it.symbol == symbol }
    val sizes = (listOfNotNull(requested) + mine.map { it.timeframe }).distinct().sortedBy { CHART_ORDER.indexOf(it) }
    return CoinUi(symbol, symbol.removeSuffix("USDT"), null, null, mine.count { it.closed == null }, sizes.ifEmpty { listOf("1h") })
}

/**
 * What to tell the user when the coin or chart size shown is not what was asked for or not what is being watched: a coin in no
 * switched-on list, or a chart size the coin is not watched on (so another one is shown). Null when there is nothing to say.
 */
internal fun coinNote(coin: CoinUi, unwatched: Boolean, requestedChart: String?, shownChart: String?): String? = when {
    unwatched -> "${coin.base} is not in a list that is switched on, so it is not being watched and its chart may be out of date."
    requestedChart != null && shownChart != null && shownChart != requestedChart ->
        "${coin.base} is not watched on the ${Fmt.chartAdjective(requestedChart)} chart, so this shows the ${Fmt.chartAdjective(shownChart)} chart."
    else -> null
}

/** The line above a coin's chart (and its Details) saying it is not quite what was asked for, with a way to the lists when the coin is not watched. */
@Composable
private fun CoinNote(note: String?, unwatched: Boolean, onOpenLists: () -> Unit) {
    if (note == null) return
    Column(Modifier.fillMaxWidth()) {
        Text(note, style = Type.Small.copy(color = Palette.Warn), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("coin-note"))
        if (unwatched) TextAction("Open Lists", onOpenLists)
    }
}

/** Where the chart's indicator choice is kept in the panel preferences. */
const val CHART_KEY = "chart"

/** The chart a coin opens on: an hour if it has one, else the next best, so a coin never opens on a chart it is not watched on. */
fun defaultChart(timeframes: List<String>): String? =
    listOf("1h", "15m", "4h", "30m", "5m", "1d", "1m").firstOrNull { it in timeframes } ?: timeframes.firstOrNull()

/**
 * Markets: your coins, the chart, and what is happening on the selected coin. Three panels side by side when there is room, two on a
 * small phone held sideways, and three tabs on a phone held upright. Live prices only run while this screen is showing.
 */
@Composable
fun MarketsScreen(
    markets: MarketsModel,
    trades: TradesModel,
    alerts: AlertsModel,
    panels: PanelPrefs,
    layout: LayoutClass,
    nav: NavState,
    onOpenLearn: (String) -> Unit,
    onOpenLists: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coins by markets.coins.collectAsStateWithLifecycle()
    val prices by markets.prices.collectAsStateWithLifecycle()
    val changes by markets.changes.collectAsStateWithLifecycle()
    val allTrades by trades.trades.collectAsStateWithLifecycle()
    val allAlerts by alerts.alerts.collectAsStateWithLifecycle()
    val pane = rememberPaneLayout(panels, "markets")
    // The indicators the user picked on the chart, kept between runs; an empty choice is kept too (volume alone until one is made).
    val saved by panels.saved.collectAsStateWithLifecycle()
    val indicators = saved?.get(CHART_KEY)?.let { text -> text.split(',').filter { it.isNotBlank() } } ?: DefaultIndicators

    val symbols = coins.map { it.symbol }.toSet()
    DisposableEffect(symbols) {
        val handle = markets.watchPrices(symbols)
        onDispose { handle.close() }
    }
    val requested = nav.symbol
    val listed = coins.firstOrNull { it.symbol == requested }
    if (coins.isEmpty() && requested == null) {
        EmptyState(
            "Nothing is being watched", "Switch a list on, or make one, and its coins appear here.",
            modifier.testTag("markets-empty"),
            action = { TextAction("Open Lists", onOpenLists) },
        )
        return
    }
    // A coin that was asked for (by a notification, or Show on chart) but is in no list that is switched on is shown as itself, from what is
    // stored, and says so; it is never swapped for the first coin of the list.
    val unwatched = requested != null && listed == null
    val coin = listed ?: requested?.let { unwatchedCoin(it, nav.timeframe, allTrades) } ?: coins.first()
    val tf = if (unwatched) nav.timeframe ?: defaultChart(coin.timeframes) else nav.timeframe?.takeIf { it in coin.timeframes } ?: defaultChart(coin.timeframes)
    val note = coinNote(coin, unwatched, nav.timeframe, tf)
    val chart by produceState<ChartUi?>(null, coin.symbol, tf, changes) { value = tf?.let { markets.chart(coin.symbol, it) } }
    val live = prices[coin.symbol]

    val list = @Composable { CoinList(coins, prices, coin.symbol, { nav.symbol = it.symbol; nav.timeframe = null; if (layout == LayoutClass.Compact) nav.marketsTab = MarketsTab.Chart }, Modifier.fillMaxSize()) }
    val chartPane = @Composable {
        ChartPane(coin, tf, chart, live, { nav.timeframe = it }, indicators, { panels.save(CHART_KEY, it.joinToString(",")) }, note, unwatched, onOpenLists, Modifier.fillMaxSize())
    }
    val details = @Composable { Details(coin, live, allTrades.filter { it.symbol == coin.symbol }, allAlerts.filter { it.symbol == coin.symbol && it.kind == "warning" }, onOpenLearn, note, unwatched, onOpenLists, Modifier.fillMaxSize()) }

    when (layout) {
        LayoutClass.Wide -> BoxWithConstraints(modifier.fillMaxSize().testTag("markets-wide")) {
            val total = maxWidth.value
            val coinsHidden = pane.isHidden("wide.coins")
            val detailsHidden = pane.isHidden("wide.details")
            val (c, d) = PaneMath.fit(total, pane.size("wide.coins", 280f), pane.size("wide.details", 320f), coinsHidden, detailsHidden)
            Row(Modifier.fillMaxSize()) {
                if (!coinsHidden) Column(Modifier.width(c.dp).pane("coins")) { list() }
                PaneDivider(
                    vertical = true, hidden = coinsHidden, label = "coins", arrow = if (coinsHidden) "›" else "‹",
                    onDrag = { pane.set("wide.coins", PaneMath.dragged(c, it, max = total - 2 * PaneMath.DIVIDER - PaneMath.MIN_CHART - d)) },
                    onToggle = { pane.toggle("wide.coins") }, modifier = Modifier.testTag("divider-coins"),
                )
                Column(Modifier.weight(1f).pane("chart")) { chartPane() }
                PaneDivider(
                    vertical = true, hidden = detailsHidden, label = "details", arrow = if (detailsHidden) "‹" else "›",
                    onDrag = { pane.set("wide.details", PaneMath.dragged(d, -it, max = total - 2 * PaneMath.DIVIDER - PaneMath.MIN_CHART - c)) },
                    onToggle = { pane.toggle("wide.details") }, modifier = Modifier.testTag("divider-details"),
                )
                if (!detailsHidden) Column(Modifier.width(d.dp).pane("details")) { details() }
            }
        }
        LayoutClass.Medium -> BoxWithConstraints(modifier.fillMaxSize().testTag("markets-medium")) {
            val totalW = maxWidth.value
            val totalH = maxHeight.value
            val coinsHidden = pane.isHidden("medium.coins")
            val detailsHidden = pane.isHidden("medium.details")
            val c = PaneMath.fitOne(totalW, pane.size("medium.coins", 220f), coinsHidden, PaneMath.MIN_SIDE, PaneMath.MIN_CHART, PaneMath.DIVIDER)
            val h = PaneMath.fitOne(totalH, pane.size("medium.details", 190f), detailsHidden, PaneMath.MIN_PANE_HEIGHT, PaneMath.MIN_CHART_HEIGHT, PaneMath.DIVIDER)
            Row(Modifier.fillMaxSize()) {
                if (!coinsHidden) Column(Modifier.width(c.dp).pane("coins")) { list() }
                PaneDivider(
                    vertical = true, hidden = coinsHidden, label = "coins", arrow = if (coinsHidden) "›" else "‹",
                    onDrag = { pane.set("medium.coins", PaneMath.dragged(c, it, max = totalW - PaneMath.DIVIDER - PaneMath.MIN_CHART)) },
                    onToggle = { pane.toggle("medium.coins") }, modifier = Modifier.testTag("divider-coins"),
                )
                Column(Modifier.weight(1f)) {
                    Column(Modifier.weight(1f).pane("chart")) { chartPane() }
                    PaneDivider(
                        vertical = false, hidden = detailsHidden, label = "details", arrow = if (detailsHidden) "▴" else "▾",
                        onDrag = {
                            pane.set("medium.details", PaneMath.dragged(h, -it, max = totalH - PaneMath.DIVIDER - PaneMath.MIN_CHART_HEIGHT, min = PaneMath.MIN_PANE_HEIGHT))
                        },
                        onToggle = { pane.toggle("medium.details") }, modifier = Modifier.testTag("divider-details"),
                    )
                    if (!detailsHidden) Column(Modifier.height(h.dp).pane("details")) { details() }
                }
            }
        }
        LayoutClass.Compact -> Column(modifier.fillMaxSize().testTag("markets-compact")) {
            Row(Modifier.fillMaxWidth()) {
                for (t in MarketsTab.entries) ChoiceText(t.label, nav.marketsTab == t, { nav.marketsTab = t }, Modifier.weight(1f).testTag("markets-tab-${t.name}"))
            }
            HRule()
            Column(Modifier.weight(1f)) {
                when (nav.marketsTab) {
                    MarketsTab.Coins -> list()
                    MarketsTab.Chart -> chartPane()
                    MarketsTab.Details -> details()
                }
            }
        }
    }
}

@Composable
private fun CoinList(coins: List<CoinUi>, prices: Map<String, Double>, selected: String, onSelect: (CoinUi) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.testTag("coin-list")) {
        items(coins, key = { it.symbol }) { c ->
            val price = prices[c.symbol] ?: c.price
            TouchRow({ onSelect(c) }, selected = c.symbol == selected, modifier = Modifier.testTag("coin-${c.symbol}")) {
                Column(Modifier.weight(1f)) {
                    Text(c.base, style = Type.BodyStrong)
                    Text(if (c.openTrades > 0) "${c.openTrades} open" else c.timeframes.joinToString(" "), style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.price(price), style = Type.NumberStrong)
                    Text(Fmt.signedPercent(c.changeFraction), style = Type.Number.copy(color = Fmt.changeColor(c.changeFraction), fontSize = Type.Small.fontSize))
                }
            }
            HRule()
        }
    }
}

@Composable
private fun ChartPane(
    coin: CoinUi,
    tf: String?,
    chart: ChartUi?,
    live: Double?,
    onChoose: (String) -> Unit,
    indicators: List<String>,
    onIndicators: (List<String>) -> Unit,
    note: String?,
    unwatched: Boolean,
    onOpenLists: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(coin.base, style = Type.Title)
            Text(
                Fmt.price(live ?: coin.price), style = Type.NumberStrong.copy(color = Fmt.changeColor(coin.changeFraction)),
                modifier = Modifier.padding(start = 12.dp).testTag("live-price"),
            )
            Text(Fmt.signedPercent(coin.changeFraction), style = Type.Small.copy(color = Fmt.changeColor(coin.changeFraction)), modifier = Modifier.padding(start = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            for (t in coin.timeframes) ChoiceText(t, t == tf, { onChoose(t) })
        }
        CoinNote(note, unwatched, onOpenLists)
        HRule()
        when {
            tf == null -> EmptyState("No chart chosen", "This coin is not watched on any chart.")
            chart == null -> Text("Loading…", style = Type.Small, modifier = Modifier.padding(16.dp))
            chart.candles.isEmpty() -> EmptyState(
                "No candles yet on the ${Fmt.chartName(tf)} chart",
                "The history for this chart is still downloading, or Binance has none for this coin yet.",
            )
            else -> ChartView(chart, live, Modifier.weight(1f), indicators, onIndicators)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(coin: CoinUi, live: Double?, trades: List<TradeUi>, warnings: List<AlertUi>, onOpenLearn: (String) -> Unit, note: String?, unwatched: Boolean, onOpenLists: () -> Unit, modifier: Modifier = Modifier) {
    val open = trades.filter { it.closed == null }
    val recent = trades.filter { it.closed != null }.take(5)
    Column(modifier.verticalScroll(rememberScrollState()).testTag("details")) {
        CoinNote(note, unwatched, onOpenLists)
        SectionLabel("Open paper trades")
        if (open.isEmpty()) Text("None on ${coin.base} right now.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        for (t in open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("open-${t.id}")) {
                Text("${t.label} · ${t.timeframe}", style = Type.BodyStrong)
                Text(
                    "Entry ${Fmt.price(t.entryPrice)}" + (live?.let { "  ·  now ${Fmt.signedPercent(it / t.entryPrice - 1)}" } ?: ""),
                    style = Type.Number.copy(color = Fmt.changeColor(live?.let { it / t.entryPrice - 1 })),
                )
                Text(exitText(t), style = Type.Small)
                TextAction("What is this pattern?", { onOpenLearn(t.variant) }, color = Palette.Muted)
            }
            HRule()
        }
        SectionLabel("Recent results")
        if (recent.isEmpty()) Text("No closed trades on ${coin.base} yet.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        for (t in recent) {
            val c = t.closed!!
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${t.label} · ${t.timeframe}", style = Type.Body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Fmt.signedPercent(c.net), style = Type.Number.copy(color = Fmt.changeColor(c.net)))
            }
        }
        SectionLabel("Warnings")
        if (warnings.isEmpty()) Text("No warnings on ${coin.base}.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        for (w in warnings.take(3)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(w.title, style = Type.BodyStrong.copy(color = Palette.Warn))
                Text(w.body, style = Type.Small)
            }
        }
    }
}

/** How an open trade will end, in a sentence. */
fun exitText(t: TradeUi): String = when (t.exitMode) {
    "trail" -> "Safety stop ${Fmt.price(t.stop)}, then a stop that follows the price up."
    "learned" -> if (t.target != null) "Target ${Fmt.price(t.target)}, stop ${Fmt.price(t.stop)}." else "Held for a fixed time."
    "held" -> "Held for a fixed time."
    else -> if (t.target != null && t.stop != null) "Target ${Fmt.price(t.target)}, stop ${Fmt.price(t.stop)}." else "Held for a fixed time."
}
