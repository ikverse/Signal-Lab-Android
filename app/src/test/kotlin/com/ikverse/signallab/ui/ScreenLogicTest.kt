package com.ikverse.signallab.ui

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.unit.sp
import com.ikverse.signallab.engine.Refusal
import com.ikverse.signallab.state.refusalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** The rules the screens run on, with no screen drawn: what filters keep, what a summary says, where a link goes. */
class ScreenLogicTest {
    private val open = FakeApp.trade(1)
    private val won = FakeApp.trade(2, net = 0.03)
    private val lost = FakeApp.trade(3, "ETHUSDT", "4h", "trend_ma20_4h", net = -0.01)
    private val all = listOf(open, won, lost)

    @Test
    fun `the status filter keeps exactly the open or the closed trades`() {
        assertEquals(listOf(1L, 2L, 3L), filterTrades(all, TradeFilter.All, "").map { it.id })
        assertEquals(listOf(1L), filterTrades(all, TradeFilter.Open, "").map { it.id })
        assertEquals(listOf(2L, 3L), filterTrades(all, TradeFilter.Closed, "").map { it.id })
    }

    @Test
    fun `the search matches the coin, the pattern name, the pattern label and the chart, in any case`() {
        assertEquals(listOf(3L), filterTrades(all, TradeFilter.All, "eth").map { it.id })
        assertEquals(listOf(3L), filterTrades(all, TradeFilter.All, "  ETHUSDT ").map { it.id })
        assertEquals(listOf(3L), filterTrades(all, TradeFilter.All, "trend_ma").map { it.id })
        assertEquals(listOf(1L, 2L, 3L), filterTrades(all, TradeFilter.All, "breakout").map { it.id })
        assertEquals(listOf(3L), filterTrades(all, TradeFilter.All, "4H").map { it.id })
        assertEquals(emptyList<Long>(), filterTrades(all, TradeFilter.All, "doge"))
    }

    @Test
    fun `the status and the search apply together`() {
        assertEquals(listOf(3L), filterTrades(all, TradeFilter.Closed, "eth").map { it.id })
        assertEquals(emptyList<Long>(), filterTrades(all, TradeFilter.Open, "eth"))
    }

    @Test
    fun `the chart filter keeps the trades taken on that chart size, together with the status and the search`() {
        assertEquals(listOf(1L, 2L, 3L), filterTrades(all, TradeFilter.All, "", null).map { it.id })
        assertEquals(listOf(1L, 2L), filterTrades(all, TradeFilter.All, "", "1h").map { it.id })
        assertEquals(listOf(3L), filterTrades(all, TradeFilter.All, "", "4h").map { it.id })
        assertEquals(emptyList<Long>(), filterTrades(all, TradeFilter.All, "", "1d"))
        assertEquals(listOf(2L), filterTrades(all, TradeFilter.Closed, "", "1h").map { it.id })
        assertEquals(emptyList<Long>(), filterTrades(all, TradeFilter.All, "eth", "1h"))
    }

    @Test
    fun `the chart sizes on offer are the ones the trades used, shortest first, with the chosen one always kept`() {
        val day = FakeApp.trade(4, "SOLUSDT", "1d")
        val quarter = FakeApp.trade(5, tf = "15m")
        val odd = FakeApp.trade(6, tf = "2h")
        assertEquals(listOf("1h", "4h"), tradeTimeframes(all))
        assertEquals(listOf("15m", "1h", "4h", "1d"), tradeTimeframes(all + day + quarter))
        assertEquals(listOf("1h", "4h", "2h"), tradeTimeframes(all + odd))
        assertEquals(listOf("15m", "1h", "4h"), tradeTimeframes(all, keep = "15m"))
        assertEquals(listOf("1h", "4h"), tradeTimeframes(all, keep = "1h"))
        assertEquals(emptyList<String>(), tradeTimeframes(emptyList()))
    }

    @Test
    fun `the summary counts open and closed and averages only the closed results`() {
        assertEquals("1 open · 2 closed · average +1.00% after costs", tradesSummary(all))
        assertEquals("1 open · 0 closed", tradesSummary(listOf(open)))
        assertEquals("0 open · 0 closed", tradesSummary(emptyList()))
    }

    @Test
    fun `trades are grouped by pattern, newest first within a group, the pattern that fired last first`() {
        val a1 = FakeApp.trade(1).copy(label = "A")
        val b2 = FakeApp.trade(2).copy(label = "B")
        val a3 = FakeApp.trade(3).copy(label = "A")
        val c4 = FakeApp.trade(4).copy(label = "C")
        val groups = groupTrades(listOf(a1, b2, a3, c4))
        assertEquals(listOf("C", "A", "B"), groups.map { it.first })
        assertEquals(listOf(3L, 1L), groups[1].second.map { it.id })
        assertEquals(emptyList<Pair<String, List<TradeUi>>>(), groupTrades(emptyList()))
    }

    private fun row(variant: String, tf: String, closed: Int, open: Int = 0, label: String = variant) =
        ScoreRowUi(variant, label, tf, open, closed, null, null, null, null, null, "No verdict", "No verdict", false)

    @Test
    fun `scorecard rows are filtered by chart and put the busiest first`() {
        val rows = listOf(row("a_1h", "1h", 0), row("b_1h", "1h", 5), row("c_4h", "4h", 9), row("d_1h", "1h", 0, open = 2))
        assertEquals(listOf("c_4h", "b_1h", "d_1h", "a_1h"), scorecardRows(rows, null).map { it.variant })
        assertEquals(listOf("b_1h", "d_1h", "a_1h"), scorecardRows(rows, "1h").map { it.variant })
        assertEquals(emptyList<String>(), scorecardRows(rows, "1d").map { it.variant })
    }

    @Test
    fun `an edge is green, a loser is red, and everything not yet known is quiet`() {
        assertEquals(Palette.Up, verdictColor("Edge"))
        assertEquals(Palette.Down, verdictColor("Losing"))
        for (v in listOf("No verdict", "No edge", "Judged on live trades only")) assertEquals(Palette.Muted, verdictColor(v))
    }

    @Test
    fun `each alert group keeps only its own kinds, and All keeps everything`() {
        val alerts = listOf("signal", "exit", "warning", "missed", "problem", "signal").mapIndexed { i, k -> FakeApp.alert(i.toLong(), k) }
        assertEquals(6, filterAlerts(alerts, AlertGroup.All).size)
        assertEquals(listOf("signal", "signal"), filterAlerts(alerts, AlertGroup.Opened).map { it.kind })
        assertEquals(listOf("exit"), filterAlerts(alerts, AlertGroup.Closed).map { it.kind })
        assertEquals(listOf("warning"), filterAlerts(alerts, AlertGroup.Warnings).map { it.kind })
        assertEquals(listOf("missed"), filterAlerts(alerts, AlertGroup.Missed).map { it.kind })
        assertEquals(listOf("problem"), filterAlerts(alerts, AlertGroup.Problems).map { it.kind })
    }

    @Test
    fun `a coin opens on an hour chart if it has one, else the next best, and never on one it is not watched on`() {
        assertEquals("1h", defaultChart(listOf("15m", "1h", "4h")))
        assertEquals("15m", defaultChart(listOf("1m", "15m")))
        assertEquals("4h", defaultChart(listOf("4h", "1d")))
        assertEquals("1m", defaultChart(listOf("1m")))
        assertNull(defaultChart(emptyList()))
    }

    @Test
    fun `links open a coin, and anything else is not a link`() {
        assertEquals(Link("SOLUSDT", "1h"), parseLink("signallab://coin/SOLUSDT?tf=1h"))
        assertEquals(Link("SOLUSDT", null), parseLink("signallab://coin/SOLUSDT"))
        assertEquals(Link("BTCUSDT", "15m"), parseLink("signallab://coin/BTCUSDT?x=1&tf=15m&y=2"))
        assertNull(parseLink(null))
        assertNull(parseLink(""))
        assertNull(parseLink("signallab://coin/"))
        assertNull(parseLink("signallab://coin/?tf=1h"))
        assertNull(parseLink("https://example.com/coin/BTCUSDT"))
    }

    @Test
    fun `prices keep the precision their size calls for`() {
        assertEquals("65000.00", Fmt.price(65_000.0))
        assertEquals("3.142", Fmt.price(3.14159))
        assertEquals("0.12346", Fmt.price(0.123456))
        assertEquals("0.00001234", Fmt.price(0.00001234))
        assertEquals("—", Fmt.price(null))
        assertEquals("—", Fmt.price(Double.NaN))
    }

    @Test
    fun `an open trade's levels are on the chart when it was switched on, else when it is the newest of its size`() {
        val open = listOf(FakeApp.trade(5, tf = "1h"), FakeApp.trade(7, tf = "1h"), FakeApp.trade(6, tf = "15m"), FakeApp.trade(3, tf = "15m"))
        assertEquals("the newest of each size to begin with", setOf(7L, 6L), tradesOnChart(open, emptyMap()))
        assertEquals("a trade switched on joins them", setOf(7L, 6L, 5L), tradesOnChart(open, mapOf(5L to true)))
        assertEquals("a trade switched off leaves", setOf(6L), tradesOnChart(open, mapOf(7L to false)))
        assertEquals("every one can be off", emptySet<Long>(), tradesOnChart(open, open.associate { it.id to false }))
        assertEquals("a choice for a trade no longer open is ignored", setOf(7L, 6L), tradesOnChart(open, mapOf(99L to true)))
        assertEquals(emptySet<Long>(), tradesOnChart(emptyList(), mapOf(1L to true)))
    }

    @Test
    fun `the chart draws the levels of the trades that are on, each named with its price`() {
        val chart = ChartUi("BTCUSDT", "1h", emptyList(), listOf(
            LevelUi(LevelKind.ENTRY, "Entry", 100.0, 5), LevelUi(LevelKind.STOP, "Stop", 95.5, 5), LevelUi(LevelKind.ENTRY, "Entry", 2.5, 7),
        ))
        assertEquals(listOf("Entry 100.00", "Stop 95.500"), levelsOf(chart, setOf(5L)).map { it.label })
        assertEquals(listOf("Entry 2.500"), levelsOf(chart, setOf(7L)).map { it.label })
        assertEquals(listOf("Entry 100.00", "Stop 95.500", "Entry 2.500"), levelsOf(chart, setOf(5L, 7L)).map { it.label })
        assertEquals(emptyList<LevelUi>(), levelsOf(chart, emptySet()))
    }

    @Test
    fun `which trades were switched on or off is kept, newest choice last, and survives being saved`() {
        val nav = NavState()
        nav.chooseChartTrade(5, true)
        nav.chooseChartTrade(7, false)
        nav.chooseChartTrade(5, false)
        assertEquals(mapOf(7L to false, 5L to false), nav.chartChoice)
        assertEquals("a trade chosen again moves to the end", listOf(7L, 5L), nav.chartChoice.keys.toList())
        val back =NavState.Saver.restore(with(NavState.Saver) { SaverScope { true }.save(nav) }!!)!!
        assertEquals(mapOf(7L to false, 5L to false), back.chartChoice)
        assertEquals(emptyMap<Long, Boolean>(), NavState.Saver.restore(with(NavState.Saver) { SaverScope { true }.save(NavState()) }!!)!!.chartChoice)
        nav.openCoin("BTCUSDT", "1h", trade = 9)
        assertEquals("opening a coin on a trade puts that trade on and leaves the rest", mapOf(7L to false, 5L to false, 9L to true), nav.chartChoice)
        repeat(300) { nav.chooseChartTrade(1000L + it, true) }
        assertEquals("only the latest choices are kept", 200, nav.chartChoice.size)
        assertTrue(nav.chartChoice.getValue(1299L))
    }

    @Test
    fun `where a price sits between two others runs from 0 to 1 and never past the ends`() {
        assertEquals(0.5f, rangeFraction(90.0, 110.0, 100.0)!!, 1e-6f)
        assertEquals(0f, rangeFraction(90.0, 110.0, 80.0)!!, 1e-6f)
        assertEquals(1f, rangeFraction(90.0, 110.0, 120.0)!!, 1e-6f)
        assertNull(rangeFraction(110.0, 90.0, 100.0))
        assertNull(rangeFraction(90.0, 110.0, null))
    }

    @Test
    fun `a closed trade's gap to random entries is in points, and unknown without a random figure`() {
        assertEquals(2.9, gapToRandom(ClosedUi(0L, 1.0, "target", 0.03, 0.001, null, null, 1))!!, 1e-9)
        assertEquals(-1.5, gapToRandom(ClosedUi(0L, 1.0, "stop", -0.01, 0.005, null, null, 1))!!, 1e-9)
        assertNull(gapToRandom(ClosedUi(0L, 1.0, "stop", -0.01, null, null, null, 1)))
        assertNull(gapToRandom(ClosedUi(0L, 1.0, "stop", -0.01, Double.NaN, null, null, 1)))
    }

    @Test
    fun `a verdict's firmness fills one, two or three dots`() {
        assertEquals(0, firmnessDots("No verdict"))
        assertEquals(1, firmnessDots("Early read"))
        assertEquals(2, firmnessDots("Provisional"))
        assertEquals(3, firmnessDots("Meaningful"))
    }

    @Test
    fun `the search also finds a trade by its short pattern name`() {
        val t = open.copy(short = "Breakout · 20 high")
        assertEquals(listOf(t.id), filterTrades(listOf(t), TradeFilter.All, "20 high").map { it.id })
    }

    @Test
    fun `a day reads as today, yesterday, or its date, with the year only when it is another`() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = 1_791_380_000_000L // 2026-10-07, mid-afternoon UTC
        assertEquals("Today · 7 Oct", Fmt.day(now - 3_600_000L, now, utc))
        assertEquals("Yesterday · 6 Oct", Fmt.day(now - 86_400_000L, now, utc))
        assertEquals("5 Oct", Fmt.day(now - 2 * 86_400_000L, now, utc))
        assertEquals("7 Oct 2025", Fmt.day(now - 365 * 86_400_000L, now, utc))
    }

    @Test
    fun `an alert about a trade is read from its numbers, and an old one from its words`() {
        val facts = AlertFacts("donchian20_1h", "Breakout · 20 high", tradeId = 2, entry = 2.126, target = 2.207, stop = 2.036)
        val opened = AlertUi(1, 0L, "signal", "Practice trade started: ZRO 15m", "b", "ZROUSDT", "15m", facts = facts)
        assertEquals("Started", alertOutcome(opened))
        assertEquals("Entry price 2.126 → profit goal 2.207 (+3.81%) · loss limit 2.036", alertFigures(opened))
        val trailing = opened.copy(facts = facts.copy(target = null, trails = true))
        assertEquals("Entry price 2.126 · loss limit 2.036, then it follows the price up", alertFigures(trailing))
        val closed = AlertUi(2, 0L, "exit", "Practice trade finished: GTC 15m, profit goal reached", "b", "GTCUSDT", "15m",
            facts = AlertFacts("x", "x", reason = "stop", net = -0.02, random = 0.0117))
        assertEquals("the numbers win over the words", "Loss limit reached", alertOutcome(closed))
        assertEquals("Random entries averaged +1.17%", alertFigures(closed))
        val old = AlertUi(3, 0L, "exit", "Practice trade finished: GTC 15m, profit goal reached", "b", "GTCUSDT", "15m")
        assertEquals("target", alertReason(old))
        assertEquals("Profit goal reached", alertOutcome(old))
        assertNull(alertFigures(old))
        assertEquals(null, alertTrade(old, listOf(won)))
        assertEquals(won, alertTrade(closed.copy(facts = closed.facts!!.copy(tradeId = 2)), listOf(won)))
        assertEquals(won, alertTrade(old.copy(link = "signallab://trades?coin=BTCUSDT&id=2"), listOf(won)))
    }

    @Test
    fun `a Learn title splits into a name and what it is`() {
        assertEquals("Trend" to "Price crosses above its average", splitTitle("Trend: price crosses above its average"))
        assertEquals("What a paper trade is" to null, splitTitle("What a paper trade is"))
        assertEquals(": odd" to null, splitTitle(": odd"))
    }

    @Test
    fun `a list's line names how many coins and the first four`() {
        val list = ListUi(1, "My coins 4", true, listOf("ORCAUSDT", "GTCUSDT", "NIGHTUSDT", "SANDUSDT", "MOVRUSDT", "ZROUSDT"), listOf("15m", "1h"))
        assertEquals("6 coins · ORCA, GTC, NIGHT, SAND +2", listCoinsLine(list))
        assertEquals("1 coin · BTC", listCoinsLine(list.copy(coins = listOf("BTCUSDT"))))
        assertEquals("0 coins", listCoinsLine(list.copy(coins = emptyList())))
    }

    @Test
    fun `the status bar hides only on a phone held sideways`() {
        assertTrue(hideStatusBar(846, 372))
        assertFalse("upright", hideStatusBar(411, 800))
        assertFalse("a tablet held sideways keeps it", hideStatusBar(1200, 700))
        assertFalse("a square window is not sideways", hideStatusBar(400, 400))
    }

    @Test
    fun `the bar has five places and the other six sit behind More`() {
        assertEquals(listOf(Dest.Today, Dest.Scorecard, Dest.Lab, Dest.Markets, Dest.More), BarPlaces)
        assertEquals(Dest.entries.toSet(), (BarPlaces + MorePlaces).toSet())
        for (d in MorePlaces) assertEquals(Dest.More, barPlaceOf(d))
        for (d in BarPlaces) assertEquals(d, barPlaceOf(d))
    }

    @Test
    fun `a place behind More returns to More on Back, and More to Today`() {
        val nav = NavState()
        nav.go(Dest.More)
        nav.go(Dest.Alerts)
        assertTrue(nav.back())
        assertEquals(Dest.More, nav.dest)
        assertTrue(nav.back())
        assertEquals(Dest.Today, nav.dest)
        assertFalse(nav.back())
    }

    @Test
    fun `on a phone Back from a Learn page goes to the Learn list first, then More`() {
        val nav = NavState()
        nav.go(Dest.Learn)
        nav.learnPage = "trend"
        assertTrue(nav.back())
        assertEquals(Dest.Learn, nav.dest)
        assertNull(nav.learnPage)
        assertTrue(nav.back())
        assertEquals(Dest.More, nav.dest)
    }

    @Test
    fun `percentages carry their sign and a missing one reads as a dash`() {
        assertEquals("+1.23%", Fmt.signedPercent(0.0123))
        assertEquals("−1.00%", Fmt.signedPercent(-0.01))
        assertEquals("0.00%", Fmt.signedPercent(0.0))
        assertEquals("0.0%", Fmt.signedPercent(-0.0004, 1))
        assertEquals("—", Fmt.signedPercent(null))
        assertEquals("55%", Fmt.percent(0.55, 0))
        assertEquals("12.50%", Fmt.percent(0.125))
    }

    @Test
    fun `a change is green up, red down, and quiet when there is none`() {
        assertEquals(Palette.Up, Fmt.changeColor(0.01))
        assertEquals(Palette.Down, Fmt.changeColor(-0.01))
        assertEquals(Palette.Muted, Fmt.changeColor(0.0))
        assertEquals(Palette.Muted, Fmt.changeColor(null))
    }

    @Test
    fun `big numbers are shortened`() {
        assertEquals("1.25B", Fmt.compact(1_250_000_000.0))
        assertEquals("3.5M", Fmt.compact(3_500_000.0))
        assertEquals("12.0K", Fmt.compact(12_000.0))
        assertEquals("999", Fmt.compact(999.0))
    }

    @Test
    fun `times read in the zone they are given`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("14 Nov 22:13", Fmt.dateTime(1_700_000_000_000L, utc))
        assertEquals("22:13", Fmt.time(1_700_000_000_000L, utc))
        assertEquals("15 Nov 00:13", Fmt.dateTime(1_700_000_000_000L, TimeZone.getTimeZone("GMT+2")))
    }

    @Test
    fun `a typed percentage becomes a fraction, and nonsense becomes null`() {
        assertEquals(0.001, Fmt.parsePercent("0.1")!!, 1e-12)
        assertEquals(0.00075, Fmt.parsePercent("0,075 %")!!, 1e-12)
        assertEquals(0.0, Fmt.parsePercent("0")!!, 0.0)
        assertEquals(0.01, Fmt.parsePercent(" 1% ")!!, 1e-12)
        assertNull(Fmt.parsePercent(""))
        assertNull(Fmt.parsePercent("abc"))
        assertNull(Fmt.parsePercent("-1"))
        assertNull(Fmt.parsePercent("NaN"))
        assertNull(Fmt.parsePercent("Infinity"))
    }

    @Test
    fun `a stored fraction is shown the way it is typed, and typing it back gives the same number`() {
        for (f in listOf(0.0, 0.001, 0.00075, 0.0025, 0.01, 0.0004)) {
            val shown = percentInput(f)
            assertEquals("$f", f, Fmt.parsePercent(shown)!!, 1e-12)
        }
        assertEquals("0.1", percentInput(0.001))
        assertEquals("0", percentInput(0.0))
    }

    @Test
    fun `every refusal has words of its own`() {
        val said = Refusal.entries.map { refusalText(it) }
        assertEquals(said.size, said.toSet().size)
        for (s in said) assertTrue(s, s.length > 10 && s.endsWith("."))
    }

    @Test
    fun `layout comes from the width alone`() {
        assertEquals(LayoutClass.Compact, LayoutClass.of(360f))
        assertEquals(LayoutClass.Compact, LayoutClass.of(559.9f))
        assertEquals(LayoutClass.Medium, LayoutClass.of(560f))
        assertEquals(LayoutClass.Medium, LayoutClass.of(819.9f))
        assertEquals(LayoutClass.Wide, LayoutClass.of(820f))
        assertEquals(LayoutClass.Wide, LayoutClass.of(1400f))
    }

    @Test
    fun `back steps towards Today one place at a time and then lets the app close`() {
        val nav = NavState()
        assertFalse(nav.canBack)
        assertFalse(nav.back())

        nav.openCoin("BTCUSDT", "1h")
        assertEquals(MarketsTab.Chart, nav.marketsTab)
        assertTrue(nav.canBack)
        assertTrue(nav.back())
        assertEquals(MarketsTab.Coins, nav.marketsTab)
        assertTrue("Coins is one step from Today", nav.canBack)

        // A page opened by a jump from Coins (What is this pattern?): Back returns to Coins, and Learn's page is closed behind it.
        nav.openLearn("trend")
        assertTrue(nav.back())
        assertEquals(Dest.Markets, nav.dest)
        assertNull(nav.learnPage)
        assertTrue(nav.canBack)

        // A page chosen inside Learn on a narrow screen: Back closes the page first, then leaves Learn for More, then Today.
        nav.go(Dest.Learn)
        nav.learnPage = "trend"
        assertTrue(nav.back())
        assertEquals(Dest.Learn, nav.dest)
        assertNull(nav.learnPage)
        assertTrue(nav.back())
        assertEquals(Dest.More, nav.dest)
        assertTrue(nav.back())
        assertEquals(Dest.Today, nav.dest)

        nav.go(Dest.Settings)
        nav.showDebug = true
        assertTrue(nav.back())
        assertFalse(nav.showDebug)
        assertEquals(Dest.Settings, nav.dest)
        assertTrue(nav.back())
        assertEquals(Dest.More, nav.dest)
        assertTrue(nav.back())
        assertEquals(Dest.Today, nav.dest)
    }

    @Test
    fun `where the user is survives being saved and restored`() {
        val nav = NavState(Dest.Learn, "SOLUSDT", "4h", "scorecard", MarketsTab.Details, showDebug = true)
        val saved = with(NavState.Saver) { SaverScope { true }.save(nav) }
        assertNotNull(saved)
        val back = NavState.Saver.restore(saved!!)!!
        assertEquals(Dest.Learn, back.dest)
        assertEquals("SOLUSDT", back.symbol)
        assertEquals("4h", back.timeframe)
        assertEquals("scorecard", back.learnPage)
        assertEquals(MarketsTab.Details, back.marketsTab)
        assertTrue(back.showDebug)

        val plain = NavState.Saver.restore(with(NavState.Saver) { SaverScope { true }.save(NavState()) }!!)!!
        assertNull(plain.symbol)
        assertNull(plain.timeframe)
        assertNull(plain.learnPage)
    }

    @Test
    fun `the chart size chosen on Trades survives being saved and restored`() {
        val nav = NavState(Dest.Trades, tradesTimeframe = "4h")
        val back = NavState.Saver.restore(with(NavState.Saver) { SaverScope { true }.save(nav) }!!)!!
        assertEquals("4h", back.tradesTimeframe)
        assertNull(NavState.Saver.restore(with(NavState.Saver) { SaverScope { true }.save(NavState()) }!!)!!.tradesTimeframe)
    }

    @Test
    fun `opening a coin or a page from anywhere goes there`() {
        val nav = NavState(Dest.Settings)
        nav.openCoin("ETHUSDT", null)
        assertEquals(Dest.Markets, nav.dest)
        assertEquals("ETHUSDT", nav.symbol)
        assertNull(nav.timeframe)
        nav.openLearn(null)
        assertEquals(Dest.Learn, nav.dest)
    }

    @Test
    fun `an open trade says how it will end in words that match its exit`() {
        assertTrue(exitText(FakeApp.trade(1, mode = "trail")).startsWith("Loss limit 95.000, then a limit that follows"))
        assertEquals("Held for a fixed time.", exitText(FakeApp.trade(1, mode = "held")))
        assertEquals("Held for a fixed time.", exitText(FakeApp.trade(1, mode = "learned")))
        assertEquals("Held for a fixed time.", exitText(FakeApp.trade(1, mode = null)))
        val learned = FakeApp.trade(1, mode = "learned").copy(target = 104.0)
        assertEquals("Profit goal 104.00, loss limit 95.000.", exitText(learned))
        val classic = FakeApp.trade(1, mode = null).copy(target = 110.0)
        assertEquals("Profit goal 110.00, loss limit 95.000.", exitText(classic))
    }

    @Test
    fun `the words for how a trade closed are plain`() {
        assertEquals("profit goal reached", exitWords("target"))
        assertEquals("loss limit reached", exitWords("stop"))
        assertEquals("time limit reached", exitWords("time"))
    }

    @Test
    fun `chart data is sent as numbers the page can read, and a broken candle is left out`() {
        val chart = ChartUi(
            "BTCUSDT", "1h",
            listOf(CandleUi(1000, 1.0, 2.0, 0.5, 1.5, 10.0), CandleUi(2000, Double.NaN, 2.0, 0.5, 1.5, 10.0), CandleUi(3000, 1.5, 2.5, 1.0, 2.0, 7.5)),
            listOf(LevelUi(LevelKind.ENTRY, "Entry", 1.5), LevelUi(LevelKind.STOP, "Stop \"x\"", Double.POSITIVE_INFINITY), LevelUi(LevelKind.TARGET, "Target", 65_000.25)),
        )
        val json = ChartJson.build(chart)
        val parsed = org.json.JSONObject(json)
        val candles = parsed.getJSONArray("candles")
        assertEquals(2, candles.length())
        assertEquals(1000L, candles.getJSONArray(0).getLong(0))
        assertEquals(2.0, candles.getJSONArray(1).getDouble(4), 0.0)
        assertEquals(7.5, candles.getJSONArray(1).getDouble(5), 0.0)
        val levels = parsed.getJSONArray("levels")
        assertEquals(2, levels.length())
        assertEquals("ENTRY", levels.getJSONObject(0).getString("kind"))
        assertEquals(65_000.25, levels.getJSONObject(1).getDouble("price"), 0.0)
    }

    @Test
    fun `very small and very large prices survive the trip to the chart page`() {
        val chart = ChartUi("X", "1m", listOf(CandleUi(1, 0.00000123, 0.00000200, 0.00000100, 0.00000150, 1.0e12)), emptyList())
        val c = org.json.JSONObject(ChartJson.build(chart)).getJSONArray("candles").getJSONArray(0)
        assertEquals(0.00000123, c.getDouble(1), 1e-15)
        assertEquals(1.0e12, c.getDouble(5), 1.0)
    }

    @Test
    fun `an empty chart is still valid for the page`() {
        val parsed = org.json.JSONObject(ChartJson.build(ChartUi("X", "1h", emptyList(), emptyList())))
        assertEquals(0, parsed.getJSONArray("candles").length())
        assertEquals(0, parsed.getJSONArray("levels").length())
    }

    @Test
    fun `text is never smaller than 11 sp and touch targets are 48 dp`() {
        for (s in listOf(Type.Title, Type.Heading, Type.Body, Type.BodyStrong, Type.Small, Type.Label, Type.Number, Type.NumberStrong)) {
            assertTrue("${s.fontSize}", s.fontSize >= 11.sp)
        }
        assertEquals(48f, MinTouch.value, 0f)
    }

    @Test
    fun `the warning for unwatched charts names them and says whether the pattern could trade at all`() {
        assertNull(unwatchedText(emptyList(), true))
        assertEquals("No active list watches 4h, so it would not trade there.", unwatchedText(listOf("4h"), false))
        assertEquals("No active list watches 15m or 4h, so it would not trade there.", unwatchedText(listOf("15m", "4h"), false))
        assertEquals(
            "No active list watches 5m, 15m or 4h, so it would never trade. Add 5m, 15m or 4h to a list first.",
            unwatchedText(listOf("5m", "15m", "4h"), true),
        )
    }

    @Test
    fun `the screens import nothing from the data layer`() {
        val dir = File("src/main/kotlin/com/ikverse/signallab/ui")
        assertTrue("run from the app module: ${dir.absolutePath}", dir.isDirectory)
        val files = dir.listFiles { f -> f.extension == "kt" }!!
        assertTrue(files.size > 10)
        for (f in files) {
            for ((i, line) in f.readLines().withIndex()) {
                assertFalse("${f.name}:${i + 1} reaches into the data layer: $line", line.trim().startsWith("import com.ikverse.signallab.data"))
                assertFalse("${f.name}:${i + 1} reaches into the scan layer: $line", line.trim().startsWith("import com.ikverse.signallab.scan"))
                assertFalse("${f.name}:${i + 1} reaches into the state layer: $line", line.trim().startsWith("import com.ikverse.signallab.state"))
            }
        }
    }

    @Test
    fun `no screen or page tells anyone to buy or sell`() {
        val words = Regex("""\b(buy|buys|buying|sell|sells|selling|bought|sold)\b""", RegexOption.IGNORE_CASE)
        val sources = File("src/main/kotlin/com/ikverse/signallab/ui").listFiles { f -> f.extension == "kt" }!!.toList() +
            File("src/main/kotlin/com/ikverse/signallab/state").listFiles { f -> f.extension == "kt" }!!.toList() +
            File("src/main/assets/learn/pages").listFiles { f -> f.extension == "md" }!!.toList()
        assertTrue(sources.size > 25)
        for (f in sources) {
            for ((i, line) in f.readLines().withIndex()) {
                val m = words.find(line)
                assertTrue("${f.name}:${i + 1} says \"${m?.value}\": $line", m == null)
            }
        }
    }

    @Test
    fun `a new list is offered a name no list has yet`() {
        assertEquals("New list", freeListName(listOf("My coins")))
        assertEquals("New list 3", freeListName(listOf("new list", "New list 2")))
    }

    @Test
    fun `a building block's menu name says its length in words, except the last N candles`() {
        assertEquals("Average price over a number of candles", menuWords("Average price over N candles"))
        assertEquals("Highest price of the last N candles", menuWords("Highest price of the last N candles"))
        assertEquals("Volume compared with normal (a number of candles)", menuWords("Volume compared with normal (N candles)"))
    }

    @Test
    fun `a change compared with a plain number reads as a percentage, other numbers as they are`() {
        assertEquals("the change over the last 24 candles is below −5%", DraftCondition("return(24)", "below", "-0.05").words)
        assertEquals("−5%", LabVocab.label("-0.05", "return(24)"))
        assertEquals("2.5%", LabVocab.label("0.025", "return(12)"))
        assertEquals("RSI(14) crosses above 30", DraftCondition("rsi(14)", "crosses_above", "30").words)
        assertEquals("-0.05", LabVocab.phrase("-0.05"))
    }
}
