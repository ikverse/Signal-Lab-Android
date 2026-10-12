package com.ikverse.signallab.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.EPOCH_START
import com.ikverse.signallab.data.candlesOf
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The chart a screen gets from the real stores, with the network never used (the graph is built but not started). */
@RunWith(RobolectricTestRunner::class)
class LiveMarketsChartTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val graph by lazy { AppGraph(ApplicationProvider.getApplicationContext<Context>(), scope) }
    private val model by lazy { LiveMarketsModel(graph, scope) }

    @After
    fun stop() = scope.cancel()

    @Test
    fun `a chart with nothing stored comes back empty, not missing`() = runBlocking {
        val c = assertNotNull(model.chart("NOPEUSDT", "1h"))
        assertTrue(c.candles.isEmpty())
        assertTrue(c.levels.isEmpty())
        assertEquals("NOPEUSDT", c.symbol)
        assertEquals("1h", c.timeframe)
    }

    @Test
    fun `a chart with candles carries them oldest first`() = runBlocking {
        graph.candles.insertKlines("BTCUSDT", Timeframe.H1, candlesOf(Timeframe.H1, EPOCH_START, 5))
        val c = assertNotNull(model.chart("BTCUSDT", "1h"))
        assertEquals(5, c.candles.size)
        assertEquals(EPOCH_START, c.candles.first().time)
    }
}
