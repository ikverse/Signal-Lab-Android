package com.ikverse.signallab.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.CoinRow
import com.ikverse.signallab.ui.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The lists model on the app's real wiring and real database, with the network never used (the graph is built but not started).
 * The first test is the bug the user hit on the first run: the empty list showed, the screen that asked was replaced because of it,
 * and the coins that were still to be added were never added.
 */
@RunWith(RobolectricTestRunner::class)
class LiveListsModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val graph by lazy { AppGraph(ApplicationProvider.getApplicationContext<Context>(), scope) }
    private val model by lazy { LiveListsModel(graph, scope) }

    @After
    fun stop() = scope.cancel()

    private fun seedCoins() = runBlocking {
        graph.candles.replaceCoins(
            listOf(
                CoinRow("BTCUSDT", "BTC", "TRADING", true, false, false, 9e9, 70_000.0, 66_000.0, 1, change24 = 0.02, trades24 = 900),
                CoinRow("ETHUSDT", "ETH", "TRADING", true, false, false, 5e9, 3_500.0, 3_300.0, 1, change24 = -0.03, trades24 = 700),
            ),
        )
    }

    @Test
    fun `a list made on the first run is whole the first time it shows, even when the screen that asked is replaced at that moment`() = runBlocking {
        seedCoins()
        withTimeout(5_000) { model.loaded.first { it } }
        val caller = scope.launch { model.create("My coins", listOf("BTCUSDT", "ETHUSDT"), setOf("1h", "4h"), activate = true) }
        // The first screen is replaced by the main one the moment any list exists, and that cancels whatever asked for the list.
        val first = withTimeout(5_000) { model.lists.first { it.isNotEmpty() } }
        caller.cancel()
        caller.join()
        val list = first.single()
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), list.coins, "the list shown first already holds its coins")
        assertTrue(list.active, "and is already switched on")
        assertEquals(listOf("1h", "4h"), list.timeframes)
        delay(300)
        assertEquals(first, model.lists.value, "nothing changes afterwards")
    }

    @Test
    fun `a refused list leaves nothing behind and says why in words`() = runBlocking {
        seedCoins()
        withTimeout(5_000) { model.loaded.first { it } }
        val refused = assertIs<Outcome.Refused>(model.create("My coins", listOf("BTCUSDT", "NOTACOINUSDT"), setOf("1h"), activate = true))
        assertEquals("Binance does not offer that coin here.", refused.message)
        delay(200)
        assertTrue(model.lists.value.isEmpty())
        assertEquals(Outcome.Done, model.create("My coins", listOf("BTCUSDT"), setOf("1h"), activate = true))
        assertEquals("You already have a list with that name.", assertIs<Outcome.Refused>(model.create("my coins", listOf("ETHUSDT"), setOf("1h"), true)).message)
    }

    @Test
    fun `coins can be added to an existing list one by one, and a coin twice is refused in words`() = runBlocking {
        seedCoins()
        withTimeout(5_000) { model.loaded.first { it } }
        assertEquals(Outcome.Done, model.create("My coins", listOf("BTCUSDT"), setOf("1h"), activate = false))
        val id = withTimeout(5_000) { model.lists.first { it.isNotEmpty() } }.single().id
        assertEquals(Outcome.Done, model.addCoin(id, "ETHUSDT"))
        assertEquals("That coin is already in this list.", assertIs<Outcome.Refused>(model.addCoin(id, "ETHUSDT")).message)
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), withTimeout(5_000) { model.lists.first { it.single().coins.size == 2 } }.single().coins)
    }
}
