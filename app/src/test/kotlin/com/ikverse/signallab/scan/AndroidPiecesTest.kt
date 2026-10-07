package com.ikverse.signallab.scan

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.Alert
import com.ikverse.signallab.data.RecordDatabase
import com.ikverse.signallab.data.SettingsStore
import com.ikverse.signallab.engine.ExitMode
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.LiveScan
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.ui.PermissionPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun alert(id: Long, kind: String, symbol: String?, tf: String?, title: String = "t$id", body: String = "b$id") =
    Alert(id, 1_000 + id, kind, symbol, tf, title, body, "signallab://coin/$symbol?tf=$tf")

@RunWith(RobolectricTestRunner::class)
class AlertTextTest {
    private fun plan(
        target: Double? = 145.9, stop: Double? = 140.6, limit: Int = 24, mode: ExitMode = if (target != null) ExitMode.CLASSIC else ExitMode.HELD,
        variant: String = "donchian20_1h",
    ) = LiveScan.Plan(variant, "donchian", "SOLUSDT", Timeframe.H1, 0, 0, 0, 1, 0, 142.35, mode, target, stop, 1.5, limit, 0, 0.002)

    @Test
    fun anOpenedTradeSaysPaperTradeAndGivesEntryTargetAndStop() {
        val t = AlertText.opened(plan())
        assertEquals("Paper trade opened: SOL 1h", t.title)
        assertEquals("Breakout: close above the 20-candle high. Entry 142.35, target 145.90, stop 140.60.", t.body)
        assertEquals("signallab://coin/SOLUSDT?tf=1h", t.link)
    }

    @Test
    fun aHeldTradeSaysHowLongItIsHeld() {
        val t = AlertText.opened(plan(target = null, stop = null, limit = 24))
        assertTrue(t.body.endsWith("Entry 142.35, held 24 candles."), t.body)
        assertTrue(AlertText.opened(plan(null, null, 1)).body.endsWith("held 1 candle."))
    }

    @Test
    fun aTrendTradeSaysItHasASafetyStopAndAStopThatFollowsThePriceUp() {
        val t = AlertText.opened(plan(target = null, stop = 140.6, limit = 72, mode = ExitMode.TRAIL, variant = "trend_ma20_1h"))
        assertEquals("Trend: close crossed above its 20-candle average. Entry 142.35, safety stop 140.60, then a stop that follows the price up.", t.body)
    }

    @Test
    fun anEndOfDayTradeSaysSo() {
        val t = AlertText.opened(plan(target = null, stop = 140.6, limit = 3, mode = ExitMode.TRAIL, variant = "intraday_breakout_1h"))
        assertTrue(t.body.endsWith("Closed by the end of the UTC day at the latest."), t.body)
    }

    @Test
    fun aLearnedTradeSaysWhereItsTargetCameFromAndAFallbackSaysItHadNothingToLearnFrom() {
        val learned = AlertText.opened(plan(target = 148.0, stop = 137.0, limit = 4, mode = ExitMode.LEARNED, variant = "bullish_harami_1h"))
        assertEquals("Bullish harami: a small candle inside the body of a big red one. Entry 142.35, target 148.00 (+3.97%, from this pattern's earlier signals), stop 137.00, within 4 candles.", learned.body)
        val fallback = AlertText.opened(plan(target = null, stop = null, limit = 6, mode = ExitMode.LEARNED, variant = "bullish_hikkake_1h"))
        assertTrue(fallback.body.endsWith("Entry 142.35, held 6 candles: too few earlier signals to learn a target from."), fallback.body)
    }

    @Test
    fun aNewCoinsAlertsCarryTheLine() {
        val t = AlertText.opened(plan(), newCoin = true)
        assertTrue(t.body.endsWith("SOL was listed on Binance less than 30 days ago. New coins fell on average in their first month."), t.body)
        assertFalse(AlertText.opened(plan()).body.contains("listed on Binance"))
    }

    @Test
    fun theWarningsSayTheyAreNotTradesAndNeverTellAnyoneToBuyOrSell() {
        val pump = AlertText.pump("PMPUSDT", Timeframe.M1, com.ikverse.signallab.engine.Warnings.Pump(0.062, 14.4, 5), newCoin = false)
        val spike = AlertText.volumeSpike("VOLUSDT", 4.1, newCoin = true)
        assertEquals("Pump warning: PMP", pump.title)
        assertEquals("PMP rose 6.2% in 5 minutes on 14 times its usual volume. Pumps like this usually peak within about a minute, and late buyers lose. No paper trade is opened.", pump.body)
        assertEquals("Volume spike: VOL", spike.title)
        assertTrue(spike.body.startsWith("VOL traded 4.1 times its usual daily volume.") && spike.body.contains("listed on Binance"))
        for (t in listOf(pump, spike)) {
            val words = (t.title + " " + t.body).lowercase().split(Regex("[^a-z]+"))
            assertTrue("buy" !in words && "sell" !in words && "advice" !in words, t.body)
        }
    }

    @Test
    fun anAlertAboutATradeCarriesItsNumbersForTheInbox() {
        val opened = org.json.JSONObject(AlertText.opened(plan(), tradeId = 7).facts!!)
        assertEquals("donchian20_1h", opened.getString("variant"))
        assertEquals(7L, opened.getLong("trade"))
        assertEquals(142.35, opened.getDouble("entry"))
        assertEquals(145.9, opened.getDouble("target"))
        assertEquals(140.6, opened.getDouble("stop"))
        assertFalse(opened.has("trails"))
        val trail = org.json.JSONObject(AlertText.opened(plan(target = null, stop = 140.6, limit = 72, mode = ExitMode.TRAIL, variant = "trend_ma20_1h")).facts!!)
        assertTrue(trail.getBoolean("trails"))
        assertFalse(trail.has("target"))
        assertFalse(trail.has("trade"), "no id is written when none is known")
        val closed = org.json.JSONObject(AlertText.closed("donchian20_1h", "SOLUSDT", Timeframe.H1, ExitReason.TARGET, 0.0182, 0.002, tradeId = 9).facts!!)
        assertEquals("target", closed.getString("reason"))
        assertEquals(0.0182, closed.getDouble("net"))
        assertEquals(0.002, closed.getDouble("random"))
        assertEquals(9L, closed.getLong("trade"))
        val noRandom = org.json.JSONObject(AlertText.closed("x", "SOLUSDT", Timeframe.H1, ExitReason.STOP, -0.01, Double.NaN).facts!!)
        assertFalse(noRandom.has("random"), "a figure that is not a number is left out")
        assertNull(AlertText.blocked().facts, "an alert that is not about a trade has none")
    }

    @Test
    fun aClosedTradeSaysHowItEndedAndWhatRandomEntriesDid() {
        val t = AlertText.closed("donchian20_1h", "SOLUSDT", Timeframe.H1, ExitReason.TARGET, 0.0182, 0.002)
        assertEquals("Paper trade closed: SOL 1h, target hit", t.title)
        assertEquals("Breakout: close above the 20-candle high. Net +1.82% after costs; random entries averaged +0.20%.", t.body)
        assertEquals("Paper trade closed: SOL 1h, stopped out", AlertText.closed("x", "SOLUSDT", Timeframe.H1, ExitReason.STOP, -0.01, Double.NaN).title)
        assertTrue(AlertText.closed("x", "SOLUSDT", Timeframe.H1, ExitReason.STOP, -0.01, Double.NaN).body.endsWith("Net -1.00% after costs."))
        assertTrue(AlertText.closed("x", "SOLUSDT", Timeframe.H4, ExitReason.TIME, 0.0, 0.0).title.endsWith("time limit"))
    }

    @Test
    fun pricesKeepTheirPrecisionAtEverySize() {
        assertEquals("67123.46", AlertText.price(67_123.456))
        assertEquals("142.35", AlertText.price(142.349))
        assertEquals("3.142", AlertText.price(3.14159))
        assertEquals("0.12346", AlertText.price(0.123456))
        assertEquals("0.00001234", AlertText.price(0.00001234))
    }

    @Test
    fun noWordingEverTellsAnyoneToBuyOrSell() {
        val texts = listOf(
            AlertText.opened(plan()), AlertText.opened(plan(null, null, 24)),
            AlertText.closed("trend_ma20_1d", "BTCUSDT", Timeframe.D1, ExitReason.TARGET, 0.03, 0.001),
            AlertText.missed("fade1h_hold24_1h", "ETHUSDT", Timeframe.H1), AlertText.blocked(),
            AlertText.unreachable(3, 5, "timeout"), AlertText.clockSkew(9_000), AlertText.stalled(Timeframe.H4, 9 * 3_600_000L),
        )
        for (t in texts) {
            val words = (t.title + " " + t.body).lowercase().split(Regex("[^a-z]+"))
            assertTrue("buy" !in words && "sell" !in words && "advice" !in words, "${t.title}: ${t.body}")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class NotifierTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun warningsHaveTheirOwnChannelAndAreNeverFoldedTogetherOrGrouped() = runTest {
        val n = Notifier(context).also { it.createChannels() }
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, assertNotNull(manager.getNotificationChannel(Notifier.CH_WARNINGS)).importance)
        val composed = Notifier.compose(listOf(
            alert(1, AlertText.KIND_WARNING, "PMPUSDT", "1m"), alert(2, AlertText.KIND_WARNING, "PMPUSDT", "1m"), alert(3, AlertText.KIND_WARNING, "XYZUSDT", "1m"),
        ))
        assertEquals(listOf(Notifier.CH_WARNINGS, Notifier.CH_WARNINGS, Notifier.CH_WARNINGS), composed.map { it.channel })
        n.deliver(listOf(alert(11, AlertText.KIND_WARNING, "PMPUSDT", "1m"), alert(12, AlertText.KIND_WARNING, "XYZUSDT", "1m")))
        val all = shadowOf(manager).allNotifications
        assertEquals(2, all.size, "one each, and no summary")
        assertTrue(all.all { it.channelId == Notifier.CH_WARNINGS })
    }

    @Test
    fun theFourChannelsExistWithTheImportanceTheirJobCallsFor() {
        Notifier(context).createChannels()
        fun importance(id: String) = assertNotNull(manager.getNotificationChannel(id), id).importance
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, importance(Notifier.CH_SIGNALS))
        assertEquals(NotificationManager.IMPORTANCE_LOW, importance(Notifier.CH_RESULTS))
        assertEquals(NotificationManager.IMPORTANCE_LOW, importance(Notifier.CH_STATUS))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, importance(Notifier.CH_PROBLEMS))
    }

    @Test
    fun severalVariantsOnOneCoinAndTimeframeBecomeOneNotification() {
        val composed = Notifier.compose(listOf(
            alert(1, AlertText.KIND_SIGNAL, "SOLUSDT", "1h", body = "Breakout"), alert(2, AlertText.KIND_SIGNAL, "SOLUSDT", "1h", body = "Trend"),
            alert(3, AlertText.KIND_SIGNAL, "ETHUSDT", "1h"), alert(4, AlertText.KIND_SIGNAL, "SOLUSDT", "4h"),
        ))
        assertEquals(3, composed.size)
        val sol = composed.first()
        assertEquals("2 paper trades opened: SOL 1h", sol.title)
        assertEquals("Breakout\nTrend", sol.body)
        assertEquals(listOf(1L, 2L), sol.ids)
        assertEquals("t3", composed[1].title)
    }

    @Test
    fun missedSignalsAreInboxOnlyAndEachProblemStandsAlone() {
        val composed = Notifier.compose(listOf(
            alert(1, AlertText.KIND_MISSED, "SOLUSDT", "1h"), alert(2, AlertText.KIND_PROBLEM, null, null), alert(3, AlertText.KIND_PROBLEM, null, null),
            alert(4, AlertText.KIND_EXIT, "SOLUSDT", "1h"),
        ))
        assertEquals(listOf(Notifier.CH_PROBLEMS, Notifier.CH_PROBLEMS, Notifier.CH_RESULTS), composed.map { it.channel })
    }

    @Test
    fun deliveryPostsOneNotificationPerGroupAndASummaryOnlyWhenThereAreSeveral() = runTest {
        val n = Notifier(context).also { it.createChannels() }
        n.deliver(listOf(alert(11, AlertText.KIND_SIGNAL, "SOLUSDT", "1h")))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        n.deliver(listOf(alert(12, AlertText.KIND_SIGNAL, "ETHUSDT", "1h"), alert(13, AlertText.KIND_SIGNAL, "BTCUSDT", "1h"), alert(14, AlertText.KIND_EXIT, "BTCUSDT", "4h")))
        val all = shadowOf(manager).allNotifications
        assertEquals(1 + 3 + 1, all.size, "one earlier, three new, and a summary for the two new signals")
        val summary = all.single { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        assertEquals(Notifier.CH_SIGNALS, summary.channelId)
        assertEquals("2 paper trades opened", summary.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(all.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }.all { it.group != null })
    }

    @Test
    fun withNotificationsOffNothingIsPostedAndNothingBreaks() = runTest {
        val n = Notifier(context).also { it.createChannels() }
        shadowOf(manager).setNotificationsEnabled(false)
        n.deliver(listOf(alert(21, AlertText.KIND_SIGNAL, "SOLUSDT", "1h")))
        assertEquals(0, shadowOf(manager).allNotifications.size)
        assertFalse(n.postTest())
        assertFalse(n.postResume())
        assertFalse(n.enabled())
    }

    @Test
    fun theTestAlertAndTheResumePromptUseTheRealChannels() {
        val n = Notifier(context).also { it.createChannels() }
        assertTrue(n.postTest())
        assertTrue(n.postResume())
        assertEquals(Notifier.CH_SIGNALS, shadowOf(manager).getNotification(Notifier.TEST_ID).channelId)
        assertEquals(Notifier.CH_PROBLEMS, shadowOf(manager).getNotification(Notifier.RESUME_ID).channelId)
    }

    @Test
    fun theStatusNotificationIsOngoingAndSilent() {
        val n = Notifier(context).also { it.createChannels() }.status("Watching 30 coins")
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notifier.CH_STATUS, n.channelId)
        assertEquals("Watching 30 coins", n.extras.getString(Notification.EXTRA_TEXT))
    }
}

@RunWith(RobolectricTestRunner::class)
class AndroidAlarmsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(AlarmManager::class.java)

    @Test
    @Config(sdk = [34])
    fun anExactAlarmIsSetAtThePhonesTimeForTheServerInstant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val alarms = AndroidAlarms(context) { it - 685 }
        assertTrue(alarms.exact)
        alarms.armAt(10_000_000)
        val a = assertNotNull(shadowOf(manager).nextScheduledAlarm)
        assertEquals(10_000_000 - 685L, a.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, a.type)
        assertTrue(a.isAllowWhileIdle)
        assertEquals(AndroidAlarms.ACTION, shadowOf(a.operation).savedIntent.action)
        assertEquals(ScanAlarmReceiver::class.java.name, shadowOf(a.operation).savedIntent.component?.className)
    }

    @Test
    @Config(sdk = [34])
    fun withoutThePermissionTheAlarmIsStillSetButNotExact() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val alarms = AndroidAlarms(context) { it }
        assertFalse(alarms.exact)
        alarms.armAt(20_000_000)
        assertEquals(20_000_000L, assertNotNull(shadowOf(manager).nextScheduledAlarm).triggerAtMs)
    }

    @Test
    @Config(sdk = [29])
    fun onAndroidTenExactAlarmsNeedNoPermission() {
        assertTrue(AndroidAlarms(context) { it }.exact)
    }

    @Test
    @Config(sdk = [34])
    fun armingAgainReplacesTheAlarmAndCancelRemovesIt() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val alarms = AndroidAlarms(context) { it }
        alarms.armAt(1_000_000)
        alarms.armAt(2_000_000)
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        assertEquals(2_000_000L, shadowOf(manager).nextScheduledAlarm!!.triggerAtMs)
        alarms.cancel()
        assertNull(shadowOf(manager).nextScheduledAlarm)
    }
}

@RunWith(RobolectricTestRunner::class)
class PermissionFlowTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val all = Grants(notifications = false, exactAlarms = false, batteryExempt = false)
    private val sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    @Test
    fun nothingIsAskedBeforeThereIsAListToScan() {
        assertNull(PermissionFlow.next(sdk, false, all, emptySet()))
    }

    @Test
    fun theyAreAskedInOrderAlertsThenAlarmsThenBattery() {
        val asked = HashSet<PermissionPrompt>()
        val order = ArrayList<PermissionPrompt>()
        while (true) {
            val p = PermissionFlow.next(sdk, true, all, asked) ?: break
            order.add(p)
            asked.add(p)
        }
        assertEquals(listOf(PermissionPrompt.NOTIFICATIONS, PermissionPrompt.EXACT_ALARMS, PermissionPrompt.BATTERY), order)
    }

    @Test
    fun whatIsAlreadyAllowedIsNotAsked() {
        assertEquals(PermissionPrompt.EXACT_ALARMS, PermissionFlow.next(sdk, true, all.copy(notifications = true), emptySet()))
        assertEquals(PermissionPrompt.BATTERY, PermissionFlow.next(sdk, true, all.copy(notifications = true, exactAlarms = true), emptySet()))
        assertNull(PermissionFlow.next(sdk, true, Grants(true, true, true), emptySet()))
    }

    @Test
    fun androidTenAsksOnlyForTheBatteryExemption() {
        assertEquals(PermissionPrompt.BATTERY, PermissionFlow.next(Build.VERSION_CODES.Q, true, all, emptySet()))
        assertNull(PermissionFlow.next(Build.VERSION_CODES.Q, true, all, setOf(PermissionPrompt.BATTERY)))
    }

    @Test
    fun androidTwelveAsksForAlarmsButNotForNotifications() {
        assertEquals(PermissionPrompt.EXACT_ALARMS, PermissionFlow.next(Build.VERSION_CODES.S, true, all, emptySet()))
    }

    /** The phone as the next two tests need it: alerts and exact alarms allowed, no battery exemption. */
    private fun phoneWithOnlyTheBatteryPromptLeft() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, false)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
    }

    @Test
    @Config(sdk = [34])
    fun aRefusalIsRememberedAndNeverAskedAgain() = runTest {
        phoneWithOnlyTheBatteryPromptLeft()
        val settings = SettingsStore(RecordDatabase(context, name = null), io = Dispatchers.Unconfined)
        val flow = PermissionFlow(context, settings, { true }, scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))
        flow.evaluate()
        assertEquals(PermissionPrompt.BATTERY, flow.pending.value)
        flow.decline(PermissionPrompt.BATTERY)
        assertNull(flow.pending.value)
        flow.evaluate()
        assertNull(flow.pending.value, "asked once is enough")
        assertTrue(settings.getBoolean(SettingsStore.ASKED_PREFIX + PermissionPrompt.BATTERY.name, false))
    }

    @Test
    @Config(sdk = [34])
    fun acceptingHandsThePromptToTheActivityAndStopsShowingIt() = runTest {
        phoneWithOnlyTheBatteryPromptLeft()
        val settings = SettingsStore(RecordDatabase(context, name = null), io = Dispatchers.Unconfined)
        val flow = PermissionFlow(context, settings, { true }, scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))
        val got = ArrayList<PermissionPrompt>()
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).let { sc -> sc.launch { flow.accepted.collect { got.add(it) } } }
        flow.evaluate()
        flow.accept(PermissionPrompt.BATTERY)
        assertEquals(listOf(PermissionPrompt.BATTERY), got)
        assertNull(flow.pending.value)
        job.cancel()
    }
}
