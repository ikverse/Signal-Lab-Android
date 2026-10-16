package com.ikverse.signallab.host

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One real question through the Claude Code installed on this PC, signed in as it is: the bridge's tool must be offered
 * under safe mode, called, and its answer used. It uses a little of the plan's usage, so it runs only when asked:
 *   SIGNAL_LAB_LIVE_CLAUDE=1 ./gradlew :analyst-host:test --tests "*LiveClaudeTest*"
 */
class LiveClaudeTest {
    @Test
    fun aRealToolCallMakesTheRoundTrip() {
        assumeTrue(System.getenv("SIGNAL_LAB_LIVE_CLAUDE") == "1")
        val dir = Files.createTempDirectory("live").toFile()
        val bridge = ToolBridge()
        val server = PhoneServer({ "LIVE000000" }, ClaudeRunner(InstalledClaude(), dir), bridge, dir, "live")
        val port = server.start(0, null, bind = "127.0.0.1")
        try {
            val tools = JSONArray().put(JSONObject().put("name", "secret_word").put("description", "Returns today's secret word.")
                .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject())))
            val spec = JSONObject().put("model", "claude-sonnet-5-5").put("system", "You answer in one short sentence.")
                .put("prompt", "Call the secret_word tool and tell me the word it returns.").put("tools", tools).put("max_budget_usd", 1.0)
            fun post(path: String, body: JSONObject) = (java.net.URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; readTimeout = 180_000
                setRequestProperty("Authorization", "Bearer LIVE000000")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val c = post("/runs", spec)
            var runId = ""
            var asked = 0
            var done: JSONObject? = null
            c.inputStream.bufferedReader().useLines { lines ->
                for (l in lines) {
                    if (!l.startsWith("data: ")) continue
                    val e = JSONObject(l.removePrefix("data: "))
                    when (e.getString("type")) {
                        "run" -> runId = e.getString("id")
                        "tool" -> {
                            asked++
                            assertEquals("secret_word", e.getString("name"))
                            post("/runs/$runId/tools/${e.getString("id")}", JSONObject().put("text", "pelican").put("is_error", false)).responseCode
                        }
                        "done" -> { done = e; break }
                    }
                }
            }
            val d = done!!
            println("LIVE ending=${d.getString("ending")} answer=${d.getString("answer")} usage=${d.optJSONObject("usage")} limits=${d.optJSONObject("limits")}")
            assertEquals("done", d.getString("ending"))
            assertTrue(asked >= 1, "the tool was called")
            assertTrue(d.getString("answer").lowercase().contains("pelican"), d.getString("answer"))
        } finally {
            server.stop()
            bridge.stop()
            dir.deleteRecursively()
        }
    }
}
