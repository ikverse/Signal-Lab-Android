package com.ikverse.signallab.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.data.history.DailyHistory
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Signal Lab 2's additions to the candle store: turnover per candle, daily history kept whole, first candles. */
@RunWith(RobolectricTestRunner::class)
class V2DataTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun store() = CandleStore(context, name = null, io = Dispatchers.Unconfined)

    @Test
    fun aVersion3FileUpgradesWithEveryCandleKept() = runTest {
        val name = "candles-v3-upgrade-test.db"
        context.deleteDatabase(name)
        // The candle table exactly as version 3 created it, with one candle in it.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE candles (symbol TEXT NOT NULL, tf TEXT NOT NULL, open_time INTEGER NOT NULL, open REAL NOT NULL, " +
                "high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL, close_time INTEGER NOT NULL, " +
                "PRIMARY KEY (symbol, tf, open_time)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE coins (symbol TEXT PRIMARY KEY, base TEXT NOT NULL, status TEXT NOT NULL, offered INTEGER NOT NULL, " +
                "stable INTEGER NOT NULL DEFAULT 0, delisted INTEGER NOT NULL DEFAULT 0, quote_volume REAL NOT NULL DEFAULT 0, " +
                "high24 REAL NOT NULL DEFAULT 0, low24 REAL NOT NULL DEFAULT 0, seen_at INTEGER NOT NULL, listed_at INTEGER, " +
                "change24 REAL NOT NULL DEFAULT 0, trades24 INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE known_gaps (symbol TEXT NOT NULL, tf TEXT NOT NULL, from_time INTEGER NOT NULL, to_time INTEGER NOT NULL, " +
                "PRIMARY KEY (symbol, tf, from_time)) WITHOUT ROWID")
            db.execSQL("INSERT INTO candles VALUES ('AUSDT','1d',$EPOCH_START,10.0,11.0,9.0,10.0,5.0,${EPOCH_START + DAY - 1})")
            db.version = 3
        }
        val s = CandleStore(context, name, io = Dispatchers.Unconfined)
        assertEquals(1, s.count("AUSDT", Timeframe.D1))
        // The old candle has no turnover stored, so it counts as volume × close; a new one counts its own.
        assertEquals(50.0, s.quoteVolumeSince(listOf("AUSDT"), EPOCH_START + 2 * DAY, 30).getValue("AUSDT"))
        s.insertKlines("AUSDT", Timeframe.D1, listOf(Kline(EPOCH_START + DAY, 10.0, 11.0, 9.0, 10.0, 5.0, EPOCH_START + 2 * DAY - 1, quoteVolume = 70.0)))
        assertEquals(120.0, s.quoteVolumeSince(listOf("AUSDT"), EPOCH_START + 2 * DAY, 30).getValue("AUSDT"))
        s.close()
    }

    @Test
    fun turnoverCountsOnlyTheLastDaysOfDailyCandles() = runTest {
        val s = store()
        val k = List(40) { i -> Kline(EPOCH_START + i * DAY, 1.0, 1.0, 1.0, 1.0, 1.0, EPOCH_START + (i + 1) * DAY - 1, quoteVolume = 1.0) }
        s.insertKlines("AUSDT", Timeframe.D1, k)
        s.insertKlines("AUSDT", Timeframe.H1, candlesOf(Timeframe.H1, EPOCH_START, 100))
        val now = EPOCH_START + 40 * DAY
        assertEquals(30.0, s.quoteVolumeSince(listOf("AUSDT", "NOPEUSDT"), now, 30).getValue("AUSDT"), "hourly candles do not count")
        assertTrue("NOPEUSDT" !in s.quoteVolumeSince(listOf("NOPEUSDT"), now, 30))
    }

    @Test
    fun theTidyUpNeverDeletesDailyCandles() = runTest {
        val s = store()
        s.insertKlines("AUSDT", Timeframe.D1, candlesOf(Timeframe.D1, EPOCH_START, 500))
        s.insertKlines("AUSDT", Timeframe.H1, candlesOf(Timeframe.H1, EPOCH_START, 500))
        val now = EPOCH_START + 1000 * DAY
        s.trim(now, DataConfig::historyDays, inUse = emptySet(), keep = DataConfig.REGIME_COIN to Timeframe.D1, alwaysKept = DataConfig.ALWAYS_KEPT)
        assertEquals(500, s.count("AUSDT", Timeframe.D1), "a coin no list watches keeps its daily history")
        assertEquals(0, s.count("AUSDT", Timeframe.H1))
    }

    @Test
    fun firstOpensGivesEachCoinsFirstCandle() = runTest {
        val s = store()
        s.insertKlines("AUSDT", Timeframe.D1, candlesOf(Timeframe.D1, EPOCH_START + 5 * DAY, 3))
        s.insertKlines("BUSDT", Timeframe.D1, candlesOf(Timeframe.D1, EPOCH_START, 3))
        assertEquals(mapOf("AUSDT" to EPOCH_START + 5 * DAY, "BUSDT" to EPOCH_START), s.firstOpens(Timeframe.D1, listOf("AUSDT", "BUSDT", "CUSDT")))
    }

    @Test
    fun dailyHistoryReachesTheListingOnceAndThenOnlyMovesForward() = runTest {
        val listed = EPOCH_START
        val now = listed + 2500 * DAY + 3_600_000L
        val market = FakeMarket(now)
        market.series["AUSDT" to Timeframe.D1] = candlesOf(Timeframe.D1, listed, 2500)
        val s = store()
        val history = DailyHistory(CandleSync(market, s))
        val first = history.ensure(listOf("AUSDT"))
        assertEquals(1, first.done)
        assertEquals(2500, s.count("AUSDT", Timeframe.D1), "every closed daily candle since the listing")
        val before = market.requestsFor("AUSDT", Timeframe.D1)
        history.ensure(listOf("AUSDT"))
        assertEquals(1, market.requestsFor("AUSDT", Timeframe.D1) - before, "the second run asks only for what is new")
    }
}
