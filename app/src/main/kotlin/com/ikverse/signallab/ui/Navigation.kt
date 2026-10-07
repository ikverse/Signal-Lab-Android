package com.ikverse.signallab.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Where in the app the user is. Eight places; the side rail shows all of them and the bottom bar scrolls sideways through all of them. */
enum class Dest(val label: String) {
    Markets("Markets"), Trades("Trades"), Scorecard("Scorecard"), Analyst("Analyst"), Alerts("Alerts"), Learn("Learn"), Lists("Lists"), Settings("Settings")
}

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
) {
    var dest by mutableStateOf(dest)
    var symbol by mutableStateOf(symbol)
    var timeframe by mutableStateOf(timeframe)
    var learnPage by mutableStateOf(learnPage)
    var marketsTab by mutableStateOf(marketsTab)
    var showDebug by mutableStateOf(showDebug)

    /** The Trades tab's filter, search and opened trade: kept here so a link can set them and they survive leaving the tab. */
    var tradesStatus by mutableStateOf(tradesStatus)
    var tradesQuery by mutableStateOf(tradesQuery)
    var tradesExpanded by mutableStateOf(tradesExpanded)
    var alertsGroup by mutableStateOf(alertsGroup)

    /** The Analyst report on show; null for the questions and the list of reports. */
    var analystReport by mutableStateOf(analystReport)

    var trail by mutableStateOf(trail)
        private set

    /** True on a phone held upright. Set by the screen each time it is laid out; not saved. */
    var narrow by mutableStateOf(true)

    /** Moves to [to]. A jump made inside the app leaves a mark to come back to; one from outside (a notification) starts a fresh trail. */
    private fun jump(to: Dest, fromApp: Boolean) {
        trail = if (fromApp && dest != to) (trail + dest).takeLast(MAX_TRAIL) else if (fromApp) trail else emptyList()
        dest = to
    }

    /** Opens a coin on one of its charts, or on its Details. */
    fun openCoin(symbol: String, timeframe: String? = null, tab: MarketsTab = MarketsTab.Chart, fromApp: Boolean = false) {
        jump(Dest.Markets, fromApp)
        this.symbol = symbol
        this.timeframe = timeframe
        marketsTab = tab
    }

    fun openLearn(page: String?, fromApp: Boolean = true) {
        jump(Dest.Learn, fromApp)
        learnPage = page
    }

    /** Opens the Trades tab with a coin to filter by (by its short name), a status, and a trade to open. */
    fun openTrades(coin: String?, status: TradeFilter, expanded: Long?, fromApp: Boolean = false) {
        jump(Dest.Trades, fromApp)
        tradesQuery = coin?.removeSuffix("USDT") ?: ""
        tradesStatus = status
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

    /** Back: out of the debug page, then along the trail of jumps, then one step towards Markets. Returns false when there is nothing to step out of. */
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
        dest != Dest.Markets -> { dest = Dest.Markets; true }
        else -> false
    }

    companion object {
        private const val MAX_TRAIL = 6

        val Saver: Saver<NavState, Any> = mapSaver(
            save = {
                mapOf(
                    "dest" to it.dest.name, "symbol" to it.symbol, "tf" to it.timeframe, "learn" to it.learnPage, "tab" to it.marketsTab.name,
                    "debug" to it.showDebug, "ts" to it.tradesStatus.name, "tq" to it.tradesQuery, "te" to it.tradesExpanded,
                    "ag" to it.alertsGroup.name, "trail" to it.trail.joinToString(",") { d -> d.name }, "ar" to it.analystReport,
                )
            },
            restore = {
                NavState(
                    Dest.valueOf(it["dest"] as String), it["symbol"] as String?, it["tf"] as String?, it["learn"] as String?,
                    MarketsTab.valueOf(it["tab"] as String), it["debug"] as Boolean,
                    TradeFilter.valueOf(it["ts"] as String), it["tq"] as String, it["te"] as Long?, AlertGroup.valueOf(it["ag"] as String),
                    (it["trail"] as String).split(',').filter { s -> s.isNotEmpty() }.map { s -> Dest.valueOf(s) },
                    it["ar"] as Long?,
                )
            },
        )
    }
}

@Composable
fun rememberNavState(): NavState = rememberSaveable(saver = NavState.Saver) { NavState() }
