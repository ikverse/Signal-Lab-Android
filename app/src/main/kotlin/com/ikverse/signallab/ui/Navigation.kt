package com.ikverse.signallab.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Where in the app the user is. Seven places; the side rail shows all of them, the bottom bar shows four and a "More". */
enum class Dest(val label: String) {
    Markets("Markets"), Trades("Trades"), Scorecard("Scorecard"), Alerts("Alerts"), Learn("Learn"), Lists("Lists"), Settings("Settings");

    companion object {
        /** What the bottom bar of a narrow screen carries; the rest sit behind "More". */
        val Primary = listOf(Markets, Trades, Scorecard, Alerts)
        val More = listOf(Learn, Lists, Settings)
    }
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
 * moves it to that coin and chart.
 */
class NavState(
    dest: Dest = Dest.Markets,
    symbol: String? = null,
    timeframe: String? = null,
    learnPage: String? = null,
    marketsTab: MarketsTab = MarketsTab.Coins,
    showMore: Boolean = false,
    showDebug: Boolean = false,
) {
    var dest by mutableStateOf(dest)
    var symbol by mutableStateOf(symbol)
    var timeframe by mutableStateOf(timeframe)
    var learnPage by mutableStateOf(learnPage)
    var marketsTab by mutableStateOf(marketsTab)
    var showMore by mutableStateOf(showMore)
    var showDebug by mutableStateOf(showDebug)

    /** Opens a coin on one of its charts. */
    fun openCoin(symbol: String, timeframe: String? = null) {
        dest = Dest.Markets
        this.symbol = symbol
        this.timeframe = timeframe
        marketsTab = MarketsTab.Chart
        showMore = false
    }

    fun openLearn(page: String?) {
        dest = Dest.Learn
        learnPage = page
        showMore = false
    }

    fun go(to: Dest) {
        dest = to
        showMore = false
    }

    /** True while [back] has somewhere to go, so the system back button is only taken over when it matters. */
    val canBack: Boolean
        get() = showDebug || showMore || (dest == Dest.Learn && learnPage != null) || (dest == Dest.Markets && marketsTab != MarketsTab.Coins) || dest != Dest.Markets

    /** Back: one step towards Markets. Returns false when already there and there is nothing to step out of. */
    fun back(): Boolean = when {
        showDebug -> { showDebug = false; true }
        showMore -> { showMore = false; true }
        dest == Dest.Learn && learnPage != null -> { learnPage = null; true }
        dest == Dest.Markets && marketsTab != MarketsTab.Coins -> { marketsTab = MarketsTab.Coins; true }
        dest != Dest.Markets -> { dest = Dest.Markets; true }
        else -> false
    }

    companion object {
        val Saver: Saver<NavState, Any> = mapSaver(
            save = { mapOf("dest" to it.dest.name, "symbol" to it.symbol, "tf" to it.timeframe, "learn" to it.learnPage, "tab" to it.marketsTab.name, "more" to it.showMore, "debug" to it.showDebug) },
            restore = {
                NavState(
                    Dest.valueOf(it["dest"] as String), it["symbol"] as String?, it["tf"] as String?, it["learn"] as String?,
                    MarketsTab.valueOf(it["tab"] as String), it["more"] as Boolean, it["debug"] as Boolean,
                )
            },
        )
    }
}

@Composable
fun rememberNavState(): NavState = rememberSaveable(saver = NavState.Saver) { NavState() }
