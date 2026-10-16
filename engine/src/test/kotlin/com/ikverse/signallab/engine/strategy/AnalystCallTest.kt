package com.ikverse.signallab.engine.strategy

import com.ikverse.signallab.engine.Golden
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.eval.Fills
import com.ikverse.signallab.engine.eval.Leave
import com.ikverse.signallab.engine.eval.Live
import com.ikverse.signallab.engine.head
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalystCallTest {
    private val panel = Golden.panel(Timeframe.D1)
    private val btc = panel.getValue("BTCUSDT")
    private val eth = panel.getValue("ETHUSDT")

    @Test
    fun itNeverFiresByItself() {
        assertTrue(AnalystCall.entries(eth).none { it })
        assertEquals(Tier.TESTING, AnalystCall.launchTier)
        assertEquals("analyst", AnalystCall.id)
    }

    @Test
    fun aCallEntersAtTheNextOpenWithItsStopAndTargetAsDistancesFromTheEntry() {
        val k = eth.size - 200
        val c = eth.head(k)
        val call = assertNotNull(AnalystCall.open(c, btc, eth.open[k], 0.06, 0.12, 20, 0.0025))
        assertEquals(eth.t[k], call.entryTime)
        assertEquals(eth.open[k], call.entryPrice)
        val stop = assertNotNull(call.stop)
        val target = assertNotNull(call.target)
        assertEquals(eth.open[k] * 0.94, stop, 1e-9)
        assertEquals(eth.open[k] * 1.12, target, 1e-9)
        assertEquals(20, call.maxBars)
        assertEquals(Live.sizeFor(call.entryPrice, call.atr), call.sizeFraction)

        // Judged on the later candles with the plan rebuilt from what was stored, it ends where the fill rules say.
        val s = AnalystCall.forCall(call.entryPrice, call.atr, call.stop, call.target, call.maxBars)
        assertEquals("analyst", s.id)
        assertEquals((call.entryPrice - stop) / call.atr, s.exit.safetyStopAtr!!, 1e-12)
        val closed = assertNotNull(Live.close(s, call, eth, btc))
        val fill = assertNotNull(Fills.resolve(eth, k, s.exit, call.atr, null))
        assertEquals(fill.exitPrice, closed.exitPrice)
        val expected = when (fill.reason) {
            Leave.STOP -> stop
            Leave.TARGET -> target
            else -> fill.exitPrice
        }
        if (fill.exitPrice != eth.open[fill.exitIdx]) assertEquals(expected, closed.exitPrice, 1e-9)
        assertTrue(!closed.randomMean.isNaN(), "its random entries were drawn")
    }

    @Test
    fun aCallOutsideItsLimitsIsRefused() {
        val c = eth.head(eth.size - 50)
        assertFailsWith<IllegalArgumentException> { AnalystCall.open(c, btc, 100.0, 0.5, null, 10, 0.0) }
        assertFailsWith<IllegalArgumentException> { AnalystCall.open(c, btc, 100.0, null, 2.0, 10, 0.0) }
        assertFailsWith<IllegalArgumentException> { AnalystCall.open(c, btc, 100.0, null, null, 61, 0.0) }
        assertFailsWith<IllegalArgumentException> { AnalystCall.open(panel.values.first().let { Golden.panel(Timeframe.H1).values.first() }, btc, 100.0, null, null, 5, 0.0) }
    }
}
