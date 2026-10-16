package com.ikverse.signallab.host

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The host against a stand-in for Claude Code: real HTTP between a pretend phone and the host, the real tool bridge, and a
 * Claude Code that replays output recorded from the real one (and, for tools, calls the bridge the way the real one does).
 */
class HostTest {
    private lateinit var dir: File
    private lateinit var bridge: ToolBridge
    private lateinit var server: PhoneServer
    private var port = 0
    private val code = "ABCDE23456"

    /** A Claude Code whose output is written by [script], given the arguments it was started with. */
    private inner class Stand(private val script: (args: List<String>, out: (String) -> Unit) -> Unit) : ClaudeLauncher {
        var args: List<String> = emptyList()
        var stdin = ""
        var mcpText = ""
        @Volatile var killed = false

        override fun start(args: List<String>, stdin: String, workDir: File): ClaudeProcess {
            this.args = args
            this.stdin = stdin
            args.indexOf("--mcp-config").takeIf { it >= 0 }?.let { mcpText = File(args[it + 1]).readText() }
            val lines = LinkedBlockingQueue<Any>()
            val end = Any()
            val t = Thread {
                try {
                    script(args) { lines.put(it) }
                } finally {
                    lines.put(end)
                }
            }.apply { isDaemon = true; start() }
            return object : ClaudeProcess {
                private var ended = false
                override fun readLine(): String? {
                    if (ended) return null
                    val l = lines.take()
                    if (l === end) { ended = true; return null }
                    return l as String
                }
                override fun errors() = "stand-in error text"
                override fun waitFor(timeoutMs: Long): Int? { t.join(timeoutMs); return if (t.isAlive) null else 0 }
                override fun kill() { killed = true; t.interrupt() }
            }
        }
    }

    private fun recorded(name: String) = javaClass.getResource("/$name")!!.readText().lines().filter { it.isNotBlank() }

    private fun startHost(launcher: ClaudeLauncher) {
        server = PhoneServer({ code }, ClaudeRunner(launcher, dir), bridge, dir, "2.1.296 (Claude Code)")
        port = server.start(0, null, bind = "127.0.0.1")
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("host").toFile()
        bridge = ToolBridge()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        bridge.stop()
        dir.deleteRecursively()
    }

    private fun spec(tools: JSONArray = JSONArray()) = JSONObject().put("model", "claude-opus-5-5")
        .put("system", "You are the \"analyst\".").put("prompt", "How is Breakout doing?")
        .put("history", JSONArray().put(JSONObject().put("role", "user").put("text", "Hello")).put(JSONObject().put("role", "assistant").put("text", "Hi.")))
        .put("tools", tools)

    private val echoTool = JSONArray().put(JSONObject().put("name", "echo").put("description", "Says x back.")
        .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject().put("x", JSONObject().put("type", "string"))).put("required", JSONArray().put("x"))))

    private fun post(path: String, body: JSONObject, auth: String? = code): HttpURLConnection =
        (java.net.URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            auth?.let { setRequestProperty("Authorization", "Bearer $it") }
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toString().toByteArray()) }
        }

    /** The phone's side of a run: every event, with each tool call answered by [answer]. */
    private fun ask(body: JSONObject, answer: (JSONObject) -> String = { "?" }): List<JSONObject> {
        val c = post("/runs", body)
        assertEquals(200, c.responseCode)
        val events = ArrayList<JSONObject>()
        var runId = ""
        c.inputStream.bufferedReader().useLines { lines ->
            for (l in lines) {
                if (!l.startsWith("data: ")) continue
                val e = JSONObject(l.removePrefix("data: "))
                events.add(e)
                when (e.getString("type")) {
                    "run" -> runId = e.getString("id")
                    "tool" -> assertEquals(200, post("/runs/$runId/tools/${e.getString("id")}", JSONObject().put("text", answer(e)).put("is_error", false)).responseCode)
                    "done" -> return events
                }
            }
        }
        return events
    }

    @Test
    fun aRecordedPlainReplyStreamsToThePhone() {
        val stand = Stand { _, out -> recorded("claude-plain-reply.jsonl").forEach(out) }
        startHost(stand)
        val events = ask(spec(echoTool))
        assertEquals(listOf("run", "text", "done"), events.map { it.getString("type") })
        assertEquals("ready", events[1].getString("delta"))
        val done = events.last()
        assertEquals("done", done.getString("ending"))
        assertEquals("ready", done.getString("answer"))
        assertEquals(4, done.getJSONObject("usage").getLong("output"))
        assertEquals(5143, done.getJSONObject("usage").getLong("cache_write"))
        assertEquals(0.63, done.getJSONObject("limits").getDouble("five_hour"), 1e-9)

        // Started headless, isolated from the owner's own Claude Code setup, with only web tools and the bridge's tools.
        val a = stand.args
        for (flag in listOf("-p", "--restricted", "--strict-mcp-config", "--no-session-persistence", "--include-partial-messages")) assertTrue(flag in a, flag)
        fun value(flag: String) = a[a.indexOf(flag) + 1]
        assertEquals("stream-json", value("--output-format"))
        assertEquals("WebSearch,WebFetch", value("--tools"))
        assertEquals("dontAsk", value("--permission-mode"))
        assertEquals("claude-opus-5-5", value("--model"))
        assertEquals("WebSearch,WebFetch,mcp__signallab__echo", value("--allowedTools"))
        assertFalse(value("--system-prompt").contains('"'), "no straight quote reaches Windows' command line")
        assertTrue(value("--system-prompt").contains("“analyst”"))
        val servers = JSONObject(stand.mcpText).getJSONObject("mcpServers")
        assertEquals(listOf("signallab"), servers.keySet().toList(), "the bridge is the one MCP server")
        assertTrue(servers.getJSONObject("signallab").getString("url").startsWith("http://127.0.0.1:"), "on this PC only")
        assertFalse(File(value("--mcp-config")).exists(), "its file is gone once the run ends")
        assertTrue(stand.stdin.startsWith("Earlier in this conversation:\n\nThe owner: Hello\n\nYou: Hi."))
        assertTrue(stand.stdin.endsWith("The owner now asks:\nHow is Breakout doing?"))
    }

    /** Calls the bridge as Claude Code does: JSON-RPC over HTTP with the token from its MCP configuration. */
    private fun mcp(config: JSONObject, method: String, params: JSONObject, id: Int?): JSONObject? {
        val server = config.getJSONObject("mcpServers").getJSONObject("signallab")
        val c = (java.net.URI(server.getString("url")).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", server.getJSONObject("headers").getString("Authorization"))
            outputStream.use { o -> o.write(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params).apply { id?.let { put("id", it) } }.toString().toByteArray()) }
        }
        if (c.responseCode == 202) return null
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    @Test
    fun aToolCallGoesThroughTheBridgeToThePhoneAndBack() {
        var listed: JSONObject? = null
        val stand = Stand { args, out ->
            val config = JSONObject(File(args[args.indexOf("--mcp-config") + 1]).readText())
            val init = mcp(config, "initialize", JSONObject().put("protocolVersion", "2025-11-25"), 1)!!
            assertEquals("2025-11-25", init.getJSONObject("result").getString("protocolVersion"))
            mcp(config, "notifications/initialized", JSONObject(), null)
            listed = mcp(config, "tools/list", JSONObject(), 2)
            val called = mcp(config, "tools/call", JSONObject().put("name", "echo").put("arguments", JSONObject().put("x", "hi")), 3)!!
            val text = called.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
            out("""{"type":"stream_event","event":{"type":"message_start","message":{}}}""")
            out(JSONObject().put("type", "assistant").put("message", JSONObject().put("stop_reason", "end_turn")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "The tool said: $text")))).toString())
            out("""{"type":"result","subtype":"success","is_error":false,"result":"The tool said: $text","num_turns":2,"usage":{"input_tokens":10,"output_tokens":5}}""")
        }
        startHost(stand)
        val events = ask(spec(echoTool)) { e -> "echo: " + e.getJSONObject("input").getString("x") }
        val tool = events.first { it.getString("type") == "tool" }
        assertEquals("echo", tool.getString("name"))
        assertEquals("hi", tool.getJSONObject("input").getString("x"))
        assertEquals("The tool said: echo: hi", events.last().getString("answer"))
        assertEquals(listOf("echo"), listed!!.getJSONObject("result").getJSONArray("tools").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } })
    }

    @Test
    fun withoutThePairingCodeNothingRunsButHelloAnswers() {
        startHost(Stand { _, _ -> error("must not start") })
        assertEquals(401, post("/runs", spec(), auth = "WRONG00000").responseCode)
        assertEquals(401, post("/runs", spec(), auth = null).responseCode)
        val hello = java.net.URI("http://127.0.0.1:$port/hello").toURL().openConnection() as HttpURLConnection
        assertEquals(200, hello.responseCode)
        assertEquals(PhoneServer.APP, JSONObject(hello.inputStream.bufferedReader().readText()).getString("app"))
        // Dashes and case do not matter in a typed code.
        assertEquals(200, (java.net.URI("http://127.0.0.1:$port/check").toURL().openConnection() as HttpURLConnection).apply { setRequestProperty("Authorization", "Bearer abcde-23456") }.responseCode)
    }

    @Test
    fun oneQuestionAtATimeAndAPhoneThatLeavesStopsClaude() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val stand = Stand { _, out ->
            out("""{"type":"stream_event","event":{"type":"message_start","message":{}}}""")
            gate.await(30, TimeUnit.SECONDS)
        }
        startHost(stand)
        // A phone whose connection drops: a raw socket, closed for real.
        val socket = java.net.Socket("127.0.0.1", port)
        val body = spec().toString().toByteArray()
        val head = listOf("POST /runs HTTP/1.1", "Host: 127.0.0.1", "Authorization: Bearer $code", "Content-Type: application/json",
            "Content-Length: ${body.size}", "", "").joinToString("\r\n")
        socket.getOutputStream().write(head.toByteArray() + body)
        val reader = socket.getInputStream().bufferedReader()
        do { val line = reader.readLine() } while (!line.startsWith("data: "))
        assertEquals(409, post("/runs", spec()).responseCode)
        socket.close()
        val until = System.currentTimeMillis() + 15_000
        while (!stand.killed && System.currentTimeMillis() < until) Thread.sleep(50)
        gate.countDown()
        assertTrue(stand.killed, "the phone left, so Claude Code was stopped by the next keep-alive")
    }

    @Test
    fun theStopButtonStopsClaudeAtOnce() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val stand = Stand { _, out ->
            out("""{"type":"stream_event","event":{"type":"message_start","message":{}}}""")
            gate.await(30, TimeUnit.SECONDS)
        }
        startHost(stand)
        val c = post("/runs", spec())
        val reader = c.inputStream.bufferedReader()
        val runId = JSONObject(reader.readLine().removePrefix("data: ")).getString("id")
        val started = System.currentTimeMillis()
        assertEquals(200, post("/runs/$runId/stop", JSONObject()).responseCode)
        while (!stand.killed && System.currentTimeMillis() - started < 3_000) Thread.sleep(20)
        gate.countDown()
        assertTrue(stand.killed, "stopped within seconds")
        val rest = reader.readText()
        assertTrue(rest.contains("\"ending\":\"stopped\""), rest)
    }

    @Test
    fun aClaudeThatFailsSaysSo() {
        startHost(ClaudeLauncher { _, _, _ -> throw java.io.IOException("not found") })
        val done = ask(spec()).last()
        assertTrue(done.getString("ending").startsWith("failed: Claude Code could not be started"), done.getString("ending"))
        server.stop()
        startHost(Stand { _, out -> out("not json") })
        val silent = ask(spec()).last()
        assertTrue(silent.getString("ending").contains("stopped without an answer"), silent.getString("ending"))
        server.stop()
        startHost(Stand { _, out -> out("""{"type":"result","subtype":"success","is_error":true,"result":"Claude AI usage limit reached"}""") })
        assertEquals("failed: Claude AI usage limit reached", ask(spec()).last().getString("ending"))
    }

    @Test
    fun theBridgeAnswersOnlyWhatItKnows() {
        val run = Run("r", RunSpec.parse(spec(echoTool)))
        assertEquals(null, bridge.respond(run, JSONObject().put("method", "notifications/initialized")))
        assertEquals(-32601, bridge.respond(run, JSONObject().put("id", 1).put("method", "resources/list"))!!.getJSONObject("error").getInt("code"))
        assertEquals(-32602, bridge.respond(run, JSONObject().put("id", 2).put("method", "tools/call").put("params", JSONObject().put("name", "rm")))!!.getJSONObject("error").getInt("code"))
        val bad = runCatching { RunSpec.parse(JSONObject().put("model", "x y").put("system", "s").put("prompt", "p")) }.exceptionOrNull()
        assertTrue(bad is IllegalArgumentException)
    }

    @Test
    fun theHostKeepsItsCodeAndCertificateBetweenStarts() {
        val home = File(dir, "home")
        val a = HostConfig(home)
        assertEquals(10, a.pairingCode.length)
        assertTrue(a.shownCode.matches(Regex("[A-Z0-9]{5}-[A-Z0-9]{5}")))
        val b = HostConfig(home)
        assertEquals(a.pairingCode, b.pairingCode)
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(64, a.fingerprint.length)
        assertEquals(a.fingerprint.takeLast(6), a.shortFingerprint)
        b.newPairingCode()
        assertNotEquals(a.pairingCode, HostConfig(home).pairingCode)
        assertNotNull(PhoneServer.tls(b))
        assertTrue(Secrets.same(Secrets.normalize("ab cd-e"), "ABCDE"))
    }
}
