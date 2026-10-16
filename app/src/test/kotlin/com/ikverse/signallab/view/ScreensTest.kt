package com.ikverse.signallab.view

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import com.ikverse.signallab.engine.strategy.Tier
import com.ikverse.signallab.model.CallUi
import com.ikverse.signallab.model.ClosedUi
import com.ikverse.signallab.model.LedgerUi
import com.ikverse.signallab.model.MarketLine
import com.ikverse.signallab.model.OpenUi
import com.ikverse.signallab.model.Point
import com.ikverse.signallab.model.SettingsUi
import com.ikverse.signallab.model.StrategiesUi
import com.ikverse.signallab.model.StrategyLine
import com.ikverse.signallab.model.StrategyRowUi
import com.ikverse.signallab.model.TodayUi
import com.ikverse.signallab.view.ledger.LedgerScreen
import com.ikverse.signallab.view.settings.SettingsActions
import com.ikverse.signallab.view.settings.SettingsScreen
import com.ikverse.signallab.view.strategies.StrategiesScreen
import com.ikverse.signallab.view.theme.SignalLabTheme
import com.ikverse.signallab.view.today.TodayScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val day = 86_400_000L

    private fun call(id: Long, base: String, entry: Double = 142.30, stop: Double? = 121.0, size: Double = 0.2) = CallUi(
        id = id, symbol = "${base}USDT", strategyId = "breakout50_1d", strategyName = "Breakout", tier = Tier.PROMISING,
        signalTime = 1_760_000_000_000L, entryTime = 1_760_000_000_000L + day, entry = entry, stop = stop, sizeFraction = size,
        sizeUsd = size * 10_000, why = "Closed above its highest price of the prior 50 days.", exitShort = "exits on a close below its 20-day low",
        lastDay = 1_760_000_000_000L + 60 * day,
    )

    private val market = MarketLine(67_840.0, 0.152, "calm", 0.00008, 2, 2)

    @Test
    fun todayShowsTheMarketTheNewCallTheOpenOnesAndWhatToDo() {
        val state = TodayUi(
            loaded = true, market = market, newCalls = listOf(call(1, "SOL")),
            open = listOf(
                OpenUi(call(2, "DOGE"), 0.1712, -0.021, -12.0, "Leave now: time limit. Paper exit at 0.1712, −2.1% after fees.", leaveNow = true, daysHeld = 14),
                OpenUi(call(3, "AVAX"), 26.4, 0.068, 95.0, "Hold. Exit if a daily close is below 24.10.", leaveNow = false, daysHeld = 9),
            ),
            closedThisWeek = listOf(ClosedUi(call(4, "ARB"), 1_760_000_000_000L, 0.5, "time", 0.079, 118.0)),
            nextPass = 1_760_054_490_000L,
        )
        var opened: Long? = null
        rule.setContent { SignalLabTheme { TodayScreen(state, onOpenCall = { opened = it }, onLedger = {}) } }
        rule.onNodeWithText("Bitcoin is above its 200-day average, swings are calm and futures traders are neutral.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("SOL").assertIsDisplayed()
        rule.onNodeWithText("142.30").assertIsDisplayed()
        rule.onNodeWithText("121.00").assertIsDisplayed()
        rule.onNodeWithText("$2,000").assertIsDisplayed()
        rule.onNodeWithTag("today").performScrollToNode(hasText("Exit now"))
        rule.onNodeWithText("Exit now").assertIsDisplayed()
        rule.onNodeWithText("Hold. Exit if a daily close is below 24.10.").assertIsDisplayed()
        rule.onNodeWithTag("today").performScrollToNode(hasTestTag("call-1"))
        rule.onNodeWithTag("call-1").performClick()
        assertEquals(1L, opened)
    }

    @Test
    fun aQuietDayExplainsThatThereIsNothingToDo() {
        rule.setContent { SignalLabTheme { TodayScreen(TodayUi(loaded = true, market = market), onOpenCall = {}, onLedger = {}) } }
        rule.onNodeWithText("No calls right now").assertIsDisplayed()
    }

    @Test
    fun theLedgerComparesTheAccountWithRandomEntries() {
        val state = LedgerUi(
            loaded = true, start = 10_000.0, value = 11_240.0, randomValue = 10_310.0,
            curve = listOf(Point(0, 10_000.0), Point(1, 11_240.0)), randomCurve = listOf(Point(0, 10_000.0), Point(1, 10_310.0)),
            strategies = listOf(StrategyLine("breakout50_1d", "Breakout", 9, 5, 1, 0.024, 624.0, "Too few calls to say anything yet.", testing = false)),
            imported = 1284, since = 1_756_684_800_000L,
        )
        var opened: String? = null
        rule.setContent { SignalLabTheme { LedgerScreen(state, onPeriod = {}, onStrategy = { opened = it }, onExport = {}) } }
        rule.onNodeWithText("$11,240").assertIsDisplayed()
        rule.onNodeWithText("+12.4%").assertIsDisplayed()
        rule.onNodeWithText("The same exits on random days would have made +3.1%", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("ledger").performScrollToNode(hasTestTag("line-breakout50_1d"))
        rule.onNodeWithTag("line-breakout50_1d").performClick()
        assertEquals("breakout50_1d", opened)
        rule.onNodeWithTag("ledger").performScrollToNode(hasText("1284 trades"))
        rule.onNodeWithText("1284 trades").assertIsDisplayed()
    }

    @Test
    fun aStrategyIsSwitchedFromItsRow() {
        val state = StrategiesUi(true, listOf(StrategyRowUi("trend50_1d", "Trend timer", Tier.PROMISING, on = true, summary = "Above the 50-day average · daily")), customs = 1, tried = 93)
        var switched: Pair<String, Boolean>? = null
        rule.setContent { SignalLabTheme { StrategiesScreen(state, onOpen = {}, onSwitch = { id, on -> switched = id to on }) } }
        rule.onNodeWithTag("switch-trend50_1d").performClick()
        assertEquals("trend50_1d" to false, switched)
        rule.onNodeWithTag("strategies").performScrollToNode(hasText("Strategies tried so far: 93", substring = true))
    }

    @Test
    fun settingsShowWhatAndroidAllowsAndOpenItsScreens() {
        var asked: com.ikverse.signallab.model.Permission? = null
        val actions = SettingsActions({ _, _ -> }, { _, _ -> }, {}, { asked = it })
        val state = SettingsUi(loaded = true, version = "2.0.0", update = "Up to date.", updateAction = "Check now",
            grants = com.ikverse.signallab.model.Grants(notifications = true, exactAlarms = false, batteryExempt = false))
        rule.setContent { SignalLabTheme { SettingsScreen(state, actions) } }
        rule.onNodeWithText("Exact alarms, for the daily close").performScrollTo().performClick()
        assertEquals(com.ikverse.signallab.model.Permission.EXACT_ALARMS, asked)
        rule.onNodeWithText("Version 2.0.0").performScrollTo().assertIsDisplayed()
    }
}
