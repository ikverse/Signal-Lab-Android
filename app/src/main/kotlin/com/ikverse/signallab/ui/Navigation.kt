package com.ikverse.signallab.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Where in the app the user is. Eight places, and More, the page that leads to the four used least. */
enum class Dest(val label: String) {
    Markets("Markets"), Trades("Trades"), Scorecard("Scorecard"), Analyst("Analyst"), Alerts("Alerts"), Learn("Learn"), Lists("Lists"), Settings("Settings"),
    More("More"),
}

/** The places in the bar along the bottom (and the side bar): the four used most, then More. */
val BarPlaces = listOf(Dest.Markets, Dest.Trades, Dest.Scorecard, Dest.Analyst, Dest.More)

/** The places reached through More. */
val MorePlaces = listOf(Dest.Alerts, Dest.Learn, Dest.Lists, Dest.Settings)

/** The bar's place that stands for [dest]: itself, or More for the places behind it. */
fun barPlaceOf(dest: Dest): Dest = if (dest in MorePlaces) Dest.More else dest

/**
 * Whether to hide the status bar: on a phone held sideways (wider than tall, and under 480 dp tall), where every line of height
 * counts. A tablet held sideways keeps it.
 */
fun hideStatusBar(widthDp: Int, heightDp: Int): Boolean = widthDp > heightDp && heightDp < 480

/** How much room there is. Decided by the window's width, never by the phone's model, so rotating or resizing just switches layouts. */
enum class LayoutClass {
    /** A phone held upright: one panel at a time, a bottom bar. */
    Compact,

    /** A small phone held sideways: a side rail and two panels. */
    Medium,

    /** A phone held sideways, a tablet or a monitor: a side rail and three panels. */
    Wide;

    companion object {
        fun of(widthDp: Float): LayoutClass = when {
            widthDp >= 820f -> Wide
            widthDp >= 560f -> Medium
            else -> Compact
        }
    }
}

/** Which panel shows on a narrow Markets screen. */
enum class MarketsTab(val label: String) { Coins("Coins"), Chart("Chart"), Details("Details") }

/**
 * Where the user is and what they were looking at: kept across rotation and a restart of the screen. Opening a notification (or a link)
 * moves it to where the link leads.
 *
 * [trail] is the tabs the user jumped away from, most recent last, so Back from a jump (Show on chart, What is this pattern?) returns to
 * the tab it left. Tapping a tab in the bar forgets the trail. [narrow] says whether the screen is a phone held upright; what only a narrow
 * screen shows (the Coins/Chart/Details tabs, the Learn page opening over its list) is never counted as something Back has to step out of
 * on a wide one, where it would be an invisible press.
 */
class NavState(
    dest: Dest = Dest.Markets,
    symbol: String? = null,
    timeframe: String? = null,
    learnPage: String? = null,
    marketsTab: MarketsTab = MarketsTab.Coins,
    showDebug: Boolean = false,
    tradesStatus: TradeFilter = TradeFilter.All,
    tradesQuery: String = "",
    tradesExpanded: Long? = null,
    alertsGroup: AlertGroup = AlertGroup.All,
    trail: List<Dest> = emptyList(),
    analystReport: Long? = null,
    chartChoice: Map<Long, Boolean> = emptyMap(),
    tradesTimeframe: String? = null,
) {
    var dest by mutableStateOf(dest)
    var symbol by mutableStateOf(symbol)
    var timeframe by mutableStateOf(timeframe)
    var learnPage by mutableStateOf(learnPage)
    var marketsTab by mutableStateOf(marketsTab)
    var showDebug by mutableStateOf(showDebug)

    /** The Trades tab's filters, search and opened trade: kept here so a link can set them and they survive leaving the tab. */
    var tradesStatus by mutableStateOf(tradesStatus)
    var tradesQuery by mutableStateOf(tradesQuery)

    /** The chart size the Trades tab is narrowed to, such as "4h"; null for any. */
    var tradesTimeframe by mutableStateOf(tradesTimeframe)
    var tradesExpanded by mutableStateOf(tradesExpanded)
    var alertsGroup by mutableStateOf(alertsGroup)

    /** The Analyst report on show; null for the questions and the list of reports. */
    var analystReport by mutableStateOf(analystReport)

    /**
     * Which open trades the user switched on or off on the chart, by trade id. A trade not in here has not been touched: it is on if it is
     * the newest open trade of its chart size, off otherwise.
     */
    var chartChoice by mutableStateOf(chartChoice)
        private set

    /** Puts one trade's levels on the chart, or takes them off. */
    fun chooseChartTrade(id: Long, on: Boolean) {
        chartChoice = ((chartChoice - id) + (id to on)).entries.toList().takeLast(MAX_CHOICES).associate { it.key to it.value }
    }

    var trail by mutableStateOf(trail)
        private set

    /** True on a phone held upright. Set by the screen each time it is laid out; not saved. */
    var narrow by mutableStateOf(true)

    /** Moves to [to]. A jump made inside the app leaves a mark to come back to; one from outside (a notification) starts a fresh trail. */
    private fun jump(to: Dest, fromApp: Boolean) {
        trail = if (fromApp && dest != to) (trail + dest).takeLast(MAX_TRAIL) else if (fromApp) trail else emptyList()
        dest = to
    }

    /** Opens a coin on one of its charts, or on its Details; with [trade], that trade's levels are put on the chart. */
    fun openCoin(symbol: String, timeframe: String? = null, tab: MarketsTab = MarketsTab.Chart, fromApp: Boolean = false, trade: Long? = null) {
        jump(Dest.Markets, fromApp)
        this.symbol = symbol
        this.timeframe = timeframe
        marketsTab = tab
        if (trade != null) chooseChartTrade(trade, true)
    }

    fun openLearn(page: String?, fromApp: Boolean = true) {
        jump(Dest.Learn, fromApp)
        learnPage = page
    }

    /** Opens the Trades tab with a coin to filter by (by its short name), a status, and a trade to open; on any chart size, so that trade is not hidden. */
    fun openTrades(coin: String?, status: TradeFilter, expanded: Long?, fromApp: Boolean = false) {
        jump(Dest.Trades, fromApp)
        tradesQuery = coin?.removeSuffix("USDT") ?: ""
        tradesStatus = status
        tradesTimeframe = null
        tradesExpanded = expanded
    }

    fun openAlerts(group: AlertGroup, fromApp: Boolean = false) {
        jump(Dest.Alerts, fromApp)
        alertsGroup = group
    }

    /** Opens the Analyst, on [report] when there is one to show. */
    fun openAnalyst(report: Long?, fromApp: Boolean = false) {
        jump(Dest.Analyst, fromApp)
        analystReport = report
    }

    /** Goes to wherever [link] leads. */
    fun openLink(link: Link, fromApp: Boolean) {
        when (link.place) {
            LinkPlace.CHART, LinkPlace.DETAILS -> {
                val symbol = link.symbol ?: return
                openCoin(symbol, link.timeframe, if (link.place == LinkPlace.DETAILS) MarketsTab.Details else MarketsTab.Chart, fromApp)
            }
            LinkPlace.TRADES -> openTrades(
                link.symbol, TradeFilter.entries.firstOrNull { it.name.equals(link.status, ignoreCase = true) } ?: TradeFilter.All, link.tradeId, fromApp,
            )
            LinkPlace.ALERTS -> openAlerts(AlertGroup.entries.firstOrNull { it.name.equals(link.group, ignoreCase = true) } ?: AlertGroup.All, fromApp)
            LinkPlace.SETTINGS -> jump(Dest.Settings, fromApp)
            LinkPlace.ANALYST -> openAnalyst(link.report, fromApp)
        }
    }

    /** A place named inside a Learn page ("settings", "alerts"…): opened as a jump, so Back returns to the page. */
    fun openPlace(name: String): Boolean {
        val to = Dest.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
        jump(to, fromApp = true)
        return true
    }

    /** A tab chosen from the bar: no trail to go back along. */
    fun go(to: Dest) {
        dest = to
        trail = emptyList()
    }

    /** True while [back] has somewhere to go, so the system back button is only taken over when it matters. */
    val canBack: Boolean
        get() = showDebug || trail.isNotEmpty() ||
            (narrow && dest == Dest.Learn && learnPage != null) || (narrow && dest == Dest.Analyst && analystReport != null) ||
            (narrow && dest == Dest.Markets && marketsTab != MarketsTab.Coins) || dest != Dest.Markets

    /**
     * Back: out of the debug page, then along the trail of jumps, then out of a page within a place, then one step towards Markets (through
     * More for the places behind it). Returns false when there is nothing to step out of.
     */
    fun back(): Boolean = when {
        showDebug -> { showDebug = false; true }
        trail.isNotEmpty() -> {
            val to = trail.last()
            trail = trail.dropLast(1)
            // Leaving Learn or the Analyst behind: its page is closed, so it opens on its list next time.
            if (dest == Dest.Learn) learnPage = null
            if (dest == Dest.Analyst) analystReport = null
            dest = to
            true
        }
        narrow && dest == Dest.Learn && learnPage != null -> { learnPage = null; true }
        narrow && dest == Dest.Analyst && analystReport != null -> { analystReport = null; true }
        narrow && dest == Dest.Markets && marketsTab != MarketsTab.Coins -> { marketsTab = MarketsTab.Coins; true }
        // A place behind More steps back to More, which steps back to Markets like any other place.
        dest in MorePlaces -> { dest = Dest.More; true }
        dest != Dest.Markets -> { dest = Dest.Markets; true }
        else -> false
    }

    companion object {
        private const val MAX_TRAIL = 6

        /** How many of the user's on/off choices for trades are kept; the oldest go first. */
        private const val MAX_CHOICES = 200

        val Saver: Saver<NavState, Any> = mapSaver(
            save = {
                mapOf(
                    "dest" to it.dest.name, "symbol" to it.symbol, "tf" to it.timeframe, "learn" to it.learnPage, "tab" to it.marketsTab.name,
                    "debug" to it.showDebug, "ts" to it.tradesStatus.name, "tq" to it.tradesQuery, "te" to it.tradesExpanded,
                    "ag" to it.alertsGroup.name, "trail" to it.trail.joinToString(",") { d -> d.name }, "ar" to it.analystReport,
                    "cts" to it.chartChoice.entries.joinToString(",") { (id, on) -> "$id:${if (on) 1 else 0}" },
                    "tt" to it.tradesTimeframe,
                )
            },
            restore = {
                NavState(
                    Dest.valueOf(it["dest"] as String), it["symbol"] as String?, it["tf"] as String?, it["learn"] as String?,
                    MarketsTab.valueOf(it["tab"] as String), it["debug"] as Boolean,
                    TradeFilter.valueOf(it["ts"] as String), it["tq"] as String, it["te"] as Long?, AlertGroup.valueOf(it["ag"] as String),
                    (it["trail"] as String).split(',').filter { s -> s.isNotEmpty() }.map { s -> Dest.valueOf(s) },
                    it["ar"] as Long?,
                    (it["cts"] as String).split(',').filter { s -> s.isNotEmpty() }.associate { s -> s.substringBefore(':').toLong() to (s.substringAfter(':') == "1") },
                    it["tt"] as String?,
                )
            },
        )
    }
}

@Composable
fun rememberNavState(): NavState = rememberSaveable(saver = NavState.Saver) { NavState() }
