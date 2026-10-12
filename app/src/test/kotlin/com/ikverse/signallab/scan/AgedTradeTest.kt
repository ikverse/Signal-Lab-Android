package com.ikverse.signallab.scan

import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.EPOCH_START
import com.ikverse.signallab.data.NewTrade
import com.ikverse.signallab.data.TradeStatus
import com.ikverse.signallab.data.candlesOf
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A trade left open through a sleep longer than its chart keeps: the scanner must still find its exit. */
@RunWith(RobolectricTestRunner::class)
class AgedTradeTest {
    private val tf = Timeframe.M15
    private val days = DataConfig.historyDays(tf) + 15
    private val day = 86_400_000L

    @Test
    fun aTradeOpenedBeforeTheKeptWindowStillClosesOnItsRealExit() = runTest {
        // Prices drift up with no dip, so a held trade runs to its time limit and nothing can stop it out early.
        val source = mapOf(
            ("AAAUSDT" to tf) to candlesOf(tf, EPOCH_START, days * tf.barsPerDay, base = 100.0, drift = 0.001, spread = 0.1),
            ("BTCUSDT" to Timeframe.D1) to candlesOf(Timeframe.D1, EPOCH_START, days, base = 50_000.0),
        )
        val e = ScanEnv(listOf("AAAUSDT"), tf, source)
        // The trade entered on the third day, held 96 candles, with no target or stop: it ended, in truth, a day later.
        val entry = EPOCH_START + 2 * day
        val limit = 96
        val id = assertNotNull(e.log.open(NewTrade(
            "lab1_15m", "lab", "AAAUSDT", tf, 0, barTime = entry - tf.ms, detectedAt = entry - 1, regime = -1, entryTime = entry, entryPrice = 100.0,
            target = null, stop = null, holdBars = limit, exitDue = entry + limit * tf.ms - 1, cost = 0.004, exitMode = "held", atr = 0.2,
        )))
        // The phone wakes long after the chart's whole retention has passed.
        e.at(EPOCH_START + days * day - tf.ms + 5_000)
        e.scanner.scan(tf)
        val t = e.log.trades(TradeStatus.ALL, limit = Int.MAX_VALUE).single { it.id == id }
        val x = assertNotNull(t.exit, "the trade was closed")
        assertEquals(ExitReason.TIME, x.reason)
        assertEquals(entry + limit * tf.ms - 1, x.exitTime)
        assertEquals(limit, x.barsHeld)
    }
}
