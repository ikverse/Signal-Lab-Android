package com.ikverse.signallab.analyst

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.PreferenceStore
import com.ikverse.signallab.ledger.Preferences
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The analyst's loop against a stand-in for the Claude API: real requests through Anthropic's SDK, answered with streamed
 * events in the API's own format. It checks what is sent, that tools run on the phone and their results go back, that a
 * paused search is resumed, and that the budget and failures end a run cleanly.
 */
@RunWith(RobolectricTestRunner::class)
class AnalystLoopTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var ledger: LedgerDatabase
    private lateinit var dir: File
    private lateinit var prefs: PreferenceStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        ledger = LedgerDatabase.open(context, name = null)
        prefs = PreferenceStore(ledger.dao())
        dir = Files.createTempDirectory("analyst").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        ledger.close()
        dir.deleteRecursively()
    }

    private val echo = ToolSpec("echo", "Says x back.", mapOf("x" to Param("string", "What to say."))) { input ->
        ToolOutput("echo: " + input.getValue("x").jsonPrimitive.content)
    }

    private fun analyst(tools: List<ToolSpec> = listOf(echo), key: String? = "sk-ant-test-0000000000000000000000") = Analyst(
        transport = { key?.let { ClaudeApiTransport(it, server.url("/").toString().trimEnd('/')) } },
        tools = tools, store = AnalystStore(dir), spend = SpendMeter(ledger.dao()), preferences = { prefs.current() },
    )

    // --- the API's streamed events -------------------------------------------------------------------------------------

    private fun event(type: String, data: String) = "event: $type\ndata: $data\n\n"

    private fun start(input: Long = 100) = event("message_start",
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":$input,"output_tokens":1,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}""")

    private fun text(index: Int, vararg parts: String) = event("content_block_start",
        """{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":""}}""") +
        parts.joinToString("") { event("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":${JsonPrimitive(it).toString()}}}""") } +
        event("content_block_stop", """{"type":"content_block_stop","index":$index}""")

    private fun toolUse(index: Int, id: String, name: String, json: String) = event("content_block_start",
        """{"type":"content_block_start","index":$index,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}""") +
        event("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":{"type":"input_json_delta","partial_json":${JsonPrimitive(json).toString()}}}""") +
        event("content_block_stop", """{"type":"content_block_stop","index":$index}""")

    private fun search(index: Int) = event("content_block_start",
        """{"type":"content_block_start","index":$index,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""") +
        event("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"bitcoin etf flows\"}"}}""") +
        event("content_block_stop", """{"type":"content_block_stop","index":$index}""")

    private fun end(stop: String, output: Long = 20, searches: Long = 0) = event("message_delta",
        """{"type":"message_delta","delta":{"stop_reason":"$stop","stop_sequence":null},"usage":{"output_tokens":$output,"server_tool_use":{"web_search_requests":$searches,"web_fetch_requests":0}}}""") +
        event("message_stop", """{"type":"message_stop"}""")

    private fun respond(body: String) = server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).build())

    private fun requestJson(): JsonObject {
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.url.encodedPath)
        assertEquals("sk-ant-test-0000000000000000000000", req.headers["x-api-key"])
        return Json.parseToJsonElement(assertNotNull(req.body).utf8()).jsonObject
    }

    // --- the tests ------------------------------------------------------------------------------------------------------

    @Test
    fun aPlainAnswerStreamsIsKeptAndIsPaidFor() = runTest {
        respond(start() + text(0, "Breakout is ", "behind random entries.") + end("end_turn"))
        val deltas = ArrayList<String>()
        val r = analyst().run(RunKind.CHAT, "How is Breakout doing?") { if (it is RunEvent.Text) deltas.add(it.delta) }
        assertEquals("Breakout is behind random entries.", r.answer)
        assertEquals(listOf("Breakout is ", "behind random entries."), deltas)
        assertEquals("done", r.log.ending)
        assertEquals(1, r.log.requests)

        val body = requestJson()
        assertEquals("claude-opus-5-5", body.getValue("model").jsonPrimitive.content)
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
        val system = body.getValue("system").jsonArray[0].jsonObject
        assertEquals("ephemeral", system.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content, "the system prompt is cached")
        assertTrue(system.getValue("text").jsonPrimitive.content.contains("(strategy"), "the rule language is in the instructions")
        val tools = body.getValue("tools").jsonArray.map { it.jsonObject }
        val mine = tools.first { it["name"]?.jsonPrimitive?.content == "echo" }
        assertTrue(mine.getValue("eager_input_streaming").jsonPrimitive.boolean)
        assertEquals(listOf("x"), mine.getValue("input_schema").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(tools.any { it["type"]?.jsonPrimitive?.content == "web_search_20260209" })
        assertTrue(tools.any { it["type"]?.jsonPrimitive?.content == "web_fetch_20260209" })
        val messages = body.getValue("messages").jsonArray
        assertEquals(1, messages.size)
        assertEquals("How is Breakout doing?", messages[0].jsonObject.getValue("content").jsonPrimitive.content)

        // 100 tokens in at $4 per million and 20 out at $20 per million.
        val cost = (100 * 4.0 + 20 * 20.0) / 1_000_000
        assertEquals(cost, r.log.costUsd, 1e-12)
        assertEquals(cost, SpendMeter(ledger.dao()).month(), 1e-9)
        val chat = AnalystStore(dir).chat()
        assertEquals(listOf(Turn.USER, Turn.ASSISTANT), chat.map { it.role })
        assertEquals(r.answer, chat[1].text)
        assertEquals(r.log.id, AnalystStore(dir).runs().single().id)
    }

    @Test
    fun theNextQuestionCarriesTheConversationBefore() = runTest {
        respond(start() + text(0, "First answer.") + end("end_turn"))
        respond(start() + text(0, "Second answer.") + end("end_turn"))
        val a = analyst()
        a.run(RunKind.CHAT, "First question")
        requestJson()
        a.run(RunKind.CHAT, "Second question")
        val messages = requestJson().getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("First answer.", messages[1].getValue("content").jsonPrimitive.content)
    }

    @Test
    fun aToolRunsOnThePhoneAndItsResultGoesBack() = runTest {
        respond(start() + text(0, "Let me check.") + toolUse(1, "toolu_1", "echo", """{"x":"hello"}""") +
            toolUse(2, "toolu_2", "echo", """{"y":1}""") + end("tool_use"))
        respond(start() + text(0, "It said hello.") + end("end_turn"))
        val working = ArrayList<String>()
        val r = analyst().run(RunKind.CHAT, "Use echo") { if (it is RunEvent.Working) working.add(it.what) }
        assertEquals("Let me check.\n\nIt said hello.", r.answer)
        assertEquals(2, r.log.requests)
        assertEquals(listOf("echo", "echo"), r.log.steps.map { it.name })
        assertEquals("echo: hello", r.log.steps[0].output)
        assertTrue(r.log.steps[1].isError, "a tool call with the wrong arguments is refused, not run")
        assertTrue(working.isNotEmpty())

        requestJson()
        val second = requestJson().getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), second.map { it.getValue("role").jsonPrimitive.content })
        val assistant = second[1].getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "tool_use", "tool_use"), assistant.map { it.getValue("type").jsonPrimitive.content }, "the whole reply goes back")
        val results = second[2].getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("toolu_1", "toolu_2"), results.map { it.getValue("tool_use_id").jsonPrimitive.content })
        assertEquals("echo: hello", (results[0]["content"] as? JsonPrimitive)?.content
            ?: (results[0].getValue("content") as JsonArray)[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertTrue(results[1].getValue("is_error").jsonPrimitive.boolean)
    }

    @Test
    fun aSearchTheServerPausedIsResumedWithoutAnExtraMessage() = runTest {
        respond(start() + search(0) + end("pause_turn", searches = 1))
        respond(start() + text(0, "Flows were positive this week.") + end("end_turn"))
        val r = analyst().run(RunKind.CHAT, "What happened with ETF flows?")
        assertEquals("Flows were positive this week.", r.answer)
        assertEquals("web_search", r.log.steps.single().name)
        assertTrue(r.log.steps.single().input.contains("bitcoin etf flows"))
        assertEquals(1, r.log.usage.searches)
        requestJson()
        val messages = requestJson().getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant"), messages.map { it.getValue("role").jsonPrimitive.content }, "the paused turn is sent back as it was")
        // A search costs a cent on top of the tokens.
        assertTrue(r.log.costUsd > 0.01)
    }

    @Test
    fun theMonthlyBudgetStopsARunBeforeAnyRequest() = runTest {
        prefs.set(Preferences.ANALYST_CAP, "0")
        val a = analyst()
        assertNotNull(a.blocked())
        val r = a.run(RunKind.CHAT, "Anything")
        assertEquals("cap", r.log.ending)
        assertEquals(0, r.log.requests)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun withoutAKeyNothingIsSent() = runTest {
        val a = analyst(key = null)
        assertEquals("Add your Claude API key in Settings first.", a.blocked())
        assertEquals("no key", a.run(RunKind.CHAT, "Anything").log.ending)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRefusalAndAFailureEndTheRunWithWords() = runTest {
        respond(start() + end("refusal"))
        val refused = analyst().run(RunKind.CHAT, "Something")
        assertEquals("declined", refused.log.ending)
        assertEquals("Claude declined to answer this.", refused.answer)

        server.enqueue(MockResponse.Builder().code(400).addHeader("Content-Type", "application/json")
            .body("""{"type":"error","error":{"type":"invalid_request_error","message":"bad request"}}""").build())
        val failed = analyst().run(RunKind.CHAT, "Something else")
        assertTrue(failed.log.ending.startsWith("failed"), failed.log.ending)
        assertTrue(failed.answer.startsWith("The analyst could not be reached"), failed.answer)
    }

    @Test
    fun aBriefIsNotPartOfTheConversation() = runTest {
        respond(start() + text(0, "Calm day.") + end("end_turn"))
        val r = analyst().run(RunKind.BRIEF, AnalystPrompt.BRIEF)
        assertEquals("Calm day.", r.answer)
        assertTrue(AnalystStore(dir).chat().isEmpty())
        assertEquals(8_000, requestJson().getValue("max_tokens").jsonPrimitive.content.toInt())
    }

    @Test
    fun theHistoryStartsWithTheOwnerAndAlternates() {
        val t = { role: String, text: String -> Turn(role, text, 0) }
        val h = Analyst.historyFor(listOf(t(Turn.ASSISTANT, "orphan"), t(Turn.USER, "a"), t(Turn.USER, "b"), t(Turn.ASSISTANT, "c"), t(Turn.USER, "d")))
        assertEquals(listOf(Turn.USER, Turn.ASSISTANT), h.map { it.role })
        assertEquals("a\n\nb", h[0].text)
        assertNull(Analyst.historyFor(emptyList()).firstOrNull())
    }

}
