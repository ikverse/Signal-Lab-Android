package com.ikverse.signallab.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.Refusal
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ctx: Context get() = ApplicationProvider.getApplicationContext()

private fun store() = CandleStore(ctx, name = null, io = Dispatchers.Unconfined)

/** A fake exchange that has [count] candles of every chart for each of [symbols], ending at the last closed candle. */
private fun exchange(vararg symbols: String, count: Int = 30, now: Long = EPOCH_START + 700 * DAY, pegged: Set<String> = emptySet()) =
    FakeMarket(now).also { m ->
        for (c in symbols) for (tf in Timeframe.entries) {
            val end = now - (now % tf.ms)
            val first = end - count * tf.ms
            m.series[c to tf] = if (c in pegged) candlesOf(tf, first, count, base = 1.0, drift = 0.00005, spread = 0.004) else candlesOf(tf, first, count)
        }
    }

/** What is downloaded, how much of it is kept, and when it is thrown away. */
@RunWith(RobolectricTestRunner::class)
class CandleStoreChartsTest {
    private val s = store()

    private fun klines(tf: Timeframe, firstOpen: Long, n: Int): List<Kline> = candlesOf(tf, firstOpen, n)

    @Test
    fun trimmingKeepsEachChartsDaysDropsUnusedChartsAndAlwaysKeepsBitcoinDaily() = runTest {
        val now = EPOCH_START + 700 * DAY
        // Hourly: 120 days of candles, of which 100 are kept. Minutes: a chart no list uses. Daily: BTC kept, ETH not (no list uses daily).
        s.insertKlines("BTCUSDT", Timeframe.H1, klines(Timeframe.H1, now - 120 * DAY, 120 * 24))
        s.insertKlines("BTCUSDT", Timeframe.M1, klines(Timeframe.M1, now - 2 * DAY, 2 * 1440))
        s.insertKlines("BTCUSDT", Timeframe.D1, klines(Timeframe.D1, now - 500 * DAY, 500))
        s.insertKlines("ETHUSDT", Timeframe.D1, klines(Timeframe.D1, now - 500 * DAY, 500))
        val removed = s.trim(now, DataConfig::historyDays, setOf(Timeframe.H1), "BTCUSDT" to Timeframe.D1)
        assertEquals(0, s.count("BTCUSDT", Timeframe.M1), "a chart nobody watches is gone")
        assertEquals(0, s.count("ETHUSDT", Timeframe.D1), "a coin's daily chart goes unless a list uses it")
        assertTrue(s.firstOpen("BTCUSDT", Timeframe.H1)!! >= now - 100 * DAY)
        assertEquals(100 * 24, s.count("BTCUSDT", Timeframe.H1))
        assertTrue(s.firstOpen("BTCUSDT", Timeframe.D1)!! >= now - 400 * DAY)
        assertEquals(400, s.count("BTCUSDT", Timeframe.D1), "bitcoin's daily candles are kept for the market regime")
        assertEquals(20 * 24 + 2 * 1440 + 100 + 500, removed)
    }

    @Test
    fun aChartAListUsesIsOnlyTrimmedByAge() = runTest {
        val now = EPOCH_START + 700 * DAY
        s.insertKlines("AUSDT", Timeframe.D1, klines(Timeframe.D1, now - 450 * DAY, 450))
        s.trim(now, DataConfig::historyDays, setOf(Timeframe.D1), "BTCUSDT" to Timeframe.D1)
        assertEquals(400, s.count("AUSDT", Timeframe.D1))
    }

    @Test
    fun trimmingForgetsTheStretchesWithNoCandlesThatAreNowTooOldToMatter() = runTest {
        val now = EPOCH_START + 700 * DAY
        s.addKnownGap("AUSDT", Timeframe.H1, (now - 200 * DAY)..(now - 190 * DAY))
        s.addKnownGap("AUSDT", Timeframe.H1, (now - 5 * DAY)..(now - 4 * DAY))
        s.trim(now, DataConfig::historyDays, setOf(Timeframe.H1), "BTCUSDT" to Timeframe.D1)
        assertEquals(1, s.knownGaps("AUSDT", Timeframe.H1).size)
    }

    @Test
    fun listingDatesAreKeptAcrossPairListRefreshes() = runTest {
        s.setListedAt("AUSDT", 12345)
        assertEquals(12345L, s.listedAt("AUSDT"), "a coin with no row yet gets one")
        s.replaceCoins(listOf(CoinRow("AUSDT", "A", "TRADING", true, false, false, 5.0, 2.0, 1.0, 99)))
        assertEquals(12345L, s.listedAt("AUSDT"))
        assertEquals(12345L, s.coin("AUSDT")!!.listedAt)
        assertNull(s.listedAt("BUSDT"))
    }

    @Test
    fun aVersionOneCandleFileOpensWithTheCurrentCodeAndGainsTheListingColumn() = runTest {
        val name = "candles-v1.db"
        ctx.deleteDatabase(name)
        val raw = ctx.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        raw.execSQL("CREATE TABLE candles (symbol TEXT NOT NULL, tf TEXT NOT NULL, open_time INTEGER NOT NULL, open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL, close_time INTEGER NOT NULL, PRIMARY KEY (symbol, tf, open_time)) WITHOUT ROWID")
        raw.execSQL("CREATE TABLE coins (symbol TEXT PRIMARY KEY, base TEXT NOT NULL, status TEXT NOT NULL, offered INTEGER NOT NULL, stable INTEGER NOT NULL DEFAULT 0, delisted INTEGER NOT NULL DEFAULT 0, quote_volume REAL NOT NULL DEFAULT 0, high24 REAL NOT NULL DEFAULT 0, low24 REAL NOT NULL DEFAULT 0, seen_at INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE known_gaps (symbol TEXT NOT NULL, tf TEXT NOT NULL, from_time INTEGER NOT NULL, to_time INTEGER NOT NULL, PRIMARY KEY (symbol, tf, from_time)) WITHOUT ROWID")
        raw.execSQL("INSERT INTO coins VALUES ('OLDUSDT','OLD','TRADING',1,0,0,9,2,1,5)")
        raw.version = 1
        raw.close()
        val upgraded = CandleStore(ctx, name, io = Dispatchers.Unconfined)
        assertEquals("OLDUSDT", upgraded.coin("OLDUSDT")?.symbol)
        assertNull(upgraded.listedAt("OLDUSDT"))
        upgraded.setListedAt("OLDUSDT", 777)
        assertEquals(777L, upgraded.listedAt("OLDUSDT"))
    }
}
