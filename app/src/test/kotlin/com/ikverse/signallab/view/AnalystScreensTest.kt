package com.ikverse.signallab.view

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.ikverse.signallab.analyst.Turn
import com.ikverse.signallab.engine.strategy.Tier
import com.ikverse.signallab.model.AnalystUi
import com.ikverse.signallab.model.CustomRowUi
import com.ikverse.signallab.model.MarketLine
import com.ikverse.signallab.model.ReportUi
import com.ikverse.signallab.model.StrategiesUi
import com.ikverse.signallab.model.SuggestionUi
import com.ikverse.signallab.model.TodayUi
import com.ikverse.signallab.view.analyst.AnalystActions
import com.ikverse.signallab.view.analyst.AnalystScreen
import com.ikverse.signallab.view.strategies.StrategiesScreen
import com.ikverse.signallab.view.theme.SignalLabTheme
import com.ikverse.signallab.view.today.TodayScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AnalystScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private class Recorder : AnalystActions {
        val sent = ArrayList<String>()
        var savedKey: String? = null
        var stopped = false
        override fun send(text: String) {
            sent.add(text)
        }
        override fun stop() {
            stopped = true
        }
        override fun saveKey(key: String): String? = if (key.startsWith("sk-ant-")) null.also { savedKey = key } else "A Claude API key starts with sk-ant-."
        override fun removeKey() {}
        override fun setModel(id: String) {}
        override fun setCap(usd: Double) {}
        override fun setBrief(on: Boolean) {}
        override fun setReview(on: Boolean) {}
        override fun clearChat() {}
        override fun openKeyPage() {}
        var shared: String? = null
        override fun shareToClaude(question: String) {
            shared = question
        }
    }

    @Test
    fun withoutAKeyTheAnalystExplainsHowToConnectAndChecksThePaste() {
        val actions = Recorder()
        rule.setContent { SignalLabTheme { AnalystScreen(AnalystUi(loaded = true), actions, onRuns = {}) } }
        rule.onNodeWithText("Connect your Claude account").assertIsDisplayed()
        rule.onNodeWithTag("key-field").performTextInput("nope")
        rule.onNodeWithTag("save-key").performClick()
        rule.onNodeWithTag("key-problem").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("ask").fetchSemanticsNodes().size, "no question box before a key")
        rule.onNodeWithTag("analyst-list").performScrollToNode(hasTestTag("share-claude"))
        rule.onNodeWithTag("share-claude").performClick()
        assertEquals("", actions.shared, "without a key the question can go to the Claude app")
    }

    @Test
    fun theConversationShowsBothSidesAndAStarterAsks() {
        val actions = Recorder()
        val empty = AnalystUi(loaded = true, keySet = true, keyHint = "abcd")
        rule.setContent { SignalLabTheme { AnalystScreen(empty, actions, onRuns = {}) } }
        rule.onAllNodesWithTag("starter")[0].performClick()
        assertEquals(listOf("How are my strategies doing against random entries?"), actions.sent)
    }

    @Test
    fun aRunShowsWhatItIsDoingAndCanBeStopped() {
        val actions = Recorder()
        val state = AnalystUi(
            loaded = true, keySet = true, running = true, streaming = "Breakout is **behind** random", working = "Reading the ledger…",
            turns = listOf(Turn(Turn.USER, "How is Breakout doing?", 1), Turn(Turn.ASSISTANT, "Earlier answer.", 2, 0.03)),
            reports = listOf(ReportUi(1, "Daily brief", "Calm day.\nNothing new.", 3)),
        )
        rule.setContent { SignalLabTheme { AnalystScreen(state, actions, onRuns = {}) } }
        rule.onNodeWithTag("report").assertIsDisplayed()
        rule.onNodeWithText("DAILY BRIEF").assertIsDisplayed()
        rule.onNodeWithText("How is Breakout doing?").assertIsDisplayed()
        rule.onNodeWithTag("analyst-list").performScrollToNode(hasTestTag("working"))
        rule.onNodeWithText("Reading the ledger…").assertIsDisplayed()
        rule.onNodeWithTag("stop").performClick()
        assertEquals(true, actions.stopped)
    }

    @Test
    fun aSuggestedCallOnTodayCanBeRecordedOrDismissed() {
        var recorded: String? = null
        var dismissed: String? = null
        val s = SuggestionUi("s1", "SOLUSDT", 0.06, 0.12, 20, "Holding its breakout on rising volume.", 0, recorded = false, price = 142.3)
        val state = TodayUi(loaded = true, market = MarketLine(67_840.0, 0.152, "calm", null, 2, 2), suggestions = listOf(s),
            contextLines = listOf("Wed 21 Oct · US inflation figures"))
        rule.setContent { SignalLabTheme { TodayScreen(state, onOpenCall = {}, onLedger = {}, onRecord = { recorded = it }, onDismiss = { dismissed = it }) } }
        rule.onNodeWithText("Wed 21 Oct · US inflation figures", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Holding its breakout on rising volume.").assertIsDisplayed()
        rule.onNodeWithTag("today").performScrollToNode(hasTestTag("record"))
        rule.onNodeWithText("Stop −6% · target +12% · up to 20 days").assertIsDisplayed()
        rule.onNodeWithTag("record").performClick()
        rule.onNodeWithTag("dismiss").performClick()
        assertEquals("s1", recorded)
        assertEquals("s1", dismissed)
    }

    @Test
    fun aProposedStrategyWaitsToBeStarted() {
        var run: Pair<String, Boolean>? = null
        val c = CustomRowUi("custom_ab", "Quiet breakout", "Breakouts from calm ranges carry further.", "(strategy …)", running = false, byAnalyst = true, tier = Tier.TESTING)
        rule.setContent {
            SignalLabTheme { StrategiesScreen(StrategiesUi(loaded = true, custom = listOf(c), customs = 1), onOpen = {}, onSwitch = { _, _ -> }, onRun = { id, on -> run = id to on }) }
        }
        rule.onNodeWithText("Proposed by the analyst · not started").assertIsDisplayed()
        rule.onNodeWithTag("run-custom_ab").performClick()
        assertEquals("custom_ab" to true, run)
    }
}
