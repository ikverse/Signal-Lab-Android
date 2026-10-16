package com.ikverse.signallab.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.SignalKey
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val context: Context get() = ApplicationProvider.getApplicationContext()

@RunWith(RobolectricTestRunner::class)
class CandleStoreTest {
    private fun store() = CandleStore(context, name = null, io = Dispatchers.Unconfined)

    @Test
    fun insertingTwiceStoresEachCandleOnce() = runTest {
        val s = store()
        val k = candlesOf(Timeframe.H1, EPOCH_START, 50)
        assertEquals(50, s.insertKlines("BTCUSDT", Timeframe.H1, k))
        assertEquals(0, s.insertKlines("BTCUSDT", Timeframe.H1, k))
        assertEquals(10, s.insertKlines("BTCUSDT", Timeframe.H1, candlesOf(Timeframe.H1, EPOCH_START + 45 * Timeframe.H1.ms, 15)))
        assertEquals(60, s.count("BTCUSDT", Timeframe.H1))
        assertEquals(0, s.count("BTCUSDT", Timeframe.H4))
    }

    @Test
    fun readingBackGivesExactlyTheDoublesThatWereStored() = runTest {
        val s = store()
        val tricky = listOf(
            Kline(EPOCH_START, 0.1 + 0.2, 1.0 / 3.0, 1e-9, 83566.97000000001, 12345.678901234567, EPOCH_START + Timeframe.D1.ms - 1),
            Kline(EPOCH_START + DAY, 7.1e-5, 7.2e-5, 6.9e-5, 7.0000000000000001e-5, 3.0e12, EPOCH_START + 2 * DAY - 1),
        )
        s.insertKlines("PEPEUSDT", Timeframe.D1, tricky)
        val c = assertNotNull(s.window("PEPEUSDT", Timeframe.D1))
        for ((i, k) in tricky.withIndex()) {
            assertEquals(k.openTime, c.t[i]); assertEquals(k.closeTime, c.closeTime[i])
            assertEquals(k.open.toRawBits(), c.open[i].toRawBits()); assertEquals(k.high.toRawBits(), c.high[i].toRawBits())
            assertEquals(k.low.toRawBits(), c.low[i].toRawBits()); assertEquals(k.close.toRawBits(), c.close[i].toRawBits())
            assertEquals(k.volume.toRawBits(), c.volume[i].toRawBits())
        }
        assertNull(s.window("NOPEUSDT", Timeframe.D1))
    }

    @Test
    fun windowsStartWhereAskedAndComeOldestFirst() = runTest {
        val s = store()
        s.insertKlines("A", Timeframe.H4, candlesOf(Timeframe.H4, EPOCH_START, 100).shuffled())
        val c = assertNotNull(s.window("A", Timeframe.H4, since = EPOCH_START + 90 * Timeframe.H4.ms))
        assertEquals(10, c.size)
        assertTrue(c.t.toList() == c.t.sorted())
        assertEquals(EPOCH_START, s.firstOpen("A", Timeframe.H4))
        assertEquals(EPOCH_START + 99 * Timeframe.H4.ms, s.lastOpen("A", Timeframe.H4))
        assertNull(s.lastOpen("A", Timeframe.H1))
    }

    @Test
    fun holesAreFoundBetweenStoredCandles() {
        val times = longArrayOf(0, 1, 2, 5, 6, 10).map { it * Timeframe.H1.ms }.toLongArray()
        assertEquals(listOf(3 * Timeframe.H1.ms..4 * Timeframe.H1.ms, 7 * Timeframe.H1.ms..9 * Timeframe.H1.ms), CandleSync.holesIn(times, Timeframe.H1))
        assertEquals(emptyList(), CandleSync.holesIn(longArrayOf(), Timeframe.H1))
    }

    @Test
    fun knownGapsAreRememberedPerCoinAndTimeframe() = runTest {
        val s = store()
        s.addKnownGap("A", Timeframe.H1, 10L..20L)
        s.addKnownGap("A", Timeframe.H1, 10L..30L) // same start: replaced
        assertEquals(listOf(10L..30L), s.knownGaps("A", Timeframe.H1))
        assertEquals(emptyList(), s.knownGaps("A", Timeframe.H4))
        assertEquals(emptyList(), s.knownGaps("B", Timeframe.H1))
    }

    private fun row(symbol: String, volume: Double, offered: Boolean = true, status: String = "TRADING", seen: Long = 1) =
        CoinRow(symbol, symbol.removeSuffix("USDT"), status, offered, false, false, volume, 2.0, 1.0, seen)

    @Test
    fun thePickerListsOnlyOfferedTradingCoinsBiggestFirstAndEscapesTheSearch() = runTest {
        val s = store()
        s.replaceCoins(listOf(row("SMALLUSDT", 10.0), row("BIGUSDT", 900.0), row("MIDUSDT", 100.0), row("HIDDENUSDT", 5000.0, offered = false),
            row("BREAKUSDT", 800.0, status = "BREAK"), row("A_BUSDT", 50.0), row("AXBUSDT", 60.0)))
        assertEquals(listOf("BIGUSDT", "MIDUSDT", "AXBUSDT", "A_BUSDT", "SMALLUSDT"), s.offeredCoins("", 10).map { it.symbol })
        assertEquals(listOf("BIGUSDT"), s.offeredCoins("big", 10).map { it.symbol })
        assertEquals(listOf("A_BUSDT"), s.offeredCoins("a_b", 10).map { it.symbol }) // _ is not a wildcard
        assertEquals(emptyList(), s.offeredCoins("%", 10).map { it.symbol }) // % is not a wildcard
        assertEquals(2, s.offeredCoins("", 2).size)
    }

    @Test
    fun aCoinThatLeavesThePairListIsMarkedDelistedAndComesBackIfItReturns() = runTest {
        val s = store()
        s.replaceCoins(listOf(row("AUSDT", 1.0), row("BUSDT", 2.0)))
        s.replaceCoins(listOf(row("AUSDT", 1.0)))
        assertEquals(true, s.coin("BUSDT")?.delisted)
        assertEquals(false, s.coin("AUSDT")?.delisted)
        assertEquals(setOf("AUSDT"), s.analysable(listOf("AUSDT", "BUSDT")))
        s.replaceCoins(listOf(row("AUSDT", 1.0), row("BUSDT", 2.0)))
        assertEquals(false, s.coin("BUSDT")?.delisted)
    }

    @Test
    fun aStablecoinMarkSurvivesPairListRefreshesAndHidesTheCoin() = runTest {
        val s = store()
        s.replaceCoins(listOf(row("AUSDT", 1.0), row("PEGUSDT", 5.0)))
        s.markStable("PEGUSDT")
        s.replaceCoins(listOf(row("AUSDT", 1.0), row("PEGUSDT", 6.0, seen = 2)))
        assertEquals(true, s.coin("PEGUSDT")?.stable)
        assertEquals(6.0, s.coin("PEGUSDT")?.quoteVolume)
        assertEquals(listOf("AUSDT"), s.offeredCoins("", 10).map { it.symbol })
        assertEquals(setOf("AUSDT", "UNKNOWNUSDT"), s.analysable(listOf("AUSDT", "PEGUSDT", "UNKNOWNUSDT")))
        assertEquals(2 to 1, s.coinCount())
    }

    @Test
    fun dailyClosesAreTheLastOnesOldestFirst() = runTest {
        val s = store()
        s.insertKlines("A", Timeframe.D1, candlesOf(Timeframe.D1, EPOCH_START, 40))
        val closes = s.dailyCloses("A", 30)
        assertEquals(30, closes.size)
        assertEquals(100.0 + 10 * 0.01 + 0.5, closes.first(), 1e-12)
        assertEquals(100.0 + 39 * 0.01 + 0.5, closes.last(), 1e-12)
    }

    @Test
    fun closesSinceReadsSeveralCoinsInOneGoOldestFirstAndLeavesOutTheUnknown() = runTest {
        val s = store()
        s.insertKlines("AUSDT", Timeframe.H1, candlesOf(Timeframe.H1, EPOCH_START, 10, base = 100.0))
        s.insertKlines("BUSDT", Timeframe.H1, candlesOf(Timeframe.H1, EPOCH_START, 10, base = 200.0))
        s.insertKlines("AUSDT", Timeframe.H4, candlesOf(Timeframe.H4, EPOCH_START, 3))
        val got = s.closesSince(Timeframe.H1, listOf("AUSDT", "BUSDT", "NOPEUSDT"), EPOCH_START + 5 * Timeframe.H1.ms)
        assertEquals(setOf("AUSDT", "BUSDT"), got.keys)
        assertEquals(5, got.getValue("AUSDT").size)
        assertEquals(EPOCH_START + 5 * Timeframe.H1.ms, got.getValue("AUSDT").first().time)
        assertEquals(candlesOf(Timeframe.H1, EPOCH_START, 10, base = 200.0).last().close, got.getValue("BUSDT").last().close)
        assertTrue(s.closesSince(Timeframe.H1, emptyList(), 0).isEmpty())
    }

    @Test
    fun theFileSurvivesBeingClosedAndReopened() = runTest {
        val name = "candles-reopen-test.db"
        context.deleteDatabase(name)
        val first = CandleStore(context, name, io = Dispatchers.Unconfined)
        first.insertKlines("A", Timeframe.H1, candlesOf(Timeframe.H1, EPOCH_START, 30))
        first.close()
        assertEquals(30, CandleStore(context, name, io = Dispatchers.Unconfined).count("A", Timeframe.H1))
    }
}
