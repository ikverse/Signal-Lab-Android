package com.ikverse.signallab.analyst

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.catalog.StrategyCatalog
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Golden
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.PreferenceStore
import com.ikverse.signallab.ledger.SettingRow
import com.ikverse.signallab.ledger.SuggestionState
import com.ikverse.signallab.runner.StrategyRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The analyst's tools on a real ledger and real candles: what they read, and the little they may write. */
@RunWith(RobolectricTestRunner::class)
class AnalystToolsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val coins = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT")
    private lateinit var ledger: LedgerDatabase
    private lateinit var candles: CandleStore
    private lateinit var prefs: PreferenceStore
    private lateinit var scope: kotlinx.coroutines.CoroutineScope
    private lateinit var catalog: StrategyCatalog
    private lateinit var tools: AnalystTools
    private var now = 0L

    private fun klines(c: Candles) = List(c.size) { i -> Kline(c.t[i], c.open[i], c.high[i], c.low[i], c.close[i], c.volume[i], c.closeTime[i], c.volume[i] * c.close[i]) }

    @Before
    fun setUp() = runTest {
        ledger = LedgerDatabase.open(context, name = null)
        candles = CandleStore(context, name = null, io = Dispatchers.Unconfined)
        prefs = PreferenceStore(ledger.dao())
        val panel = Golden.panel(Timeframe.D1).filterKeys { it in coins }
        for ((sym, c) in panel) candles.insertKlines(sym, Timeframe.D1, klines(c))
        now = panel.getValue("BTCUSDT").closeTime.last() + 1
        ledger.dao().putSetting(SettingRow(StrategyRunner.UNIVERSE, coins.joinToString(","), now))
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        catalog = StrategyCatalog(ledger.dao(), Builtins.all, scope)
        tools = AnalystTools(ledger.dao(), candles, null, catalog, prefs, now = { now })
    }

    @After
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        candles.close()
        ledger.close()
    }

    private suspend fun call(name: String, input: JsonObject = JsonObject(emptyMap())): ToolOutput {
        val spec = tools.all.first { it.name == name }
        spec.problem(input)?.let { return ToolOutput(it, isError = true) }
        return spec.run(input)
    }

    private fun json(o: ToolOutput) = Json.parseToJsonElement(o.text).jsonObject

    @Test
    fun theToolsHaveDistinctNamesAndCheckTheirArguments() {
        assertEquals(tools.all.size, tools.all.map { it.name }.toSet().size)
        val candleTool = tools.all.first { it.name == "candles" }
        assertEquals("Missing argument \"symbol\".", candleTool.problem(JsonObject(emptyMap())))
        assertEquals("Argument \"count\" must be a integer.", candleTool.problem(buildJsonObject { put("symbol", "X"); put("timeframe", "1d"); put("count", "ten") }))
        assertEquals("Unknown argument: extra.", candleTool.problem(buildJsonObject { put("symbol", "X"); put("timeframe", "1d"); put("count", 5); put("extra", 1) }))
        // As the API describes them: every required argument is listed.
        for (spec in tools.all) {
            val t = Analyst.sdkTool(spec)
            assertEquals(spec.name, t.name())
            assertEquals(spec.params.filter { it.value.required }.keys.toList(), t.inputSchema().required().orElse(emptyList()))
        }
    }

    @Test
    fun theMarketStateNamesTheWatchedCoinsAndBitcoinsRegime() = runTest {
        val m = json(call("market_state"))
        assertEquals(coins, m.getValue("watched_coins").jsonArray.map { it.jsonObject.getValue("symbol").jsonPrimitive.content })
        assertTrue(m.getValue("summary").jsonPrimitive.content.startsWith("Bitcoin is"))
        assertEquals(0, m.getValue("open_calls").jsonPrimitive.int)
    }

    @Test
    fun candlesComeBackNewestLastAndUnknownCoinsAreRefused() = runTest {
        val out = json(call("candles", buildJsonObject { put("symbol", "ethusdt"); put("timeframe", "1d"); put("count", 5) }))
        val rows = out.getValue("rows").jsonArray
        assertEquals(5, rows.size)
        val eth = Golden.panel(Timeframe.D1).getValue("ETHUSDT")
        assertEquals(eth.t.last(), rows.last().jsonArray[0].jsonPrimitive.content.toLong())
        assertTrue(call("candles", buildJsonObject { put("symbol", "NOPEUSDT"); put("timeframe", "1d"); put("count", 5) }).isError)
        assertTrue(call("candles", buildJsonObject { put("symbol", "ETHUSDT"); put("timeframe", "5m"); put("count", 5) }).isError)
    }

    @Test
    fun aBacktestIsRegisteredOnceAndCountsTowardTheStrategiesTried() = runTest {
        val rule = "(strategy (entry (> close (highest high 50))) (exit (< close (lowest low 20))) (max_bars 60) (stop_atr 3))"
        val before = ledger.dao().strategiesTried()
        val out = json(call("backtest", buildJsonObject { put("definition", rule) }))
        assertTrue(out.getValue("registered_as").jsonPrimitive.content.startsWith("custom_"))
        assertTrue(out.getValue("calls").jsonPrimitive.int > 10)
        assertEquals(before + 1, ledger.dao().strategiesTried())
        // The same rule written differently is the same rule: no second registration.
        call("backtest", buildJsonObject { put("definition", rule.replace(" (", "  (")) })
        assertEquals(before + 1, ledger.dao().strategiesTried())
        val bad = call("backtest", buildJsonObject { put("definition", "(strategy (entry (future close)) (max_bars 5))") })
        assertTrue(bad.isError)
        assertTrue(bad.text.contains("Unknown operation"), bad.text)
    }

    @Test
    fun aProposalWaitsForTheOwnerAndIsNotSavedTwice() = runTest {
        val input = buildJsonObject {
            put("name", "Quiet breakout")
            put("definition", "(strategy (entry (and (> close (highest high 20)) (< (atr 14) (sma (atr 14) 50)))) (max_bars 20) (stop_atr 2))")
            put("thesis", "Breakouts from calm ranges tend to carry further. Wrong if it trails random entries after 100 calls.")
            putJsonArray("sources") { add("https://example.org/paper") }
        }
        val out = json(call("propose_strategy", input))
        val id = out.getValue("saved_as").jsonPrimitive.content
        val rows = ledger.dao().customRules()
        assertEquals(1, rows.size, "the proposal is in the ledger")
        assertEquals(id, assertNotNull(StrategyCatalog.read(rows.single()), "its rule reads back: ${rows.single().definitionV2}").id)
        val saved = kotlinx.coroutines.withContext(Dispatchers.Default) { withTimeout(5_000) { catalog.saved.first { it.isNotEmpty() } } }.single()
        assertEquals(id, saved.id)
        assertEquals("Quiet breakout", saved.row.title)
        assertTrue(saved.byAnalyst)
        assertFalse(id in prefs.current().customLive, "a proposal does not run until the owner starts it")
        assertEquals(1, ledger.dao().countReports())
        assertTrue(call("propose_strategy", input).isError)
        assertEquals(id, catalog.all.value.last().id)
    }

    @Test
    fun aSuggestedCallWaitsOnTodayAndAtMostThreeWait() = runTest {
        fun s(sym: String, stop: Double = 0.06) = buildJsonObject {
            put("symbol", sym); put("stop_pct", stop); put("target_pct", 0.12); put("days", 20); put("thesis", "Holding its breakout on rising volume.")
        }
        assertTrue(call("suggest_call", s("ETHUSDT", stop = 0.5)).isError)
        assertTrue(call("suggest_call", s("NOPEUSDT")).isError)
        repeat(3) { assertFalse(call("suggest_call", s(coins[it])).isError) }
        assertTrue(call("suggest_call", s("ETHUSDT")).isError)
        val waiting = prefs.current().suggestions
        assertEquals(3, waiting.size)
        assertTrue(waiting.all { it.state == SuggestionState.SUGGESTED && it.days == 20 && it.targetPct == 0.12 })
    }

    @Test
    fun aContextLineShowsUntilItsDayAndTheOldestMakesRoom() = runTest {
        val day = java.time.Instant.ofEpochMilli(now).atOffset(java.time.ZoneOffset.UTC).toLocalDate()
        assertTrue(call("flag_context", buildJsonObject { put("text", "Too far"); put("until", day.plusDays(30).toString()) }).isError)
        for (i in 1..6) {
            now += 1
            assertFalse(call("flag_context", buildJsonObject { put("text", "Event $i"); put("until", day.plusDays(2).toString()) }).isError)
        }
        val lines = prefs.current().contextLines
        assertEquals(5, lines.size)
        assertEquals((2..6).map { "Event $it" }.toSet(), lines.map { it.text }.toSet())
    }

    @Test
    fun theLedgerAndEvidenceReadWithoutCalls() = runTest {
        val l = json(call("ledger"))
        assertTrue(l.getValue("strategies").jsonArray.isEmpty())
        val e = json(call("strategy_evidence", buildJsonObject { put("id", "trend50_1d") }))
        assertEquals("Trend timer", e.getValue("name").jsonPrimitive.content)
        assertNotNull(e["backtest"])
        assertTrue(call("strategy_evidence", buildJsonObject { put("id", "nope") }).isError)
        val list = Json.parseToJsonElement(call("list_strategies").text).jsonArray
        assertEquals(Builtins.all.size + 1, list.size, "the built-ins and the analyst's own")
    }
}
