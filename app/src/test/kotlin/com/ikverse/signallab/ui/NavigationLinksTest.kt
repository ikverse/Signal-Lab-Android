package com.ikverse.signallab.ui

import com.ikverse.signallab.data.Alert
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.scan.AlertText
import com.ikverse.signallab.scan.Notifier
import com.ikverse.signallab.state.LearnIndex
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where each link leads, how Back unwinds a jump, and the words and numbers around them. All plain logic. */
class NavigationLinksTest {
    // --- the addresses alerts carry

    @Test
    fun `every kind of address is read, and anything else is not a link`() {
        assertEquals(Link("SOLUSDT", "1h"), parseLink("signallab://coin/SOLUSDT?tf=1h"))
        assertEquals(Link("SOLUSDT", "1h", LinkPlace.DETAILS), parseLink("signallab://coin/SOLUSDT?tf=1h&show=details"))
        assertEquals(Link("SOLUSDT", null, LinkPlace.DETAILS), parseLink("signallab://coin/SOLUSDT?show=details"))
        assertEquals(Link("SOLUSDT", "4h", LinkPlace.CHART), parseLink("signallab://coin/SOLUSDT?show=chart&tf=4h"))
        assertEquals(Link("BTCUSDT", null, LinkPlace.TRADES, 12, "closed"), parseLink("signallab://trades?coin=BTCUSDT&id=12&status=closed"))
        assertEquals(Link(null, null, LinkPlace.TRADES, null, "open"), parseLink("signallab://trades?status=open"))
        assertEquals(Link(null, null, LinkPlace.TRADES), parseLink("signallab://trades"))
        assertEquals(Link(null, null, LinkPlace.ALERTS, group = "problems"), parseLink("signallab://alerts?group=problems"))
        assertEquals(Link(null, null, LinkPlace.SETTINGS), parseLink("signallab://settings"))
        assertEquals(Link(null, null, LinkPlace.ANALYST, report = 3), parseLink("signallab://analyst?report=3"))
        assertEquals(Link(null, null, LinkPlace.ANALYST), parseLink("signallab://analyst"))
        for (bad in listOf(null, "", "signallab://", "signallab://coin/", "signallab://coin/?tf=1h", "signallab://nowhere", "https://example.com/trades", "signallab://settingsx")) {
            assertNull(parseLink(bad), "for [$bad]")
        }
        assertEquals(Link(null, null, LinkPlace.TRADES, null, null), parseLink("signallab://trades?id=notanumber"), "an id that is not a number is ignored")
    }

    @Test
    fun `the addresses the scanner writes are the ones the screens read`() {
        assertEquals(Link("BTCUSDT", "1h", LinkPlace.CHART), parseLink(AlertText.link("BTCUSDT", Timeframe.H1)))
        assertEquals(Link("BTCUSDT", "5m", LinkPlace.DETAILS), parseLink(AlertText.detailsLink("BTCUSDT", Timeframe.M5)))
        assertEquals(Link("BTCUSDT", null, LinkPlace.TRADES, 7, null), parseLink(AlertText.tradesLink("BTCUSDT", 7)))
        assertEquals(Link("BTCUSDT", null, LinkPlace.TRADES), parseLink(AlertText.tradesLink("BTCUSDT")))
        assertEquals(Link(null, null, LinkPlace.TRADES, null, "closed"), parseLink(AlertText.tradesLink(status = "closed")))
        assertEquals(Link(null, null, LinkPlace.ALERTS, group = "problems"), parseLink(AlertText.PROBLEMS_LINK))
        assertEquals(Link(null, null, LinkPlace.SETTINGS), parseLink(AlertText.SETTINGS_LINK))
    }

    @Test
    fun `each alert leads where its content is shown`() {
        val tf = Timeframe.H1
        val opened = AlertText.closed("donchian20_1h", "SOLUSDT", tf, ExitReason.TARGET, 0.03, 0.001, tradeId = 42)
        assertEquals(Link("SOLUSDT", null, LinkPlace.TRADES, 42), parseLink(opened.link), "a closed trade: the trade, in Trades")
        assertEquals(Link("SOLUSDT", null, LinkPlace.TRADES), parseLink(AlertText.closed("v", "SOLUSDT", tf, ExitReason.STOP, -0.01, Double.NaN).link), "with no trade id, the coin's trades")
        assertEquals(LinkPlace.DETAILS, parseLink(AlertText.volumeSpike("SOLUSDT", 5.0, false).link)!!.place, "a volume spike: Details, where warnings are")
        assertEquals("1d", parseLink(AlertText.volumeSpike("SOLUSDT", 5.0, false).link)!!.timeframe)
        assertEquals(LinkPlace.SETTINGS, parseLink(AlertText.blocked().link)!!.place, "Binance blocked: the place that says what to do")
        assertEquals(LinkPlace.ALERTS, parseLink(AlertText.unreachable(2, 10, "timeout").link)!!.place)
        assertEquals(LinkPlace.ALERTS, parseLink(AlertText.stalled(Timeframe.H1, 9_000_000).link)!!.place)
        assertEquals(LinkPlace.CHART, parseLink(AlertText.missed("v", "SOLUSDT", tf).link)!!.place)
    }

    @Test
    fun `a grouped notification leads where its parts agree`() {
        fun alert(id: Long, kind: String, link: String?) = Alert(id, 1, kind, "SOLUSDT", "1h", "t", "b", link)
        val closedTwo = Notifier.compose(listOf(alert(1, "exit", AlertText.tradesLink("SOLUSDT", 1)), alert(2, "exit", AlertText.tradesLink("SOLUSDT", 2)))).single()
        assertEquals(AlertText.tradesLink("SOLUSDT"), closedTwo.link, "several closed: the coin's trades, not one trade opened out of them")
        val closedOne = Notifier.compose(listOf(alert(1, "exit", AlertText.tradesLink("SOLUSDT", 1)))).single()
        assertEquals(AlertText.tradesLink("SOLUSDT", 1), closedOne.link)
        val openedTwo = Notifier.compose(listOf(alert(1, "signal", AlertText.link("SOLUSDT", Timeframe.H1)), alert(2, "signal", AlertText.link("SOLUSDT", Timeframe.H1)))).single()
        assertEquals(AlertText.link("SOLUSDT", Timeframe.H1), openedTwo.link, "several opened on one chart: that chart")
    }

    @Test
    fun `an alert row goes where its link goes, else to its coin, else nowhere`() {
        fun row(link: String?, symbol: String?, tf: String? = "1h") = AlertUi(1, 0, "x", "t", "b", symbol, tf, link)
        assertEquals(Link("SOLUSDT", null, LinkPlace.TRADES, 9), alertLink(row(AlertText.tradesLink("SOLUSDT", 9), "SOLUSDT")))
        assertEquals(Link(null, null, LinkPlace.SETTINGS), alertLink(row(AlertText.SETTINGS_LINK, null, null)))
        assertEquals(Link("SOLUSDT", "1h"), alertLink(row(null, "SOLUSDT")), "an older alert with no link opens its coin")
        assertEquals(Link("SOLUSDT", "1h"), alertLink(row("garbage", "SOLUSDT")))
        assertNull(alertLink(row(null, null, null)))
    }

    // --- links inside Learn pages

    @Test
    fun `a link in a Learn page is a page, a place, or nothing`() {
        assertEquals(WebLink.Page("scorecard"), parseWebLink("learn:scorecard"))
        assertEquals(WebLink.Place("settings"), parseWebLink("go:settings"))
        for (bad in listOf("", "learn:", "go:", "https://example.com", "http://x/learn:y", "file:///android_asset/learn/page.html", "javascript:alert(1)")) assertNull(parseWebLink(bad), "for [$bad]")
    }

    // --- notification numbers

    @Test
    fun `no alert notification can share a number with the status, the test, the resume prompt or a summary`() {
        val fixed = listOf(Notifier.STATUS_ID, Notifier.TEST_ID, Notifier.RESUME_ID, Notifier.SUMMARY_SIGNALS, Notifier.SUMMARY_RESULTS)
        assertEquals(fixed.size, fixed.toSet().size, "the fixed numbers are all different")
        assertTrue(fixed.all { it < Notifier.ALERT_ID_BASE })
        val seen = HashSet<Int>()
        for (alertId in 1L..200_000L) {
            val n = Notifier.notificationId(alertId)
            assertTrue(n !in fixed, "alert $alertId got $n")
            assertTrue(seen.add(n), "alert $alertId repeats $n")
        }
        assertEquals(101, Notifier.notificationId(1), "the very first alert, which used to be notification 1")
    }

    // --- the note under a chart

    private val btc = CoinUi("BTCUSDT", "BTC", null, null, 0, listOf("15m", "1h", "4h"))

    @Test
    fun `the note says what is not as asked, and only then`() {
        assertNull(coinNote(btc, false, null, "1h"))
        assertNull(coinNote(btc, false, "1h", "1h"))
        assertEquals("BTC is not watched on the 1-day chart, so this shows the 1-hour chart.", coinNote(btc, false, "1d", "1h"))
        assertEquals("1-minute, 5-minute, 15-minute, 30-minute, 1-hour, 4-hour, 1-day", listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d").joinToString(", ") { Fmt.chartAdjective(it) })
        assertEquals("BTC is not in a list that is switched on, so it is not being watched and its chart may be out of date.", coinNote(btc, true, "1h", "1h"))
        assertTrue(coinNote(btc, true, "1d", "1d")!!.startsWith("BTC is not in a list"), "an unwatched coin says that first")
    }

    @Test
    fun `a coin shown only because it was asked for has its open trades counted and the chart sizes it needs`() {
        fun trade(id: Long, symbol: String, tf: String, closed: Boolean) = FakeApp.trade(id, symbol, tf, net = if (closed) 0.01 else null)
        val trades = listOf(trade(1, "ADAUSDT", "4h", false), trade(2, "ADAUSDT", "1h", true), trade(3, "ADAUSDT", "4h", false), trade(4, "BTCUSDT", "15m", false))
        val ada = unwatchedCoin("ADAUSDT", "1d", trades)
        assertEquals("ADA", ada.base)
        assertEquals(2, ada.openTrades)
        assertEquals(listOf("1h", "4h", "1d"), ada.timeframes, "the size asked for and the ones its trades used, shortest first")
        assertNull(ada.price)
        assertEquals(listOf("1h"), unwatchedCoin("XYZUSDT", null, emptyList()).timeframes, "with nothing to go on, an hour")
        assertEquals(listOf("5m"), unwatchedCoin("XYZUSDT", "5m", emptyList()).timeframes)
    }

    // --- Back and the trail

    @Test
    fun `a link from outside the app starts a fresh trail`() {
        val nav = NavState()
        nav.go(Dest.Alerts)
        nav.openLink(Link("BTCUSDT", "1h"), fromApp = false)
        assertEquals(Dest.Markets, nav.dest)
        assertEquals(emptyList(), nav.trail)
        assertEquals(MarketsTab.Chart, nav.marketsTab)
        assertEquals("BTCUSDT", nav.symbol)
    }

    @Test
    fun `every kind of link sets what it leads to`() {
        val nav = NavState()
        nav.openLink(Link("BTCUSDT", "1h", LinkPlace.DETAILS), fromApp = false)
        assertEquals(Dest.Markets, nav.dest); assertEquals(MarketsTab.Details, nav.marketsTab)
        nav.openLink(Link("ETHUSDT", null, LinkPlace.TRADES, 5, "closed"), fromApp = false)
        assertEquals(Dest.Trades, nav.dest); assertEquals("ETH", nav.tradesQuery); assertEquals(TradeFilter.Closed, nav.tradesStatus); assertEquals(5L, nav.tradesExpanded)
        nav.openLink(Link(null, null, LinkPlace.TRADES, null, "open"), fromApp = false)
        assertEquals("", nav.tradesQuery); assertEquals(TradeFilter.Open, nav.tradesStatus); assertNull(nav.tradesExpanded)
        nav.openLink(Link(null, null, LinkPlace.TRADES), fromApp = false)
        assertEquals(TradeFilter.All, nav.tradesStatus)
        nav.openLink(Link(null, null, LinkPlace.ALERTS, group = "problems"), fromApp = false)
        assertEquals(Dest.Alerts, nav.dest); assertEquals(AlertGroup.Problems, nav.alertsGroup)
        nav.openLink(Link(null, null, LinkPlace.ALERTS, group = "nonsense"), fromApp = false)
        assertEquals(AlertGroup.All, nav.alertsGroup)
        nav.openLink(Link(null, null, LinkPlace.SETTINGS), fromApp = false)
        assertEquals(Dest.Settings, nav.dest)
        nav.go(Dest.Lists)
        nav.openLink(Link(null, null, LinkPlace.CHART), fromApp = false)
        assertEquals(Dest.Lists, nav.dest, "a chart link with no coin goes nowhere")
    }

    @Test
    fun `opening Trades clears the chart size so the trade it names is not hidden`() {
        val nav = NavState(Dest.Trades, tradesTimeframe = "4h")
        nav.openLink(Link("BTCUSDT", null, LinkPlace.TRADES, 2), fromApp = false)
        assertNull(nav.tradesTimeframe)
        nav.tradesTimeframe = "1h"
        nav.openTrades(null, TradeFilter.Open, null, fromApp = true)
        assertNull(nav.tradesTimeframe)
    }

    @Test
    fun `an answer shared back opens its report, and on a phone Back closes the report before leaving`() {
        val nav = NavState()
        nav.go(Dest.Trades)
        nav.openLink(Link(null, place = LinkPlace.ANALYST, report = 3), fromApp = false)
        assertEquals(Dest.Analyst, nav.dest)
        assertEquals(3L, nav.analystReport)
        assertEquals(emptyList(), nav.trail)
        assertTrue(nav.back()); assertEquals(Dest.Analyst, nav.dest); assertNull(nav.analystReport)
        assertTrue(nav.back()); assertEquals(Dest.Markets, nav.dest)

        // A wide screen shows the report beside the list, so there is no report to step out of first.
        val wide = NavState().also { it.narrow = false }
        wide.openAnalyst(4)
        assertTrue(wide.back()); assertEquals(Dest.Markets, wide.dest)

        // The page on how it works, opened from the Analyst, returns to the report that was open.
        val jump = NavState()
        jump.openAnalyst(5)
        jump.openLearn("analyst")
        assertTrue(jump.back()); assertEquals(Dest.Analyst, jump.dest); assertEquals(5L, jump.analystReport)
    }

    @Test
    fun `back from a jump returns to the tab that was left, along the whole trail`() {
        val nav = NavState()
        nav.narrow = false
        nav.go(Dest.Trades)
        nav.openCoin("BTCUSDT", "1h", fromApp = true)       // Trades -> Markets
        assertEquals(listOf(Dest.Trades), nav.trail)
        nav.openLearn("trend")                               // Markets -> Learn
        assertEquals(listOf(Dest.Trades, Dest.Markets), nav.trail)
        assertTrue(nav.canBack)
        assertTrue(nav.back()); assertEquals(Dest.Markets, nav.dest); assertNull(nav.learnPage, "the page is closed behind it")
        assertTrue(nav.back()); assertEquals(Dest.Trades, nav.dest)
        assertTrue(nav.back()); assertEquals(Dest.Markets, nav.dest, "then one step towards Markets, as before")
        assertFalse(nav.canBack)
        assertFalse(nav.back())
    }

    @Test
    fun `jumping to the tab already open adds nothing to the trail, and choosing a tab from the bar forgets it`() {
        val nav = NavState()
        nav.go(Dest.Learn)
        nav.openLearn("trend")
        assertEquals(emptyList(), nav.trail)
        nav.go(Dest.Trades)
        nav.openCoin("BTCUSDT", null, fromApp = true)
        assertEquals(listOf(Dest.Trades), nav.trail)
        nav.go(Dest.Alerts)
        assertEquals(emptyList(), nav.trail)
        assertTrue(nav.back()); assertEquals(Dest.More, nav.dest, "Alerts sits behind More")
        assertTrue(nav.back()); assertEquals(Dest.Markets, nav.dest)
    }

    @Test
    fun `the trail is kept short`() {
        val nav = NavState()
        repeat(20) {
            nav.openLearn(null)
            nav.openCoin("A", null, fromApp = true)
            nav.openTrades(null, TradeFilter.All, null, fromApp = true)
            nav.openAlerts(AlertGroup.All, fromApp = true)
        }
        var steps = 0
        while (nav.trail.isNotEmpty()) { nav.back(); steps++ }
        assertEquals(6, steps, "twenty rounds of jumps, and only the last six places can be gone back through")
    }

    @Test
    fun `on a wide screen the tabs that are not there are never a step`() {
        val nav = NavState()
        nav.narrow = false
        nav.openCoin("BTCUSDT", "1h", fromApp = false)       // a notification: marketsTab becomes Chart, which a wide screen never shows
        assertFalse(nav.canBack, "so there is nothing to step out of")
        assertFalse(nav.back())
        nav.go(Dest.Learn)
        nav.learnPage = "trend"                              // a page open beside its list is the normal state
        assertTrue(nav.canBack, "Learn itself is a place to leave")
        assertTrue(nav.back()); assertEquals(Dest.More, nav.dest); assertEquals("trend", nav.learnPage, "straight out, not to another page")
    }

    @Test
    fun `on a narrow screen those steps are real`() {
        val nav = NavState()
        nav.narrow = true
        nav.openCoin("BTCUSDT", "1h", fromApp = false)
        assertTrue(nav.canBack)
        assertTrue(nav.back()); assertEquals(MarketsTab.Coins, nav.marketsTab)
        assertFalse(nav.canBack)
        nav.go(Dest.Learn); nav.learnPage = "trend"
        assertTrue(nav.back()); assertNull(nav.learnPage); assertEquals(Dest.Learn, nav.dest)
    }

    @Test
    fun `a place named in a Learn page opens as a jump so Back returns to the page`() {
        val nav = NavState()
        nav.go(Dest.Learn)
        nav.learnPage = "chart-sizes"
        assertTrue(nav.openPlace("settings"))
        assertEquals(Dest.Settings, nav.dest)
        assertEquals("chart-sizes", nav.learnPage, "the page is still open under it")
        assertTrue(nav.back()); assertEquals(Dest.Learn, nav.dest); assertEquals("chart-sizes", nav.learnPage)
        assertTrue(nav.openPlace("ALERTS")); assertEquals(Dest.Alerts, nav.dest)
        assertFalse(nav.openPlace("nowhere"))
        assertEquals(Dest.Alerts, nav.dest)
    }

    @Test
    fun `back leaves the debug page before anything else`() {
        val nav = NavState()
        nav.go(Dest.Trades)
        nav.openCoin("BTCUSDT", null, fromApp = true)
        nav.showDebug = true
        assertTrue(nav.back()); assertFalse(nav.showDebug)
        assertTrue(nav.back()); assertEquals(Dest.Trades, nav.dest)
    }

    // --- Learn pages

    private val pages = File("src/main/assets/learn/pages")

    private fun page(id: String) = File(pages, "$id.md").readText()

    @Test
    fun `every link in a Learn page leads to a page or a place that exists`() {
        assertTrue(pages.isDirectory, "run from the app module: ${pages.absolutePath}")
        val ids = LearnIndex.entries.map { it.id }.toSet()
        val places = Dest.entries.map { it.name.lowercase() }.toSet()
        var links = 0
        for (e in LearnIndex.entries) {
            for (m in Regex("""\]\(([^)]*)\)""").findAll(page(e.id))) {
                links++
                when (val link = parseWebLink(m.groupValues[1])) {
                    is WebLink.Page -> assertTrue(link.id in ids, "${e.id} links to the page ${link.id}, which does not exist")
                    is WebLink.Place -> assertTrue(link.name in places, "${e.id} links to the place ${link.name}, which does not exist")
                    null -> throw AssertionError("${e.id} has a link that goes nowhere: ${m.value}")
                }
            }
        }
        assertEquals(19, links, "the links the pages carry")
    }

    @Test
    fun `the sentences that send the reader somewhere are links`() {
        val expected = listOf(
            "analyst" to "The [Analyst](go:analyst) hands your record",
            "analyst" to "the same numbers you see on the [Scorecard](go:scorecard)",
            "analyst" to "See [how the scorecard judges](learn:scorecard).",
            "analyst" to "New patterns for the [pattern lab](learn:pattern-lab)",
            "pattern-lab" to "In the [Analyst](go:analyst), ask",
            "pattern-lab" to "See [how the scorecard judges](learn:scorecard).",
            "chart-sizes" to "(see [What a paper trade is](learn:paper-trade))",
            "chart-sizes" to "off in [Settings](go:settings) to save battery",
            "costs" to "lower it in [Settings](go:settings)",
            "costs" to "turn it on in [Settings](go:settings)",
            "drop-fade" to "(see [What a paper trade is](learn:paper-trade))",
            "drop-fade" to "the [scorecard](go:scorecard) will say whether it holds",
            "harami" to "(see [What a paper trade is](learn:paper-trade))",
            "hikkake" to "(see [What a paper trade is](learn:paper-trade))",
            "paper-trade" to "(see [What costs are charged](learn:costs))",
            "paper-trade" to "(see [How the scorecard judges](learn:scorecard))",
            "paper-trade" to "in [Alerts](go:alerts) and",
            "scorecard" to "the bottom of the [Scorecard](go:scorecard)",
            "trend" to "The [scorecard](go:scorecard) will tell",
        )
        for ((id, text) in expected) assertTrue(page(id).contains(text), "$id should contain: $text")
        assertEquals(expected.size, LearnIndex.entries.sumOf { Regex("""\]\((learn|go):""").findAll(page(it.id)).count() })
    }

    @Test
    fun `no Learn page links to the web`() {
        for (e in LearnIndex.entries) assertFalse(Regex("""\]\(https?:""").containsMatchIn(page(e.id)), e.id)
    }

    @Test
    fun `a link's target page is not the page it is on`() {
        for (e in LearnIndex.entries) assertNotEquals("learn:${e.id}", Regex("""\]\((learn:[a-z-]+)\)""").findAll(page(e.id)).map { it.groupValues[1] }.firstOrNull { it == "learn:${e.id}" })
    }
}
