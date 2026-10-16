package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Golden
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.head
import com.ikverse.signallab.engine.strategy.Builtins
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real candles replayed day by day through the live rules, as the phone would meet them: after each daily close,
 * open calls are checked first and then new signals, with the open of the day now starting as the entry and exit price.
 * Every call must equal the backtest's trade: same entry, same exit candle, price and reason, same result.
 */
class LiveEqualsBacktestTest {
    private class Open(val call: NewCall)

    private class Done(val call: NewCall, val closed: ClosedCall)

    @Test
    fun everyLiveCallEqualsItsBacktestTrade() {
        val panel = Golden.panel(Timeframe.D1)
        val btcFull = panel.getValue("BTCUSDT")
        // A shorter replay keeps the test quick: three coins, every day of their history.
        val coins = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT").filter { it in panel }
        var compared = 0
        // Strategies that read one daily coin at a time; the ranking and the hourly strategy are replayed in the app runner test.
        for (s in Builtins.all.filter { !it.crossSectional && it.timeframe == Timeframe.D1 }) {
            for (symbol in coins) {
                val c = panel.getValue(symbol)
                val open = HashMap<String, Open>()
                val done = ArrayList<Done>()
                for (d in 1 until c.size) {
                    val closed = c.head(d + 1)
                    val btc = btcFull.head(btcFull.closeTime.indexOfLast { it <= closed.closeTime[d] } + 1)
                    // After the last stored day there is no forming candle to enter or leave at.
                    val forming = if (d + 1 < c.size) c.open[d + 1] else Double.NaN
                    open[symbol]?.let { o ->
                        Live.close(s, o.call, closed, btc, forming)?.let { done.add(Done(o.call, it)); open.remove(symbol) }
                    }
                    // Busy while a call is open, or when the last one leaves at the very open this one would enter at.
                    val entryTime = closed.t[d] + Timeframe.D1.ms
                    val busy = open.containsKey(symbol) || done.lastOrNull()?.closed?.exitTime?.let { it >= entryTime } == true
                    Live.signal(s, closed, btc, forming, busy, EngineConfig.costFor(symbol))?.let { open[symbol] = Open(it) }
                }
                val backtest = Backtest.run(s, mapOf(symbol to c), btcFull).associateBy { it.signalTime }
                for (dn in done) {
                    val b = backtest[dn.call.signalTime]
                    // The backtest only counts trades whose whole window lies inside the data; a live call near the end may not.
                    if (b == null) {
                        assertTrue(dn.call.signalTime + (s.exit.maxBars + 1) * Timeframe.D1.ms >= c.t.last(), "${s.id} $symbol: live call at ${dn.call.signalTime} missing from the backtest")
                        continue
                    }
                    val what = "${s.id} $symbol ${dn.call.signalTime}"
                    assertEquals(b.entryTime, dn.call.entryTime, "$what entry time")
                    assertEquals(b.entryPrice, dn.call.entryPrice, "$what entry price")
                    assertEquals(b.exitTime, dn.closed.exitTime, "$what exit time")
                    assertEquals(b.exitPrice, dn.closed.exitPrice, "$what exit price")
                    assertEquals(b.reason, dn.closed.reason, "$what reason")
                    assertEquals(b.barsHeld, dn.closed.barsHeld, "$what bars held")
                    assertEquals(b.net, dn.closed.net, "$what net")
                    compared++
                }
                // And the other way round: every backtest trade the replay had time to finish was made live.
                val liveSignals = done.map { it.call.signalTime }.toSet()
                for (b in backtest.values) assertTrue(b.signalTime in liveSignals, "${s.id} $symbol: backtest trade at ${b.signalTime} never made live")
            }
        }
        assertTrue(compared > 100, "only $compared calls compared")
    }

    @Test
    fun sizeHoldsOneAverageTrueRangeAsOnePercentWithACap() {
        assertEquals(0.10, Live.sizeFor(100.0, 10.0), 1e-12)
        assertEquals(Live.MAX_SIZE, Live.sizeFor(100.0, 1.0))
        assertEquals(0.0, Live.sizeFor(100.0, Double.NaN))
    }
}
