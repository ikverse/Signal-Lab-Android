package com.ikverse.signallab.analyst

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebFetchTool20260209
import com.anthropic.models.messages.WebSearchTool20260209
import com.fasterxml.jackson.databind.JsonNode
import com.ikverse.signallab.ledger.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID

/** Why a run was started. */
@Serializable
enum class RunKind { CHAT, BRIEF, REVIEW, EXPLAIN }

/** One message of the conversation as the owner sees it and as it is kept between runs: its words only. */
@Serializable
data class Turn(val role: String, val text: String, val at: Long, val costUsd: Double? = null, val runId: String? = null) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/** One thing the phone or Anthropic's servers did for a run: a tool the phone ran, or a web search or fetch. */
@Serializable
data class Step(val kind: String, val name: String, val input: String, val output: String, val isError: Boolean = false)

/** Everything one run sent and received, for the "what was sent" page. */
@Serializable
data class RunLog(
    val id: String,
    val kind: RunKind,
    val startedAt: Long,
    val model: String,
    val prompt: String,
    /** How many earlier messages of the conversation went with it. */
    val historyTurns: Int,
    val steps: List<Step>,
    val usage: Usage,
    val costUsd: Double,
    val requests: Int,
    /** How it ended: "done", "cut short", "declined", "cap", "rounds", "failed: …", "stopped". */
    val ending: String,
    val answer: String,
    /** "api" for the Claude API on the owner's key, "pc" for Claude Code on the owner's PC. */
    val route: String = ROUTE_API,
    /** For a run on the PC: what it would have cost at API prices, and how much of the plan's limits are used. */
    val note: String? = null,
) {
    companion object {
        const val ROUTE_API = "api"
        const val ROUTE_PC = "pc"
    }
}

/** What a run gives back to the screen. */
class RunResult(val answer: String, val log: RunLog)

/** What happens during a run, for the screen to show as it goes. */
sealed interface RunEvent {
    class Text(val delta: String) : RunEvent

    class Working(val what: String) : RunEvent
}

/** The analyst's files: the conversation, and the last runs' logs. All under the app's private files, none synced. */
class AnalystStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val chatFile get() = File(dir, "chat.json")
    private val runsDir get() = File(dir, "runs")

    @Synchronized
    fun chat(): List<Turn> = runCatching { json.decodeFromString(ListSerializer(Turn.serializer()), chatFile.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun append(vararg turns: Turn) {
        val all = (chat() + turns).takeLast(MAX_TURNS)
        write(chatFile, json.encodeToString(ListSerializer(Turn.serializer()), all))
    }

    @Synchronized
    fun clearChat() {
        chatFile.delete()
    }

    @Synchronized
    fun save(log: RunLog) {
        runsDir.mkdirs()
        write(File(runsDir, "${log.startedAt}-${log.id}.json"), json.encodeToString(RunLog.serializer(), log))
        runsDir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_RUNS)?.forEach { it.delete() }
    }

    @Synchronized
    fun runs(): List<RunLog> = runsDir.listFiles()?.sortedByDescending { it.name }
        ?.mapNotNull { f -> runCatching { json.decodeFromString(RunLog.serializer(), f.readText()) }.getOrNull() }.orEmpty()

    fun run(id: String): RunLog? = runs().firstOrNull { it.id == id }

    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    companion object {
        const val MAX_TURNS = 60
        const val MAX_RUNS = 50
    }
}

/**
 * The analyst: Claude with Signal Lab's tools, Anthropic's web search and fetch, and the owner's conversation. One run is a
 * loop of requests: Claude answers, or asks for tools; the phone runs its tools and sends the results back; a search the
 * server paused is resumed; it stops when Claude is done, at a limit of rounds, or before a request that the month's
 * spending cap could not cover.
 */
class Analyst(
    private val transport: () -> AnalystTransport?,
    private val tools: List<ToolSpec>,
    private val store: AnalystStore,
    private val spend: SpendMeter,
    private val preferences: suspend () -> Preferences,
    private val now: () -> Long = System::currentTimeMillis,
    /** Claude Code on the owner's PC, tried first when paired; null when there is none. */
    private val pc: PcRoute? = null,
) {
    private val byName = tools.associateBy { it.name }

    /** A run cannot start, in a sentence; null when it can. */
    suspend fun blocked(): String? {
        if (runCatching { pc?.reachable() }.getOrNull() != null) return null
        if (transport() == null) {
            return if (runCatching { pc?.paired() }.getOrNull() != null) "Your PC is not answering. Start the analyst host on it, or ask in the Claude app."
            else "Pair your PC, or add a Claude API key, under Key, model and budget."
        }
        val p = preferences()
        if (spend.month() >= p.analystCapUsd) return "This month's analyst budget of ${usd(p.analystCapUsd)} is used up. Raise it in Settings."
        return null
    }

    suspend fun run(kind: RunKind, prompt: String, onEvent: (RunEvent) -> Unit = {}): RunResult {
        val p = preferences()
        val model = ClaudeModel.of(p.analystModel)
        val id = UUID.randomUUID().toString()
        val started = now()
        val history = if (kind == RunKind.CHAT || kind == RunKind.EXPLAIN) historyFor(store.chat()) else emptyList()
        val steps = ArrayList<Step>()
        var usage = Usage()
        var cost = 0.0
        var requests = 0
        val texts = ArrayList<String>()
        var ending = "done"

        fun finish(): RunResult {
            val said = texts.joinToString("\n\n").trim()
            val answer = when {
                said.isEmpty() -> endingWords(ending)
                ending == "done" -> said
                else -> said + "\n\n" + endingWords(ending)
            }
            val log = RunLog(id, kind, started, model.id, prompt, history.size, steps, usage, cost, requests, ending, answer)
            store.save(log)
            if (kind == RunKind.CHAT || kind == RunKind.EXPLAIN) {
                store.append(Turn(Turn.USER, prompt, started), Turn(Turn.ASSISTANT, answer, now(), cost, id))
            }
            return RunResult(answer, log)
        }

        // Claude Code on the owner's PC first: it runs on their own plan.
        val host = runCatching { pc?.reachable() }.getOrNull()
        if (host != null) return runOnPc(host, kind, prompt, model, id, started, history, onEvent)

        val t = transport() ?: run {
            ending = "no key"
            return finish()
        }
        val messages = ArrayList<MessageParam>()
        for (turn in history) messages.add(text(turn.role, turn.text))
        messages.add(text(Turn.USER, prompt))
        var continuations = 0
        try {
            for (round in 0 until MAX_ROUNDS) {
                if (spend.month() >= p.analystCapUsd) {
                    ending = "cap"
                    break
                }
                requests++
                val buffer = StringBuilder()
                val reply: Message = t.send(params(model, messages, kind)) { delta ->
                    buffer.append(delta)
                    onEvent(RunEvent.Text(delta))
                }
                val u = usageOf(reply)
                usage += u
                val c = model.cost(u)
                cost += c
                spend.add(c)
                messages.add(reply.toParam())
                val said = reply.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("").trim()
                if (said.isNotEmpty()) texts.add(said)
                for (b in reply.content()) {
                    b.serverToolUse().ifPresent { s ->
                        steps.add(Step(s.name().toString(), s.name().toString(), plain(s._input()), "run on Anthropic's servers"))
                        onEvent(RunEvent.Working(if (s.name().toString().contains("fetch")) "Reading a page…" else "Searching the web…"))
                    }
                }
                when (reply.stopReason().orElse(null)) {
                    StopReason.TOOL_USE -> {
                        val calls = reply.content().mapNotNull { it.toolUse().orElse(null) }
                        val results = ArrayList<ContentBlockParam>()
                        for (call in calls) {
                            onEvent(RunEvent.Working(working(call.name())))
                            val input = plain(call._input())
                            val output = runTool(call.name(), input)
                            steps.add(Step("tool", call.name(), input, output.text.take(LOG_OUTPUT), output.isError))
                            results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                                .toolUseId(call.id()).content(output.text).isError(output.isError).build()))
                        }
                        messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build())
                        onEvent(RunEvent.Text("\n\n"))
                    }
                    StopReason.PAUSE_TURN -> {
                        // The server paused a long search; sending the conversation back as it is resumes it.
                        if (++continuations > MAX_CONTINUATIONS) {
                            ending = "rounds"
                            break
                        }
                    }
                    StopReason.MAX_TOKENS -> {
                        ending = "cut short"
                        break
                    }
                    StopReason.REFUSAL -> {
                        ending = "declined"
                        break
                    }
                    else -> break
                }
                if (round == MAX_ROUNDS - 1) ending = "rounds"
            }
        } catch (e: CancellationException) {
            ending = "stopped"
            withContext(kotlinx.coroutines.NonCancellable) { finish() }
            throw e
        } catch (e: Exception) {
            ending = "failed: " + (e.message?.take(200) ?: e::class.java.simpleName)
        }
        return finish()
    }

    /**
     * One run through Claude Code on the owner's PC. The PC runs the loop; each tool it asks for is run here, on this
     * phone's own data, with the same checks as on the API route, and its answer goes back to the PC.
     */
    private suspend fun runOnPc(
        host: HostClient, kind: RunKind, prompt: String, model: ClaudeModel, id: String, started: Long, history: List<Turn>, onEvent: (RunEvent) -> Unit,
    ): RunResult {
        val steps = ArrayList<Step>()
        var ending = "failed: The PC stopped answering."
        var answer = ""
        var usage = Usage()
        var note: String? = null
        var runId = ""
        val spec = org.json.JSONObject().put("model", model.id).put("system", AnalystPrompt.system).put("prompt", prompt)
            .put("history", org.json.JSONArray().apply { for (t in history) put(org.json.JSONObject().put("role", t.role).put("text", t.text)) })
            .put("tools", org.json.JSONArray().apply { for (t in tools) put(toolJson(t)) })
            .put("max_budget_usd", PC_BUDGET_USD)
        fun finish(): RunResult {
            val said = answer.trim()
            val words = when {
                said.isEmpty() -> endingWords(ending)
                ending == "done" -> said
                else -> said + "\n\n" + endingWords(ending)
            }
            val log = RunLog(id, kind, started, model.id, prompt, history.size, steps, usage, 0.0, 1, ending, words, RunLog.ROUTE_PC, note)
            store.save(log)
            if (kind == RunKind.CHAT || kind == RunKind.EXPLAIN) store.append(Turn(Turn.USER, prompt, started), Turn(Turn.ASSISTANT, words, now(), null, id))
            return RunResult(words, log)
        }
        try {
            host.run(spec) { e ->
                when (e.optString("type")) {
                    "run" -> runId = e.optString("id")
                    "text" -> onEvent(RunEvent.Text(e.optString("delta")))
                    "working" -> onEvent(RunEvent.Working(e.optString("what")))
                    "server_tool" -> steps.add(Step(e.optString("name"), e.optString("name"), e.optString("input"), "run by Claude Code on your PC"))
                    "tool" -> {
                        val name = e.optString("name")
                        onEvent(RunEvent.Working(working(name)))
                        val input = (e.optJSONObject("input") ?: org.json.JSONObject()).toString()
                        val out = runTool(name, input)
                        steps.add(Step("tool", name, input, out.text.take(LOG_OUTPUT), out.isError))
                        host.answer(runId, e.optString("id"), out.text, out.isError)
                    }
                    "done" -> {
                        ending = e.optString("ending").ifBlank { "done" }
                        answer = e.optString("answer")
                        e.optJSONObject("usage")?.let { u ->
                            usage = Usage(u.optLong("input"), u.optLong("output"), u.optLong("cache_read"), u.optLong("cache_write"), u.optLong("searches"), u.optLong("fetches"))
                        }
                        note = pcNote(e)
                    }
                }
            }
        } catch (e: CancellationException) {
            ending = "stopped"
            withContext(kotlinx.coroutines.NonCancellable) { finish() }
            throw e
        } catch (e: Exception) {
            ending = "failed: " + (e.message?.take(200) ?: "The PC stopped answering.")
            (pc as? HostPairing)?.recheck()
        }
        return finish()
    }

    private suspend fun runTool(name: String, input: String): ToolOutput {
        val spec = byName[name] ?: return ToolOutput("There is no tool called $name.", isError = true)
        val obj = runCatching { Json.parseToJsonElement(input.ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: return ToolOutput("The input was not a JSON object.", isError = true)
        spec.problem(obj)?.let { return ToolOutput(it, isError = true) }
        return try {
            withContext(Dispatchers.IO) { spec.run(obj) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutput("The tool failed: ${e.message ?: e::class.java.simpleName}", isError = true)
        }
    }

    private fun params(model: ClaudeModel, messages: List<MessageParam>, kind: RunKind): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(if (kind == RunKind.CHAT || kind == RunKind.REVIEW) 16_000L else 8_000L)
            .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(AnalystPrompt.system)
                .cacheControl(CacheControlEphemeral.builder().build()).build()))
        for (spec in tools) b.addTool(sdkTool(spec))
        b.addTool(WebSearchTool20260209.builder().maxUses(MAX_SEARCHES).build())
        b.addTool(WebFetchTool20260209.builder().maxUses(MAX_FETCHES).maxContentTokens(20_000L).build())
        return b.messages(messages).build()
    }

    companion object {
        const val MAX_ROUNDS = 12
        const val MAX_CONTINUATIONS = 4
        const val MAX_SEARCHES = 5L
        const val MAX_FETCHES = 3L
        const val HISTORY_TURNS = 16

        /** A ceiling per run on the PC, at API prices as Claude Code counts them; on a plan it only stops a runaway run. */
        const val PC_BUDGET_USD = 5.0

        /** The tool as the PC host passes it on to Claude Code: name, description and JSON Schema. */
        fun toolJson(spec: ToolSpec): org.json.JSONObject {
            val props = org.json.JSONObject()
            for ((name, p) in spec.params) {
                val o = org.json.JSONObject().put("type", p.type).put("description", p.description)
                p.items?.let { o.put("items", org.json.JSONObject().put("type", it)) }
                props.put(name, o)
            }
            val schema = org.json.JSONObject().put("type", "object").put("properties", props)
                .put("required", org.json.JSONArray(spec.params.filter { it.value.required }.keys.toList()))
            return org.json.JSONObject().put("name", spec.name).put("description", spec.description).put("input_schema", schema)
        }

        /** "Plan limits used: 63% of five hours, 39% of the week · about $0.10 at API prices". */
        fun pcNote(done: org.json.JSONObject): String? {
            val parts = ArrayList<String>()
            done.optJSONObject("limits")?.let { l ->
                val five = l.optDouble("five_hour")
                val week = l.optDouble("seven_day")
                val used = listOfNotNull(
                    five.takeUnless { it.isNaN() }?.let { "${Math.round(it * 100)}% of five hours" },
                    week.takeUnless { it.isNaN() }?.let { "${Math.round(it * 100)}% of the week" },
                )
                if (used.isNotEmpty()) parts.add("Plan limits used: " + used.joinToString(", "))
            }
            done.optDouble("api_price_usd").takeUnless { it.isNaN() }?.let { parts.add("about ${usd(it)} at API prices") }
            return parts.joinToString(" · ").ifEmpty { null }
        }
        private const val LOG_OUTPUT = 4_000

        fun usd(v: Double): String = "$" + String.format(java.util.Locale.US, "%.2f", v)

        /** The tool as the API describes it; its input streams as it is written, and the phone checks it before running. */
        fun sdkTool(spec: ToolSpec): Tool {
            val props = Tool.InputSchema.Properties.builder()
            for ((name, p) in spec.params) {
                val schema = LinkedHashMap<String, Any>()
                schema["type"] = p.type
                schema["description"] = p.description
                p.items?.let { schema["items"] = mapOf("type" to it) }
                props.putAdditionalProperty(name, JsonValue.from(schema))
            }
            return Tool.builder()
                .name(spec.name)
                .description(spec.description)
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(spec.params.filter { it.value.required }.keys.toList()).build())
                .eagerInputStreaming(true)
                .build()
        }

        /** The last [HISTORY_TURNS] turns, starting with the owner's and alternating, as the API requires. */
        fun historyFor(chat: List<Turn>): List<Turn> {
            val merged = ArrayList<Turn>()
            for (t in chat.takeLast(HISTORY_TURNS)) {
                val last = merged.lastOrNull()
                if (last != null && last.role == t.role) merged[merged.size - 1] = last.copy(text = last.text + "\n\n" + t.text) else merged.add(t)
            }
            while (merged.firstOrNull()?.role == Turn.ASSISTANT) merged.removeAt(0)
            if (merged.lastOrNull()?.role == Turn.USER) merged.removeAt(merged.size - 1)
            return merged
        }

        private fun text(role: String, text: String): MessageParam = MessageParam.builder()
            .role(if (role == Turn.USER) MessageParam.Role.USER else MessageParam.Role.ASSISTANT).content(text).build()

        /** A JSON value from the SDK as compact JSON text. */
        fun plain(v: JsonValue): String = runCatching { v.convert(JsonNode::class.java).toString() }.getOrDefault("{}")

        fun usageOf(m: Message): Usage {
            val u = m.usage()
            val server = u.serverToolUse().orElse(null)
            return Usage(
                input = u.inputTokens(), output = u.outputTokens(),
                cacheRead = u.cacheReadInputTokens().orElse(0L), cacheWrite = u.cacheCreationInputTokens().orElse(0L),
                searches = server?.webSearchRequests() ?: 0L, fetches = server?.webFetchRequests() ?: 0L,
            )
        }

        private fun working(tool: String) = when (tool) {
            "market_state" -> "Reading the market…"
            "ledger" -> "Reading the ledger…"
            "list_strategies", "strategy_evidence" -> "Reading the strategies…"
            "candles", "derivatives" -> "Reading prices…"
            "backtest" -> "Backtesting a rule…"
            "propose_strategy" -> "Saving a proposal…"
            "suggest_call" -> "Writing a suggestion…"
            "flag_context" -> "Adding a line to Today…"
            else -> "Working…"
        }

        fun endingWords(ending: String): String = when {
            ending == "no key" -> "Add your Claude API key in Settings first."
            ending == "cap" -> "Stopped: this month's analyst budget is used up."
            ending == "declined" -> "Claude declined to answer this."
            ending == "cut short" -> "The answer was cut short."
            ending == "rounds" -> "Stopped after too many steps."
            ending == "stopped" -> "Stopped."
            ending.startsWith("failed") -> "The analyst could not be reached: ${ending.removePrefix("failed: ")}"
            else -> "No answer."
        }
    }
}
