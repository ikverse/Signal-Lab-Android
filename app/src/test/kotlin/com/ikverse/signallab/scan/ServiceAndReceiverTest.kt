package com.ikverse.signallab.scan

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.ikverse.signallab.SignalLabApplication
import com.ikverse.signallab.data.CoinRow
import com.ikverse.signallab.data.SettingsStore
import com.ikverse.signallab.data.WatchlistResult
import com.ikverse.signallab.state.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wake-ups: the alarm, a restart, and the service that does the work, on the app's real wiring (nothing here reaches the network). */
@RunWith(RobolectricTestRunner::class)
class ServiceAndReceiverTest {
    private val app get() = appContext as SignalLabApplication
    private val graph: AppGraph get() = app.graph

    private fun activateAList() = runBlocking {
        graph.candles.replaceCoins(listOf(CoinRow("BTCUSDT", "BTC", "TRADING", true, false, false, 1e9, 70_000.0, 66_000.0, 1)))
        val list = (graph.watchlists.create("Majors") as WatchlistResult.Ok).value
        graph.watchlists.add(list.id, "BTCUSDT")
        graph.watchlists.setActive(list.id, true)
    }

    /** The receivers hand their work to a background thread; wait for what it does. */
    private fun <T : Any> eventually(timeoutMs: Long = 5_000, check: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            check()?.let { return it }
            Thread.sleep(10)
        }
        return null
    }

    private fun startedService(): Intent? = shadowOf(app).peekNextStartedService()

    private fun settleWithoutAService() = Thread.sleep(300)

    @Test
    fun aRestartStartsTheServiceWhenAListIsActive() {
        activateAList()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = assertNotNull(eventually { startedService() })
        assertEquals(ScanService::class.java.name, started.component?.className)
    }

    @Test
    fun anAppUpdateStartsItToo() {
        activateAList()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNotNull(eventually { startedService() })
    }

    @Test
    fun aRestartWithNothingToWatchStartsNothing() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        settleWithoutAService()
        assertNull(startedService())
    }

    @Test
    fun aRestartWithBackgroundScanningSwitchedOffStartsNothing() {
        activateAList()
        runBlocking { graph.settings.setBoolean(SettingsStore.SCAN_IN_BACKGROUND, false) }
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        settleWithoutAService()
        assertNull(startedService())
    }

    @Test
    fun otherBroadcastsToTheRestartReceiverAreIgnored() {
        activateAList()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        settleWithoutAService()
        assertNull(startedService())
    }

    @Test
    fun theAlarmStartsTheServiceToDoTheScan() {
        activateAList()
        ScanAlarmReceiver().onReceive(app, Intent(AndroidAlarms.ACTION))
        val started = assertNotNull(eventually { startedService() })
        assertEquals(ScanService::class.java.name, started.component?.className)
    }

    @Test
    fun theServiceShowsItsQuietNotificationAtOnce() {
        val service = Robolectric.buildService(ScanService::class.java).create().get()
        val n = assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(Notifier.CH_STATUS, n.channelId)
        assertEquals(Notifier.STATUS_ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(ScanService.running)
    }

    @Test
    fun withNothingToWatchTheServiceStopsItselfAndRemovesItsNotification() {
        val controller = Robolectric.buildService(ScanService::class.java).create()
        controller.startCommand(0, 0)
        val service = controller.get()
        assertNotNull(eventually { if (shadowOf(service).isStoppedBySelf) true else null }, "the service never stopped itself")
        assertFalse(shadowOf(service).isLastForegroundNotificationAttached)
    }

    @Test
    @Config(sdk = [34])
    fun fromAndroidFourteenTheServiceDeclaresTheSpecialUseType() {
        val controller = Robolectric.buildService(ScanService::class.java).create()
        val shadow = shadowOf(controller.get())
        // The shadow keeps the type it was started with in a private field; there is no getter to ask.
        val type = org.robolectric.shadows.ShadowService::class.java.getDeclaredField("foregroundServiceType").apply { isAccessible = true }.getInt(shadow)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, type)
    }

    @Test
    @Config(sdk = [29])
    fun onAndroidTenItIsAnOrdinaryForegroundService() {
        val service = Robolectric.buildService(ScanService::class.java).create().get()
        assertNotNull(shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun theManifestDeclaresTheServiceAsSpecialUseWithItsReason() {
        val info = app.packageManager.getServiceInfo(android.content.ComponentName(app, ScanService::class.java), android.content.pm.PackageManager.GET_META_DATA)
        assertFalse(info.exported)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
        val requested = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        for (p in listOf(
            "android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS", "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE", "android.permission.SCHEDULE_EXACT_ALARM", "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.WAKE_LOCK", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        )) assertTrue(p in requested, "manifest lacks $p")
        assertFalse("android.permission.USE_EXACT_ALARM" in requested, "USE_EXACT_ALARM is reserved for alarm and calendar apps")
    }

    @Test
    fun whenAndroidRefusesToStartItFromTheBackgroundTheUserIsAskedToResume() {
        val refusing = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent?) = throw IllegalStateException("not allowed from the background")
        }
        graph.notifier.createChannels()
        ScanService.start(refusing)
        val manager = app.getSystemService(NotificationManager::class.java)
        val n = assertNotNull(shadowOf(manager).getNotification(Notifier.RESUME_ID))
        assertEquals(Notifier.CH_PROBLEMS, n.channelId)
    }

    @Test
    fun syncingTheServiceStartsItOnlyWhenThereIsAListToWatch() {
        runBlocking {
            graph.syncService(app as Context)
            assertNull(startedService(), "nothing to watch, nothing to start")
            activateAList()
            graph.syncService(app)
            assertNotNull(startedService())
        }
    }
}
