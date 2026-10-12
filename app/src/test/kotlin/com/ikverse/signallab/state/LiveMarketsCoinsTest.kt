package com.ikverse.signallab.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.CoinRow
import com.ikverse.signallab.data.WatchlistResult
import com.ikverse.signallab.data.candlesOf
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The coin rows a screen gets from the real stores, with the network never used (the graph is built but not started). */
@RunWith(RobolectricTestRunner::class)
class LiveMarketsCoinsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val graph by lazy { AppGraph(ApplicationProvider.getApplicationContext<Context>(), scope) }
    private val model by lazy { LiveMarketsModel(graph, scope) }

    @After
    fun stop() = scope.cancel()

    @Test
    fun `each coin gets its last price, its day change and a line, from candles up to the exchange's now`() = runBlocking {
        val now = graph.market.nowMs()
        val start = (now / Timeframe.H1.ms) * Timeframe.H1.ms - 30 * Timeframe.H1.ms
        graph.candles.replaceCoins(listOf(
            CoinRow("AUSDT", "A", "TRADING", true, false, false, 1e9, 2.0, 1.0, 1),
            CoinRow("BUSDT", "B", "TRADING", true, false, false, 1e9, 2.0, 1.0, 1),
        ))
        // A: 30 hourly candles ending now, so a day-old close exists. B: nothing stored.
        graph.candles.insertKlines("AUSDT", Timeframe.H1, candlesOf(Timeframe.H1, start, 30, base = 100.0, drift = 1.0, spread = 0.0))
        assertIs<WatchlistResult.Ok<*>>(graph.watchlists.createWith("L", listOf("AUSDT", "BUSDT"), setOf(Timeframe.H1), activate = true))
        val coins = withTimeout(5_000) { model.coins.first { it.size == 2 } }
        val a = assertNotNull(coins.firstOrNull { it.symbol == "AUSDT" })
        val last = candlesOf(Timeframe.H1, start, 30, base = 100.0, drift = 1.0, spread = 0.0).last()
        assertEquals(last.close, a.price)
        val dayAgo = candlesOf(Timeframe.H1, start, 30, base = 100.0, drift = 1.0, spread = 0.0)[29 - 24]
        assertEquals(last.close / dayAgo.close - 1, assertNotNull(a.changeFraction), 1e-12)
        assertEquals(25, a.spark.size, "a day of hourly closes plus one")
        val b = assertNotNull(coins.firstOrNull { it.symbol == "BUSDT" })
        assertNull(b.price)
        assertNull(b.changeFraction)
        assertEquals(0, b.spark.size)
    }
}
