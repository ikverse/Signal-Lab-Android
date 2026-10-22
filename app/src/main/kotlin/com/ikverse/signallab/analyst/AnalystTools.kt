package com.ikverse.signallab.analyst

import com.ikverse.signallab.catalog.StrategyCatalog
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.futures.DerivativesStore
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.eval.Backtest
import com.ikverse.signallab.engine.eval.Evidence
import com.ikverse.signallab.engine.eval.Moments
import com.ikverse.signallab.engine.strategy.AnalystCall
import com.ikverse.signallab.engine.strategy.dsl.CustomStrategy
import com.ikverse.signallab.engine.strategy.dsl.Rule
import com.ikverse.signallab.engine.strategy.dsl.RuleError
import com.ikverse.signallab.ledger.AnalystNotes
import com.ikverse.signallab.ledger.ContextLine
import com.ikverse.signallab.ledger.CustomStrategyRow
import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.PreferenceStore
import com.ikverse.signallab.ledger.ReportRow
import com.ikverse.signallab.ledger.SettingRow
import com.ikverse.signallab.ledger.StrategyRow
import com.ikverse.signallab.ledger.Suggestion
import com.ikverse.signallab.ledger.SuggestionState
import com.ikverse.signallab.model.LedgerModel
import com.ikverse.signallab.model.MarketLine
import com.ikverse.signallab.model.StrategiesModel
import com.ikverse.signallab.model.StrategyTexts
import com.ikverse.signallab.runner.StrategyRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

/** One argument of a tool, as JSON Schema describes it. */
class Param(val type: String, val description: String, val required: Boolean = true, val items: String? = null)

/** What a tool sends back to the model. */
class ToolOutput(val text: String, val isError: Boolean = false)

/** One tool the analyst may call, run on the phone. */
class ToolSpec(val name: String, val description: String, val params: Map<String, Param>, val run: suspend (JsonObject) -> ToolOutput) {
    /** Why [input] does not fit this tool's arguments, or null when it does. */
    fun problem(input: JsonObject): String? {
        for ((name, p) in params) {
            val v = input[name]
            if (v == null || v is JsonNull) {
                if (p.required) return "Missing argument \"$name\"."
                continue
            }
            val ok = when (p.type) {
                "string" -> v is JsonPrimitive && v.isString
                "integer" -> v is JsonPrimitive && !v.isString && v.longOrNull != null
                "number" -> v is JsonPrimitive && !v.isString && v.doubleOrNull != null
                "array" -> v is JsonArray && v.all { it is JsonPrimitive && it.isString }
                else -> true
            }
            if (!ok) return "Argument \"$name\" must be a ${p.type}${if (p.type == "array") " of strings" else ""}."
        }
        val unknown = input.keys - params.keys
        if (unknown.isNotEmpty()) return "Unknown argument${if (unknown.size > 1) "s" else ""}: ${unknown.joinToString()}."
        return null
    }
}

/**
 * The analyst's view of Signal Lab: everything it may read, and the only things it may write. It cannot trade, cannot
 * change a strategy or a setting, and cannot record a call; a call it suggests waits for the owner on Today, and a strategy
 * it proposes waits for the owner in Strategies.
 */
class AnalystTools(
    private val dao: LedgerDao,
    private val candles: CandleStore,
    private val derivatives: DerivativesStore?,
    private val catalog: StrategyCatalog,
    private val prefs: PreferenceStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val all: List<ToolSpec> by lazy {
        listOf(marketState, ledger, listStrategies, strategyEvidence, candleTool, derivativesTool, backtest, propose, suggestCall, flagContext)
    }

    private fun out(o: JsonElement) = ToolOutput(o.toString().let { if (it.length > MAX_OUTPUT) it.take(MAX_OUTPUT) + "…(cut)" else it })

    private fun fail(why: String) = ToolOutput(why, isError = true)

    private suspend fun universe(): List<String> =
        dao.setting(StrategyRunner.UNIVERSE)?.split(',')?.filter { it.isNotBlank() }.orEmpty().ifEmpty { listOf(DataConfig.REGIME_COIN) }

    private fun day(t: Long): String = Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    private val marketState = ToolSpec(
        "market_state",
        "Bitcoin's regime (price against its 200-day average, how calm or stormy the last 30 days were, futures funding), " +
            "which strategies may fire, the coins Signal Lab watches with their 1-, 7- and 30-day returns, how many calls are open, " +
            "and the context lines on Today. Call it first in most conversations.",
        emptyMap(),
    ) { _ ->
        val p = prefs.current()
        val btc = candles.window(DataConfig.REGIME_COIN, Timeframe.D1)
        val funding = derivatives?.funding(DataConfig.REGIME_COIN, now() - 2 * DAY)?.lastOrNull()?.rate
        val futures = com.ikverse.signallab.model.FuturesContext.read(derivatives, now())
        val line = MarketLine.of(btc, funding, catalog.builtins.filter { !it.retired }, futures)
        val coins = universe()
        val windows = coins.mapNotNull { sym -> candles.latest(sym, Timeframe.D1, 31)?.let { sym to it } }
        val openCalls = dao.openCalls().count { it.source == "v2" }
        out(buildJsonObject {
            put("as_of", btc?.let { day(it.t.last()) } ?: "no data")
            put("summary", line.sentence + " " + line.allowedSentence)
            putNum("btc_price", line.btcPrice)
            putNum("btc_vs_200d_average", line.vsAverage)
            put("swings_30d", line.swings ?: "unknown")
            putNum("btc_funding_8h", funding)
            putNum("btc_funding_30d_per_year", futures?.funding30d)
            putNum("btc_funding_7d_per_year", futures?.funding7d)
            putNum("btc_quarterly_premium_per_year", futures?.quarterlyPremium)
            putNum("btc_implied_vol", futures?.impliedVol)
            putNum("btc_implied_vol_change_7d", futures?.impliedVolChange7d)
            futures?.sentence?.let { put("futures_summary", it) }
            put("open_calls", openCalls)
            putJsonArray("context_on_today") { p.contextLines.filter { it.until >= now() - DAY }.forEach { add("${day(it.until)}: ${it.text}") } }
            putJsonArray("watched_coins") {
                for ((sym, c) in windows) {
                    val rank = coins.indexOf(sym)
                    val n = c.size
                    fun ret(k: Int) = if (n > k) c.close[n - 1] / c.close[n - 1 - k] - 1 else null
                    add(buildJsonObject {
                        put("symbol", sym)
                        put("rank_by_turnover", rank + 1)
                        putNum("close", c.close[n - 1])
                        putNum("ret_1d", ret(1))
                        putNum("ret_7d", ret(7))
                        putNum("ret_30d", ret(30))
                    })
                }
            }
        })
    }

    private val ledger = ToolSpec(
        "ledger",
        "The paper ledger: for each strategy its finished calls, how many ended up, the average result after fees minus what " +
            "random entries on the same coin did (excess), and where it stands; then the recent finished calls and every open " +
            "call. Signal Lab 1's imported history is not included. Excess is the number that matters: a strategy is only worth " +
            "anything if it beats random entries.",
        mapOf(
            "strategy" to Param("string", "Only this strategy id, such as breakout50_1d or analyst.", required = false),
            "days" to Param("integer", "Only calls finished in the last this many days.", required = false),
        ),
    ) { input ->
        val only = input["strategy"]?.jsonPrimitive?.contentOrNull
        val days = input["days"]?.jsonPrimitive?.longOrNull
        val from = days?.let { now() - it * DAY } ?: Long.MIN_VALUE
        val calls = dao.allCalls().filter { it.source == "v2" && (only == null || it.strategyId == only) }
        val exits = dao.allExits().associateBy { it.callId }
        val names = catalog.all.value.associate { it.id to it.name }
        val openRows = calls.filter { exits[it.id] == null }.sortedBy { it.entryTime }.take(30)
        val lastClose = openRows.associate { c -> c.id to candles.latest(c.symbol, Timeframe.of(c.tf), 1)?.close?.lastOrNull() }
        out(buildJsonObject {
            putJsonArray("strategies") {
                for ((id, mine) in calls.groupBy { it.strategyId }) {
                    val fin = mine.mapNotNull { c -> exits[c.id]?.takeIf { it.exitTime >= from } }
                    val excess = fin.mapNotNull { it.excess }
                    add(buildJsonObject {
                        put("id", id)
                        put("name", names[id] ?: id)
                        put("finished", fin.size)
                        put("ended_up", fin.count { it.net > 0 })
                        put("open", mine.count { exits[it.id] == null })
                        putNum("mean_net", fin.takeIf { it.isNotEmpty() }?.map { it.net }?.average())
                        putNum("mean_excess", excess.takeIf { it.isNotEmpty() }?.average())
                        put("standing", LedgerModel.standing(excess))
                    })
                }
            }
            putJsonArray("recent_finished") {
                calls.mapNotNull { c -> exits[c.id]?.takeIf { it.exitTime >= from }?.let { c to it } }.sortedByDescending { it.second.exitTime }
                    .take(30).forEach { (c, e) ->
                        add(buildJsonObject {
                            put("strategy", c.strategyId)
                            put("symbol", c.symbol)
                            put("entered", day(c.entryTime))
                            put("left", day(e.exitTime))
                            put("why_left", e.reason)
                            putNum("net", e.net)
                            putNum("excess", e.excess)
                        })
                    }
            }
            putJsonArray("open") {
                openRows.forEach { c ->
                    val last = lastClose[c.id]
                    add(buildJsonObject {
                        put("strategy", c.strategyId)
                        put("symbol", c.symbol)
                        put("entered", day(c.entryTime))
                        putNum("entry", c.entryPrice)
                        putNum("stop", c.stop)
                        putNum("target", c.target)
                        put("max_days", c.maxBars)
                        putNum("change_since_entry", last?.let { it / c.entryPrice - 1 })
                    })
                }
            }
        })
    }

    private val listStrategies = ToolSpec(
        "list_strategies",
        "Every strategy: id, name, timeframe, tier (Proven, Promising, Watch, Testing, Failing), whether its calls show on Today, " +
            "a one-line summary, and for custom strategies the rule and whether it is running.",
        emptyMap(),
    ) { _ ->
        val p = prefs.current()
        val saved = catalog.saved.value.associateBy { it.id }
        out(buildJsonArray {
            for (s in catalog.all.value) {
                add(buildJsonObject {
                    put("id", s.id)
                    put("name", s.name)
                    put("timeframe", s.timeframe.label)
                    put("tier", p.tierOf(s).label)
                    put("on_today", p.isOn(s))
                    put("summary", if (s.id == AnalystCall.id) "Your own suggested calls, once the owner records them" else StrategyTexts.summary(s))
                    saved[s.id]?.let {
                        put("rule", it.strategy.definition.print())
                        put("running", s.id in p.customLive)
                    }
                })
            }
        })
    }

    private suspend fun panelFor(tf: Timeframe): Map<String, Candles> {
        val since = if (tf == Timeframe.D1) 0L else now() - StrategyRunner.HOURLY_DAYS * DAY
        val panel = LinkedHashMap<String, Candles>()
        for (sym in universe()) candles.window(sym, tf, since)?.let { panel[sym] = it }
        return panel
    }

    private val strategyEvidence = ToolSpec(
        "strategy_evidence",
        "One strategy's evidence card: why it should work, what the research says, its exact rule, its tier and why, its backtest " +
            "on the watched coins (at the owner's fees and at double), and its live record.",
        mapOf("id" to Param("string", "The strategy id, from list_strategies.")),
    ) { input ->
        val id = input.getValue("id").jsonPrimitive.content
        val s = catalog.all.value.firstOrNull { it.id == id } ?: return@ToolSpec fail("No strategy $id. Ids come from list_strategies.")
        val p = prefs.current()
        val btc = candles.window(DataConfig.REGIME_COIN, Timeframe.D1) ?: return@ToolSpec fail("No Bitcoin history yet.")
        val facts = withContext(Dispatchers.Default) { StrategiesModel.backtest(s, panelFor(s.timeframe), btc, p) }
        val live = dao.allCalls().filter { it.strategyId == s.id }.mapNotNull { dao.exitOf(it.id)?.excess }
        out(buildJsonObject {
            put("id", s.id)
            put("name", s.name)
            put("tier", p.tierOf(s).label)
            put("tier_reason", p.tierReasons[s.id] ?: "not judged yet")
            put("research", StrategyTexts.research(s))
            put("why", StrategyTexts.rationale(s))
            putJsonArray("rule") { StrategyTexts.rule(s).forEach { add(it) } }
            if (facts == null) put("backtest", "no past calls on the watched coins") else put("backtest", buildJsonObject {
                put("coins", facts.coins)
                put("calls", facts.calls)
                putNum("hit_rate", facts.hit)
                putNum("mean_excess", facts.meanExcess)
                facts.ci?.let { putNum("excess_ci95_low", it.first); putNum("excess_ci95_high", it.second) }
                putNum("t_week_clustered", facts.t)
                putNum("mean_excess_at_double_fees", facts.excessAtDoubleCost)
                putNum("excess_bull", facts.bull)
                putNum("excess_bear", facts.bear)
                putNum("avg_hold_days", facts.avgHoldDays)
                put("since", day(facts.from))
            })
            put("live", buildJsonObject {
                put("finished", live.size)
                putNum("mean_excess", live.takeIf { it.isNotEmpty() }?.average())
                put("standing", LedgerModel.standing(live))
            })
        })
    }

    private val candleTool = ToolSpec(
        "candles",
        "Closed candles Signal Lab has stored for a coin: daily for the whole history, hourly for the last 100 days. Rows are " +
            "[open time (UTC ms), open, high, low, close, volume], oldest first.",
        mapOf(
            "symbol" to Param("string", "A Binance spot pair such as SOLUSDT."),
            "timeframe" to Param("string", "1d or 1h."),
            "count" to Param("integer", "How many of the newest candles, at most 300."),
        ),
    ) { input ->
        val sym = input.getValue("symbol").jsonPrimitive.content.uppercase()
        val tf = when (input.getValue("timeframe").jsonPrimitive.content) {
            "1d" -> Timeframe.D1
            "1h" -> Timeframe.H1
            else -> return@ToolSpec fail("Timeframe must be 1d or 1h.")
        }
        val n = input.getValue("count").jsonPrimitive.longOrNull?.toInt()?.coerceIn(1, 300) ?: 100
        val c = candles.latest(sym, tf, n) ?: return@ToolSpec fail("No ${tf.label} candles stored for $sym. Watched coins are in market_state.")
        out(buildJsonObject {
            put("symbol", sym)
            put("timeframe", tf.label)
            putJsonArray("rows") {
                for (i in 0 until c.size) add(buildJsonArray {
                    add(JsonPrimitive(c.t[i]))
                    for (v in doubleArrayOf(c.open[i], c.high[i], c.low[i], c.close[i], c.volume[i])) add(JsonPrimitive(sig(v)))
                })
            }
        })
    }

    private val derivativesTool = ToolSpec(
        "derivatives",
        "Binance futures data stored for BTCUSDT or ETHUSDT: 8-hour funding rates and hourly open interest (sampled every 8 hours). " +
            "Research finds these useless for predicting direction; use them for context and warnings only.",
        mapOf(
            "symbol" to Param("string", "BTCUSDT or ETHUSDT."),
            "days" to Param("integer", "How many days back, at most 30."),
        ),
    ) { input ->
        val store = derivatives ?: return@ToolSpec fail("Futures data is off in Settings.")
        val sym = input.getValue("symbol").jsonPrimitive.content.uppercase()
        val days = input.getValue("days").jsonPrimitive.longOrNull?.coerceIn(1, 30) ?: 7
        val since = now() - days * DAY
        val f = store.funding(sym, since)
        val oi = store.openInterest(sym, "1h", since).filterIndexed { i, _ -> i % 8 == 0 }
        if (f.isEmpty() && oi.isEmpty()) return@ToolSpec fail("No futures data stored for $sym.")
        out(buildJsonObject {
            putJsonArray("funding") { f.forEach { add(buildJsonArray { add(JsonPrimitive(it.time)); add(JsonPrimitive(sig(it.rate))) }) } }
            putJsonArray("open_interest_usdt") { oi.forEach { add(buildJsonArray { add(JsonPrimitive(it.time)); add(JsonPrimitive(sig(it.value))) }) } }
        })
    }

    /** Parses and registers [text]: every distinct rule ever tested counts toward the number of strategies tried. */
    private suspend fun register(text: String, source: String): CustomStrategy {
        val d = Rule.parse(text)
        val s = CustomStrategy(CustomStrategy.idFor(d), "Draft", d)
        dao.insertStrategy(StrategyRow(s.id, "custom", d.timeframe.label, d.print(), now(), retired = false, source = source))
        return s
    }

    private val backtest = ToolSpec(
        "backtest",
        "Backtests a rule written in Signal Lab's rule language on the watched coins, with the same fills, fees and matched random " +
            "entries as the built-in strategies, at the owner's fees and at double. Every distinct rule you test is registered " +
            "for good and raises the bar every strategy must clear (the deflated Sharpe uses the count), so test only rules you " +
            "would stand behind, not variations fished for a good number. The language is described in your instructions.",
        mapOf("definition" to Param("string", "A whole definition: (strategy (timeframe 1d) (entry …) … (max_bars n)).")),
    ) { input ->
        val text = input.getValue("definition").jsonPrimitive.content
        val s = try {
            register(text, "analyst-test")
        } catch (e: RuleError) {
            return@ToolSpec fail("The rule does not read: ${e.message}")
        }
        val p = prefs.current()
        val btc = candles.window(DataConfig.REGIME_COIN, Timeframe.D1) ?: return@ToolSpec fail("No Bitcoin history yet.")
        val panel = panelFor(s.timeframe)
        if (panel.isEmpty()) return@ToolSpec fail("No ${s.timeframe.label} candles stored for the watched coins.")
        val trials = dao.strategiesTried()
        val result = withContext(Dispatchers.Default) {
            val trades = Backtest.run(s, panel, btc, p::costFor)
            if (trades.isEmpty()) return@withContext null
            val sum = Backtest.summarize(trades, s.id)
            val doubled = Backtest.run(s, panel, btc) { 2 * p.costFor(it) }.mapNotNull { it.excess.takeUnless(Double::isNaN) }
            val excess = trades.mapNotNull { it.excess.takeUnless(Double::isNaN) }.toDoubleArray()
            val m = Moments.of(excess)
            buildJsonObject {
                put("registered_as", s.id)
                put("strategies_tried", trials)
                put("rule", s.definition.print())
                put("coins", panel.size)
                put("calls", sum.n)
                putNum("hit_rate", sum.hit)
                putNum("mean_net", sum.meanNet)
                putNum("mean_excess", sum.meanExcess)
                sum.excessCi95?.let { putNum("excess_ci95_low", it.first); putNum("excess_ci95_high", it.second) }
                putNum("t_week_clustered", sum.tCluster)
                putNum("mean_excess_at_double_fees", if (doubled.isEmpty()) null else doubled.average())
                putNum("probabilistic_sharpe", if (m.n > 2) Evidence.probabilisticSharpe(m) else null)
                putNum("deflated_sharpe", if (m.n > 2) Evidence.deflatedSharpe(m, maxOf(trials, 1)) else null)
                sum.byRegime[1]?.let { put("bull_calls", it.first); putNum("bull_mean_excess", it.second) }
                sum.byRegime[0]?.let { put("bear_calls", it.first); putNum("bear_mean_excess", it.second) }
                putNum("avg_hold_bars", trades.sumOf { it.barsHeld }.toDouble() / trades.size)
                put("from", day(trades.minOf { it.signalTime }))
            }
        } ?: return@ToolSpec out(buildJsonObject {
            put("registered_as", s.id)
            put("calls", 0)
            put("note", "The rule never fired on the watched coins.")
        })
        out(result)
    }

    private val propose = ToolSpec(
        "propose_strategy",
        "Saves a rule as a custom strategy for the owner to review in Strategies. It does not run until the owner starts it; then " +
            "it starts in Testing and only its live calls can give it a verdict, because it was defined after seeing a backtest. " +
            "Propose only what you have backtested and would defend; say plainly what could make it fail.",
        mapOf(
            "name" to Param("string", "A short name, at most 40 characters."),
            "definition" to Param("string", "The whole definition in the rule language."),
            "thesis" to Param("string", "Why it should work and what would prove it wrong, in plain words, at most 800 characters."),
            "sources" to Param("array", "Links or citations behind the idea.", required = false, items = "string"),
        ),
    ) { input ->
        val name = input.getValue("name").jsonPrimitive.content.trim().take(40)
        val thesis = input.getValue("thesis").jsonPrimitive.content.trim().take(800)
        if (name.isEmpty() || thesis.isEmpty()) return@ToolSpec fail("A proposal needs a name and a thesis.")
        val sources = (input["sources"] as? JsonArray)?.map { it.jsonPrimitive.content.take(300) }.orEmpty().take(8)
        val s = try {
            register(input.getValue("definition").jsonPrimitive.content, "analyst")
        } catch (e: RuleError) {
            return@ToolSpec fail("The rule does not read: ${e.message}")
        }
        catalog.saved.value.firstOrNull { it.id == s.id }?.let { return@ToolSpec fail("That rule is already saved as \"${it.row.title}\".") }
        val t = now()
        val report = UUID.randomUUID().toString()
        val body = buildString {
            append(thesis)
            append("\n\nRule: ").append(s.definition.print())
            if (sources.isNotEmpty()) append("\n\nSources:\n").append(sources.joinToString("\n") { "- $it" })
        }
        dao.insertReport(ReportRow(syncId = report, receivedAt = t, card = "proposal", title = "Proposed: $name", body = body))
        dao.insertCustom(CustomStrategyRow(syncId = UUID.randomUUID().toString(), createdAt = t, title = name, reason = thesis,
            definitionV1 = null, definitionV2 = s.definition.print(), archived = false, reportSyncId = report))
        out(buildJsonObject {
            put("saved_as", s.id)
            put("status", "Waiting for the owner in Strategies. It runs only once the owner starts it.")
        })
    }

    private val suggestCall = ToolSpec(
        "suggest_call",
        "Suggests a long paper call of your own judgment on a coin with daily history. It appears on Today for the owner to record " +
            "or dismiss; if recorded, it enters at the next daily open and is judged like every strategy, against random entries " +
            "on the same coin with the same stop, target and time limit, under the strategy \"Analyst\". At most 3 suggestions " +
            "wait at once. Suggest only when you can name a reason the data supports.",
        mapOf(
            "symbol" to Param("string", "A Binance spot pair such as SOLUSDT."),
            "stop_pct" to Param("number", "The stop below the entry as a fraction, 0.06 = 6%; at most 0.30."),
            "target_pct" to Param("number", "The target above the entry as a fraction; at most 1.0. Omit for none.", required = false),
            "days" to Param("integer", "The most days to hold it, 1 to 60."),
            "thesis" to Param("string", "Why, in plain words, at most 400 characters."),
        ),
    ) { input ->
        val sym = input.getValue("symbol").jsonPrimitive.content.uppercase()
        val stop = input.getValue("stop_pct").jsonPrimitive.doubleOrNull ?: 0.0
        val target = input["target_pct"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull
        val days = input.getValue("days").jsonPrimitive.longOrNull?.toInt() ?: 0
        val thesis = input.getValue("thesis").jsonPrimitive.content.trim().take(400)
        if (!(stop > 0 && stop <= AnalystCall.MAX_STOP)) return@ToolSpec fail("stop_pct must be above 0 and at most 0.30.")
        if (target != null && !(target > 0 && target <= AnalystCall.MAX_TARGET)) return@ToolSpec fail("target_pct must be above 0 and at most 1.0.")
        if (days !in 1..AnalystCall.MAX_DAYS) return@ToolSpec fail("days must be 1 to 60.")
        if (thesis.isEmpty()) return@ToolSpec fail("A suggestion needs a thesis.")
        if (candles.latest(sym, Timeframe.D1, 1) == null) return@ToolSpec fail("No daily history for $sym. Suggest a watched coin.")
        val waiting = prefs.current().suggestions.filter { it.state == SuggestionState.SUGGESTED && now() - it.createdAt < AnalystNotes.SUGGESTION_LIFETIME_MS }
        if (waiting.size >= 3) return@ToolSpec fail("Three suggestions already wait for the owner.")
        val s = Suggestion(UUID.randomUUID().toString(), sym, stop, target, days, thesis, now())
        dao.putSetting(SettingRow(AnalystNotes.SUGGESTION_PREFIX + s.id, AnalystNotes.encode(s), now()))
        out(buildJsonObject {
            put("status", "Waiting for the owner on Today. If recorded, it enters at the next daily open.")
            put("id", s.id)
        })
    }

    private val flagContext = ToolSpec(
        "flag_context",
        "Puts one dated line of context on Today, such as \"US inflation figures Wednesday\" or \"Binance maintenance Friday\": events " +
            "that could move prices, never advice. At most 5 lines show; the oldest goes when a sixth arrives.",
        mapOf(
            "text" to Param("string", "The line, at most 120 characters."),
            "until" to Param("string", "The last day to show it, YYYY-MM-DD, at most 14 days ahead."),
        ),
    ) { input ->
        val text = input.getValue("text").jsonPrimitive.content.trim().take(120)
        val until = runCatching { LocalDate.parse(input.getValue("until").jsonPrimitive.content) }.getOrNull()
            ?: return@ToolSpec fail("until must be a date such as 2026-10-20.")
        val untilMs = until.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        if (untilMs < now() - DAY || untilMs > now() + 14 * DAY) return@ToolSpec fail("until must be from today to 14 days ahead.")
        if (text.isEmpty()) return@ToolSpec fail("The line is empty.")
        val active = prefs.current().contextLines.filter { it.until >= now() - DAY }
        if (active.size >= 5) active.minByOrNull { it.createdAt }?.let { dao.deleteSetting(AnalystNotes.CONTEXT_PREFIX + it.id) }
        val line = ContextLine(UUID.randomUUID().toString(), text, now(), untilMs)
        dao.putSetting(SettingRow(AnalystNotes.CONTEXT_PREFIX + line.id, AnalystNotes.encode(line), now()))
        out(buildJsonObject { put("status", "On Today until $until.") })
    }

    companion object {
        private const val DAY = 86_400_000L
        const val MAX_OUTPUT = 40_000

        /** Six significant digits: enough for any price, short enough to keep requests small. */
        fun sig(v: Double): Double {
            if (v == 0.0 || v.isNaN() || v.isInfinite()) return v
            val digits = 6 - (kotlin.math.floor(kotlin.math.log10(abs(v))).toInt() + 1)
            val f = Math.pow(10.0, digits.toDouble())
            return round(v * f) / f
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putNum(key: String, v: Double?) {
            put(key, if (v == null || v.isNaN() || v.isInfinite()) JsonNull else JsonPrimitive(sig(v)))
        }

    }
}
