package com.ikverse.signallab.data.universe

import com.ikverse.signallab.data.CoinRow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UniverseTest {
    private val day = 86_400_000L
    private val now = 1_760_000_000_000L
    private val old = now - 400 * day

    private fun coin(
        symbol: String, offered: Boolean = true, stable: Boolean = false, delisted: Boolean = false,
        status: String = "TRADING", listedAt: Long? = old,
    ) = CoinRow(symbol, symbol.removeSuffix("USDT"), status, offered, stable, delisted, 0.0, 0.0, 0.0, now, listedAt)

    @Test
    fun theBusiestEligibleCoinsAreRankedByTurnover() {
        val coins = listOf(coin("AUSDT"), coin("BUSDT"), coin("CUSDT"), coin("DUSDT"))
        val vol = mapOf("AUSDT" to 5.0, "BUSDT" to 50.0, "CUSDT" to 20.0, "DUSDT" to 1.0)
        val got = Universe.select(coins, vol, emptyMap(), now, size = 3)
        assertEquals(listOf("BUSDT" to 1, "CUSDT" to 2, "AUSDT" to 3), got.map { it.symbol to it.rank })
        assertTrue(got.none { it.pinned })
    }

    @Test
    fun coinsNotWorthWatchingAreLeftOut() {
        val coins = listOf(
            coin("OKUSDT"),
            coin("USDCUSDT", stable = true),
            coin("BULLUSDT", offered = false),
            coin("GONEUSDT", delisted = true),
            coin("HALTUSDT", status = "BREAK"),
            coin("NEWUSDT", listedAt = now - 30 * day),
            coin("UNKNOWNUSDT", listedAt = null),
            coin("ETHBTC"),
            coin("QUIETUSDT"),
        )
        val vol = coins.associate { it.symbol to 100.0 } + ("QUIETUSDT" to 0.0)
        assertEquals(listOf("OKUSDT"), Universe.select(coins, vol, emptyMap(), now).map { it.symbol })
    }

    @Test
    fun aCoinJoinsOnlyAfterHalfAYearOnTheExchange() {
        val coins = listOf(coin("HALFUSDT", listedAt = now - 179 * day), coin("SIXUSDT", listedAt = now - 181 * day))
        assertEquals(listOf("SIXUSDT"), Universe.select(coins, mapOf("HALFUSDT" to 9.0, "SIXUSDT" to 1.0), emptyMap(), now).map { it.symbol })
    }

    @Test
    fun aCoinWithNoListingDayIsAgedByItsFirstStoredDailyCandle() {
        val coins = listOf(coin("AUSDT", listedAt = null), coin("BUSDT", listedAt = null))
        val first = mapOf("AUSDT" to now - 200 * day, "BUSDT" to now - 10 * day)
        assertEquals(listOf("AUSDT"), Universe.select(coins, mapOf("AUSDT" to 1.0, "BUSDT" to 9.0), first, now).map { it.symbol })
    }

    @Test
    fun pinsAreAddedAndExcludesRemoved() {
        val coins = listOf(coin("AUSDT"), coin("BUSDT"), coin("CUSDT"), coin("NEWUSDT", listedAt = now - day), coin("GONEUSDT", delisted = true))
        val vol = mapOf("AUSDT" to 3.0, "BUSDT" to 2.0, "CUSDT" to 1.0, "NEWUSDT" to 0.5, "GONEUSDT" to 9.0)
        val got = Universe.select(coins, vol, emptyMap(), now, pins = setOf("NEWUSDT", "CUSDT", "GONEUSDT"), excludes = setOf("AUSDT"), size = 2)
        assertEquals(listOf("BUSDT", "CUSDT", "NEWUSDT"), got.map { it.symbol })
        assertEquals(listOf(false, true, true), got.map { it.pinned })
        assertEquals(0, got.last().rank, "a pin outside the busiest has no rank")
    }

    @Test
    fun equalTurnoverIsBrokenByName() {
        val coins = listOf(coin("ZUSDT"), coin("AUSDT"))
        assertEquals(listOf("AUSDT", "ZUSDT"), Universe.select(coins, mapOf("ZUSDT" to 1.0, "AUSDT" to 1.0), emptyMap(), now).map { it.symbol })
    }
}
