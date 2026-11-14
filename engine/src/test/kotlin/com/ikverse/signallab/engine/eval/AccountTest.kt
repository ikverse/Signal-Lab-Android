package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.strategy.Breakout
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.engine.strategy.NearHigh
import com.ikverse.signallab.engine.strategy.Tier
import com.ikverse.signallab.engine.strategy.TrendTimer
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTest {
    private val day = 86_400_000L
    private val first = LocalDate.parse("2024-01-01").toEpochDay() * day

    /** A coin that sits at 100 with a 2% daily range: its average true range is 2% of its price, so a plain call is capped at 20%. */
    private fun flat(symbol: String, n: Int = 60): Candles {
        val t = LongArray(n) { first + it * day }
        return Candles(symbol, Timeframe.D1, t, DoubleArray(n) { 100.0 }, DoubleArray(n) { 101.0 }, DoubleArray(n) { 99.0 }, DoubleArray(n) { 100.0 },
            DoubleArray(n) { 1000.0 }, LongArray(n) { t[it] + day - 1 })
    }

    /** A trade signalled on candle [s], entering at the next open and leaving [held] days later with [net] after fees. */
    private fun trade(c: Candles, s: Int, held: Int, net: Double) = BacktestTrade(
        c.symbol, c.t[s], c.t[s + 1], 100.0, c.t[s + 1 + held], 100.0 * (1 + net), Leave.TIME, held, net, net, Double.NaN, Double.NaN, 1,
    )

    @Test
    fun aCallIsPaidOutAtItsResultOnTheShareOfTheAccountItHeld() {
        val btc = flat("BTCUSDT")
        val days = Account.run(listOf(Account.Entry(trade(btc, 20, 5, 0.10), false, 0)), mapOf("BTCUSDT" to btc), btc, first, Account.Sizing.PLAIN)
        assertEquals(10_000.0 * (1 + 0.20 * 0.10), days.last().equity, 1e-6)
        assertEquals(0.20, days.first { it.time == btc.t[22] }.held, 1e-9)
        assertEquals(0.0, days.last().held, 1e-12)
    }

    @Test
    fun theCallsTogetherNeverHoldMoreThanTheAccount() {
        val btc = flat("BTCUSDT")
        val coins = (1..7).map { flat("C${it}USDT") }
        val panel = coins.associateBy { it.symbol } + ("BTCUSDT" to btc)
        val entries = coins.mapIndexed { i, c -> Account.Entry(trade(c, 20, 10, 0.0), false, i) }
        val capped = Account.run(entries, panel, btc, first, Account.Sizing.PLAIN)
        assertEquals(1.0, capped.maxOf { it.held }, 1e-9)
        val borrowing = Account.run(entries, panel, btc, first, Account.Sizing.PLAIN, maxHeld = Double.MAX_VALUE)
        assertEquals(1.4, borrowing.maxOf { it.held }, 1e-9)
    }

    @Test
    fun aCallThatDoesNotFitShrinksToTheRoomLeftOrIsSkipped() {
        assertEquals(0.10, Risk.fit(0.10, held = 0.50), 1e-12)
        assertEquals(0.05, Risk.fit(0.10, held = 0.95), 1e-12)
        assertEquals(0.0, Risk.fit(0.10, held = 0.995), 1e-12)
        assertEquals(0.0, Risk.fit(0.10, held = 1.0), 1e-12)
    }

    @Test
    fun theSummaryReportsTheWorstFallAndEachYear() {
        val y2 = LocalDate.parse("2025-01-01").toEpochDay() * day
        val days = listOf(
            Account.Day(first, 12_000.0, 1.0), Account.Day(first + day, 9_000.0, 0.5), Account.Day(y2, 9_900.0, 0.0), Account.Day(y2 + day, 10_890.0, 0.0),
        )
        val s = assertNotNull(Account.summarize(days))
        assertEquals(-0.25, s.worstFall, 1e-12)
        assertEquals(listOf(2024, 2025), s.byYear.map { it.first })
        assertEquals(-0.10, s.byYear[0].second, 1e-12)
        assertEquals(0.21, s.byYear[1].second, 1e-12)
        assertEquals(1.0, s.maxHeld)
        assertNull(Account.summarize(days.take(1)))
    }

    @Test
    fun holdingBitcoinFollowsItsPriceFromTheFirstOpen() {
        val btc = flat("BTCUSDT")
        val days = Account.holding(btc, btc.t[10])
        assertEquals(btc.size - 10, days.size)
        assertEquals(10_000.0, days.last().equity, 1e-9)
    }

    @Test
    fun everyDailyBuiltInHasARecordAndOnlyASteadierOneThanBitcoinEarnsItsPlace() {
        for (s in Builtins.all.filter { it.timeframe == Timeframe.D1 }) assertNotNull(AccountRecords.of(s.id), "${s.id} has no account record")
        val btc = AccountRecords.BITCOIN
        assertFalse(btc.steadierThan(btc))
        assertTrue(AccountRecord(20_000.0, 0.1, -0.30, 1.0, emptyList()).steadierThan(btc))
        assertFalse(AccountRecord(9_000.0, -0.01, -0.30, 1.0, emptyList()).steadierThan(btc), "an account that shrank")
        assertFalse(AccountRecord(20_000.0, 0.1, -0.80, 1.0, emptyList()).steadierThan(btc), "a deeper fall than Bitcoin's")
        assertFalse(AccountRecord(20_000.0, 0.1, -0.30, 0.5, emptyList()).steadierThan(btc), "less return for its swings")
    }

    @Test
    fun aSteadierAccountThanBitcoinMakesAResearchedStrategyPromising() {
        val noise = DoubleArray(600) { if (it % 2 == 0) 0.05 else -0.05 }
        val none = DoubleArray(0)
        val good = AccountRecord(20_000.0, 0.1, -0.30, 1.0, emptyList())
        val verdict = Tiers.judge(Breakout(), noise, noise, none, 100, account = good)
        assertEquals(Tier.PROMISING, verdict.tier)
        assertTrue(verdict.reason.contains("one account"), verdict.reason)
        assertEquals(Tier.WATCH, Tiers.judge(Breakout(), noise, noise, none, 100, account = AccountRecords.BITCOIN).tier)
        // One study behind it: the account alone does not lift it.
        assertEquals(Tier.TESTING, Tiers.judge(NearHigh(), noise, noise, none, 100, account = good).tier)
        // Live calls behind random entries outweigh the past.
        val behind = DoubleArray(40) { if (it % 2 == 0) 0.04 else -0.05 }
        assertEquals(Tier.WATCH, Tiers.judge(Breakout(), noise, noise, behind, 100, account = good).tier)
        // The records as they stand: Trend timer earns its place on Today, Breakout does not.
        assertEquals(Tier.PROMISING, Tiers.judge(TrendTimer(), noise, noise, none, 100).tier)
        assertEquals(Tier.WATCH, Tiers.judge(Breakout(), noise, noise, none, 100).tier)
    }
}
