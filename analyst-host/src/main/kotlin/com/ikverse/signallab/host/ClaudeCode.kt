package com.ikverse.signallab.host

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** A running Claude Code: its output a line at a time, and a way to stop it. */
interface ClaudeProcess {
    /** The next line of its output, or null when it has ended. */
    fun readLine(): String?

    /** The last of what it wrote to its error stream, for a failure's message. */
    fun errors(): String

    /** Waits for it to end and gives its exit code; null if it did not end in time. */
    fun waitFor(timeoutMs: Long): Int?

    fun kill()
}

/** Starts Claude Code. The real one runs the `claude` program; tests replay recorded output. */
fun interface ClaudeLauncher {
    fun start(args: List<String>, stdin: String, workDir: File): ClaudeProcess
}

/** The `claude` program installed on this PC, signed in with the owner's account as it already is. */
class InstalledClaude(private val program: String = "claude") : ClaudeLauncher {
    override fun start(args: List<String>, stdin: String, workDir: File): ClaudeProcess {
        val p = ProcessBuilder(listOf(program) + args).directory(workDir).start()
        p.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stdin) }
        val out = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
        val err = StringBuffer()
        Thread {
            runCatching { p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { err.append(it).append('\n'); if (err.length > 8000) err.delete(0, 4000) } }
        }.apply { isDaemon = true }.start()
        return object : ClaudeProcess {
            override fun readLine(): String? = out.readLine()
            override fun errors(): String = err.toString().trim().takeLast(600)
            override fun waitFor(timeoutMs: Long): Int? = if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.exitValue() else null
            override fun kill() {
                p.descendants().forEach { it.destroyForcibly() }
                p.destroyForcibly()
            }
        }
    }

    /** "2.1.296 (Claude Code)", or null when it is not installed. */
    fun version(): String? = runCatching {
        val p = ProcessBuilder(program, "--version").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) text else null
    }.getOrNull()
}

/**
 * Runs one question through Claude Code and turns its streamed output into events for the phone: text as it arrives, what
 * it is doing, the web searches it made, and at the end the whole answer with its usage and how it ended.
 *
 * Claude Code is started headless with the analyst's instructions in place of its own, Signal Lab's tools through the
 * bridge, web search and web fetch, and nothing else: no commands, no files, none of the owner's own Claude Code settings,
 * instructions, plugins or hooks (restricted mode ignores the settings files), and no saved session. Permissions refuse
 * anything not allowed up front. Safe mode is not used: it also drops the MCP server the bridge is.
 */
class ClaudeRunner(private val launcher: ClaudeLauncher, private val workDir: File) {
    /** The command line; [mcpConfigFile] is a file holding the bridge's MCP configuration. */
    fun args(spec: RunSpec, mcpConfigFile: String): List<String> {
        val allowed = listOf("WebSearch", "WebFetch") + spec.tools.map { "mcp__${BRIDGE_NAME}__${it.name}" }
        return listOf(
            "-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
            "--model", spec.model,
            "--system-prompt", commandLineSafe(spec.system),
            "--restricted",
            "--tools", "WebSearch,WebFetch",
            "--mcp-config", mcpConfigFile, "--strict-mcp-config",
            "--allowedTools", allowed.joinToString(","),
            "--permission-mode", "dontAsk",
            "--max-budget-usd", String.format(java.util.Locale.US, "%.2f", spec.maxBudgetUsd),
            "--no-session-persistence",
        )
    }

    /** The question as Claude Code reads it: the conversation so far, then the question. */
    fun input(spec: RunSpec): String = buildString {
        if (spec.history.isNotEmpty()) {
            append("Earlier in this conversation:\n\n")
            for (t in spec.history) append(if (t.role == "user") "The owner: " else "You: ").append(t.text.trim()).append("\n\n")
            append("The owner now asks:\n")
        }
        append(spec.prompt)
    }

    /** Runs [run] to its end. [mcpConfigFile] tells Claude Code where the bridge for this run is. */
    fun execute(run: Run, mcpConfigFile: String) {
        val proc = try {
            launcher.start(args(run.spec, mcpConfigFile), input(run.spec), workDir)
        } catch (e: Exception) {
            run.emit(done("failed: Claude Code could not be started (${e.message}). Is it installed and signed in on this PC?", "", null, null, 0, null))
            return
        }
        val watcher = Thread {
            try {
                while (true) {
                    if (run.closed) { proc.kill(); return@Thread }
                    if (proc.waitFor(500) != null) return@Thread
                }
            } catch (_: InterruptedException) {
                // The run ended normally and no longer needs watching.
            }
        }.apply { isDaemon = true; start() }
        val texts = ArrayList<String>()
        var messages = 0
        var result: JSONObject? = null
        var limits: JSONObject? = null
        var refused = false
        var cutShort = false
        while (true) {
            val line = proc.readLine() ?: break
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            when (o.optString("type")) {
                "stream_event" -> {
                    val e = o.optJSONObject("event") ?: continue
                    when (e.optString("type")) {
                        "message_start" -> if (messages++ > 0) run.emit(text("\n\n"))
                        "content_block_delta" -> e.optJSONObject("delta")?.takeIf { it.optString("type") == "text_delta" }?.let { run.emit(text(it.optString("text"))) }
                        "content_block_start" -> e.optJSONObject("content_block")?.let { b ->
                            if (b.optString("type") in setOf("tool_use", "server_tool_use")) working(b.optString("name"))?.let { run.emit(it) }
                        }
                    }
                }
                "assistant" -> {
                    val m = o.optJSONObject("message") ?: continue
                    when (m.optString("stop_reason")) {
                        "refusal" -> refused = true
                        "max_tokens" -> cutShort = true
                    }
                    val said = StringBuilder()
                    for (b in m.optJSONArray("content").objects()) {
                        when (b.optString("type")) {
                            "text" -> said.append(b.optString("text"))
                            "tool_use", "server_tool_use" -> if (!b.optString("name").startsWith("mcp__")) {
                                run.emit(JSONObject().put("type", "server_tool").put("name", b.optString("name")).put("input", (b.optJSONObject("input") ?: JSONObject()).toString()))
                            }
                        }
                    }
                    said.toString().trim().takeIf { it.isNotEmpty() }?.let { texts.add(it) }
                }
                "rate_limit_event" -> limits = o.optJSONObject("rate_limit_info")
                "result" -> result = o
            }
        }
        proc.waitFor(10_000)
        watcher.interrupt()
        val r = result
        val ending = when {
            run.closed -> "stopped"
            r == null -> "failed: Claude Code stopped without an answer. ${proc.errors()}".trim()
            refused || r.optString("stop_reason") == "refusal" -> "declined"
            r.optString("subtype") == "error_max_budget_usd" -> "rounds"
            r.optBoolean("is_error") || r.optString("subtype") != "success" -> "failed: " + (r.optString("result").ifBlank { r.optString("subtype") }).take(300)
            cutShort || r.optString("stop_reason") == "max_tokens" -> "cut short"
            else -> "done"
        }
        val answer = texts.joinToString("\n\n").ifBlank { r?.optString("result").orEmpty() }
        run.emit(done(ending, answer, r?.optJSONObject("usage"), r?.optDouble("total_cost_usd"), r?.optInt("num_turns") ?: 0, limits))
    }

    companion object {
        const val BRIDGE_NAME = "signallab"

        /**
         * [s] as one argument that survives Windows' command line: Java does not escape a quote inside an argument, so
         * straight quotes become typographic ones, and a trailing backslash (which would escape the closing quote) goes.
         */
        fun commandLineSafe(s: String): String {
            val out = StringBuilder()
            var open = true
            for (ch in s) {
                if (ch == '"') {
                    out.append(if (open) '“' else '”')
                    open = !open
                } else out.append(ch)
            }
            return out.toString().trimEnd('\\', ' ', '\n')
        }

        fun text(delta: String): JSONObject = JSONObject().put("type", "text").put("delta", delta)

        fun working(tool: String): JSONObject? = when {
            tool == "WebSearch" || tool == "web_search" -> JSONObject().put("type", "working").put("what", "Searching the web…")
            tool == "WebFetch" || tool == "web_fetch" -> JSONObject().put("type", "working").put("what", "Reading a page…")
            else -> null
        }

        fun done(ending: String, answer: String, usage: JSONObject?, cost: Double?, turns: Int, limits: JSONObject?): JSONObject {
            val u = JSONObject()
            if (usage != null) {
                u.put("input", usage.optLong("input_tokens")).put("output", usage.optLong("output_tokens"))
                    .put("cache_read", usage.optLong("cache_read_input_tokens")).put("cache_write", usage.optLong("cache_creation_input_tokens"))
                usage.optJSONObject("server_tool_use")?.let { s -> u.put("searches", s.optLong("web_search_requests")).put("fetches", s.optLong("web_fetch_requests")) }
            }
            val o = JSONObject().put("type", "done").put("ending", ending).put("answer", answer).put("usage", u).put("turns", turns)
            if (cost != null && !cost.isNaN()) o.put("api_price_usd", cost)
            limits?.let { l ->
                val windows = l.optJSONObject("unifiedWindows")
                val five = windows?.optJSONObject("five_hour")?.optDouble("utilization")
                val week = windows?.optJSONObject("seven_day")?.optDouble("utilization")
                o.put("limits", JSONObject().put("status", l.optString("status")).putOpt("five_hour", five?.takeUnless { it.isNaN() })
                    .putOpt("seven_day", week?.takeUnless { it.isNaN() }))
            }
            return o
        }

        /** The bridge as Claude Code's MCP configuration names it. */
        fun mcpConfig(url: String, token: String): String = JSONObject().put("mcpServers", JSONObject().put(BRIDGE_NAME,
            JSONObject().put("type", "http").put("url", url).put("headers", JSONObject().put("Authorization", "Bearer $token")))).toString()
    }
}
