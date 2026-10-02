package com.ikverse.signallab.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchlistRulesTest {
    private fun coins(prefix: String, n: Int) = (1..n).map { "$prefix${it}USDT" }
    private fun list(id: Long, symbols: List<String>, active: Boolean) = Watchlist(id, "List $id", symbols, active)

    @Test
    fun aCoinInTwoActiveListsCountsOnce() {
        val a = list(1, listOf("BTCUSDT", "ETHUSDT"), true)
        val b = list(2, listOf("ETHUSDT", "SOLUSDT"), true)
        val off = list(3, listOf("DOGEUSDT"), false)
        assertEquals(listOf("BTCUSDT", "ETHUSDT", "SOLUSDT"), WatchlistRules.activeCoins(listOf(a, b, off)).toList())
    }

    @Test
    fun aListHoldsAtMostThirtyCoinsAndNoDuplicates() {
        val full = list(1, coins("A", 30), false)
        assertEquals(Refusal.LIST_FULL, WatchlistRules.checkAdd(listOf(full), full, "NEWUSDT"))
        assertEquals(Refusal.DUPLICATE_COIN, WatchlistRules.checkAdd(listOf(full), full, "A5USDT"))
        val almost = list(1, coins("A", 29), false)
        assertNull(WatchlistRules.checkAdd(listOf(almost), almost, "NEWUSDT"))
    }

    @Test
    fun activeCoinsAreCappedAtOneHundredFifty() {
        val lists = (1..5).map { list(it.toLong(), coins("L$it", 30), true) } // exactly 150 distinct
        assertEquals(150, WatchlistRules.activeCoins(lists).size)
        val inactive = list(9, coins("Z", 3), false)
        assertEquals(Refusal.OVER_ACTIVE_CAP, WatchlistRules.checkActivate(lists + inactive, inactive))
        val overlapping = list(10, coins("L1", 3), false) // coins already counted
        assertNull(WatchlistRules.checkActivate(lists + overlapping, overlapping))
    }

    @Test
    fun addingToAnActiveListRespectsTheCapButAnInactiveOneIsNotCapped() {
        val five = (1..5).map { list(it.toLong(), coins("L$it", 30), true) } // 150 distinct, every list full
        val sixth = list(6, listOf("L1_1USDT".replace("L1_1", "L11")), true) // shares a coin, has room
        val all = five + sixth
        assertEquals(150, WatchlistRules.activeCoins(all).size)
        assertEquals(Refusal.OVER_ACTIVE_CAP, WatchlistRules.checkAdd(all, sixth, "BRANDNEWUSDT"))
        assertNull(WatchlistRules.checkAdd(all, sixth, "L21USDT")) // already counted elsewhere, so free
        val parked = list(7, listOf("PARKEDUSDT"), false)
        assertNull(WatchlistRules.checkAdd(all + parked, parked, "BRANDNEWUSDT")) // inactive lists are not capped
    }

    @Test
    fun namesAreTrimmedCollapsedAndUniqueIgnoringCase() {
        assertEquals("My coins" to null, WatchlistRules.cleanName("  My   coins ", emptyList()))
        assertEquals(null to Refusal.NAME_BLANK, WatchlistRules.cleanName("   ", emptyList()))
        assertEquals(null to Refusal.NAME_TOO_LONG, WatchlistRules.cleanName("x".repeat(41), emptyList()))
        assertEquals(null to Refusal.NAME_TAKEN, WatchlistRules.cleanName("my COINS", listOf("My coins")))
        assertEquals("x".repeat(40) to null, WatchlistRules.cleanName("x".repeat(40), emptyList()))
    }

    @Test
    fun theFilterHidesPeggedLeveragedAndStableLookingPairs() {
        val bases = setOf("BTC", "ETH", "USDC", "BTCUP", "BTCDOWN", "SOL", "UP", "FOO")
        assertTrue(CoinFilter.offered("BTC", bases, 90_000.0, 85_000.0))
        assertFalse(CoinFilter.offered("USDC", bases, 1.001, 0.999))
        assertFalse(CoinFilter.offered("BTCUP", bases, 10.0, 8.0)) // leveraged: BTC exists
        assertFalse(CoinFilter.offered("BTCDOWN", bases, 10.0, 8.0))
        assertTrue(CoinFilter.offered("UP", bases, 2.0, 1.5)) // "UP" alone is a real name, not a suffix of anything
        assertFalse(CoinFilter.offered("FOO", bases, 1.01, 0.99)) // a dollar peg nobody named
        assertTrue(CoinFilter.offered("FOO", bases, 1.2, 0.99))
    }

    @Test
    fun stablecoinByBehaviourNeedsAFullWindowAllInsideTheBand() {
        assertTrue(CoinFilter.looksStable(DoubleArray(40) { 1.0 + 0.01 * kotlin.math.sin(it.toDouble()) }))
        assertFalse(CoinFilter.looksStable(DoubleArray(29) { 1.0 })) // too little history to say
        val depeg = DoubleArray(40) { 1.0 }.also { it[35] = 0.9 }
        assertFalse(CoinFilter.looksStable(depeg))
        val oldDepeg = DoubleArray(40) { 1.0 }.also { it[3] = 0.9 } // outside the last 30
        assertTrue(CoinFilter.looksStable(oldDepeg))
    }

    // --- charts per list ------------------------------------------------------------------------------------------

    private fun on(id: Long, symbols: List<String>, active: Boolean, vararg tfs: Timeframe) = Watchlist(id, "List $id", symbols, active, tfs.toSet())

    @Test
    fun aNewListIsWatchedOnFifteenMinutesOneHourAndFourHours() {
        assertEquals(setOf(Timeframe.M15, Timeframe.H1, Timeframe.H4), Watchlist(1, "x", emptyList(), false).timeframes)
        assertEquals(setOf(Timeframe.H1, Timeframe.H4, Timeframe.D1), Timeframe.LEGACY_DEFAULT)
    }

    @Test
    fun oneMinuteChartsAreCappedAtTenCoinsAcrossAllActiveLists() {
        val a = on(1, coins("A", 10), true, Timeframe.M1)
        assertEquals(Refusal.OVER_FAST_CAP, WatchlistRules.checkAdd(listOf(a), a, "NEWUSDT"))
        val b = on(2, coins("B", 3), false, Timeframe.M1, Timeframe.H1)
        assertEquals(Refusal.OVER_FAST_CAP, WatchlistRules.checkActivate(listOf(a, b), b))
        val sameCoins = on(3, coins("A", 3), false, Timeframe.M1)
        assertNull(WatchlistRules.checkActivate(listOf(a, sameCoins), sameCoins), "coins already watched cost nothing more")
        val slow = on(4, coins("S", 20), false, Timeframe.H1)
        assertNull(WatchlistRules.checkActivate(listOf(a, slow), slow), "slower charts have their own cap")
    }

    @Test
    fun fiveMinuteChartsAreCappedAtThirtyCoins() {
        val a = on(1, coins("A", 30), true, Timeframe.M5)
        val b = on(2, coins("B", 1), false, Timeframe.M5)
        assertEquals(Refusal.OVER_FAST_CAP, WatchlistRules.checkActivate(listOf(a, b), b))
        val c = on(3, coins("A", 5), false, Timeframe.M5)
        assertNull(WatchlistRules.checkActivate(listOf(a, c), c))
    }

    @Test
    fun theOverallCapOfOneHundredFiftyStillHoldsOnTheSlowerCharts() {
        val lists = (1..5).map { on(it.toLong(), coins("L$it", 30), true, Timeframe.H1) }
        val extra = on(9, coins("Z", 1), false, Timeframe.D1)
        assertEquals(Refusal.OVER_ACTIVE_CAP, WatchlistRules.checkActivate(lists + extra, extra))
    }

    @Test
    fun changingTheChartsOfAnActiveListIsCheckedAgainstTheCaps() {
        val l = on(1, coins("A", 11), true, Timeframe.H1)
        assertEquals(Refusal.NO_TIMEFRAME, WatchlistRules.checkTimeframes(listOf(l), l, emptySet()))
        assertEquals(Refusal.OVER_FAST_CAP, WatchlistRules.checkTimeframes(listOf(l), l, setOf(Timeframe.H1, Timeframe.M1)))
        assertNull(WatchlistRules.checkTimeframes(listOf(l), l, setOf(Timeframe.M5, Timeframe.H4)))
        val off = on(2, coins("B", 30), false, Timeframe.H1)
        assertNull(WatchlistRules.checkTimeframes(listOf(off), off, setOf(Timeframe.M1)), "a list that is off costs nothing, so it can be set up first")
        assertEquals(Refusal.OVER_FAST_CAP, WatchlistRules.checkActivate(listOf(off.copy(timeframes = setOf(Timeframe.M1))), off.copy(timeframes = setOf(Timeframe.M1))))
    }

    @Test
    fun eachCoinKnowsEveryChartItIsWatchedOn() {
        val a = on(1, listOf("BTCUSDT", "ETHUSDT"), true, Timeframe.M15)
        val b = on(2, listOf("ETHUSDT"), true, Timeframe.H1, Timeframe.D1)
        val off = on(3, listOf("DOGEUSDT"), false, Timeframe.M1)
        val by = WatchlistRules.timeframesByCoin(listOf(a, b, off))
        assertEquals(setOf(Timeframe.M15), by["BTCUSDT"])
        assertEquals(setOf(Timeframe.M15, Timeframe.H1, Timeframe.D1), by["ETHUSDT"])
        assertNull(by["DOGEUSDT"])
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), WatchlistRules.activeCoinsOn(listOf(a, b, off), Timeframe.M15).toList())
        assertEquals(listOf("ETHUSDT"), WatchlistRules.activeCoinsOn(listOf(a, b, off), Timeframe.D1).toList())
    }

    @Test
    fun timeframesRoundTripThroughTheirStoredText() {
        val set = setOf(Timeframe.H4, Timeframe.M5, Timeframe.D1)
        assertEquals("5m,4h,1d", Timeframe.formatSet(set))
        assertEquals(set, Timeframe.parseSet("5m,4h,1d"))
        assertEquals(setOf(Timeframe.H1), Timeframe.parseSet("1h, 9x"))
    }
}
