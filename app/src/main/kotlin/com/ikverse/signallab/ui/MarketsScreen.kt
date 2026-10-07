package com.ikverse.signallab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The chart sizes in the order the app lists them. */
private val CHART_ORDER = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d")

/** Below this height (a phone held sideways) Markets shows the coins and the chart only, and the open trades slide in over the chart. */
private val SHORT_HEIGHT = 480.dp

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

/** The open trades the chart can show the levels of, in the order the chart has them, and which one is on show (the first unless [chosen] is among them). */
internal fun levelTrades(chart: ChartUi?, chosen: Long?): Pair<List<Long>, Int> {
    val ids = chart?.levels?.map { it.tradeId }?.distinct().orEmpty()
    return ids to ids.indexOf(chosen).coerceAtLeast(0)
}

/** The levels of one trade, each named with its price ("Target 2.949"), as the chart draws them. */
internal fun levelsOf(chart: ChartUi, tradeId: Long?): List<LevelUi> =
    chart.levels.filter { it.tradeId == tradeId }.map { it.copy(label = "${it.label} ${Fmt.price(it.price)}") }

/**
 * Markets: your coins, the chart, and what is happening on the selected coin. Three tabs on a phone held upright; on a phone held
 * sideways the coins and the chart, with the open trades sliding in over the chart; two or three panels on a bigger screen. Live prices
 * only run while this screen is showing.
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
    lists: List<ListUi> = emptyList(),
    scanning: Boolean = true,
    onOpenAlerts: () -> Unit = {},
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
    val control = remember { ChartControl() }

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
    val mine = allTrades.filter { it.symbol == coin.symbol }
    val openHere = mine.filter { it.closed == null }

    val pickCoin = { c: CoinUi ->
        nav.symbol = c.symbol
        nav.timeframe = null
        nav.chartTrade = null
        if (layout == LayoutClass.Compact) nav.marketsTab = MarketsTab.Chart
    }
    val list = @Composable { compact: Boolean ->
        CoinList(coins, prices, coin.symbol, pickCoin, Modifier.fillMaxSize(), compact) {
            WatchHeader(lists, coins.size, scanning, onOpenAlerts, compact)
        }
    }
    val chartPane = @Composable { sideways: Boolean, onOpenTrades: (() -> Unit)? ->
        ChartPane(
            coin, tf, chart, live, openHere, nav.chartTrade, { nav.chartTrade = it }, { nav.timeframe = it; nav.chartTrade = null }, indicators,
            { panels.save(CHART_KEY, it.joinToString(",")) }, note, unwatched, onOpenLists, control, sideways, onOpenTrades, Modifier.fillMaxSize(),
        )
    }
    val showOnChart = { t: TradeUi ->
        nav.timeframe = t.timeframe
        nav.chartTrade = t.id
        if (layout == LayoutClass.Compact) nav.marketsTab = MarketsTab.Chart
    }
    val details = @Composable { strip: Boolean, header: (@Composable () -> Unit)?, onShow: (TradeUi) -> Unit ->
        Details(
            coin, live, mine, allAlerts.filter { it.symbol == coin.symbol && it.kind == "warning" }, onOpenLearn, onShow, note, unwatched, onOpenLists,
            Modifier.fillMaxSize(), strip, header,
        )
    }

    if (layout == LayoutClass.Compact) {
        Column(modifier.fillMaxSize().testTag("markets-compact")) {
            Tabs(MarketsTab.entries, nav.marketsTab, { it.label }, { nav.marketsTab = it }, tag = { "markets-tab-${it.name}" })
            Column(Modifier.weight(1f)) {
                when (nav.marketsTab) {
                    MarketsTab.Coins -> list(false)
                    MarketsTab.Chart -> chartPane(false, null)
                    MarketsTab.Details -> details(true, null, showOnChart)
                }
            }
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        when {
            maxHeight < SHORT_HEIGHT -> Box(Modifier.fillMaxSize().testTag("markets-short")) {
                var tradesOpen by rememberSaveable { mutableStateOf(false) }
                val total = screenWidth.value
                val coinsHidden = pane.isHidden("short.coins")
                val c = PaneMath.fitOne(total, pane.size("short.coins", 216f), coinsHidden, PaneMath.MIN_SIDE, PaneMath.MIN_CHART, PaneMath.DIVIDER)
                val shownC = animatedPaneSize(c, pane.dragging)
                Row(Modifier.fillMaxSize()) {
                    if (paneOpen(coinsHidden, shownC)) Column(Modifier.width(shownC.dp).pane("coins")) { list(true) }
                    PaneDivider(
                        vertical = true, hidden = coinsHidden, label = "coins",
                        onDrag = { pane.set("short.coins", PaneMath.dragged(c, it, max = total - PaneMath.DIVIDER - PaneMath.MIN_CHART)) },
                        onToggle = { pane.toggle("short.coins") }, modifier = Modifier.testTag("divider-coins"),
                        onDragging = { pane.dragging = it },
                    )
                    Column(Modifier.weight(1f).pane("chart")) { chartPane(true) { tradesOpen = true } }
                }
                // The open trades slide in over the right of the chart, and Back (or the cross) slides them away.
                BackHandler(tradesOpen) { tradesOpen = false }
                AnimatedVisibility(
                    tradesOpen, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    enter = slideInHorizontally(tween(Motion.ENTER_MS, easing = Motion.EaseOut)) { it } + fadeIn(tween(Motion.ENTER_MS)),
                    exit = slideOutHorizontally(tween(Motion.EXIT_MS, easing = Motion.EaseOut)) { it } + fadeOut(tween(Motion.EXIT_MS)),
                ) {
                    Row(Modifier.fillMaxHeight()) {
                        Box(Modifier.width(20.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
                        Column(Modifier.width(min(304.dp, screenWidth * 0.5f)).fillMaxHeight().background(Palette.Background).pane("details")) {
                            VRuleLeft {
                                details(false, {
                                    PanelHeader("Open trades", openHere.size) { tradesOpen = false }
                                }) { t -> showOnChart(t); tradesOpen = false }
                            }
                        }
                    }
                }
            }
            layout == LayoutClass.Wide -> BoxWithConstraints(Modifier.fillMaxSize().testTag("markets-wide")) {
                val total = maxWidth.value
                val coinsHidden = pane.isHidden("wide.coins")
                val detailsHidden = pane.isHidden("wide.details")
                val (c, d) = PaneMath.fit(total, pane.size("wide.coins", 280f), pane.size("wide.details", 320f), coinsHidden, detailsHidden)
                val shownC = animatedPaneSize(c, pane.dragging)
                val shownD = animatedPaneSize(d, pane.dragging)
                Row(Modifier.fillMaxSize()) {
                    if (paneOpen(coinsHidden, shownC)) Column(Modifier.width(shownC.dp).pane("coins")) { list(false) }
                    PaneDivider(
                        vertical = true, hidden = coinsHidden, label = "coins",
                        onDrag = { pane.set("wide.coins", PaneMath.dragged(c, it, max = total - 2 * PaneMath.DIVIDER - PaneMath.MIN_CHART - d)) },
                        onToggle = { pane.toggle("wide.coins") }, modifier = Modifier.testTag("divider-coins"),
                        onDragging = { pane.dragging = it },
                    )
                    Column(Modifier.weight(1f).pane("chart")) { chartPane(false, null) }
                    PaneDivider(
                        vertical = true, hidden = detailsHidden, label = "details",
                        onDrag = { pane.set("wide.details", PaneMath.dragged(d, -it, max = total - 2 * PaneMath.DIVIDER - PaneMath.MIN_CHART - c)) },
                        onToggle = { pane.toggle("wide.details") }, modifier = Modifier.testTag("divider-details"),
                        onDragging = { pane.dragging = it },
                    )
                    if (paneOpen(detailsHidden, shownD)) Column(Modifier.width(shownD.dp).pane("details")) { details(false, null, showOnChart) }
                }
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize().testTag("markets-medium")) {
                val totalW = maxWidth.value
                val totalH = maxHeight.value
                val coinsHidden = pane.isHidden("medium.coins")
                val detailsHidden = pane.isHidden("medium.details")
                val c = PaneMath.fitOne(totalW, pane.size("medium.coins", 220f), coinsHidden, PaneMath.MIN_SIDE, PaneMath.MIN_CHART, PaneMath.DIVIDER)
                val h = PaneMath.fitOne(totalH, pane.size("medium.details", 190f), detailsHidden, PaneMath.MIN_PANE_HEIGHT, PaneMath.MIN_CHART_HEIGHT, PaneMath.DIVIDER)
                val shownC = animatedPaneSize(c, pane.dragging)
                val shownH = animatedPaneSize(h, pane.dragging)
                Row(Modifier.fillMaxSize()) {
                    if (paneOpen(coinsHidden, shownC)) Column(Modifier.width(shownC.dp).pane("coins")) { list(false) }
                    PaneDivider(
                        vertical = true, hidden = coinsHidden, label = "coins",
                        onDrag = { pane.set("medium.coins", PaneMath.dragged(c, it, max = totalW - PaneMath.DIVIDER - PaneMath.MIN_CHART)) },
                        onToggle = { pane.toggle("medium.coins") }, modifier = Modifier.testTag("divider-coins"),
                        onDragging = { pane.dragging = it },
                    )
                    Column(Modifier.weight(1f)) {
                        Column(Modifier.weight(1f).pane("chart")) { chartPane(false, null) }
                        PaneDivider(
                            vertical = false, hidden = detailsHidden, label = "details",
                            onDrag = {
                                pane.set("medium.details", PaneMath.dragged(h, -it, max = totalH - PaneMath.DIVIDER - PaneMath.MIN_CHART_HEIGHT, min = PaneMath.MIN_PANE_HEIGHT))
                            },
                            onToggle = { pane.toggle("medium.details") }, modifier = Modifier.testTag("divider-details"),
                            onDragging = { pane.dragging = it },
                        )
                        if (paneOpen(detailsHidden, shownH)) Column(Modifier.height(shownH.dp).pane("details")) { details(false, null, showOnChart) }
                    }
                }
            }
        }
    }
}

/** A thin rule down the left edge of what it holds: the edge of a panel that slides over another. */
@Composable
private fun VRuleLeft(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        VRule()
        Column(Modifier.weight(1f)) { content() }
    }
}

/** The top of a panel that slides in: its name, how many things are in it, and a cross to close it. */
@Composable
private fun PanelHeader(title: String, count: Int, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp).testTag("panel-header"), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.BodyStrong)
        Spacer(Modifier.width(8.dp))
        CountBadge(count)
        Spacer(Modifier.weight(1f))
        IconAction(Glyphs.Close, "Close", onClose)
    }
}

/**
 * The line over the coins: which list is being watched (or how many), whether scanning runs, and on charts how many coins and which
 * chart sizes. The bell opens Alerts. [compact] is the narrow panel of a phone held sideways: the name and the dot only.
 */
@Composable
private fun WatchHeader(lists: List<ListUi>, coinCount: Int, scanning: Boolean, onOpenAlerts: () -> Unit, compact: Boolean) {
    val active = lists.filter { it.active }
    val name = when (active.size) {
        0 -> "Coins"
        1 -> active[0].name
        else -> "${active.size} lists"
    }
    val charts = active.flatMap { it.timeframes }.distinct().sortedBy { CHART_ORDER.indexOf(it) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp).testTag("watch-header"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                ScanDot(scanning, withWord = !compact)
            }
            if (!compact) Text("$coinCount ${if (coinCount == 1) "coin" else "coins"}" + charts.joinToString("") { " · $it" }, style = Type.Small, maxLines = 1)
        }
        IconAction(Glyphs.Bell, "Alerts", onOpenAlerts, tint = Palette.Muted)
    }
}

/** A green dot (and "Watching") while background scanning is on; a hollow one (and "Paused") when it is off. */
@Composable
private fun ScanDot(scanning: Boolean, withWord: Boolean) {
    val word = if (scanning) "Watching" else "Paused"
    Row(Modifier.semantics { contentDescription = word }, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) {
            if (scanning) drawCircle(Palette.Up) else drawCircle(Palette.Faint, style = Stroke(1.5.dp.toPx()))
        }
        if (withWord) Text(word, style = Type.Small.copy(color = if (scanning) Palette.Up else Palette.Muted), modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun CoinList(
    coins: List<CoinUi>,
    prices: Map<String, Double>,
    selected: String,
    onSelect: (CoinUi) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    header: @Composable () -> Unit,
) {
    LazyColumn(modifier.testTag("coin-list")) {
        item(key = "header") {
            header()
            if (!compact) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Coin", style = Type.Label, modifier = Modifier.weight(1f))
                    Text("24h", style = Type.Label, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("Price", style = Type.Label, modifier = Modifier.width(96.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            HRule()
        }
        items(coins, key = { it.symbol }) { c ->
            val price = prices[c.symbol] ?: c.price
            TouchRow(
                { onSelect(c) }, selected = c.symbol == selected, minHeight = if (compact) 47.dp else RowHeight,
                modifier = Modifier.testTag("coin-${c.symbol}"),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.base, style = if (compact) Type.BodyStrong.copy(fontSize = 16.sp) else Type.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (c.openTrades > 0) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.Accent))
                            Spacer(Modifier.width(6.dp))
                            Text("${c.openTrades} open", style = Type.Small, maxLines = 1)
                        } else {
                            Text(c.timeframes.joinToString(" "), style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (!compact) {
                    Sparkline(c.spark, Fmt.changeColor(c.changeFraction), Modifier.padding(horizontal = 6.dp).size(64.dp, 22.dp))
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 6.dp)) {
                    Text(Fmt.price(price), style = Type.NumberStrong)
                    Spacer(Modifier.height(3.dp))
                    ChangePill(c.changeFraction, small = compact)
                }
            }
            HRule()
        }
    }
}

/** A day of closes as a thin line, in the colour of the day's change. Nothing is drawn with fewer than three points. */
@Composable
private fun Sparkline(points: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (points.size < 3) return@Canvas
        val lo = points.min()
        val hi = points.max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val pad = 2.dp.toPx()
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = size.width * i / (points.size - 1)
            val y = pad + (size.height - 2 * pad) * (1 - ((p - lo) / span)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun ChartPane(
    coin: CoinUi,
    tf: String?,
    chart: ChartUi?,
    live: Double?,
    open: List<TradeUi>,
    chosenTrade: Long?,
    onChooseTrade: (Long?) -> Unit,
    onChoose: (String) -> Unit,
    indicators: List<String>,
    onIndicators: (List<String>) -> Unit,
    note: String?,
    unwatched: Boolean,
    onOpenLists: () -> Unit,
    control: ChartControl,
    sideways: Boolean,
    onOpenTrades: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val price = live ?: coin.price
    // Held sideways the header is one row, so its buttons sit closer; each is still 48 dp tall to touch.
    val toolWidth = if (sideways) 36.dp else MinTouch
    val tools = @Composable {
        IconAction(Glyphs.Indicators, "Indicators", { control.indicators() }, Modifier.width(toolWidth))
        IconAction(Glyphs.Draw, "Draw", { control.draw() }, Modifier.width(toolWidth))
        IconAction(Glyphs.Latest, "Latest candle", { control.latest() }, Modifier.width(toolWidth))
    }
    Column(modifier) {
        if (sideways) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(coin.base, style = Type.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(Fmt.price(price), style = Type.Kpi, modifier = Modifier.testTag("live-price"))
                Spacer(Modifier.width(6.dp))
                ChangePill(coin.changeFraction, small = true)
                Spacer(Modifier.weight(1f))
                Segmented(coin.timeframes, tf, onChoose, minItem = 40.dp)
                tools()
                if (onOpenTrades != null && open.isNotEmpty()) TonalButton("${open.size} trades", onOpenTrades, Modifier.padding(start = 2.dp).testTag("open-trades"))
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(coin.base, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                Text(Fmt.price(price), style = Type.Big, modifier = Modifier.testTag("live-price"))
                Spacer(Modifier.width(10.dp))
                ChangePill(coin.changeFraction)
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Segmented(coin.timeframes, tf, onChoose)
                Spacer(Modifier.weight(1f))
                tools()
            }
        }
        CoinNote(note, unwatched, onOpenLists)
        HRule()
        val (ids, at) = levelTrades(chart, chosenTrade)
        val shown = ids.getOrNull(at)
        when {
            tf == null -> EmptyState("No chart chosen", "This coin is not watched on any chart.")
            chart == null -> Text("Loading…", style = Type.Small, modifier = Modifier.padding(16.dp))
            chart.candles.isEmpty() -> EmptyState(
                "No candles yet on the ${Fmt.chartName(tf)} chart",
                "The history for this chart is still downloading, or Binance has none for this coin yet.",
            )
            else -> ChartView(chart.copy(levels = levelsOf(chart, shown)), live, Modifier.weight(1f), indicators, onIndicators, control = control)
        }
        if (chart != null && tf != null && ids.isNotEmpty()) {
            HRule()
            val trade = open.firstOrNull { it.id == shown }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag("level-strip"), verticalAlignment = Alignment.CenterVertically) {
                Text("Levels", style = Type.Small)
                Spacer(Modifier.width(8.dp))
                ChartTag(tf)
                Spacer(Modifier.width(8.dp))
                Text(trade?.short ?: "Open trade", style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (ids.size > 1) {
                    Text("${at + 1} of ${ids.size}", style = Type.Small.copy(fontFeatureSettings = "tnum"), modifier = Modifier.testTag("level-count"))
                    IconAction(Glyphs.ChevronLeft, "Previous trade", { onChooseTrade(ids[(at - 1 + ids.size) % ids.size]) })
                    IconAction(Glyphs.ChevronRight, "Next trade", { onChooseTrade(ids[(at + 1) % ids.size]) })
                }
            }
        }
    }
}

/** The coin, its price and its day's change, over its Details on a phone held upright, where the chart is on another tab. */
@Composable
private fun CoinStrip(coin: CoinUi, live: Double?) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp).testTag("coin-strip"), verticalAlignment = Alignment.CenterVertically) {
        Text(coin.base, style = Type.BodyStrong)
        Spacer(Modifier.weight(1f))
        Text(Fmt.price(live ?: coin.price), style = Type.Number)
        Spacer(Modifier.width(10.dp))
        ChangePill(coin.changeFraction, small = true)
    }
    HRule()
}

@Composable
private fun Details(
    coin: CoinUi,
    live: Double?,
    trades: List<TradeUi>,
    warnings: List<AlertUi>,
    onOpenLearn: (String) -> Unit,
    onShowOnChart: (TradeUi) -> Unit,
    note: String?,
    unwatched: Boolean,
    onOpenLists: () -> Unit,
    modifier: Modifier = Modifier,
    strip: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    val open = trades.filter { it.closed == null }
    val recent = trades.filter { it.closed != null }.take(5)
    Column(modifier.verticalScroll(rememberScrollState()).testTag("details")) {
        header?.invoke()
        if (strip) CoinStrip(coin, live)
        CoinNote(note, unwatched, onOpenLists)
        SectionLabel("Open paper trades", count = open.size)
        if (open.isEmpty()) Text("None on ${coin.base} right now.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        for (t in open) {
            OpenTradeCard(t, live, { onShowOnChart(t) }, { onOpenLearn(t.variant) })
            Spacer(Modifier.height(10.dp))
        }
        SectionLabel("Recent results")
        if (recent.isEmpty()) Text("No closed trades on ${coin.base} yet.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        for (t in recent) {
            val c = t.closed!!
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ChartTag(t.timeframe)
                Text(t.short, style = Type.Body, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Fmt.signedPercent(c.net), style = Type.NumberStrong.copy(color = Fmt.changeColor(c.net)))
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
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * An open trade: its chart size, its pattern and how it stands now; a bar from its stop to its target with a tick where it was entered
 * and a dot at the price now; the three prices; and the way to its chart and its explanation.
 */
@Composable
private fun OpenTradeCard(t: TradeUi, live: Double?, onShowOnChart: () -> Unit, onAbout: () -> Unit) {
    val now = live?.let { it / t.entryPrice - 1 }
    RaisedGroup(Modifier.testTag("open-${t.id}")) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChartTag(t.timeframe)
                Text(t.short, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(Fmt.signedPercent(now), style = Type.NumberStrong.copy(color = Fmt.changeColor(now), fontSize = 17.sp))
            }
            val stop = t.stop
            val target = t.target
            when {
                t.exitMode == "trail" && stop != null -> {
                    // A stop that follows the price up has no target: the bar runs as far above the entry as the stop is below it, and fades.
                    val top = t.entryPrice + (t.entryPrice - stop)
                    RangeBar(0.5f, rangeFraction(stop, top, live), Fmt.changeColor(now), Modifier.padding(top = 14.dp, bottom = 4.dp), openEnded = true)
                    PriceTrio("Safety stop", Fmt.price(stop), "Entry", Fmt.price(t.entryPrice), "Then", "trails up")
                }
                stop != null && target != null -> {
                    RangeBar(rangeFraction(stop, target, t.entryPrice) ?: 0.5f, rangeFraction(stop, target, live), Fmt.changeColor(now), Modifier.padding(top = 14.dp, bottom = 4.dp))
                    PriceTrio("Stop", Fmt.price(stop), "Entry", Fmt.price(t.entryPrice), "Target", Fmt.price(target))
                }
                else -> {
                    Text("Entry ${Fmt.price(t.entryPrice)}", style = Type.Number, modifier = Modifier.padding(top = 8.dp))
                    Text(exitText(t), style = Type.Small)
                }
            }
        }
        Row(Modifier.padding(start = 2.dp, bottom = 2.dp)) {
            TextAction("Show on chart", onShowOnChart)
            TextAction("About this pattern", onAbout)
        }
    }
}

/** Three prices side by side, each under its name: start, middle and end. */
@Composable
private fun PriceTrio(a: String, av: String, b: String, bv: String, c: String, cv: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Stat(a, av, Modifier.weight(1f))
        Stat(b, bv, Modifier.weight(1f), align = Alignment.CenterHorizontally)
        Stat(c, cv, Modifier.weight(1f), align = Alignment.End)
    }
}

/** How an open trade will end, in a sentence. */
fun exitText(t: TradeUi): String = when (t.exitMode) {
    "trail" -> "Safety stop ${Fmt.price(t.stop)}, then a stop that follows the price up."
    "learned" -> if (t.target != null) "Target ${Fmt.price(t.target)}, stop ${Fmt.price(t.stop)}." else "Held for a fixed time."
    "held" -> "Held for a fixed time."
    else -> if (t.target != null && t.stop != null) "Target ${Fmt.price(t.target)}, stop ${Fmt.price(t.stop)}." else "Held for a fixed time."
}
