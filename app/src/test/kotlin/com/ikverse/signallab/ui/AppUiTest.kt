package com.ikverse.signallab.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertWidthIsEqualTo
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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.scan.AlertText
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val PHONE_UPRIGHT = "w400dp-h800dp"
private const val PHONE_SIDEWAYS = "w700dp-h360dp"
private const val TABLET = "w1200dp-h700dp"

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
        // A place far along the bottom bar is scrolled to before it is touched; the side rail does not scroll.
        if (t.startsWith("nav-") && exists("bottom-bar")) tag(t).performScrollTo()
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
    fun `a phone held upright has a bottom bar that scrolls through every place, and one panel at a time`() {
        show(FakeApp.full())
        assertTrue(exists("bottom-bar"))
        assertTrue(!exists("rail"))
        for (d in Dest.entries) assertTrue(exists("nav-${d.name}"))
        assertTrue(exists("markets-compact"))
        assertTrue(exists("coin-list"))
        assertTrue(!exists("details"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the three Markets tabs share the whole row, with no empty space on the right`() {
        show(FakeApp.full())
        val tabs = MarketsTab.entries.map { tag("markets-tab-${it.name}").getBoundsInRoot() }
        assertEquals(400f, tabs.last().right.value, 0.5f)
        for (t in tabs) assertEquals(400f / 3, t.width.value, 1f)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the app starts below a notch and beside a punch hole, however big the phone says they are`() {
        show(FakeApp.full())
        val before = tag("markets-compact").getBoundsInRoot()
        rule.runOnUiThread {
            val cutout = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(48, 90, 0, 0)).build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, cutout)
        }
        rule.waitForIdle()
        val after = tag("markets-compact").getBoundsInRoot()
        assertTrue("top ${after.top} not below the cutout (was ${before.top})", after.top.value >= 90f)
        assertTrue("left ${after.left} not beside the cutout", after.left.value >= 48f)
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
    fun `the bar reaches Learn, Lists and Settings and each opens`() {
        show(FakeApp.full())
        click("nav-Settings")
        assertTrue(exists("settings"))
        click("nav-Lists")
        assertTrue(exists("lists"))
        click("nav-Learn")
        assertTrue(exists("learn-screen"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `back from a place goes to Markets, and Markets lets the app close`() {
        show(FakeApp.full())
        click("nav-Trades")
        back()
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
        click("nav-Learn")
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
        rule.onAllNodes(hasText("What is this pattern?")).onFirst().performScrollTo().performClick()
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
        click("nav-Learn")
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
        click("nav-Lists")
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
        click("nav-Lists")
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
        click("nav-Settings")
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

    // --- updates (in settings) ---------------------------------------------------------------------

    private fun offered(status: UpdateStatus = UpdateStatus.AVAILABLE, canInstall: Boolean = true, message: String? = null, progress: Float? = null) = UpdateUi(
        status, version = "0.2.0", notes = "Adds the updater.", progress = progress, message = message, checkedAt = 1_700_000_000_000L, canInstall = canInstall,
    )

    private fun openUpdates(app: FakeApp) {
        show(app)
        click("nav-Settings")
        tag("updates").performScrollTo()
    }

    private fun updatesShowAndWork() {
        val app = FakeApp.full()
        app.settings.updateState.value = offered()
        openUpdates(app)
        tag("update-status").assertTextEquals("Version 0.2.0 is available.")
        tag("update-notes").assertTextEquals("Adds the updater.")
        rule.onNodeWithText("Download and install").performScrollTo().assertIsDisplayed()
        tag("update-check").performScrollTo().performClick()
        tag("update-install").performScrollTo().performClick()
        rule.waitUntil(3_000) { app.settings.log.size == 2 }
        assertEquals(listOf("check update", "install update"), app.settings.log)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `updates on a phone held upright show the offer and both buttons work`() = updatesShowAndWork()

    @Config(qualifiers = PHONE_SIDEWAYS)
    @Test
    fun `updates on a phone held sideways show the offer and both buttons work`() = updatesShowAndWork()

    @Config(qualifiers = TABLET)
    @Test
    fun `updates on a tablet show the offer and both buttons work`() = updatesShowAndWork()

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `with nothing on offer there is no install button and no notes, only a check`() {
        val app = FakeApp.full()
        app.settings.updateState.value = UpdateUi(UpdateStatus.UP_TO_DATE, checkedAt = 1_700_000_000_000L)
        openUpdates(app)
        tag("update-status").assertTextEquals("You have the newest version.")
        assertTrue(!exists("update-install"))
        assertTrue(!exists("update-notes"))
        assertTrue(exists("update-check"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `while checking or downloading, neither button can be pressed`() {
        val app = FakeApp.full()
        app.settings.updateState.value = offered(UpdateStatus.DOWNLOADING, canInstall = true, progress = 0.4f)
        openUpdates(app)
        tag("update-status").assertTextEquals("Downloading version 0.2.0: 40%")
        tag("update-check").performScrollTo().performClick()
        tag("update-install").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(app.settings.log.isEmpty())
        app.settings.updateState.value = UpdateUi(UpdateStatus.CHECKING)
        rule.waitForIdle()
        tag("update-check").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(app.settings.log.isEmpty())
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a failure shows its reason, and Install appears only when trying again could work`() {
        val app = FakeApp.full()
        app.settings.updateState.value = UpdateUi(UpdateStatus.FAILED, message = "The update is signed with a different key than this app.", canInstall = false)
        openUpdates(app)
        tag("update-message").assertTextContains("different key", substring = true)
        assertTrue(!exists("update-install"))
        app.settings.updateState.value = offered(UpdateStatus.FAILED, canInstall = true, message = "The download stopped early.")
        rule.waitForIdle()
        tag("update-message").assertTextEquals("The download stopped early.")
        rule.onNodeWithText("Install").performScrollTo().assertIsDisplayed()
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the explanation of how updates are checked is on the screen`() {
        openUpdates(FakeApp.full())
        rule.onNodeWithText("signed with the same key as this app", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("asks you before it installs anything", substring = true).performScrollTo().assertExists()
    }

    // --- resizable panels (landscape) ---------------------------------------------------------------

    private fun density() = rule.activity.resources.displayMetrics.density

    private fun widthOf(t: String) = tag(t).getBoundsInRoot().width

    private fun heightOf(t: String) = tag(t).getBoundsInRoot().height

    private fun dragBy(t: String, dx: Float, dy: Float = 0f) {
        tag(t).performTouchInput {
            down(center)
            moveBy(Offset(dx, dy))
            up()
        }
        rule.waitForIdle()
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `a wide screen starts with the coin list and the details at their usual widths`() {
        show(FakeApp.full())
        tag("pane-coins").assertWidthIsEqualTo(280.dp)
        tag("pane-details").assertWidthIsEqualTo(320.dp)
        tag("divider-coins").assertExists()
        tag("divider-details").assertExists()
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `dragging a divider resizes the panel beside it, the right way round`() {
        show(FakeApp.full())
        val coins = widthOf("pane-coins").value
        dragBy("divider-coins", 100f * density())
        val grown = widthOf("pane-coins").value
        assertTrue("coins $coins -> $grown", grown > coins + 50f && grown <= coins + 101f)

        val details = widthOf("pane-details").value
        dragBy("divider-details", -80f * density())
        val widened = widthOf("pane-details").value
        assertTrue("details $details -> $widened", widened > details + 40f && widened <= details + 81f)
        dragBy("divider-details", 60f * density())
        assertTrue(widthOf("pane-details").value < widened - 30f)
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `a panel cannot be made narrower than its minimum, and the chart is always left its own`() {
        show(FakeApp.full())
        dragBy("divider-coins", -3000f * density())
        assertEquals(PaneMath.MIN_SIDE, widthOf("pane-coins").value, 0.6f)
        dragBy("divider-coins", 5000f * density())
        assertTrue("chart ${widthOf("pane-chart").value}", widthOf("pane-chart").value >= PaneMath.MIN_CHART - 0.6f)
        dragBy("divider-details", -5000f * density())
        assertTrue("chart ${widthOf("pane-chart").value}", widthOf("pane-chart").value >= PaneMath.MIN_CHART - 0.6f)
        assertTrue(widthOf("pane-coins").value >= PaneMath.MIN_SIDE - 0.6f)
        assertTrue(widthOf("pane-details").value >= PaneMath.MIN_SIDE - 0.6f)
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `the arrow hides a panel and brings it back at the size it had`() {
        show(FakeApp.full())
        dragBy("divider-coins", 40f * density())
        val before = widthOf("pane-coins").value
        val chartBefore = widthOf("pane-chart").value
        rule.onNodeWithContentDescription("Hide coins").performClick()
        rule.waitForIdle()
        assertTrue(!exists("pane-coins"))
        assertTrue(widthOf("pane-chart").value > chartBefore + before - 1f)
        rule.onNodeWithContentDescription("Show coins").assertExists()
        rule.onNodeWithContentDescription("Hide coins").assertDoesNotExist()
        // A hidden panel is not dragged back to a size by touching its divider.
        dragBy("divider-coins", 200f * density())
        assertTrue(!exists("pane-coins"))
        rule.onNodeWithContentDescription("Show coins").performClick()
        rule.waitForIdle()
        assertEquals(before, widthOf("pane-coins").value, 0.6f)

        rule.onNodeWithContentDescription("Hide details").performClick()
        rule.waitForIdle()
        assertTrue(!exists("pane-details"))
        assertTrue(exists("pane-chart"))
        rule.onNodeWithContentDescription("Show details").performClick()
        rule.waitForIdle()
        assertTrue(exists("pane-details"))
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `the chart has no hide button, and hiding both side panels leaves the chart the whole width`() {
        show(FakeApp.full())
        rule.onNodeWithContentDescription("Hide chart").assertDoesNotExist()
        rule.onNodeWithContentDescription("Hide coins").performClick()
        rule.onNodeWithContentDescription("Hide details").performClick()
        rule.waitForIdle()
        assertTrue(exists("pane-chart"))
        assertTrue(exists("chart-placeholder"))
        assertTrue(widthOf("pane-chart").value > 1200f - 112f - 2 * PaneMath.DIVIDER - 2f)
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `sizes are saved when changed, and nothing is saved before the user changes anything`() {
        val first = FakePanels()
        show(FakeApp.full(first))
        assertTrue(first.log.isEmpty())
        dragBy("divider-coins", 60f * density())
        rule.onNodeWithContentDescription("Hide details").performClick()
        rule.waitForIdle()
        assertTrue(first.log.isNotEmpty())
        val saved = first.state.value!!.getValue("markets")
        assertTrue(saved, saved.contains("wide.coins=") && saved.contains("!wide.details"))
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `a saved layout is applied once it has been read`() {
        show(FakeApp.full(FakePanels(mapOf("markets" to "wide.coins=200.0;wide.details=250.0"))))
        tag("pane-coins").assertWidthIsEqualTo(200.dp)
        tag("pane-details").assertWidthIsEqualTo(250.dp)
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `a saved hidden panel stays hidden`() {
        show(FakeApp.full(FakePanels(mapOf("markets" to "!wide.coins"))))
        assertTrue(!exists("pane-coins"))
        rule.onNodeWithContentDescription("Show coins").assertExists()
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `a layout saved in damaged form is ignored and the usual sizes apply`() {
        show(FakeApp.full(FakePanels(mapOf("markets" to ";;=;wide.coins=abc;wide.details=-5;!;wide.coins=NaN"))))
        tag("pane-coins").assertWidthIsEqualTo(280.dp)
        tag("pane-details").assertWidthIsEqualTo(320.dp)
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `nothing is saved before the saved layout has been read`() {
        val waiting = FakePanels(initial = null)
        show(FakeApp.full(waiting))
        dragBy("divider-coins", 60f * density())
        assertTrue(waiting.log.isEmpty())
        tag("pane-coins").assertExists()
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `the layout survives the screen being rebuilt`() {
        val restore = StateRestorationTester(rule)
        val app = FakeApp.full(FakePanels(initial = null))
        restore.setContent { SignalLabApp(app, debug = false, webViews = false) }
        rule.waitForIdle()
        dragBy("divider-coins", 70f * density())
        rule.onNodeWithContentDescription("Hide details").performClick()
        rule.waitForIdle()
        val width = widthOf("pane-coins").value
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(width, widthOf("pane-coins").value, 0.6f)
        assertTrue(!exists("pane-details"))
    }

    @Config(qualifiers = PHONE_SIDEWAYS)
    @Test
    fun `a small phone held sideways can resize the list and hide the details under the chart`() {
        show(FakeApp.full())
        tag("pane-coins").assertWidthIsEqualTo(220.dp)
        val chart = heightOf("pane-chart").value
        val details = heightOf("pane-details").value
        // Down makes the details shorter and the chart taller; up makes them taller again.
        dragBy("divider-details", 0f, 40f * density())
        val shorter = heightOf("pane-details").value
        assertTrue("details $details -> $shorter", shorter < details - 20f)
        assertTrue(heightOf("pane-chart").value > chart + 20f)
        dragBy("divider-details", 0f, -40f * density())
        assertTrue(heightOf("pane-details").value > shorter + 10f)
        assertTrue(heightOf("pane-chart").value >= PaneMath.MIN_CHART_HEIGHT - 0.6f)
        dragBy("divider-coins", 50f * density())
        assertTrue(widthOf("pane-coins").value > 220f)
        rule.onNodeWithContentDescription("Hide details").performClick()
        rule.waitForIdle()
        assertTrue(!exists("pane-details"))
        assertTrue(heightOf("pane-chart").value > chart)
        rule.onNodeWithContentDescription("Hide coins").performClick()
        rule.waitForIdle()
        assertTrue(!exists("pane-coins"))
        assertTrue(exists("pane-chart"))
    }

    @Config(qualifiers = PHONE_SIDEWAYS)
    @Test
    fun `the details under the chart cannot be dragged so tall that the chart disappears`() {
        show(FakeApp.full())
        dragBy("divider-details", 0f, -5000f * density())
        assertTrue("chart ${heightOf("pane-chart").value}", heightOf("pane-chart").value >= PaneMath.MIN_CHART_HEIGHT - 0.6f)
        dragBy("divider-details", 0f, 5000f * density())
        assertTrue(heightOf("pane-details").value >= PaneMath.MIN_PANE_HEIGHT - 0.6f)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `held upright there are no dividers, and the three tabs are as they were`() {
        show(FakeApp.full())
        assertTrue(!exists("divider-coins"))
        assertTrue(!exists("divider-details"))
        assertTrue(!exists("pane-coins"))
        rule.onNodeWithContentDescription("Chart").assertExists()
        rule.onNodeWithContentDescription("Details").assertExists()
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `every touchable thing on a wide screen, the buttons on the dividers too, is at least 48 dp tall`() {
        show(FakeApp.full())
        everyTouchTargetIsBigEnough()
        rule.onNodeWithContentDescription("Hide coins").performClick()
        rule.waitForIdle()
        everyTouchTargetIsBigEnough()
    }

    @Config(qualifiers = PHONE_SIDEWAYS)
    @Test
    fun `every touchable thing on a small phone held sideways is at least 48 dp tall`() {
        show(FakeApp.full())
        everyTouchTargetIsBigEnough()
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
        everyTouchTargetIsBigEnough()
        for (m in listOf("nav-Learn", "nav-Lists", "nav-Settings")) {
            click(m)
            everyTouchTargetIsBigEnough()
        }
    }

    // --- adding coins to a list that already exists ------------------------------------------------------

    private fun appWithOneCoinList() = FakeApp(
        lists = FakeLists(listOf(ListUi(1, "My coins", true, listOf("BTCUSDT"), listOf("1h")))),
        markets = FakeMarkets(listOf(FakeApp.btc)),
    )

    private fun openTheListAndAddCoins(app: FakeApp) {
        show(app)
        click("nav-Lists")
        rule.onNodeWithText("My coins").performClick()
        rule.waitForIdle()
        click("add-coins")
    }

    private fun appears(description: String) = rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `add coins opens a picker whose coins are really on the screen, and touching one adds it to the list`() {
        val app = appWithOneCoinList()
        openTheListAndAddCoins(app)
        assertTrue(exists("add-coins-pane"))
        assertTrue(!exists("list-detail"))
        rule.waitUntil(3_000) { appears("ETH, not ticked") }
        rule.onNodeWithContentDescription("ETH, not ticked").assertIsDisplayed()
        tag("chosen-count").assertTextEquals("1 of 30 in this list")
        rule.onNodeWithContentDescription("BTC, ticked").assertExists() // already in the list: ticked, and touching it does nothing
        rule.onNodeWithContentDescription("BTC, ticked").performClick()
        rule.onNodeWithContentDescription("ETH, not ticked").performClick()
        rule.waitUntil(3_000) { app.lists.log.contains("add 1 ETHUSDT") }
        assertEquals(listOf("add 1 ETHUSDT"), app.lists.log)
        rule.waitUntil(3_000) { appears("ETH, ticked") }
        tag("chosen-count").assertTextEquals("2 of 30 in this list")
        click("add-done")
        assertTrue(exists("list-detail"))
        rule.waitUntil(3_000) { rule.onAllNodesWithText("ETH").fetchSemanticsNodes().isNotEmpty() }
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a refused addition says why inside the picker and leaves the coin unticked`() {
        val app = appWithOneCoinList()
        app.lists.refuse = "A list holds at most 30 coins."
        openTheListAndAddCoins(app)
        pick("ETH")
        rule.waitUntil(3_000) { exists("problem") }
        tag("problem").assertTextEquals("A list holds at most 30 coins.")
        assertTrue(exists("add-coins-pane"))
        rule.onNodeWithContentDescription("ETH, not ticked").assertExists()
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `back from the picker returns to the list, and back again to the lists`() {
        val app = appWithOneCoinList()
        openTheListAndAddCoins(app)
        back()
        assertTrue(exists("list-detail"))
        assertTrue(!exists("add-coins-pane"))
        back()
        assertTrue(!exists("list-detail"))
        assertTrue(exists("lists"))
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `on a wide screen the picker takes the right-hand pane and the lists stay beside it`() {
        val app = appWithOneCoinList()
        show(app)
        click("nav-Lists")
        click("add-coins")
        assertTrue(exists("add-coins-pane"))
        assertTrue(exists("pane-list"))
        rule.waitUntil(3_000) { appears("ETH, not ticked") }
        rule.onNodeWithContentDescription("ETH, not ticked").assertIsDisplayed()
        click("add-done")
        assertTrue(exists("list-detail"))
    }

    // --- the coin picker's rankings ------------------------------------------------------------------------

    private fun chooseSource(s: PickSource) {
        tag("source-${s.name}").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun chooseWindow(w: PickWindow) {
        tag("window-${w.name}").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun asked(app: FakeApp, source: PickSource, window: PickWindow? = null) =
        app.lists.offerCalls.any { it.second == source && (window == null || it.third == window) }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the picker starts on volume and offers six rankings`() {
        val app = FakeApp()
        show(app)
        rule.waitUntil(3_000) { app.lists.offerCalls.isNotEmpty() }
        assertEquals(Triple("", PickSource.VOLUME, PickWindow.H24), app.lists.offerCalls.first())
        for (s in PickSource.entries) assertTrue("source ${s.name}", exists("source-${s.name}"))
        assertEquals(6, PickSource.entries.size)
        assertTrue(!exists("windows"))
        tag("source-note").assertTextContains("most trading", substring = true)
        assertEquals(0, app.lists.listingChecks)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `choosing gainers asks for gainers, shows each coin's change, and the window chips come and go`() {
        val app = FakeApp()
        app.lists.offeredBy[PickSource.GAINERS] = listOf(OfferUi("SOLUSDT", "SOL", 5e8, 0.183))
        show(app)
        chooseSource(PickSource.GAINERS)
        rule.waitUntil(3_000) { asked(app, PickSource.GAINERS, PickWindow.H24) }
        rule.waitUntil(3_000) { rule.onAllNodesWithText("+18.30% in 24h", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!appears("BTC, not ticked"))
        tag("source-note").assertTextContains("Biggest rises over 24h", substring = true)
        assertTrue(exists("windows"))
        chooseWindow(PickWindow.D7)
        rule.waitUntil(3_000) { asked(app, PickSource.GAINERS, PickWindow.D7) }
        rule.waitUntil(3_000) { rule.onAllNodesWithText("+18.30% in 7d", substring = true).fetchSemanticsNodes().isNotEmpty() }
        chooseSource(PickSource.LOSERS)
        rule.waitUntil(3_000) { asked(app, PickSource.LOSERS, PickWindow.D7) }
        assertTrue("the window chosen is kept when switching between gainers and losers", exists("windows"))
        chooseSource(PickSource.VOLUME)
        rule.waitUntil(3_000) { appears("BTC, not ticked") }
        assertTrue(!exists("windows"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `every ranking words the number it ranked by`() {
        val app = FakeApp()
        app.lists.offeredBy[PickSource.ACTIVE] = listOf(OfferUi("SOLUSDT", "SOL", 5e8, 12_345.0))
        app.lists.offeredBy[PickSource.VOLATILE] = listOf(OfferUi("SOLUSDT", "SOL", 5e8, 0.444))
        app.lists.offeredBy[PickSource.NEW] = listOf(OfferUi("SOLUSDT", "SOL", 5e8, (System.currentTimeMillis() - 3 * 86_400_000L - 1_000).toDouble()))
        show(app)
        chooseSource(PickSource.ACTIVE)
        rule.waitUntil(3_000) { rule.onAllNodesWithText("12.3K trades in 24h", substring = true).fetchSemanticsNodes().isNotEmpty() }
        chooseSource(PickSource.VOLATILE)
        rule.waitUntil(3_000) { rule.onAllNodesWithText("44.4% range in 24h", substring = true).fetchSemanticsNodes().isNotEmpty() }
        chooseSource(PickSource.NEW)
        rule.waitUntil(3_000) { rule.onAllNodesWithText("listed 3 days ago", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `new starts the lookup of listing days once, shows how far it has got, asks again as it finds more, and offers a retry`() {
        val app = FakeApp()
        app.lists.check.value = ListingCheckUi(running = true, done = 40, total = 400)
        show(app)
        assertTrue(!exists("listing-progress"))
        chooseSource(PickSource.NEW)
        rule.waitUntil(3_000) { app.lists.listingChecks == 1 }
        tag("listing-progress").assertTextEquals("Checking listing dates: 40 of 400…")
        val before = app.lists.offerCalls.count { it.second == PickSource.NEW }
        app.lists.check.value = ListingCheckUi(running = true, done = 50, total = 400)
        rule.waitUntil(3_000) { app.lists.offerCalls.count { it.second == PickSource.NEW } > before }
        app.lists.check.value = ListingCheckUi(running = false, done = 200, total = 400, failed = "Could not reach Binance")
        rule.waitUntil(3_000) { exists("listing-failed") }
        assertTrue(!exists("listing-progress"))
        rule.onNodeWithText("Could not check every listing date: Could not reach Binance").assertExists()
        rule.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(2, app.lists.listingChecks)
        chooseSource(PickSource.VOLUME)
        assertTrue(!exists("listing-failed"))
        assertTrue(!exists("listing-progress"))
        assertEquals("the other rankings never start it", 2, app.lists.listingChecks)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `an empty answer says why in words, and a coin ticked under one ranking stays ticked under another`() {
        val app = FakeApp()
        app.lists.offeredBy[PickSource.GAINERS] = listOf(OfferUi("SOLUSDT", "SOL", 5e8, 0.1))
        app.lists.offeredBy[PickSource.LOSERS] = emptyList()
        show(app)
        chooseSource(PickSource.GAINERS)
        pick("SOL")
        tag("start").assertTextContains("1 coin", substring = true)
        chooseSource(PickSource.LOSERS)
        rule.waitUntil(3_000) { rule.onAllNodesWithText("Nothing to show").fetchSemanticsNodes().isNotEmpty() }
        app.lists.offerProblem = "Could not reach Binance"
        chooseSource(PickSource.GAINERS)
        chooseSource(PickSource.LOSERS)
        rule.waitUntil(3_000) { rule.onAllNodesWithText("Could not load this list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Could not reach Binance").assertExists()
        app.lists.offerProblem = null
        chooseSource(PickSource.VOLUME)
        rule.waitUntil(3_000) { appears("SOL, ticked") }
    }

    @Config(qualifiers = PHONE_SIDEWAYS)
    @Test
    fun `on a small phone held sideways the first-run page scrolls and the coin list still gets a usable height`() {
        val app = FakeApp()
        show(app)
        rule.waitUntil(3_000) { exists("offers") }
        tag("offers").performScrollTo()
        assertTrue(heightOf("offers").value > 100f)
        tag("start").assertIsDisplayed()
    }

    // --- resizable panes on Lists and Learn ---------------------------------------------------------------------

    @Config(qualifiers = TABLET)
    @Test
    fun `lists and learn start at their usual widths with a divider, and the page beside has room`() {
        show(FakeApp.full())
        click("nav-Lists")
        tag("pane-list").assertWidthIsEqualTo(320.dp)
        tag("divider-lists").assertExists()
        assertTrue(widthOf("pane-page").value >= PaneMath.MIN_PAGE)
        click("nav-Learn")
        tag("pane-list").assertWidthIsEqualTo(300.dp)
        tag("divider-learn").assertExists()
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `dragging the divider on lists and on learn resizes the list the right way round`() {
        show(FakeApp.full())
        for ((dest, divider) in listOf("nav-Lists" to "divider-lists", "nav-Learn" to "divider-learn")) {
            click(dest)
            val before = widthOf("pane-list").value
            dragBy(divider, 100f * density())
            val wider = widthOf("pane-list").value
            assertTrue("$dest $before -> $wider", wider > before + 50f && wider <= before + 101f)
            dragBy(divider, -60f * density())
            assertTrue("$dest $wider -> ${widthOf("pane-list").value}", widthOf("pane-list").value < wider - 30f)
        }
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `the list cannot be dragged narrower than its minimum, nor so wide that the page disappears`() {
        show(FakeApp.full())
        for (dest in listOf("nav-Lists", "nav-Learn")) {
            click(dest)
            val divider = if (dest == "nav-Lists") "divider-lists" else "divider-learn"
            dragBy(divider, -3000f * density())
            assertEquals(PaneMath.MIN_SIDE, widthOf("pane-list").value, 0.6f)
            dragBy(divider, 5000f * density())
            assertTrue("$dest page ${widthOf("pane-page").value}", widthOf("pane-page").value >= PaneMath.MIN_PAGE - 0.6f)
            assertTrue(widthOf("pane-list").value >= PaneMath.MIN_SIDE - 0.6f)
        }
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `the arrow hides the list and brings it back at the size it had, on lists and on learn`() {
        show(FakeApp.full())
        for ((dest, label) in listOf("nav-Lists" to "lists", "nav-Learn" to "pages")) {
            click(dest)
            dragBy(if (label == "lists") "divider-lists" else "divider-learn", 40f * density())
            val before = widthOf("pane-list").value
            val pageBefore = widthOf("pane-page").value
            rule.onNodeWithContentDescription("Hide $label").performClick()
            rule.waitForIdle()
            assertTrue(!exists("pane-list"))
            assertTrue(widthOf("pane-page").value > pageBefore + before - 1f)
            rule.onNodeWithContentDescription("Show $label").performClick()
            rule.waitForIdle()
            assertEquals(before, widthOf("pane-list").value, 0.6f)
        }
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `lists and learn keep their sizes under their own names, and nothing is saved until the user changes something`() {
        val panels = FakePanels()
        show(FakeApp.full(panels))
        click("nav-Lists")
        click("nav-Learn")
        assertTrue(panels.log.isEmpty())
        click("nav-Lists")
        dragBy("divider-lists", 60f * density())
        click("nav-Learn")
        rule.onNodeWithContentDescription("Hide pages").performClick()
        rule.waitForIdle()
        val saved = panels.state.value!!
        assertTrue(saved.toString(), saved.getValue("lists").contains("list="))
        assertTrue(saved.toString(), saved.getValue("learn").contains("!list"))
        assertTrue("markets is untouched", "markets" !in saved)
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `saved sizes for lists and learn are applied, and damaged ones are ignored`() {
        show(FakeApp.full(FakePanels(mapOf("lists" to "list=200.0", "learn" to "list=250.0"))))
        click("nav-Lists")
        tag("pane-list").assertWidthIsEqualTo(200.dp)
        click("nav-Learn")
        tag("pane-list").assertWidthIsEqualTo(250.dp)
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `a damaged saved layout for lists is ignored`() {
        show(FakeApp.full(FakePanels(mapOf("lists" to ";;=;list=abc;list=-5;!;list=NaN"))))
        click("nav-Lists")
        tag("pane-list").assertWidthIsEqualTo(320.dp)
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `a saved hidden list stays hidden`() {
        show(FakeApp.full(FakePanels(mapOf("learn" to "!list"))))
        click("nav-Learn")
        assertTrue(!exists("pane-list"))
        rule.onNodeWithContentDescription("Show pages").assertExists()
    }

    @Config(qualifiers = PHONE_SIDEWAYS)
    @Test
    fun `a small phone held sideways can resize lists and learn too`() {
        show(FakeApp.full())
        for ((dest, divider) in listOf("nav-Lists" to "divider-lists", "nav-Learn" to "divider-learn")) {
            click(dest)
            assertTrue("$dest", exists(divider))
            assertTrue(widthOf("pane-page").value >= PaneMath.MIN_PAGE - 0.6f)
            val before = widthOf("pane-list").value
            dragBy(divider, -40f * density())
            assertTrue("$dest $before -> ${widthOf("pane-list").value}", widthOf("pane-list").value < before - 10f)
            dragBy(divider, 5000f * density())
            assertTrue(widthOf("pane-page").value >= PaneMath.MIN_PAGE - 0.6f)
        }
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `held upright lists and learn have no dividers, as before`() {
        show(FakeApp.full())
        click("nav-Lists")
        assertTrue(!exists("divider-lists"))
        assertTrue(!exists("pane-list"))
        click("nav-Learn")
        assertTrue(!exists("divider-learn"))
        assertTrue(!exists("pane-list"))
        click("learn-trend")
        assertTrue(!exists("divider-learn"))
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `every touchable thing on lists and learn, the divider buttons too, is at least 48 dp tall`() {
        show(FakeApp.full())
        for (dest in listOf("nav-Lists", "nav-Learn")) {
            click(dest)
            everyTouchTargetIsBigEnough()
        }
        click("nav-Lists")
        click("add-coins")
        everyTouchTargetIsBigEnough()
    }

    // --- keeping the screen on and dimmed --------------------------------------------------------------------------

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the screen switch turns keeping the screen on and dimmed on and off, and the dimness choices appear only while it is on`() {
        val app = FakeApp.full()
        openSettings(app)
        assertTrue(!exists("dim-levels"))
        rule.onNodeWithContentDescription("Keep the screen on and dim it, off").performScrollTo().performClick()
        rule.waitUntil(3_000) { app.settings.log.contains("dim true") }
        rule.waitUntil(3_000) { exists("dim-levels") }
        rule.onNodeWithContentDescription("Dim, chosen").assertExists()
        everyTouchTargetIsBigEnough()
        tag("dim-VERY_DIM").performScrollTo().performClick()
        rule.waitUntil(3_000) { app.settings.log.contains("dim level VERY_DIM") }
        rule.onNodeWithContentDescription("Very dim, chosen").assertExists()
        rule.onNodeWithContentDescription("Dim, chosen").assertDoesNotExist()
        tag("dim-SOFT").performScrollTo().performClick()
        rule.waitUntil(3_000) { app.settings.log.contains("dim level SOFT") }
        assertEquals(DimLevel.SOFT, app.settings.state.value.dimLevel)
        rule.onNodeWithContentDescription("Keep the screen on and dim it, on").performScrollTo().performClick()
        rule.waitUntil(3_000) { app.settings.log.contains("dim false") }
        rule.waitUntil(3_000) { !exists("dim-levels") }
        assertEquals(listOf("dim true", "dim level VERY_DIM", "dim level SOFT", "dim false"), app.settings.log)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the dim switch explains what it does and what it does not`() {
        openSettings(FakeApp.full())
        rule.onNodeWithText("Your usual brightness comes back", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("Scanning does not depend on it", substring = true).performScrollTo().assertExists()
    }

    // --- the side bar on the right --------------------------------------------------------------------------------

    private fun appWithRail(onRight: Boolean) = FakeApp.full().also { it.settings.state.value = it.settings.state.value.copy(railOnRight = onRight) }

    private fun left(t: String) = tag(t).getBoundsInRoot().left.value

    private fun right(t: String) = tag(t).getBoundsInRoot().right.value

    private fun rootWidth() = rule.onRoot().getBoundsInRoot().width.value

    @Config(qualifiers = TABLET)
    @Test
    fun `the side bar is on the left unless the setting says otherwise`() {
        show(appWithRail(false))
        assertTrue("rail at ${left("rail")}", left("rail") < 20f)
        assertTrue(right("rail") < left("pane-chart"))
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `with the setting on the side bar is on the right edge and the panels are on its left`() {
        show(appWithRail(true))
        assertTrue("rail ends at ${right("rail")} of ${rootWidth()}", right("rail") > rootWidth() - 20f)
        assertTrue(right("pane-chart") < left("rail"))
        assertTrue(right("pane-details") < left("rail"))
        assertTrue("the coin list is now the leftmost thing", left("pane-coins") < 20f)
        assertEquals("the bar keeps its width", 112f, widthOf("rail").value, 0.6f)
    }

    @Config(qualifiers = PHONE_SIDEWAYS)
    @Test
    fun `a small phone held sideways moves its side bar too`() {
        show(appWithRail(true))
        assertTrue(right("rail") > rootWidth() - 20f)
        assertTrue(right("pane-chart") < left("rail"))
        for (d in Dest.entries) assertTrue("nav-${d.name}", exists("nav-${d.name}"))
    }

    @Config(qualifiers = PHONE_SIDEWAYS)
    @Test
    fun `every place is still reached from the side bar on the right`() {
        show(appWithRail(true))
        click("nav-Alerts")
        assertTrue(exists("alerts"))
        click("nav-Lists")
        assertTrue(exists("lists"))
        click("nav-Markets")
        assertTrue(exists("markets-medium"))
        assertTrue(right("rail") > rootWidth() - 20f)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `held upright the bar stays along the bottom whatever the setting`() {
        show(appWithRail(true))
        assertTrue(exists("bottom-bar"))
        assertTrue(!exists("rail"))
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `the switch in Settings moves the bar as soon as it is touched, and back`() {
        val app = appWithRail(false)
        show(app)
        click("nav-Settings")
        assertTrue(left("rail") < 20f)
        rule.onNodeWithContentDescription("Side bar on the right, off").performScrollTo().performClick()
        rule.waitUntil(3_000) { app.settings.log.contains("rail right true") }
        rule.waitForIdle()
        assertTrue("rail now at ${left("rail")}", left("rail") > rootWidth() - 130f)
        assertTrue(exists("settings"))
        rule.onNodeWithContentDescription("Side bar on the right, on").performScrollTo().performClick()
        rule.waitUntil(3_000) { app.settings.log.contains("rail right false") }
        rule.waitForIdle()
        assertTrue(left("rail") < 20f)
        assertEquals(listOf("rail right true", "rail right false"), app.settings.log)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the side bar switch says what it does and when`() {
        openSettings(FakeApp.full())
        rule.onNodeWithText("under your right thumb", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("Held upright, the bar along the bottom stays where it is", substring = true).performScrollTo().assertExists()
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `every touchable thing is still 48 dp tall with the side bar on the right`() {
        show(appWithRail(true))
        everyTouchTargetIsBigEnough()
        click("nav-Learn")
        everyTouchTargetIsBigEnough()
        click("nav-Settings")
        everyTouchTargetIsBigEnough()
    }

    // --- the navigation review: links, notes, Back, kept state, a deleted list ----------------------------------------

    private fun noteText() = tag("coin-note")

    private fun chosen(label: String) = rule.onNodeWithContentDescription("$label, chosen")

    private fun twoLists() = FakeLists(
        listOf(
            ListUi(1, "First", true, listOf("BTCUSDT"), listOf("1h")),
            ListUi(2, "Second", false, listOf("ETHUSDT"), listOf("1h")),
        ),
    )

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a coin in no switched-on list opens as itself with a note, never as the first coin`() {
        val nav = NavState()
        show(FakeApp.full(), nav = nav)
        rule.runOnUiThread { nav.openCoin("ADAUSDT", "1h") }
        rule.waitForIdle()
        tag("chart-placeholder").assertTextContains("ADAUSDT 1h", substring = true)
        noteText().assertTextContains("ADA is not in a list that is switched on, so it is not being watched", substring = true)
        rule.onNodeWithText("Open Lists").performClick()
        rule.waitForIdle()
        assertTrue(exists("lists"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the note is on Details too, and a watched coin has none`() {
        val nav = NavState()
        show(FakeApp.full(), nav = nav)
        rule.runOnUiThread { nav.openLink(Link("ADAUSDT", null, LinkPlace.DETAILS), fromApp = false) }
        rule.waitForIdle()
        assertTrue(exists("details"))
        noteText().assertTextContains("ADA is not in a list", substring = true)
        rule.runOnUiThread { nav.go(Dest.Markets); nav.symbol = "ETHUSDT"; nav.timeframe = null; nav.marketsTab = MarketsTab.Chart }
        rule.waitForIdle()
        tag("chart-placeholder").assertTextContains("ETHUSDT 1h", substring = true)
        assertTrue(!exists("coin-note"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a chart size the coin is not watched on says so and shows the one it is`() {
        val nav = NavState()
        show(FakeApp.full(), nav = nav)
        rule.runOnUiThread { nav.openCoin("BTCUSDT", "1d") }
        rule.waitForIdle()
        tag("chart-placeholder").assertTextContains("BTCUSDT 1h", substring = true)
        noteText().assertTextContains("BTC is not watched on the 1-day chart, so this shows the 1-hour chart.", substring = true)
        assertTrue("it is watched, so there is no way to the lists offered", rule.onAllNodesWithText("Open Lists").fetchSemanticsNodes().isEmpty())
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `with no list switched on a coin that was asked for still opens, and with none asked for the page says nothing is watched`() {
        val nav = NavState()
        show(FakeApp(lists = FakeLists(listOf(FakeApp.list)), markets = FakeMarkets(emptyList())), nav = nav)
        assertTrue(exists("markets-empty"))
        rule.runOnUiThread { nav.openCoin("ADAUSDT", "1h") }
        rule.waitForIdle()
        assertTrue(!exists("markets-empty"))
        tag("chart-placeholder").assertTextContains("ADAUSDT 1h", substring = true)
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `on a wide screen the coin asked for is charted and the list beside it is still the watched coins`() {
        val nav = NavState()
        show(FakeApp.full(), nav = nav)
        rule.runOnUiThread { nav.openCoin("ADAUSDT", "1h") }
        rule.waitForIdle()
        tag("chart-placeholder").assertTextContains("ADAUSDT 1h", substring = true)
        assertEquals("one note over the chart and one over Details, since both are on show", 2, rule.onAllNodesWithTag("coin-note").fetchSemanticsNodes().size)
        assertTrue(exists("coin-BTCUSDT"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `Show on chart for a trade of a switched-off list shows that coin, and Back returns to the trade`() {
        val app = FakeApp(
            markets = FakeMarkets(listOf(FakeApp.btc)), lists = FakeLists(listOf(FakeApp.list)),
            trades = FakeTrades(listOf(FakeApp.trade(3, "ETHUSDT", net = -0.01))),
        )
        show(app)
        click("nav-Trades")
        click("trade-3")
        rule.onNodeWithText("Show on chart").performClick()
        rule.waitForIdle()
        tag("chart-placeholder").assertTextContains("ETHUSDT", substring = true)
        noteText().assertTextContains("ETH is not in a list", substring = true)
        back()
        assertTrue(exists("trades"))
        rule.onNodeWithText("Show on chart").assertExists() // the trade is still opened out
    }

    // links from notifications

    private fun openLink(app: FakeApp, link: Link) {
        app.link.value = link
        rule.waitUntil(3_000) { app.link.value == null }
        rule.waitForIdle()
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a closed-trade notification opens Trades for that coin with that trade opened out`() {
        val app = FakeApp.full()
        show(app)
        openLink(app, Link("BTCUSDT", null, LinkPlace.TRADES, tradeId = 2))
        assertTrue(exists("trades"))
        rule.onNodeWithContentDescription("Filter by coin, pattern or chart").assertTextContains("BTC")
        tag("trades-summary").assertTextContains("1 open · 1 closed", substring = true)
        rule.onNodeWithText("Result after costs").assertExists()
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the opened and closed summaries open Trades on that filter`() {
        val app = FakeApp.full()
        show(app)
        openLink(app, Link(null, null, LinkPlace.TRADES, status = "open"))
        chosen("Open").assertExists()
        tag("trades-summary").assertTextContains("1 open · 0 closed", substring = true)
        openLink(app, Link(null, null, LinkPlace.TRADES, status = "closed"))
        chosen("Closed").assertExists()
        tag("trades-summary").assertTextContains("0 open · 2 closed", substring = true)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a warning notification opens the coin on its Details tab`() {
        val app = FakeApp.full()
        show(app)
        openLink(app, Link("BTCUSDT", "1h", LinkPlace.DETAILS))
        assertTrue(exists("details"))
        assertTrue(!exists("chart-placeholder"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a problem notification opens Alerts on Problems, and the blocked one opens Settings`() {
        val app = FakeApp.full()
        app.alerts.state.value = app.alerts.state.value + FakeApp.alert(9, "problem", symbol = null, tf = null)
        show(app)
        openLink(app, Link(null, null, LinkPlace.ALERTS, group = "problems"))
        assertTrue(exists("alerts"))
        chosen("Problems").assertExists()
        openLink(app, Link(null, null, LinkPlace.SETTINGS))
        assertTrue(exists("settings"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `an alert in the inbox goes where its notification goes`() {
        val app = FakeApp.full()
        val at = 1_700_000_000_000L
        app.alerts.state.value = listOf(
            AlertUi(1, at, "exit", "Closed one", "b", "BTCUSDT", "1h", AlertText.tradesLink("BTCUSDT", 2)),
            AlertUi(2, at, "warning", "A warning", "b", "BTCUSDT", "1h", AlertText.detailsLink("BTCUSDT", com.ikverse.signallab.engine.Timeframe.H1)),
            AlertUi(3, at, "problem", "Blocked", "b", null, null, AlertText.SETTINGS_LINK),
            AlertUi(4, at, "problem", "Nothing to open", "b", null, null, null),
        )
        show(app)
        click("nav-Alerts")
        click("alert-1")
        assertTrue(exists("trades"))
        rule.onNodeWithText("Result after costs").assertExists()
        back()
        assertTrue("Back returns to Alerts", exists("alerts"))
        click("alert-2")
        assertTrue(exists("details"))
        back()
        click("alert-3")
        assertTrue(exists("settings"))
        back()
        click("alert-4")
        assertTrue("a problem with nowhere to go stays where it is", exists("alerts"))
    }

    // Back

    @Config(qualifiers = TABLET)
    @Test
    fun `on a wide screen one Back leaves after a notification opened a coin`() {
        val app = FakeApp.full()
        show(app)
        openLink(app, Link("ETHUSDT", "1h"))
        back()
        assertTrue(rule.activity.isFinishing)
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `on a wide screen Back from a Learn page leaves Learn instead of jumping to another page`() {
        val nav = NavState()
        show(FakeApp.full(), nav = nav)
        click("nav-Learn")
        click("learn-scorecard")
        back()
        assertEquals(Dest.Markets, nav.dest)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `Back from a jump returns to the tab that was left, with its filter, and a tab chosen from the bar forgets the trail`() {
        val nav = NavState()
        show(FakeApp.full(), nav = nav)
        click("nav-Trades")
        rule.onNodeWithContentDescription("Open").performClick()
        rule.waitForIdle()
        click("trade-1")
        rule.onNodeWithText("Show on chart").performClick()
        rule.waitForIdle()
        assertEquals(Dest.Markets, nav.dest)
        back()
        assertEquals(Dest.Trades, nav.dest)
        chosen("Open").assertExists()
        rule.onNodeWithText("What is this pattern?").performClick()
        rule.waitForIdle()
        assertEquals(Dest.Learn, nav.dest)
        back()
        assertEquals(Dest.Trades, nav.dest)
        rule.onNodeWithText("Show on chart").performClick()
        rule.waitForIdle()
        click("nav-Alerts")
        back()
        assertEquals("tapping a tab forgets the way back to Trades", Dest.Markets, nav.dest)
    }

    // kept state

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `Trades keeps its filter and search, and Alerts its group, while another tab is on show`() {
        val app = FakeApp.full()
        show(app)
        click("nav-Trades")
        rule.onNodeWithContentDescription("Closed").performClick()
        rule.onNodeWithContentDescription("Filter by coin, pattern or chart").performTextReplacement("ETH")
        rule.waitForIdle()
        click("nav-Scorecard")
        click("nav-Trades")
        chosen("Closed").assertExists()
        rule.onNodeWithContentDescription("Filter by coin, pattern or chart").assertTextContains("ETH")
        click("nav-Alerts")
        rule.onNodeWithContentDescription("Warnings").performClick()
        rule.waitForIdle()
        click("nav-Trades")
        click("nav-Alerts")
        chosen("Warnings").assertExists()
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `a half-filled New list is still there after another tab has been on show`() {
        show(FakeApp.full())
        click("nav-Lists")
        click("new-list")
        rule.onNodeWithContentDescription("List name").performTextReplacement("Mine")
        rule.waitForIdle()
        click("nav-Settings")
        assertTrue(!exists("setup"))
        click("nav-Lists")
        assertTrue(exists("setup"))
        rule.onNodeWithContentDescription("List name").assertTextContains("Mine")
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the open list in Lists is still open after another tab has been on show`() {
        show(FakeApp(lists = twoLists(), markets = FakeMarkets(listOf(FakeApp.btc))))
        click("nav-Lists")
        rule.onNodeWithText("Second").performClick()
        rule.waitForIdle()
        assertTrue(exists("list-detail"))
        click("nav-Alerts")
        click("nav-Lists")
        assertTrue(exists("list-detail"))
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `a link's filters survive the screen being rebuilt`() {
        val restore = StateRestorationTester(rule)
        val app = FakeApp.full()
        restore.setContent { SignalLabApp(app, debug = false, webViews = false) }
        rule.waitForIdle()
        openLink(app, Link(null, null, LinkPlace.TRADES, status = "closed"))
        chosen("Closed").assertExists()
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertTrue(exists("trades"))
        chosen("Closed").assertExists()
    }

    // a deleted list

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `deleting one of two lists on a narrow phone returns to the lists and never to a blank screen`() {
        val nav = NavState()
        val app = FakeApp(lists = twoLists(), markets = FakeMarkets(listOf(FakeApp.btc)))
        show(app, nav = nav)
        click("nav-Lists")
        rule.onNodeWithText("Second").performClick()
        rule.waitForIdle()
        click("delete")
        click("delete")
        rule.waitUntil(3_000) { app.lists.log.contains("delete 2") }
        rule.waitUntil(3_000) { !exists("list-detail") }
        assertTrue("the list of lists is showing", exists("new-list"))
        rule.onNodeWithText("First").assertIsDisplayed()
        back()
        assertEquals("Back is not swallowed by a screen that is no longer there", Dest.Markets, nav.dest)
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `a list that disappears under an open one, from outside the screen, returns to the lists`() {
        val app = FakeApp(lists = twoLists(), markets = FakeMarkets(listOf(FakeApp.btc)))
        show(app)
        click("nav-Lists")
        rule.onNodeWithText("Second").performClick()
        rule.waitForIdle()
        assertTrue(exists("list-detail"))
        app.lists.state.value = app.lists.state.value.filter { it.id != 2L }
        rule.waitForIdle()
        assertTrue(!exists("list-detail"))
        assertTrue(exists("new-list"))
    }

    @Config(qualifiers = PHONE_UPRIGHT)
    @Test
    fun `the picker closes with the list it was adding to`() {
        val app = FakeApp(lists = twoLists(), markets = FakeMarkets(listOf(FakeApp.btc)))
        show(app)
        click("nav-Lists")
        rule.onNodeWithText("Second").performClick()
        rule.waitForIdle()
        click("add-coins")
        assertTrue(exists("add-coins-pane"))
        app.lists.state.value = app.lists.state.value.filter { it.id != 2L }
        rule.waitForIdle()
        assertTrue(!exists("add-coins-pane"))
        assertTrue(exists("new-list"))
    }

    @Config(qualifiers = TABLET)
    @Test
    fun `on a wide screen deleting the open list shows the next one`() {
        val app = FakeApp(lists = twoLists(), markets = FakeMarkets(listOf(FakeApp.btc)))
        show(app)
        click("nav-Lists")
        rule.onAllNodesWithText("Second")[0].performClick()
        rule.waitForIdle()
        click("delete")
        click("delete")
        rule.waitUntil(3_000) { app.lists.log.contains("delete 2") }
        rule.waitForIdle()
        assertTrue(exists("list-detail"))
        assertTrue(rule.onAllNodesWithText("First").fetchSemanticsNodes().size >= 2) // in the list and as the title of the open one
        assertTrue(rule.onAllNodesWithText("Second").fetchSemanticsNodes().isEmpty())
    }
}
