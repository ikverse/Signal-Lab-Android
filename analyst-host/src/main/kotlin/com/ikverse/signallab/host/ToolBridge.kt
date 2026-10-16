package com.ikverse.signallab.host

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Signal Lab's tools as Claude Code sees them: a small MCP server (JSON-RPC over HTTP) on this PC's loopback address only.
 * It lists the tools the phone offered for the run, and hands each call to the phone through the run, waiting for its
 * answer. Nothing outside this PC can reach it, and each request must carry the bridge's own random token.
 */
class ToolBridge(private val token: String = Secrets.random(32)) {
    private val runs = ConcurrentHashMap<String, Run>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "bridge").apply { isDaemon = true } }
        server.createContext("/mcp/") { ex -> runCatching { handle(ex) }.onFailure { runCatching { ex.close() } } }
        server.start()
    }

    val port: Int get() = server.address.port

    fun register(run: Run) {
        runs[run.id] = run
    }

    fun unregister(run: Run) {
        runs.remove(run.id)
    }

    /** Claude Code's MCP configuration for [run]. */
    fun configFor(run: Run): String = ClaudeRunner.mcpConfig("http://127.0.0.1:$port/mcp/${run.id}", token)

    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        if (!Secrets.same(ex.requestHeaders.getFirst("Authorization").orEmpty(), "Bearer $token")) return reply(ex, 401, null)
        val run = runs[ex.requestURI.path.removePrefix("/mcp/").trimEnd('/')] ?: return reply(ex, 404, null)
        when (ex.requestMethod) {
            "POST" -> {}
            "DELETE" -> return reply(ex, 200, null)
            // No stream of server-initiated messages: the bridge only ever answers.
            else -> return reply(ex, 405, null)
        }
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8).trim()
        val replyBody: String? = if (body.startsWith("[")) {
            val out = JSONArray()
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) respond(run, arr.getJSONObject(i))?.let { out.put(it) }
            if (out.length() == 0) null else out.toString()
        } else {
            respond(run, JSONObject(body))?.toString()
        }
        if (replyBody == null) reply(ex, 202, null) else reply(ex, 200, replyBody)
    }

    /** The answer to one JSON-RPC message; null for a notification, which has none. */
    internal fun respond(run: Run, msg: JSONObject): JSONObject? {
        if (!msg.has("id")) return null
        val id = msg.get("id")
        val params = msg.optJSONObject("params") ?: JSONObject()
        val result: JSONObject = when (msg.optString("method")) {
            "initialize" -> JSONObject()
                .put("protocolVersion", params.optString("protocolVersion").ifBlank { PROTOCOL })
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", ClaudeRunner.BRIDGE_NAME).put("version", "1"))
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", JSONArray().apply {
                for (t in run.spec.tools) put(JSONObject().put("name", t.name).put("description", t.description).put("inputSchema", t.inputSchema))
            })
            "tools/call" -> {
                val name = params.optString("name")
                if (run.spec.tools.none { it.name == name }) return error(id, -32602, "No tool called $name.")
                val a = run.callTool(name, params.optJSONObject("arguments") ?: JSONObject())
                JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", a.text))).put("isError", a.isError)
            }
            else -> return error(id, -32601, "Unknown method ${msg.optString("method")}.")
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }

    private fun error(id: Any, code: Int, message: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message))

    private fun reply(ex: HttpExchange, code: Int, body: String?) {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        if (bytes != null) ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes?.size?.toLong() ?: -1)
        if (bytes != null) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
    }
}
