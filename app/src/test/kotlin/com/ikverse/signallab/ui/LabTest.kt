package com.ikverse.signallab.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The lab's words and the draft behind the builder: plain functions, held to what the engine reads. */
class LabDraftTest {
    @Test
    fun `a draft reads as one sentence`() {
        val d = LabDraft(listOf(DraftCondition("rsi(14)", "crosses_above", "30"), DraftCondition("close", "above", "sma(200)")), listOf("1h", "4h"), "trail")
        assertEquals(
            "Enter when RSI(14) crosses above 30 and the price is above the 200-candle average price, on 1-hour and 4-hour charts. " +
                "Leave with a trailing stop (it follows the price up).",
            d.sentence,
        )
        assertEquals("Leave after 24 candles.", LabDraft(exit = "hold(24)").sentence.substringAfter("charts. "))
    }

    @Test
    fun `the written form is what the engine reads`() {
        val d = LabDraft(listOf(DraftCondition("rsi(14)", "crosses_above", "30")), listOf("4h", "1h"), "hold(12)")
        assertEquals("""{"charts":["1h","4h"],"when":[{"left":"rsi(14)","is":"crosses_above","right":"30"}],"exit":"hold(12)"}""", d.definition)
        assertEquals(d.conditions, LabDraft.read(d.definition)!!.conditions)
        assertEquals(listOf("1h", "4h"), LabDraft.read(d.definition)!!.charts)
        assertEquals("hold(12)", LabDraft.read(d.definition)!!.exit)
        assertNull(LabDraft.read("not json"))
        assertNull(LabDraft.read("""{"charts":["1h"]}"""))
    }

    @Test
    fun `a draft can be read back from its written form with a title`() {
        val back = LabDraft.read(LabDraft.examples[0].definition, "Mine")!!
        assertEquals(LabDraft.examples[0].conditions, back.conditions)
        assertEquals("Mine", back.title)
        assertEquals("Mine", back.name)
        assertEquals("RSI(14) crosses above 30", LabDraft(listOf(DraftCondition("rsi(14)", "crosses_above", "30"))).name)
    }

    @Test
    fun `what cannot be run is caught before the engine sees it`() {
        assertNull(LabDraft().problem)
        assertEquals("Add at least one condition.", LabDraft(conditions = emptyList()).problem)
        assertEquals("Choose at least one chart size.", LabDraft(charts = emptyList()).problem)
        assertEquals("A condition cannot compare two plain numbers.", LabDraft(listOf(DraftCondition("3", "above", "5"))).problem)
        assertEquals("A condition cannot compare something with itself.", LabDraft(listOf(DraftCondition("close", "above", "close"))).problem)
    }

    @Test
    fun `operands split into a block and a length`() {
        assertEquals("sma" to 50, LabVocab.split("sma(50)").let { it.first!!.id to it.second })
        assertEquals("close" to null, LabVocab.split("close").let { it.first!!.id to it.second })
        assertNull(LabVocab.split("30").first)
        assertTrue(LabVocab.isNumber("0.05"))
        assertFalse(LabVocab.isNumber("close"))
        assertEquals("volume compared with normal (20 candles)", LabVocab.phrase("volume_ratio(20)"))
        assertEquals("-0.05", LabVocab.phrase("-0.05"))
    }

    @Test
    fun `every example is a complete idea`() {
        for (e in LabDraft.examples) {
            assertNull(e.name, e.problem)
            assertTrue(e.name, e.conditions.size in 1..LabVocab.MAX_CONDITIONS)
        }
    }

    @Test
    fun `no lab wording tells anyone to buy or sell`() {
        val words = Regex("""\b(buy|buys|buying|sell|sells|selling|bought|sold)\b""", RegexOption.IGNORE_CASE)
        for (e in LabDraft.examples) assertFalse(e.sentence, words.containsMatchIn(e.sentence))
        for (b in LabVocab.blocks) assertFalse(b.menu, words.containsMatchIn(b.menu + b.phrase))
    }

    @Test
    fun `progress joins the charts of one idea and leaves the others out`() {
        fun row(v: String, closed: Int, open: Int, mean: Double?, verdict: String) = ScoreRowUi(v, v, "1h", open, closed, null, mean, null, null, null, verdict, "", false)
        val rows = listOf(row("lab3_1h", 10, 1, 0.01, "No verdict"), row("lab3_4h", 20, 0, 0.04, "No verdict"), row("lab30_1h", 99, 0, 0.9, "Edge"), row("donchian20_1h", 50, 0, 0.0, "Edge"))
        val p = LabProgress.of(3, rows)
        assertEquals(30, p.closed)
        assertEquals(1, p.open)
        assertEquals(0.03, p.average!!, 1e-9)
        assertEquals(1f, p.fraction, 0f)
        assertEquals("Slightly ahead so far", p.status)
        assertEquals("30 trades finished, average +3.0% per trade", p.result)
    }

    @Test
    fun `an idea with no trade yet says it is waiting`() {
        val p = LabProgress.of(7, emptyList())
        assertEquals("Waiting for its first trade", p.status)
        assertEquals(0f, p.fraction, 0f)
        assertEquals("No trades yet", p.result)
        assertEquals("Working", LabProgress(7, 40, 0, "Edge", 0.02, emptyList()).status)
        assertEquals("Not working", LabProgress(7, 40, 0, "Losing", -0.02, emptyList()).status)
        assertEquals("No better than guessing", LabProgress(7, 40, 0, "No edge", 0.0, emptyList()).status)
    }

    @Test
    fun `a backtest row is said in a sentence`() {
        assertEquals(
            "1-hour chart: 46 trades over 12 coins. Won 57%, average +0.9% per trade after fees.",
            labRowWords(LabRowUi("1h", 12, 46, 0.57, 0.009, 0.001, 1.0)),
        )
        assertEquals("4-hour chart: it never matched on 3 coins.", labRowWords(LabRowUi("4h", 3, 0, null, null, null, null)))
        assertTrue(labRowWords(LabRowUi("1d", 0, 0, null, null, null, null)).contains("no stored candles"))
    }
}

/** The Lab tab drawn for real. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class LabScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(app: FakeApp, nav: NavState = NavState(Dest.Lab)) {
        rule.setContent { SignalLabApp(app, debug = false, webViews = false, nav = nav) }
        rule.waitForIdle()
    }

    private fun exists(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()

    private fun click(t: String) {
        // The bars do not scroll; everything else on a page may have to.
        if (t.startsWith("nav-")) rule.onNodeWithTag(t).performClick() else rule.onNodeWithTag(t).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun textIn(tag: String, text: String) =
        rule.onNode(hasText(text, substring = true) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true).assertExists()

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun appWithTest(): FakeApp = FakeApp.full().also {
        it.analyst.labState.value = listOf(
            LabPatternUi(
                3, "RSI bounce", "RSI(14) crosses above 30 on 1h; a trailing stop", 1_700_000_000_000L, null,
                definition = """{"charts":["1h"],"when":[{"left":"rsi(14)","is":"crosses_above","right":"30"}],"exit":"trail"}""", reason = "It bounced",
            ),
        )
        it.analyst.budgetState.value = LabBudgetUi(running = 1, maxRunning = 10, newLeft = 14, maxNew = 15)
        it.scorecard.state.value = ScorecardUi(listOf(ScoreRowUi("lab3_1h", "RSI bounce", "1h", 1, 12, 0.5, 0.008, null, null, null, "No verdict", "No verdict", false)), 21)
        it.trades.state.value = it.trades.state.value + FakeApp.trade(9, variant = "lab3_1h")
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `the Lab is in the bar and its home says how much room is left`() {
        show(FakeApp.full(), NavState())
        click("nav-Lab")
        assertTrue(exists("lab-home"))
        textIn("lab-budget", "You can run 5 more tests")
        textIn("lab-budget", "5 new ideas left in the next 30 days")
        assertTrue(exists("no-lab"))
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `a live test shows how far it is from a verdict and opens on its own page`() {
        show(appWithTest())
        textIn("lab-3", "RSI bounce")
        textIn("lab-3", "12 of 30 trades")
        textIn("lab-3", "Slightly ahead so far")
        click("lab-3")
        assertTrue(exists("lab-test"))
        textIn("lab-sentence", "Enter when RSI(14) crosses above 30")
        textIn("lab-progress", "12 of 30 trades done")
        assertTrue(exists("lab-trade-9"))
        assertFalse(exists("lab-code"))
        click("lab-code-toggle")
        textIn("lab-code", "\"rsi(14)\"")
        back()
        assertTrue("Back returns to the lab's home", exists("lab-home"))
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `stopping a test takes two touches`() {
        val app = appWithTest()
        show(app)
        click("lab-3")
        click("stop-3")
        assertTrue(app.analyst.log.none { it.startsWith("stop") })
        click("stop-3")
        rule.waitUntil(3_000) { app.analyst.log.contains("stop 3") }
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `the builder says the idea in words, changes with a touch, and refuses what cannot be run`() {
        show(FakeApp.full())
        click("lab-build")
        assertTrue(exists("lab-builder"))
        textIn("builder-sentence", "Enter when RSI(14) crosses above 30")
        click("part-0-2")
        click("block-number")
        click("part-0-1")
        click("compare-below")
        textIn("builder-sentence", "RSI(14) is below")
        click("add-condition")
        assertTrue(exists("part-1-0"))
        click("chart-4h")
        textIn("builder-sentence", "1-hour and 4-hour charts")
        click("exit-hold")
        textIn("builder-sentence", "Leave after 24 candles.")
        click("stepper-more")
        textIn("builder-sentence", "Leave after 25 candles.")
        click("remove-1")
        assertTrue(!exists("part-1-0"))
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `a draft that compares a value with itself cannot be started`() {
        show(FakeApp.full())
        click("lab-build")
        click("part-0-0")
        click("block-close")
        click("part-0-2")
        click("block-close")
        assertTrue(exists("builder-flaw"))
        textIn("builder-flaw", "cannot compare something with itself")
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `Try it on past data shows a sentence per chart with the warning, and Start live test hands the idea over`() {
        val app = FakeApp.full()
        show(app)
        click("lab-build")
        click("builder-past")
        rule.waitUntil(3_000) { app.analyst.log.any { it.startsWith("backtest") } }
        textIn("lab-builder", "1-hour chart: 40 trades over 3 coins")
        textIn("lab-builder", "For reference only.")
        click("builder-start")
        rule.waitUntil(3_000) { app.analyst.log.any { it.startsWith("start") } }
        assertTrue(exists("lab-home"))
        textIn("lab-message", "Live test started")
        val started = app.analyst.log.single { it.startsWith("start") }
        assertTrue(started, started.startsWith("start RSI(14) crosses above 30"))
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `a refused start says why and stays in the builder`() {
        val app = FakeApp.full().also { it.analyst.labRefuse = "10 lab patterns are already running. Stop one first." }
        show(app)
        click("lab-build")
        click("builder-start")
        rule.waitUntil(3_000) { exists("builder-problem") }
        textIn("builder-problem", "already running")
        assertTrue(exists("lab-builder"))
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `an example opens in the builder ready to change`() {
        show(FakeApp.full())
        click("lab-examples")
        click("example-1")
        assertTrue(exists("lab-builder"))
        textIn("builder-sentence", "volume compared with normal (20 candles) is above 1.5")
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `ideas Claude suggested open in the builder`() {
        val app = FakeApp.full()
        app.analyst.state.value = listOf(
            ReportUi(
                1, 1_700_000_000_000L, "suggest-patterns", "Ideas", "text",
                listOf(
                    LabSuggestionUi("Dip buyer", "It dips then rises", "x", null, """{"charts":["1h"],"when":[{"left":"rsi(14)","is":"below","right":"30"}],"exit":"trail"}"""),
                    LabSuggestionUi("Broken", null, null, "no conditions", null),
                ),
            ),
        )
        show(app)
        click("lab-ideas")
        assertTrue(exists("lab-ideas-page"))
        textIn("idea-0", "Dip buyer")
        textIn("idea-0", "Enter when RSI(14) is below 30")
        textIn("idea-1", "Cannot be tested: no conditions")
        assertTrue(!exists("idea-open-1"))
        click("idea-open-0")
        assertTrue(exists("lab-builder"))
        textIn("builder-sentence", "RSI(14) is below 30")
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test
    fun `Ask Claude for ideas asks the suggestion question`() {
        val app = FakeApp.full()
        show(app)
        click("lab-ask")
        rule.waitUntil(3_000) { app.analyst.log.contains("suggest-patterns") }
    }

    @Config(qualifiers = "w1200dp-h700dp")
    @Test
    fun `a wide screen puts the tests, the idea and its chart side by side, each side pane able to hide`() {
        show(appWithTest())
        click("lab-3")
        assertTrue(exists("lab-home"))
        assertTrue(exists("lab-test"))
        assertTrue(exists("lab-chart"))
        assertTrue(exists("divider-lab"))
        assertTrue(exists("divider-lab-idea"))
        rule.onNodeWithTag("divider-lab").performClick()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Show tests")).assertExists()
        assertNotNull(rule.onNodeWithTag("lab-test"))
    }

    @Config(qualifiers = "w1200dp-h700dp")
    @Test
    fun `a wide screen with nothing chosen says what to do`() {
        show(FakeApp.full())
        assertTrue(exists("lab-hint"))
    }
}
