package com.ikverse.signallab.scan

import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.binance.BinanceException
import com.ikverse.signallab.engine.CandleClock
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeAlarms(override var exact: Boolean = true) : AlarmScheduler {
    val armed = ArrayList<Long>()
    var cancelled = 0

    override fun armAt(serverInstant: Long) {
        armed.add(serverInstant)
    }

    override fun cancel() {
        cancelled++
    }
}

/** The schedule: what is scanned when, what is retried, and what is said when scanning cannot work. */
@RunWith(RobolectricTestRunner::class)
class ScanControllerTest {
    private val tf = Timeframe.D1
    private val eth = GoldenData.candles("ETHUSDT", tf)
    private val source = GoldenData.klines(tf).mapKeys { it.key to tf }
    private val hour = 3_600_000L

    /** A moment well inside the data, [afterMs] after a UTC midnight close. */
    private val midnight = eth.t[eth.size - 100]

    private class Rig(val env: ScanEnv, val alarms: FakeAlarms, val pauses: ArrayList<Long>, val controller: ScanController, val work: BooleanArray, val skew: LongArray)

    private fun rig(startAt: Long, coins: List<String> = listOf("ETHUSDT", "BTCUSDT")): Rig {
        val env = ScanEnv(coins, tf, source)
        env.at(startAt)
        val alarms = FakeAlarms()
        val pauses = ArrayList<Long>()
        val work = booleanArrayOf(true)
        val skew = longArrayOf(0)
        val controller = ScanController(
            env.scanner, env.market, alarms, env.log, env.sink, ScanHealth(),
            hasWork = { work[0] }, skewMs = { skew[0] },
            pause = { ms -> pauses.add(ms); env.at(env.market.now + ms) },
        )
        return Rig(env, alarms, pauses, controller, work, skew)
    }

    @Test
    fun itWaitsForTheExchangeToPublishTheCandleBeforeScanning() = runTest {
        val r = rig(midnight + 1_000)
        r.controller.scanDue()
        assertEquals(listOf(DataConfig.SCAN_SETTLE_MS - 1_000), r.pauses.take(1))
    }

    @Test
    fun itScansOnlyWhatHasNotBeenHandledYet() = runTest {
        val r = rig(midnight + 6_000)
        r.controller.scanDue()
        assertTrue(r.env.scanner.isUpToDate(Timeframe.H1) && r.env.scanner.isUpToDate(tf))
        val calls = r.env.market.klineCalls.size
        r.controller.scanDue()
        assertEquals(calls, r.env.market.klineCalls.size, "nothing had closed, so nothing was downloaded")
    }

    @Test
    fun aMidnightScanDoesTheHourlyTheFourHourlyAndTheDailyCandles() = runTest {
        val r = rig(midnight + 6_000)
        r.controller.scanDue()
        val scanned = r.env.market.klineCalls.map { it.second }.toSet()
        assertEquals(setOf(Timeframe.H1, Timeframe.H4, Timeframe.D1), scanned)
    }

    @Test
    fun anAlarmIsSetForTheNextCloseJustAfterItAndTheHealthSaysSo() = runTest {
        val r = rig(midnight + 7 * hour + 6_000)
        r.controller.scanDue()
        val next = midnight + 8 * hour
        assertEquals(CandleClock.nextClose(midnight + 7 * hour + 6_000), next)
        assertEquals(next + DataConfig.SCAN_SETTLE_MS, r.alarms.armed.last())
    }

    @Test
    fun withNothingToWatchTheAlarmIsCancelled() = runTest {
        val r = rig(midnight + 6_000)
        r.work[0] = false
        r.controller.arm()
        assertEquals(1, r.alarms.cancelled)
        assertTrue(r.alarms.armed.isEmpty())
    }

    @Test
    fun theAlarmIsSetEvenWhenAScanFails() = runTest {
        val r = rig(midnight + 6_000)
        r.env.market.failKlinesFrom = 1
        r.controller.scanDue()
        assertTrue(r.alarms.armed.isNotEmpty())
    }

    @Test
    fun whileSomethingWaitsItRetriesEveryThirtySecondsAndThenGivesUp() = runTest {
        val r = rig(midnight + 6_000)
        r.env.hidden.add("BTCUSDT" to tf) // BTC never arrives, so no regime is ever current
        r.env.at(r.env.market.now)
        r.controller.scanDue()
        val retries = r.pauses.count { it == DataConfig.SCAN_RETRY_MS }
        assertEquals(3 * DataConfig.SCAN_RETRIES, retries, "five tries for each of the three timeframes")
    }

    @Test
    fun itStopsRetryingWhenTheEntryCandleIsAboutToEnd() = runTest {
        val r = rig(midnight + hour - 40_000) // 40 seconds before the hourly candle that follows the close ends
        r.env.hidden.add("BTCUSDT" to tf)
        r.env.at(r.env.market.now)
        r.controller.scanDue()
        assertTrue(r.pauses.count { it == DataConfig.SCAN_RETRY_MS } < 3 * DataConfig.SCAN_RETRIES)
    }

    @Test
    fun aBlockedNetworkIsRaisedOnceAndNotAgainDuringTheCooldown() = runTest {
        val r = rig(midnight + 6_000)
        r.env.market.failKlinesFrom = 1
        r.env.market.failWith = BinanceException.Blocked()
        r.controller.scanDue()
        val problems: suspend () -> List<com.ikverse.signallab.data.Alert> =
            { r.env.log.alerts(100).filter { it.kind == AlertText.KIND_PROBLEM && it.title == AlertText.blocked().title } }
        assertEquals(1, problems().size)
        assertEquals(1, r.env.sink.delivered.count { it.title == AlertText.blocked().title })
        r.env.at(r.env.market.now + hour)
        r.controller.scanDue()
        assertEquals(1, problems().size, "the same problem an hour later is not raised again")
        r.env.at(r.env.market.now + DataConfig.PROBLEM_COOLDOWN_MS)
        r.controller.scanDue()
        assertEquals(2, problems().size, "after the cooldown it is")
    }

    @Test
    fun aScanThatCannotReachBinanceMostOfTheTimeIsRaised() = runTest {
        val r = rig(midnight + 6_000)
        r.env.market.failKlinesFrom = 1
        r.controller.scanDue()
        assertTrue(r.env.log.alerts(100).any { it.kind == AlertText.KIND_PROBLEM && it.title.startsWith("Could not update") })
    }

    @Test
    fun noCompleteScanForTwoCandlesIsAStall() = runTest {
        val r = rig(midnight + 6_000)
        r.env.market.failKlinesFrom = 1
        r.controller.scanDue()
        r.env.at(midnight + 3 * hour)
        r.controller.scanDue()
        val titles = r.env.log.alerts(100).map { it.title }
        assertTrue("Scanning has stalled on 1h" in titles, titles.toString())
        assertTrue("Scanning has stalled on 4h" !in titles, "four-hour candles have not been missed twice yet")
    }

    @Test
    fun aCompleteScanIsNotAStall() = runTest {
        val r = rig(midnight + 6_000)
        r.controller.scanDue()
        r.env.at(midnight + 3 * hour + 6_000)
        r.controller.scanDue()
        assertTrue(r.env.log.alerts(100).none { it.title.startsWith("Scanning has stalled") })
    }

    @Test
    fun aClockMoreThanFiveSecondsOffIsRaised() = runTest {
        val r = rig(midnight + 6_000)
        r.skew[0] = -8_000
        r.controller.scanDue()
        assertTrue(r.env.log.alerts(100).any { it.title == "The phone's clock is off" })
        val ok = rig(midnight + 6_000)
        ok.skew[0] = 4_000
        ok.controller.scanDue()
        assertNull(ok.env.log.alerts(100).firstOrNull { it.title == "The phone's clock is off" })
    }

    @Test
    fun scanAllScansEveryTimeframeEvenWhenNothingIsDue() = runTest {
        val r = rig(midnight + 6_000)
        r.controller.scanDue()
        val before = r.env.market.klineCalls.size
        r.controller.scanAll()
        assertTrue(r.env.market.klineCalls.size > before)
    }
}
