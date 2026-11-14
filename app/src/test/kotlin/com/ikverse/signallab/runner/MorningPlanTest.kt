package com.ikverse.signallab.runner

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MorningPlanTest {
    private val hour = 3_600_000L
    private val midnight = LocalDate.parse("2026-11-14").toEpochDay() * 86_400_000L

    @Test
    fun thePlanIsDueOnceADayFromTheChosenHourOnThePhonesClock() {
        val today = LocalDate.parse("2026-11-14").toEpochDay()
        assertNull(MorningPlan.dueDay(midnight + 7 * hour, ZoneOffset.UTC, 8, lastDay = null), "before the hour")
        assertEquals(today, MorningPlan.dueDay(midnight + 8 * hour + 90_000, ZoneOffset.UTC, 8, lastDay = today - 1))
        assertNull(MorningPlan.dueDay(midnight + 9 * hour, ZoneOffset.UTC, 8, lastDay = today), "already made today")
        // 06:01 UTC is 08:01 in Cairo in winter.
        assertEquals(today, MorningPlan.dueDay(midnight + 6 * hour + 60_000, ZoneId.of("Africa/Cairo"), 8, lastDay = null))
        // A phone that slept through the hour still gets the plan on its next pass that day.
        assertEquals(today, MorningPlan.dueDay(midnight + 15 * hour, ZoneOffset.UTC, 8, lastDay = today - 1))
    }

    @Test
    fun thePlanListsSellsFirstThenBuysWithAmountsAndStops() {
        val plan = assertNotNull(MorningPlan.words(
            sells = listOf(MorningPlan.Sell("SOL", "Trend timer", "the strategy's exit signal")),
            buys = listOf(MorningPlan.Buy("ETH", "Trend timer", 360.9, null), MorningPlan.Buy("BNB", "Breakout", null, "612.40")),
        ))
        assertEquals("Today's plan: 1 to sell, 2 to buy", plan.title)
        assertEquals(
            "Sell SOL (Trend timer): the strategy's exit signal.\nBuy 360 USDT of ETH (Trend timer).\nBuy BNB (Breakout), then place a stop at 612.40.",
            plan.text,
        )
        assertFalse(plan.opensCall)
        assertEquals("Today's plan: 1 to buy", MorningPlan.words(emptyList(), listOf(MorningPlan.Buy("ETH", "Trend timer", null, null)))?.title)
        assertNull(MorningPlan.words(emptyList(), emptyList()), "a day with nothing to do")
    }
}
