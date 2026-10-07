package com.ikverse.signallab.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.analyst.Handoff
import com.ikverse.signallab.analyst.LabFormat
import com.ikverse.signallab.analyst.PromptCards
import com.ikverse.signallab.data.CoinRow
import com.ikverse.signallab.data.NewTrade
import com.ikverse.signallab.data.TradeExit
import com.ikverse.signallab.data.WatchlistResult
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.ui.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Analyst on the app's real wiring and real database, with the network never used (the graph is built but not started). */
@RunWith(RobolectricTestRunner::class)
class LiveAnalystModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val graph by lazy { AppGraph(context, scope) }
    private val handoffs = MutableSharedFlow<Handoff>(replay = 1)
    private val model by lazy { LiveAnalystModel(graph, context, scope, handoffs, clock = { T0 + 30 * DAY }) }

    @After
    fun stop() = scope.cancel()

    private fun seedTrade(): Long = runBlocking {
        val bar = T0 + 5 * HOUR
        val id = graph.tradeLog.open(NewTrade("donchian20_1h", "donchian", "SOLUSDT", Timeframe.H1, 0, bar, bar + HOUR, 1, bar + HOUR, 150.0, 155.0, 147.0, 24, bar + 25 * HOUR))!!
        graph.tradeLog.close(id, TradeExit(bar + 4 * HOUR, 155.0, ExitReason.TARGET, 3, 0.033, 0.031, 0.002, 0.029))
        id
    }

    @Test
    fun `a question hands over one draft, the question above the data built from the record`() = runBlocking<Unit> {
        seedTrade()
        assertEquals(Outcome.Done, model.ask("weekly-review"))
        val text = handoffs.replayCache.single().text
        val question = text.substringBefore("# Signal Lab data file")
        assertTrue(question.contains("${PromptCards.MARKER} Weekly review"))
        val data = text.substringAfter(question)
        assertTrue(data.startsWith("# Signal Lab data file"))
        assertTrue(data.contains("## Latest closed trades (1 of 1)"))
        assertTrue(data.contains("| SOL | donchian20_1h | 1h | target |"), data.substringAfter("## Latest closed trades"))
    }

    @Test
    fun `explaining a trade adds that trade, and needs one that exists`() = runBlocking<Unit> {
        val id = seedTrade()
        assertIs<Outcome.Refused>(model.ask("explain-trade"))
        assertIs<Outcome.Refused>(model.ask("explain-trade", 999))
        assertIs<Outcome.Refused>(model.ask("no-such-question"))
        assertTrue(handoffs.replayCache.isEmpty(), "nothing is handed over for a refused question")
        assertEquals(Outcome.Done, model.ask("explain-trade", id))
        val text = handoffs.replayCache.single().text
        assertTrue(text.contains("| Trade number | $id |"))
        assertTrue(text.contains("no longer kept"), "no candles are stored in this test")
    }

    @Test
    fun `an answer shared back is kept as a report, filed under its question, and shown safely`() = runBlocking<Unit> {
        val id = assertNotNull(model.keep("**Signal Lab report: What's fading?**\n\n<b>Nothing</b> is fading."))
        val reports = withTimeout(5_000) { model.reports.first { it.isNotEmpty() } }
        val report = reports.single()
        assertEquals(id, report.id)
        assertEquals("fading", report.card)
        assertEquals("What's fading?", report.title)
        assertEquals("&lt;b>Nothing&lt;/b> is fading.", report.markdown)
        assertNull(model.notice.value)
    }

    @Test
    fun `text that cannot be kept says why instead`() = runBlocking<Unit> {
        assertNull(model.keep("   "))
        assertTrue(model.notice.value!!.contains("empty"))
        model.noticeSeen()
        assertNull(model.notice.value)
        assertNull(model.keep("x".repeat(400_000)))
        assertTrue(model.notice.value!!.contains("too long"))
    }

    private val definition = """{"charts":["1h","4h"],"when":[{"left":"close","is":"above","right":"sma(50)"}],"exit":"trail"}"""

    private fun definition(n: Int) = """{"charts":["1h"],"when":[{"left":"close","is":"above","right":"sma($n)"}],"exit":"trail"}"""

    /** An active list named [name], with a coin in it, watched on [charts]: lab patterns on those charts can trade. */
    private fun watch(name: String, vararg charts: String) = runBlocking {
        graph.candles.replaceCoins(listOf(CoinRow("BTCUSDT", "BTC", "TRADING", true, false, false, 9e9, 70_000.0, 66_000.0, 1, change24 = 0.02, trades24 = 900)))
        graph.watchlists.load()
        assertIs<WatchlistResult.Ok<*>>(graph.watchlists.createWith(name, listOf("BTCUSDT"), charts.map(Timeframe::of).toSet(), activate = true))
    }

    @Test
    fun `a suggestion in a kept answer can be started, counts as tested, and shows as running`() = runBlocking<Unit> {
        watch("Both", "1h", "4h")
        val answer = """
            Signal Lab report: Suggest 3 patterns

            ```signal-lab-patterns
            [{"title": "Above the 50", "charts": ["4h", "1h"], "when": [{"left": "close", "is": "above", "right": "sma(50)"}], "exit": "trail"}]
            ```
        """.trimIndent()
        val report = assertNotNull(model.keep(answer))
        val before = graph.tradeLog.variantCount()
        assertEquals(Outcome.Done, model.startLab(definition, "Above the 50", null, report))
        val lab = graph.labStore.all().single()
        assertEquals(definition, lab.definition)
        assertEquals(report, lab.reportId)
        assertEquals(before + 2, graph.tradeLog.variantCount(), "one variant per chart, counted at once")
        assertTrue(graph.tradeLog.registeredVariants().map { it.name }.containsAll(listOf("lab${lab.id}_1h", "lab${lab.id}_4h")))
        val shown = withTimeout(5_000) { model.reports.first { r -> r.any { it.suggestions.any { s -> s.runningAs != null } } } }.single()
        assertEquals("suggest-patterns", shown.card)
        assertEquals(lab.id, shown.suggestions.single().runningAs)
        assertEquals("close above SMA(50) on 1h and 4h; a trailing stop", shown.suggestions.single().summary)
        assertTrue(shown.markdown.contains("signal-lab-patterns"), "the answer itself still shows the block")

        val again = model.startLab(definition, "Same rule, other name", null, null)
        assertTrue((again as Outcome.Refused).message.contains("already being forward-tested as lab ${lab.id}"))
        assertEquals(Outcome.Done, model.stopLab(lab.id))
        assertIs<Outcome.Refused>(model.stopLab(lab.id))
        val listed = withTimeout(5_000) { model.lab.first { it.isNotEmpty() && it.single().stoppedAt != null } }
        assertEquals("Above the 50", listed.single().title)
    }

    @Test
    fun `the lab runs a few patterns at a time and starts only a few in a month`() = runBlocking<Unit> {
        watch("Hourly", "1h")
        for (n in 1..EngineConfig.LAB_MAX_RUNNING) assertEquals(Outcome.Done, model.startLab(definition(10 + n), "P$n", null, null))
        val full = model.startLab(definition(30), "One too many", null, null)
        assertTrue((full as Outcome.Refused).message.contains("already running"))
        model.stopLab(graph.labStore.all().first().id)
        // A slot is free, but five were started within the window.
        val tooSoon = model.startLab(definition(31), "Too soon", null, null)
        assertTrue((tooSoon as Outcome.Refused).message.contains("in the last ${EngineConfig.LAB_NEW_WINDOW_DAYS} days"), tooSoon.message)
        val budget = withTimeout(5_000) { model.budget.first { it.running == EngineConfig.LAB_MAX_RUNNING - 1 } }
        assertEquals(0, budget.newLeft)
        assertIs<Outcome.Refused>(model.startLab("not a pattern", "Broken", null, null))
    }

    @Test
    fun `a backtest with no stored candles says so for each chart instead of inventing numbers`() = runBlocking<Unit> {
        val r = model.backtest(definition)
        assertEquals(listOf("1h", "4h"), r.rows.map { it.chart })
        assertTrue(r.rows.all { it.coins == 0 && it.trades == 0 && it.hitRate == null })
        assertTrue(r.note.startsWith("For reference only"))
        assertTrue(model.backtest("nonsense").rows.isEmpty())
    }

    @Test
    fun `the data handed to Claude lists the lab patterns`() = runBlocking<Unit> {
        watch("Both", "1h", "4h")
        model.startLab(definition, "Above the 50", null, null)
        assertEquals(Outcome.Done, model.ask("suggest-patterns"))
        val text = handoffs.replayCache.single().text
        assertTrue(text.contains("## Lab patterns"))
        assertTrue(text.contains("| Above the 50 | close above SMA(50) on 1h and 4h; a trailing stop |"))
        assertTrue(text.contains(LabFormat.guide.lines().first()), "the question carries the form")
        assertFalse(text.contains("None has been forward-tested yet."))
    }

    @Test
    fun `a pattern no active list watches is refused, and one partly watched starts with the gap shown`() = runBlocking<Unit> {
        watch("Daily", "1d")
        val refused = model.startLab(definition, "Above the 50", null, null)
        assertEquals("No active list watches 1h or 4h, so it would never trade. Add 1h or 4h to a list first.", (refused as Outcome.Refused).message)
        assertTrue(graph.labStore.all().isEmpty(), "nothing was started")

        watch("Hourly", "1h")
        assertEquals(Outcome.Done, model.startLab(definition, "Above the 50", null, null))
        val running = withTimeout(5_000) { model.lab.first { it.isNotEmpty() && it.single().unwatched.isNotEmpty() } }.single()
        assertEquals(listOf("4h"), running.unwatched)
        assertFalse(running.neverTrades)

        val answer = """
            ```signal-lab-patterns
            [{"title": "Four-hour only", "charts": ["4h"], "when": [{"left": "close", "is": "above", "right": "sma(20)"}], "exit": "trail"}]
            ```
        """.trimIndent()
        val id = assertNotNull(model.keep(answer))
        val suggestion = withTimeout(5_000) { model.reports.first { r -> r.any { it.id == id } } }.single { it.id == id }.suggestions.single()
        assertEquals(listOf("4h"), suggestion.unwatched)
        assertTrue(suggestion.neverTrades)
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val T0 = 1_700_006_400_000L
    }
}
