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
    fun `the summary counts open and closed and averages only the closed results`() {
        assertEquals("1 open · 2 closed · average +1.00% after costs", tradesSummary(all))
        assertEquals("1 open · 0 closed", tradesSummary(listOf(open)))
        assertEquals("0 open · 0 closed", tradesSummary(emptyList()))
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
    fun `percentages carry their sign and a missing one reads as a dash`() {
        assertEquals("+1.23%", Fmt.signedPercent(0.0123))
        assertEquals("-1.00%", Fmt.signedPercent(-0.01))
        assertEquals("+0.00%", Fmt.signedPercent(0.0))
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
    fun `every place is in the bottom bar or behind More, and none is in both`() {
        assertEquals(Dest.entries.toSet(), (Dest.Primary + Dest.More).toSet())
        assertTrue(Dest.Primary.intersect(Dest.More.toSet()).isEmpty())
        assertEquals(4, Dest.Primary.size)
    }

    @Test
    fun `back steps towards Markets one place at a time and then lets the app close`() {
        val nav = NavState()
        assertFalse(nav.canBack)
        assertFalse(nav.back())

        nav.openCoin("BTCUSDT", "1h")
        assertEquals(MarketsTab.Chart, nav.marketsTab)
        assertTrue(nav.canBack)
        assertTrue(nav.back())
        assertEquals(MarketsTab.Coins, nav.marketsTab)
        assertFalse(nav.canBack)

        // A page opened by a jump from Markets (What is this pattern?): Back returns to Markets, and Learn's page is closed behind it.
        nav.openLearn("trend")
        assertTrue(nav.back())
        assertEquals(Dest.Markets, nav.dest)
        assertNull(nav.learnPage)
        assertFalse(nav.canBack)

        // A page chosen inside Learn on a narrow screen: Back closes the page first, then leaves Learn.
        nav.go(Dest.Learn)
        nav.learnPage = "trend"
        assertTrue(nav.back())
        assertEquals(Dest.Learn, nav.dest)
        assertNull(nav.learnPage)
        assertTrue(nav.back())
        assertEquals(Dest.Markets, nav.dest)

        nav.go(Dest.Settings)
        nav.showDebug = true
        assertTrue(nav.back())
        assertFalse(nav.showDebug)
        assertEquals(Dest.Settings, nav.dest)
        assertTrue(nav.back())
        assertEquals(Dest.Markets, nav.dest)

        nav.showMore = true
        assertTrue(nav.back())
        assertFalse(nav.showMore)
    }

    @Test
    fun `where the user is survives being saved and restored`() {
        val nav = NavState(Dest.Learn, "SOLUSDT", "4h", "scorecard", MarketsTab.Details, showMore = true, showDebug = true)
        val saved = with(NavState.Saver) { SaverScope { true }.save(nav) }
        assertNotNull(saved)
        val back = NavState.Saver.restore(saved!!)!!
        assertEquals(Dest.Learn, back.dest)
        assertEquals("SOLUSDT", back.symbol)
        assertEquals("4h", back.timeframe)
        assertEquals("scorecard", back.learnPage)
        assertEquals(MarketsTab.Details, back.marketsTab)
        assertTrue(back.showMore)
        assertTrue(back.showDebug)

        val plain = NavState.Saver.restore(with(NavState.Saver) { SaverScope { true }.save(NavState()) }!!)!!
        assertNull(plain.symbol)
        assertNull(plain.timeframe)
        assertNull(plain.learnPage)
    }

    @Test
    fun `opening a coin or a page from anywhere goes there and closes More`() {
        val nav = NavState(Dest.Settings, showMore = true)
        nav.openCoin("ETHUSDT", null)
        assertEquals(Dest.Markets, nav.dest)
        assertEquals("ETHUSDT", nav.symbol)
        assertNull(nav.timeframe)
        assertFalse(nav.showMore)
        nav.showMore = true
        nav.openLearn(null)
        assertEquals(Dest.Learn, nav.dest)
        assertFalse(nav.showMore)
    }

    @Test
    fun `an open trade says how it will end in words that match its exit`() {
        assertTrue(exitText(FakeApp.trade(1, mode = "trail")).startsWith("Safety stop 95.000, then a stop that follows"))
        assertEquals("Held for a fixed time.", exitText(FakeApp.trade(1, mode = "held")))
        assertEquals("Held for a fixed time.", exitText(FakeApp.trade(1, mode = "learned")))
        assertEquals("Held for a fixed time.", exitText(FakeApp.trade(1, mode = null)))
        val learned = FakeApp.trade(1, mode = "learned").copy(target = 104.0)
        assertEquals("Target 104.00, stop 95.000.", exitText(learned))
        val classic = FakeApp.trade(1, mode = null).copy(target = 110.0)
        assertEquals("Target 110.00, stop 95.000.", exitText(classic))
    }

    @Test
    fun `the words for how a trade closed are plain`() {
        assertEquals("target hit", exitWords("target"))
        assertEquals("stopped out", exitWords("stop"))
        assertEquals("time limit", exitWords("time"))
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
}
