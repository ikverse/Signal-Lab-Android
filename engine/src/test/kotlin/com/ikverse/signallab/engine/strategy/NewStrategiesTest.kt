package com.ikverse.signallab.engine.strategy

import com.ikverse.signallab.engine.Golden
import com.ikverse.signallab.engine.Synth
import com.ikverse.signallab.engine.Timeframe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewStrategiesTest {
    private val day = 86_400_000L

    @Test
    fun hatedRallyFiresOnceWhenAPositiveMonthTurnsNegative() {
        val c = Synth.candles(200, Timeframe.D1, seed = 3, symbol = "BTCUSDT")
        val step = 8 * 3_600_000L
        val from = c.t.first() - 60 * day
        // Funding positive for the first 120 days, then negative.
        val n = ((c.closeTime.last() - from) / step).toInt() + 1
        val time = LongArray(n) { from + it * step }
        val turn = c.t.first() + 60 * day
        val rate = DoubleArray(n) { if (time[it] < turn) 0.0001 else -0.0001 }
        val book = FundingBook().apply { update(mapOf("BTCUSDT" to FundingSeries(time, rate))) }
        val flags = HatedRally(book).entries(c)
        val fired = flags.indices.filter { flags[it] }
        assertEquals(1, fired.size, "once per stretch: $fired")
        // The 30-day mean turns negative about 15 days after the turn, and the 7-day mean below −5% a year first.
        val firedAt = c.t[fired.single()]
        assertTrue(firedAt > turn && firedAt < turn + 20 * day, "fired ${(firedAt - turn) / day} days after the turn")
        assertTrue(HatedRally(FundingBook()).entries(c).none { it }, "no funding, no calls")
    }

    @Test
    fun hatedRallyReadsOnlyFundingSettledByTheClose() {
        val c = Synth.candles(150, Timeframe.D1, seed = 5, symbol = "ETHUSDT")
        val step = 8 * 3_600_000L
        val from = c.t.first() - 60 * day
        val n = ((c.closeTime.last() - from) / step).toInt() + 1
        val time = LongArray(n) { from + it * step }
        val rate = DoubleArray(n) { if (it % 400 < 200) 0.0002 else -0.0003 }
        val book = FundingBook().apply { update(mapOf("ETHUSDT" to FundingSeries(time, rate))) }
        val s = HatedRally(book)
        val full = s.entries(c)
        for (k in listOf(40, 80, 120, 149)) {
            val cut = s.entries(c.slice(0, k))
            for (i in 0 until k) assertEquals(full[i], cut[i], "flag $i changed when history was cut at $k")
        }
    }

    @Test
    fun theTrendIndicatorsAreTheTwentyEightOfThePaper() {
        val c = Golden.panel(Timeframe.D1).getValue("BTCUSDT")
        val all = TrendIndicators.of(c)
        assertEquals(28, TrendIndicators.NAMES.size)
        assertEquals(28, all.size)
        assertTrue(all.all { series -> series.size == c.size && series[c.size - 1].isFinite() }, "every indicator has a value at the end")
    }

    @Test
    fun trendLeadersRanksOnMondaysAndBuysTheTopFifth() {
        val panel = Golden.panel(Timeframe.D1)
        val flags = TrendLeaders().entriesAcross(panel)
        val monday = { t: Long -> ((t / day) + 3) % 7 == 0L }
        var weeks = 0
        val byTime = HashMap<Long, Int>()
        for ((sym, f) in flags) {
            val c = panel.getValue(sym)
            for (i in f.indices) if (f[i]) {
                assertTrue(monday(c.t[i]), "fired on a day that is not Monday")
                byTime.merge(c.t[i], 1, Int::plus)
            }
        }
        weeks = byTime.size
        assertTrue(weeks > 100, "ranked only $weeks weeks")
        assertTrue(byTime.values.all { it in 2..3 }, "the top fifth of up to 12 coins is 2 or 3: ${byTime.values.toSet()}")
    }
}
