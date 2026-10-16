package com.ikverse.signallab.host

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** One message of the conversation before this question, as the phone keeps it: its words only. */
class Turn(val role: String, val text: String)

/** One tool the phone offers for a run: run on the phone, described here so Claude Code can list it. */
class ToolDef(val name: String, val description: String, val inputSchema: JSONObject)

/** What the phone asked for. */
class RunSpec(
    val model: String,
    val system: String,
    val prompt: String,
    val history: List<Turn>,
    val tools: List<ToolDef>,
    /** A ceiling on what one run may cost at API prices, as Claude Code measures it; on a plan it only bounds a runaway run. */
    val maxBudgetUsd: Double,
) {
    companion object {
        /** Reads the phone's request; throws [IllegalArgumentException] naming what is missing. */
        fun parse(o: JSONObject): RunSpec {
            fun need(key: String): String = o.optString(key).ifBlank { throw IllegalArgumentException("Missing \"$key\".") }
            val model = need("model")
            require(model.matches(Regex("[a-z0-9.-]{3,60}"))) { "That is not a model id." }
            val history = o.optJSONArray("history").objects().map { Turn(it.getString("role"), it.getString("text")) }
            val tools = o.optJSONArray("tools").objects().map { t ->
                val name = t.getString("name")
                require(name.matches(Regex("[a-z_]{1,40}"))) { "Tool names are lower case letters and underscores." }
                ToolDef(name, t.optString("description"), t.optJSONObject("input_schema") ?: JSONObject().put("type", "object"))
            }
            return RunSpec(model, need("system"), need("prompt"), history, tools, o.optDouble("max_budget_usd", 5.0).coerceIn(0.1, 50.0))
        }
    }
}

/** A tool's answer from the phone. */
class ToolAnswer(val text: String, val isError: Boolean)

/**
 * One question in progress: the events going to the phone, and the tool calls waiting for the phone's answers. Claude Code
 * calls a tool through the bridge; the bridge asks the phone through this run's events and waits for its answer.
 */
class Run(val id: String, val spec: RunSpec) {
    private val queue = LinkedBlockingQueue<JSONObject>()
    private val pending = ConcurrentHashMap<String, CompletableFuture<ToolAnswer>>()
    private val calls = AtomicInteger()

    /** The phone has gone (it stopped the run, or the connection dropped): nothing more is waited for. */
    @Volatile
    var closed = false
        private set

    fun emit(event: JSONObject) {
        if (!closed) queue.put(event)
    }

    /** The next event for the phone, or null if none came within [timeoutMs]. */
    fun next(timeoutMs: Long): JSONObject? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** Asks the phone to run tool [name] with [input], and waits for its answer. */
    fun callTool(name: String, input: JSONObject, timeoutMs: Long = TOOL_TIMEOUT_MS): ToolAnswer {
        if (closed) return ToolAnswer("The phone has stopped this run.", true)
        val callId = "c" + calls.incrementAndGet()
        val f = CompletableFuture<ToolAnswer>()
        pending[callId] = f
        emit(JSONObject().put("type", "tool").put("id", callId).put("name", name).put("input", input))
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            ToolAnswer("The phone did not answer within ${timeoutMs / 1000} seconds.", true)
        } finally {
            pending.remove(callId)
        }
    }

    /** The phone's answer to call [callId]; false when no such call is waiting. */
    fun answer(callId: String, text: String, isError: Boolean): Boolean = pending[callId]?.complete(ToolAnswer(text, isError)) ?: false

    fun close() {
        closed = true
        for (f in pending.values) f.complete(ToolAnswer("The phone has stopped this run.", true))
    }

    companion object {
        const val TOOL_TIMEOUT_MS = 60_000L
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
