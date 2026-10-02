package com.ikverse.signallab.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val PHONE_UPRIGHT = "w400dp-h800dp"
private const val PHONE_SIDEWAYS = "w700dp-h360dp"
private const val TABLET = "w1000dp-h700dp"

/** The screens, drawn for real (without web views) and used the way a person would: what shows, what a touch does, what survives rotation. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class AppUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(app: FakeApp, debug: Boolean = false, nav: NavState? = null) {
        rule.setContent { if (nav == null) SignalLabApp(app, debug, webViews = false) else SignalLabApp(app, debug, webViews = false, nav = nav) }
        rule.waitForIdle()
    }

    private fun tag(t: String) = rule.onNodeWithTag(t)

    private fun click(t: String) {
        tag(t).performClick()
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun pick(base: String) {
        rule.waitUntil(3_000) { rule.onAllNodesWithContentDescription("$base, not ticked").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("$base, not ticked").performClick()
        rule.waitForIdle()
    }

    private fun exists(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()

    // --- first run ----------------------------------------------------------------------

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `while the saved lists are still being read nothing is decided`() {
        show(FakeApp(lists = FakeLists(loaded = false)))
        assertTrue(exists("loading"))
        assertTrue(!exists("setup"))
        assertTrue(!exists("bottom-bar"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `with no list at all the app asks for coins first, and nothing else`() {
        show(FakeApp(lists = FakeLists()))
        assertTrue(exists("setup"))
        assertTrue(!exists("bottom-bar"))
        assertTrue(!exists("rail"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `choosing coins and starting makes the list, switched on, with the charts chosen, and opens the app`() {
        val app = FakeApp(markets = FakeMarkets(listOf(FakeApp.btc)))
        show(app)
        pick("BTC")
        tag("start").assertTextContains("1 coin", substring = true)
        click("start")
        rule.waitUntil(3_000) { app.lists.log.any { it.startsWith("create") } }
        assertEquals("create My coins BTCUSDT 15m,1h,4h true", app.lists.log.single { it.startsWith("create") })
        rule.waitUntil(3_000) { exists("bottom-bar") }
        assertTrue(!exists("setup"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the start button does nothing until a coin is chosen`() {
        val app = FakeApp()
        show(app)
        click("start")
        assertTrue(app.lists.log.isEmpty())
        assertTrue(exists("setup"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a refused list says why and stays on the same screen`() {
        val app = FakeApp()
        app.lists.refuse = "Too many coins on 1-minute charts."
        show(app)
        pick("BTC")
        click("start")
        rule.waitUntil(3_000) { exists("problem") }
        tag("problem").assertTextEquals("Too many coins on 1-minute charts.")
        assertTrue(exists("setup"))
    }

    // --- shape by width -------------------------------------------------------------------

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a phone held upright has a bottom bar with four places and More, and one panel at a time`() {
        show(FakeApp.full())
        assertTrue(exists("bottom-bar"))
        assertTrue(!exists("rail"))
        for (d in Dest.Primary) assertTrue(exists("nav-${d.name}"))
        assertTrue(exists("nav-More"))
        assertTrue(!exists("nav-Learn"))
        assertTrue(exists("markets-compact"))
        assertTrue(exists("coin-list"))
        assertTrue(!exists("details"))
    }

    @Config(qualifiers = PHONE_SIDEWAYS)
    @Test
    fun `a small phone held sideways has a side rail with all seven places and two panels`() {
        show(FakeApp.full())
        assertTrue(exists("rail"))
        assertTrue(!exists("bottom-bar"))
        for (d in Dest.entries) assertTrue(d.name, exists("nav-${d.name}"))
        assertTrue(exists("markets-medium"))
        assertTrue(exists("coin-list"))
        assertTrue(exists("details"))
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `a big screen shows the coins, the chart and the details side by side`() {
        show(FakeApp.full())
        assertTrue(exists("rail"))
        assertTrue(exists("markets-wide"))
        assertTrue(exists("coin-list"))
        assertTrue(exists("chart-placeholder"))
        assertTrue(exists("details"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the compact Markets screen switches between coins, chart and details with its three tabs`() {
        show(FakeApp.full())
        rule.onNodeWithContentDescription("Chart").performClick()
        rule.waitForIdle()
        assertTrue(exists("chart-placeholder"))
        assertTrue(!exists("coin-list"))
        rule.onNodeWithContentDescription("Details").performClick()
        rule.waitForIdle()
        assertTrue(exists("details"))
        rule.onNodeWithContentDescription("Coins").performClick()
        rule.waitForIdle()
        assertTrue(exists("coin-list"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `touching a coin opens its chart and shows its price`() {
        show(FakeApp.full())
        click("coin-ETHUSDT")
        assertTrue(exists("chart-placeholder"))
        tag("live-price").assertTextEquals("3200.50")
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a live price replaces the stored one`() {
        val app = FakeApp.full()
        show(app)
        click("coin-ETHUSDT")
        app.markets.priceState.value = mapOf("ETHUSDT" to 3300.0)
        rule.waitForIdle()
        tag("live-price").assertTextEquals("3300.00")
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the chart asked for is the coin and chart chosen`() {
        val app = FakeApp.full()
        show(app)
        click("coin-BTCUSDT")
        rule.waitUntil(3_000) { app.markets.charts.isNotEmpty() }
        assertEquals("BTCUSDT" to "1h", app.markets.charts.last())
        rule.onNodeWithContentDescription("4h").performClick()
        rule.waitForIdle()
        assertEquals("BTCUSDT" to "4h", app.markets.charts.last())
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `live prices run only while Markets is on screen`() {
        val app = FakeApp.full()
        show(app)
        assertEquals(1, app.markets.open)
        assertEquals(setOf("BTCUSDT", "ETHUSDT", "SOLUSDT"), app.markets.watches.last())
        click("nav-Trades")
        assertEquals(0, app.markets.open)
        click("nav-Alerts")
        assertEquals(0, app.markets.open)
        click("nav-Markets")
        assertEquals(1, app.markets.open)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `with lists but no coin being watched Markets says so and offers Lists`() {
        val app = FakeApp(lists = FakeLists(listOf(FakeApp.list.copy(active = false))))
        show(app)
        assertTrue(exists("markets-empty"))
        rule.onNodeWithText("Open Lists").performClick()
        rule.waitForIdle()
        assertTrue(exists("lists"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a download in progress is shown above everything and goes when it ends`() {
        val app = FakeApp.full()
        show(app)
        app.lists.progress.value = DownloadUi(total = 30, ready = 4, current = "SOLUSDT", running = true)
        rule.waitForIdle()
        rule.onNodeWithText("coin 5 of 30", substring = true).assertExists()
        app.lists.progress.value = DownloadUi(total = 30, ready = 30)
        rule.waitForIdle()
        assertTrue(!exists("download-banner"))
    }

    // --- moving around -----------------------------------------------------------------------

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `More holds Learn, Lists and Settings and each opens`() {
        show(FakeApp.full())
        click("nav-More")
        assertTrue(exists("more"))
        for (d in Dest.More) assertTrue(exists("more-${d.name}"))
        click("more-Settings")
        assertTrue(exists("settings"))
        click("nav-More")
        click("more-Lists")
        assertTrue(exists("lists"))
        click("nav-More")
        click("more-Learn")
        assertTrue(exists("learn-screen"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `back from a place goes to Markets, from More goes to where it was, and Markets lets the app close`() {
        show(FakeApp.full())
        click("nav-Trades")
        back()
        assertTrue(exists("markets-compact"))
        click("nav-More")
        back()
        assertTrue(!exists("more"))
        assertTrue(exists("markets-compact"))
        click("coin-BTCUSDT")
        back()
        assertTrue(exists("coin-list"))
        // Nothing left to step out of: the back button is the system's.
        rule.runOnUiThread { assertTrue(!rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a notification link opens that coin on that chart, once`() {
        val app = FakeApp.full()
        show(app)
        click("nav-Trades")
        app.link.value = Link("ETHUSDT", "1h")
        rule.waitForIdle()
        assertTrue(exists("markets-compact"))
        assertTrue(exists("chart-placeholder"))
        tag("live-price").assertTextEquals("3200.50")
        assertEquals(null, app.link.value)
        // Using the link again would be a second tap; leaving and coming back does not repeat it.
        click("nav-Trades")
        assertTrue(exists("trades"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a link to a chart the coin is not watched on falls back to one it is watched on`() {
        val app = FakeApp.full()
        show(app)
        app.link.value = Link("ETHUSDT", "5m")
        rule.waitForIdle()
        rule.waitUntil(3_000) { app.markets.charts.any { it.first == "ETHUSDT" } }
        assertEquals("ETHUSDT" to "1h", app.markets.charts.last { it.first == "ETHUSDT" })
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `where the user is survives the screen being rebuilt`() {
        val restore = StateRestorationTester(rule)
        val app = FakeApp.full()
        restore.setContent { SignalLabApp(app, debug = false, webViews = false) }
        rule.waitForIdle()
        click("coin-ETHUSDT")
        click("nav-More")
        click("more-Learn")
        click("learn-scorecard")
        rule.onNodeWithTag("learn-text").assertTextContains("Text of the scorecard page.", substring = true)
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag("learn-text").assertTextContains("Text of the scorecard page.", substring = true)
        back()
        assertTrue(exists("learn-trend"))
        back()
        assertTrue(exists("markets-compact"))
    }

    // --- the other screens -----------------------------------------------------------------------

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the trades screen filters by status and by search, and the summary follows`() {
        show(FakeApp.full())
        click("nav-Trades")
        tag("trades-summary").assertTextEquals("1 open · 2 closed · average +1.00% after costs")
        rule.onNodeWithContentDescription("Closed").performClick()
        rule.waitForIdle()
        assertTrue(!exists("trade-1"))
        assertTrue(exists("trade-2"))
        assertTrue(exists("trade-3"))
        rule.onNodeWithContentDescription("Open").performClick()
        rule.waitForIdle()
        assertTrue(exists("trade-1"))
        assertTrue(!exists("trade-2"))
        rule.onNodeWithContentDescription("All").performClick()
        rule.onNodeWithContentDescription("Filter by coin, pattern or chart").performTextReplacement("eth")
        rule.waitForIdle()
        assertTrue(exists("trade-3"))
        assertTrue(!exists("trade-1"))
        tag("trades-summary").assertTextEquals("0 open · 1 closed · average -1.00% after costs")
        rule.onNodeWithContentDescription("Filter by coin, pattern or chart").performTextReplacement("zzz")
        rule.waitForIdle()
        rule.onNodeWithText("Nothing matches").assertIsDisplayed()
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a trade opens to show what it did, and leads to its chart and its explanation`() {
        show(FakeApp.full())
        click("nav-Trades")
        click("trade-2")
        rule.onNodeWithText("Result after costs").assertIsDisplayed()
        rule.onAllNodes(hasText("+3.00%", substring = true)).assertCountEquals(2)
        rule.onNodeWithText("Held for").assertIsDisplayed()
        rule.onAllNodes(hasText("What is this pattern?")).onFirst().performClick()
        rule.waitForIdle()
        assertTrue(exists("learn-screen"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `with no trades the screen says so instead of showing an empty list`() {
        show(FakeApp.full().also { it.trades.state.value = emptyList() })
        click("nav-Trades")
        rule.onNodeWithText("No paper trades yet").assertIsDisplayed()
        tag("trades-summary").assertTextEquals("0 open · 0 closed")
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the scorecard shows each pattern's row, its verdict, and the warning about testing many patterns`() {
        show(FakeApp.full())
        click("nav-Scorecard")
        rule.onNodeWithText("Breakout: close above the 20-candle high · 1h").assertExists()
        rule.onNodeWithText("No verdict").assertExists()
        tag("multiple-tests-note").performScrollTo().assertTextContains("20 patterns have been tested", substring = true)
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `on a wide screen the scorecard has columns`() {
        show(FakeApp.full())
        click("nav-Scorecard")
        rule.onNodeWithText("VS RANDOM").assertExists()
        rule.onNodeWithText("WIN RATE").assertExists()
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `touching a scorecard row explains that pattern`() {
        show(FakeApp.full().also { it.scorecard.state.value = ScorecardUi(listOf(ScoreRowUi("trend_ma20_1h", "Trend", "1h", 0, 0, null, null, null, null, null, "No verdict", "No verdict", false)), 1) })
        click("nav-Scorecard")
        click("score-trend_ma20_1h")
        assertTrue(exists("learn-screen"))
        rule.onNodeWithTag("learn-text").assertTextContains("Text of the trend page.", substring = true)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the alerts inbox offers only the groups that have something, and a group shows only its own`() {
        show(FakeApp.full().also { it.alerts.state.value = it.alerts.state.value.filter { a -> a.kind != "missed" } })
        click("nav-Alerts")
        rule.onNodeWithContentDescription("Warnings").assertExists()
        rule.onNodeWithContentDescription("Missed").assertDoesNotExist()
        rule.onNodeWithContentDescription("Problems").assertDoesNotExist()
        rule.onNodeWithContentDescription("Warnings").performClick()
        rule.waitForIdle()
        assertTrue(exists("alert-3"))
        assertTrue(!exists("alert-1"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `touching an alert opens the coin it is about`() {
        show(FakeApp.full())
        click("nav-Alerts")
        click("alert-1")
        assertTrue(exists("markets-compact"))
        assertTrue(exists("chart-placeholder"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `an alert with no coin does nothing when touched`() {
        show(FakeApp.full().also { it.alerts.state.value = listOf(FakeApp.alert(9, "problem", symbol = null, tf = null)) })
        click("nav-Alerts")
        click("alert-9")
        assertTrue(exists("alerts"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the learn list shows every group and a page opens, with back to the list`() {
        show(FakeApp.full())
        click("nav-More")
        click("more-Learn")
        rule.onNodeWithText("HOW IT WORKS").assertExists()
        rule.onNodeWithText("PATTERNS").assertExists()
        click("learn-trend")
        rule.onNodeWithTag("learn-text").assertTextContains("Text of the trend page.", substring = true)
        back()
        assertTrue(exists("learn-trend"))
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `on a wide screen the learn list and a page sit side by side, the first page already open`() {
        show(FakeApp.full())
        click("nav-Learn")
        assertTrue(exists("learn-trend"))
        rule.onNodeWithTag("learn-text").assertExists()
    }

    // --- lists -----------------------------------------------------------------------------------

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a list can be switched off and back on, and a refusal is shown in words`() {
        val app = FakeApp.full()
        show(app)
        click("nav-More")
        click("more-Lists")
        rule.onNodeWithText("My coins").performClick()
        rule.waitForIdle()
        assertTrue(exists("list-detail"))
        rule.onNodeWithContentDescription("Watching, on").performClick()
        rule.waitUntil(3_000) { app.lists.log.contains("active 1 false") }
        assertTrue(!app.lists.state.value.single().active)
        app.lists.refuse = "That would watch more than 150 coins at once."
        rule.onNodeWithContentDescription("Watching, off").performClick()
        rule.waitUntil(3_000) { exists("problem") }
        tag("problem").assertTextEquals("That would watch more than 150 coins at once.")
        assertTrue(!app.lists.state.value.single().active)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `deleting a list asks again first and then removes it`() {
        val app = FakeApp.full()
        show(app)
        click("nav-More")
        click("more-Lists")
        rule.onNodeWithText("My coins").performClick()
        rule.waitForIdle()
        click("delete")
        assertTrue(app.lists.log.none { it.startsWith("delete") })
        rule.onNodeWithText("Tap again to delete “My coins”").assertExists()
        click("delete")
        rule.waitUntil(3_000) { app.lists.log.contains("delete 1") }
        // The last list gone, the app asks for coins again.
        rule.waitUntil(3_000) { exists("setup") }
    }

    // --- settings --------------------------------------------------------------------------------

    private fun openSettings(app: FakeApp, debug: Boolean = false) {
        show(app, debug)
        click("nav-More")
        click("more-Settings")
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `settings shows the fee as it is typed and saving sends it as a fraction`() {
        val app = FakeApp.full()
        openSettings(app)
        rule.onNodeWithContentDescription("Exchange fee each way, in %").assertTextContains("0.1")
        rule.onNodeWithContentDescription("Exchange fee each way, in %").performTextReplacement("0.075")
        click("save-costs")
        rule.waitUntil(3_000) { app.settings.log.any { it.startsWith("extra") } }
        assertEquals(0.00075, app.settings.state.value.feePerSide, 1e-12)
        assertEquals(2, app.settings.log.size)
        assertEquals(0.00075, app.settings.log[0].removePrefix("fee ").toDouble(), 1e-12)
        assertEquals("extra 0.0 0.0", app.settings.log[1])
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a fee that is not a number is refused on the screen and nothing is saved`() {
        val app = FakeApp.full()
        openSettings(app)
        rule.onNodeWithContentDescription("Exchange fee each way, in %").performTextReplacement("abc")
        click("save-costs")
        tag("problem").assertTextContains("number", substring = true)
        assertTrue(app.settings.log.isEmpty())
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a fee the app refuses shows its reason, and the extra costs are not saved either`() {
        val app = FakeApp.full()
        app.settings.refuse = "The fee should be between 0% and 1.0% each way."
        openSettings(app)
        rule.onNodeWithContentDescription("Exchange fee each way, in %").performTextReplacement("5")
        click("save-costs")
        rule.waitUntil(3_000) { exists("problem") }
        tag("problem").assertTextEquals("The fee should be between 0% and 1.0% each way.")
        assertEquals(listOf("fee 0.05"), app.settings.log)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `each switch changes exactly its own setting`() {
        val app = FakeApp.full()
        openSettings(app)
        rule.onNodeWithContentDescription("Scan in the background, on").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Follow charts under an hour, on").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Use Binance.US, off").performScrollTo().performClick()
        rule.waitUntil(3_000) { app.settings.log.size == 3 }
        assertEquals(listOf("background false", "fast false", "us true"), app.settings.log)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the permissions panel shows what Android allows and each button opens its own screen`() {
        val app = FakeApp.full()
        openSettings(app)
        for (title in listOf("Alerts", "Exact alarms", "Battery exemption")) {
            rule.onNode(hasText(title) and hasAnyAncestor(hasTestTag("settings"))).performScrollTo().assertExists()
        }
        rule.onAllNodes(hasText("Allowed")).assertCountEquals(1)
        rule.onAllNodes(hasText("Not allowed")).assertCountEquals(2)
        rule.onAllNodes(hasText("Allow")).assertCountEquals(2)
        rule.onAllNodes(hasText("Review")).assertCountEquals(1)
        rule.onAllNodes(hasText("Allow"))[0].performScrollTo().performClick()
        rule.onAllNodes(hasText("Allow"))[1].performScrollTo().performClick()
        rule.onNodeWithText("Review").performScrollTo().performClick()
        assertEquals(listOf("open EXACT_ALARMS", "open BATTERY", "open NOTIFICATIONS"), app.settings.log)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `about shows the version and the not-advice line, and a long press opens the debug page only in a debug build`() {
        val release = FakeApp.full()
        openSettings(release, debug = false)
        tag("version").performScrollTo().assertTextEquals("Version 9.9.9")
        rule.onNodeWithText("research, not financial advice", substring = true).performScrollTo().assertExists()
        tag("version").performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue(!exists("debug"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `in a debug build a long press on the version opens the debug page, with the chart check, and back returns`() {
        val app = FakeApp.full()
        openSettings(app, debug = true)
        tag("version").performScrollTo().performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue(exists("debug"))
        rule.onNodeWithText("debug line").assertExists()
        rule.onNodeWithText("Run chart check (2,000 candles)").assertExists()
        rule.onNodeWithText("Scan now").performClick()
        assertEquals(listOf("scan"), app.debug!!.log)
        back()
        assertTrue(exists("settings"))
        assertTrue(!exists("debug"))
    }

    // --- every control is big enough to touch ---------------------------------------------------------

    private fun everyTouchTargetIsBigEnough() {
        val targets = rule.onAllNodes(hasClickAction())
        val count = targets.fetchSemanticsNodes().size
        assertTrue("nothing to touch", count > 0)
        for (i in 0 until count) {
            try {
                targets[i].assertHeightIsAtLeast(48.dp)
            } catch (e: AssertionError) {
                throw AssertionError(targets[i].printToString() + " :: " + e.message)
            }
        }
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `every touchable thing on every screen is at least 48 dp tall`() {
        show(FakeApp.full())
        everyTouchTargetIsBigEnough()
        click("coin-BTCUSDT")
        everyTouchTargetIsBigEnough()
        for (d in listOf("nav-Trades", "nav-Scorecard", "nav-Alerts")) {
            click(d)
            everyTouchTargetIsBigEnough()
        }
        click("nav-More")
        everyTouchTargetIsBigEnough()
        for (m in listOf("more-Learn", "more-Lists", "more-Settings")) {
            click(m)
            everyTouchTargetIsBigEnough()
            click("nav-More")
        }
    }
}
