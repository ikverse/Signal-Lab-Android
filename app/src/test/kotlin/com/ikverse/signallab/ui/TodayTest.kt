package com.ikverse.signallab.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.ikverse.signallab.analyst.ReportText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Today says and how it reads: the wording is plain functions, the screen is drawn for real. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class TodayTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(app: FakeApp, nav: NavState = NavState()) {
        rule.setContent { SignalLabApp(app, debug = false, webViews = false, nav = nav) }
        rule.waitForIdle()
    }

    private fun textIn(tag: String, text: String) =
        rule.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()

    private fun exists(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()

    // --- wording -------------------------------------------------------------------------------

    @Test
    fun `a card says what matched and never tells anyone to act`() {
        assertEquals("SOL has a setup.", PlainWords.headline("SOL", lab = false))
        assertEquals("SOL matched one of your lab ideas.", PlainWords.headline("SOL", lab = true))
    }

    @Test
    fun `every built-in pattern has its own reason and a lab idea says it is unproven`() {
        val reasons = listOf("trend_ma50_1d", "donchian20_1d", "tsmom_4h", "xsmom_1d", "fade_1h", "intraday_mom_1h", "intraday_breakout_1h", "bullish_harami_1h", "bullish_hikkake_1h")
            .map { PlainWords.why(it) }
        assertEquals("each is worded on its own", reasons.size, reasons.toSet().size)
        assertTrue(PlainWords.why("lab3_1h").contains("unproven"))
        assertTrue(PlainWords.isLab("lab12_4h"))
        assertFalse(PlainWords.isLab("donchian20_1h"))
        assertEquals("A pattern the app watches for matched.", PlainWords.why("nothing_known"))
    }

    @Test
    fun `how long ago reads in the largest whole unit`() {
        val now = 10_000_000_000L
        assertEquals("just now", PlainWords.ago(now - 20_000, now))
        assertEquals("just now", PlainWords.ago(now + 5_000, now))
        assertEquals("12 min ago", PlainWords.ago(now - 12 * 60_000L, now))
        assertEquals("1 hour ago", PlainWords.ago(now - 90 * 60_000L, now))
        assertEquals("5 hours ago", PlainWords.ago(now - 5 * 3_600_000L, now))
        assertEquals("1 day ago", PlainWords.ago(now - 30 * 3_600_000L, now))
        assertEquals("3 days ago", PlainWords.ago(now - 80 * 3_600_000L, now))
    }

    @Test
    fun `a move is a fraction of the start, and nothing is made up when a price is missing`() {
        assertEquals(0.066, PlainWords.move(100.0, 106.6)!!, 1e-9)
        assertEquals(-0.05, PlainWords.move(100.0, 95.0)!!, 1e-9)
        assertNull(PlainWords.move(null, 1.0))
        assertNull(PlainWords.move(100.0, null))
        assertNull(PlainWords.move(0.0, 1.0))
        assertNull(PlainWords.move(100.0, Double.NaN))
        assertEquals("+6.6%", PlainWords.signed(0.066))
        assertEquals("−5.0%", PlainWords.signed(-0.05))
    }

    @Test
    fun `the record is quoted only once enough trades have finished`() {
        assertEquals("No finished trades yet, so no record to show.", PlainWords.record(0, null))
        assertEquals("Only 4 finished so far, too few to say how often it wins.", PlainWords.record(4, 0.5))
        assertEquals("Won 41 of 60 finished practice trades.", PlainWords.record(60, 41 / 60.0))
        assertEquals("Only 3 finished so far, too few to say how often it wins.", PlainWords.record(3, null))
        assertEquals("1 trade", PlainWords.count(1, "trade"))
        assertEquals("3 trades", PlainWords.count(3, "trade"))
    }

    @Test
    fun `where the price stands against the entry`() {
        assertEquals("Now 102.00, +2.0% since the practice entry.", PlainWords.since(100.0, 102.0))
        assertNull(PlainWords.since(100.0, null))
    }

    @Test
    fun `the setups are the open trades, newest first`() {
        val old = FakeApp.trade(1)
        val closed = FakeApp.trade(2, net = 0.03)
        val new = FakeApp.trade(5)
        assertEquals(listOf(5L, 1L), setupsOf(listOf(old, closed, new)).map { it.id })
        assertEquals("SOL", baseOf("SOLUSDT"))
    }

    @Test
    fun `a trade finds the scorecard row of its pattern on its own chart`() {
        val t = FakeApp.trade(1, variant = "donchian20_1h", tf = "1h")
        val row = ScoreRowUi("donchian20_1h", "x", "1h", 1, 12, 0.5, 0.0, 0.0, 0.0, 0.0, "", "", false)
        assertEquals(row, rowOf(t, listOf(row)))
        assertNull(rowOf(FakeApp.trade(2, variant = "donchian20_1h", tf = "4h"), listOf(row)))
    }

    // --- the screen ----------------------------------------------------------------------------

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `the app opens on Today, with a card for each open trade`() {
        show(FakeApp.full())
        assertTrue(exists("today"))
        textIn("setup-1", "BTC has a setup.")
        textIn("setup-1", "Entry price")
        textIn("setup-1", "Profit goal")
        textIn("setup-1", "Loss limit")
        assertTrue("a closed trade is not a setup", !exists("setup-2"))
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `with nothing open Today says so and promises a notification`() {
        show(FakeApp.full().also { it.trades.state.value = emptyList() })
        assertTrue(exists("today-empty"))
        textIn("today-empty", "Nothing worth looking at right now")
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `See chart opens the coin on its chart with the trade's lines`() {
        val nav = NavState()
        show(FakeApp.full(), nav)
        rule.onNodeWithTag("setup-chart-1").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Dest.Markets, nav.dest)
        assertEquals("BTCUSDT", nav.symbol)
        assertEquals("1h", nav.timeframe)
        assertEquals(true, nav.chartChoice[1L])
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        assertEquals("Back returns to Today", Dest.Today, nav.dest)
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `live prices are watched only while Today shows`() {
        val app = FakeApp.full()
        show(app)
        assertEquals(1, app.markets.open)
        rule.onNodeWithTag("nav-Scorecard").performClick()
        rule.waitForIdle()
        assertEquals(0, app.markets.open)
    }

    @Config(qualifiers = "w1200dp-h700dp")
    @Test
    fun `a wide screen shows the list beside the chart and plan of the chosen setup`() {
        show(FakeApp.full().also { it.trades.state.value = listOf(FakeApp.trade(1), FakeApp.trade(4, "ETHUSDT")) })
        assertTrue(exists("setup-detail"))
        assertTrue(exists("divider-today"))
        rule.onNode(hasText("ETH has a setup.") and hasAnyAncestor(hasTestTag("setup-4")), useUnmergedTree = true).assertExists()
        // The newest setup is chosen first; touching the other one changes the detail.
        rule.onNodeWithTag("setup-1").performClick()
        rule.waitForIdle()
        rule.onNode(hasText("BTC has a setup.") and hasAnyAncestor(hasTestTag("setup-detail")), useUnmergedTree = true).assertExists()
    }
}

/** What "Does it work?" says: the sums behind the headline and the words for each verdict. */
class WorksWordsTest {
    private fun closed(id: Long, net: Double, at: Long) = FakeApp.trade(id, net = net).let { it.copy(closed = it.closed!!.copy(exitTime = at)) }

    @Test
    fun `no finished trade means no account`() {
        assertNull(PlainWords.account(emptyList()))
        assertNull(PlainWords.account(listOf(FakeApp.trade(1))))
    }

    @Test
    fun `the account adds finished trades in the order they finished`() {
        val a = PlainWords.account(listOf(closed(1, -0.01, 300), closed(2, 0.03, 100), closed(3, 0.02, 200), FakeApp.trade(4)))!!
        assertEquals(3, a.n)
        assertEquals(2, a.wins)
        assertEquals(0.04, a.total, 1e-9)
        assertEquals(listOf(0.03, 0.05, 0.04), a.curve.map { Math.round(it * 1e9) / 1e9 })
        assertEquals(0.04 / 3, a.average, 1e-9)
    }

    @Test
    fun `each verdict has a plain word and a reason that names how many trades are behind it`() {
        assertEquals("Working", PlainWords.verdictWord("Edge"))
        assertEquals("Not working", PlainWords.verdictWord("Losing"))
        assertEquals("No better than guessing", PlainWords.verdictWord("No edge"))
        assertEquals("Too early to tell", PlainWords.verdictWord("No verdict"))
        assertEquals("Too early to tell", PlainWords.verdictWord("Judged on live trades only"))
        assertEquals("Only 9 finished so far. It needs about 30 before we say anything.", PlainWords.verdictReason("No verdict", 9))
        assertEquals("Nothing finished yet. It needs about 30 trades before we say anything.", PlainWords.verdictReason("No verdict", 0))
        assertEquals("Did worse than random entries over 35 finished trades.", PlainWords.verdictReason("Losing", 35))
        assertTrue(PlainWords.verdictReason("Edge", 1).contains("1 finished trade,"))
    }

    @Test
    fun `patterns are listed working first and not working last`() {
        fun row(v: String, verdict: String, closed: Int) = ScoreRowUi(v, v, "1h", 0, closed, null, null, null, null, null, verdict, "", false)
        val order = worksOrder(listOf(row("a", "Losing", 40), row("b", "No verdict", 9), row("c", "Edge", 60), row("d", "No edge", 50), row("e", "No verdict", 20)))
        assertEquals(listOf("c", "e", "b", "d", "a"), order.map { it.variant })
    }
}

/** The Coins list: what each coin's own trades say, its warning, and the coins offered. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class CoinsListTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(app: FakeApp) {
        rule.setContent { SignalLabApp(app, debug = false, webViews = false, nav = NavState(Dest.Markets)) }
        rule.waitForIdle()
    }

    private fun exists(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()

    private fun textIn(tag: String, text: String) =
        rule.onNode(androidx.compose.ui.test.hasText(text, substring = true) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true).assertExists()

    @Test
    fun `a coin's note follows its finished trades`() {
        assertEquals("Too few finished trades to judge yet.", PlainWords.coinNote(4, 4))
        assertEquals("Signals on this coin have worked well.", PlainWords.coinNote(10, 6))
        assertEquals("Working about as well as the rest.", PlainWords.coinNote(10, 5))
        assertEquals("Mostly losing. Consider removing it.", PlainWords.coinNote(10, 4))
        assertEquals("Mostly losing. Consider removing it.", PlainWords.coinNote(10, 2))
    }

    @Config(qualifiers = "w1200dp-h700dp")
    @Test
    fun `the list says what each coin's trades show and marks a coin with a warning`() {
        show(FakeApp.full())
        textIn("note-BTCUSDT", "Too few finished trades")
        fun marked(symbol: String) = rule.onAllNodesWithTag("warn-$symbol", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue("BTC has a warning alert", marked("BTCUSDT"))
        assertTrue(!marked("ETHUSDT"))
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `coins the user does not watch yet are offered with an Add button that puts them in the active list`() {
        val app = FakeApp.full()
        app.lists.offered = listOf(OfferUi("BTCUSDT", "BTC", 2e9), OfferUi("DOGEUSDT", "DOGE", 9e8))
        show(app)
        rule.onNodeWithTag("coin-list").performScrollToNode(hasTestTag("add-DOGEUSDT"))
        assertTrue(exists("add-DOGEUSDT"))
        assertTrue("a coin already watched is not offered", !exists("add-BTCUSDT"))
        textIn("add-DOGEUSDT", "Among the most traded today")
        rule.onNodeWithTag("add-button-DOGEUSDT").performClick()
        rule.waitUntil(3_000) { app.lists.log.any { it.startsWith("add 1 DOGEUSDT") } }
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `nothing is offered when no list is switched on`() {
        val app = FakeApp.full()
        app.lists.state.value = app.lists.state.value.map { it.copy(active = false) }
        app.lists.offered = listOf(OfferUi("DOGEUSDT", "DOGE", 9e8))
        show(app)
        assertTrue(!exists("add-DOGEUSDT"))
    }
}

/** The first-run introduction. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class IntroTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun exists(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()

    private fun show(app: FakeApp, nav: NavState = NavState()) {
        rule.setContent { SignalLabApp(app, debug = false, webViews = false, nav = nav) }
        rule.waitForIdle()
    }

    private fun appWithIntro(): FakeApp = FakeApp.full(FakePanels(mapOf("intro" to "")))

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `a new user sees three cards, and the last one ends it`() {
        val app = appWithIntro()
        show(app)
        assertTrue(exists("intro"))
        assertTrue(!exists("today"))
        rule.onNodeWithTag("intro-next").performClick()
        rule.onNodeWithTag("intro-next").performClick()
        rule.waitForIdle()
        assertTrue(exists("intro-learn"))
        rule.onNodeWithTag("intro-next").performClick()
        rule.waitForIdle()
        assertTrue(!exists("intro"))
        assertTrue(exists("today"))
        assertEquals(listOf("intro seen"), (app.panels as FakePanels).log)
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `skipping ends it at once, and it does not come back`() {
        val app = appWithIntro()
        show(app)
        rule.onNodeWithTag("intro-skip").performClick()
        rule.waitForIdle()
        assertTrue(!exists("intro"))
        assertEquals("seen", (app.panels as FakePanels).state.value!!["intro"])
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `Learn the basics first ends it and opens a Learn page`() {
        val nav = NavState()
        show(appWithIntro(), nav)
        repeat(2) { rule.onNodeWithTag("intro-next").performClick() }
        rule.waitForIdle()
        rule.onNodeWithTag("intro-learn").performClick()
        rule.waitForIdle()
        assertTrue(!exists("intro"))
        assertEquals(Dest.Learn, nav.dest)
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `nothing covers the app before the saved choices are read, or before any list exists`() {
        show(FakeApp.full(FakePanels(initial = null)))
        assertTrue(!exists("intro"))
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `Settings can show it again`() {
        val app = FakeApp.full()
        show(app, NavState(Dest.More))
        rule.onNodeWithTag("show-intro").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(exists("intro"))
    }
}

/** A shared link is not an answer. */
class ReportLinkTest {
    @Test
    fun `a bare link is a link`() {
        assertTrue(ReportText.isLink("https://claude.ai/share/0a1b2c3d-1111-2222-3333-444455556666"))
        assertTrue(ReportText.isLink("  https://claude.ai/share/abc  \n"))
    }

    @Test
    fun `a title and a link is a link`() {
        assertTrue(ReportText.isLink("Signal Lab report: What my results mean\nhttps://claude.ai/share/abc"))
    }

    @Test
    fun `an answer is not, even when it mentions an address`() {
        val answer = "Signal Lab report: Weekly review\n\n" + "Your pullback pattern beat random entries on 41 of 60 trades. ".repeat(4) + "More at https://example.com/x"
        assertFalse(ReportText.isLink(answer))
        assertFalse(ReportText.isLink("A short answer with no address."))
        assertFalse(ReportText.isLink(""))
    }
}

/** A foldable's fold. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class FoldTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun exists(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a divider starts on a vertical fold when both panes keep their room`() {
        val book = FoldInfo(horizontal = false, halfOpen = false, startDp = 400f, sizeDp = 0f)
        assertEquals(400f - 14f, FoldMath.firstPane(book, originDp = 0f, totalDp = 800f)!!, 0.01f)
        assertEquals("the rail takes room before the panes", 400f - 72f - 14f, FoldMath.firstPane(book, 72f, 728f)!!, 0.01f)
        assertEquals("a hinge with width is split evenly", 400f - 14f + 10f, FoldMath.firstPane(book.copy(sizeDp = 20f), 0f, 800f)!!, 0.01f)
    }

    @Test
    fun `no fold, a fold across the screen or one that leaves a pane too narrow changes nothing`() {
        assertNull(FoldMath.firstPane(null, 0f, 800f))
        assertNull(FoldMath.firstPane(FoldInfo(horizontal = true, halfOpen = true, startDp = 400f), 0f, 800f))
        assertNull(FoldMath.firstPane(FoldInfo(false, false, 100f), 0f, 800f))
        assertNull(FoldMath.firstPane(FoldInfo(false, false, 700f), 0f, 800f))
    }

    @Test
    fun `the held-up half stays between a fifth and four fifths of the screen`() {
        assertEquals(400f, FoldMath.topHalf(FoldInfo(true, true, 400f), 800f), 0.01f)
        assertEquals(160f, FoldMath.topHalf(FoldInfo(true, true, 30f), 800f), 0.01f)
        assertEquals(640f, FoldMath.topHalf(FoldInfo(true, true, 790f), 800f), 0.01f)
    }

    @Test
    fun `only a half-open fold across the screen stands like a laptop`() {
        assertTrue(FoldInfo(true, true, 300f).tabletop)
        assertFalse(FoldInfo(true, false, 300f).tabletop)
        assertFalse(FoldInfo(false, true, 300f).tabletop)
        assertFalse(FoldInfo(true, true, 0f).tabletop)
    }

    @Config(qualifiers = "w700dp-h900dp")
    @Test
    fun `standing like a laptop shows the coin on the top half and the app below`() {
        val app = FakeApp.full()
        app.markets.priceState.value = mapOf("BTCUSDT" to 65_100.0)
        rule.setContent { SignalLabApp(app, debug = false, webViews = false, nav = NavState(), fold = FoldInfo(true, true, 450f)) }
        rule.waitForIdle()
        assertTrue(exists("tabletop-header"))
        assertTrue("the app is still there", exists("today"))
        rule.onNode(androidx.compose.ui.test.hasText("65100.00") and hasTestTag("tabletop-price"), useUnmergedTree = true).assertExists()
    }

    @Config(qualifiers = "w700dp-h900dp")
    @Test
    fun `without a fold there is no held-up half`() {
        rule.setContent { SignalLabApp(FakeApp.full(), debug = false, webViews = false, nav = NavState()) }
        rule.waitForIdle()
        assertTrue(!exists("tabletop-header"))
    }
}
