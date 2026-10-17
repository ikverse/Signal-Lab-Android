package com.ikverse.signallab.analyst

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.PreferenceStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The analyst through Claude Code on the owner's PC, against a stand-in for the analyst host, and pairing with it. */
@RunWith(RobolectricTestRunner::class)
class AnalystPcTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var server = MockWebServer()
    private lateinit var ledger: LedgerDatabase
    private lateinit var prefs: PreferenceStore
    private lateinit var dir: File

    @Before
    fun setUp() {
        ledger = LedgerDatabase.open(context, name = null)
        prefs = PreferenceStore(ledger.dao())
        dir = Files.createTempDirectory("pc").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        ledger.close()
        dir.deleteRecursively()
    }

    private object Flip : SecretCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x33).toByte() }
        override fun decrypt(sealed: ByteArray) = encrypt(sealed)
    }

    private val echo = ToolSpec("echo", "Says x back.", mapOf("x" to Param("string", "What to say."))) { input ->
        ToolOutput("echo: " + input.getValue("x").jsonPrimitive.content)
    }

    /** A paired PC that is the stand-in server, over plain HTTP. */
    private inner class StandIn(private val up: Boolean = true) : PcRoute {
        override suspend fun paired() = PairedHost("127.0.0.1:${server.port}", "PIN", "ABCDE23456")
        override suspend fun reachable(): HostClient? = if (up) HostClient(server.url("/").toString().trimEnd('/'), "ABCDE23456", OkHttpClient()) else null
    }

    private fun analyst(pc: PcRoute?, key: String? = null) = Analyst(
        transport = { key?.let { ClaudeApiTransport(it, server.url("/").toString().trimEnd('/')) } },
        tools = listOf(echo), store = AnalystStore(dir), spend = SpendMeter(ledger.dao()), preferences = { prefs.current() }, pc = pc,
    )

    private fun sse(vararg events: JSONObject) = MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
        .body(events.joinToString("") { "data: $it\n\n" }).build()

    @Test
    fun aQuestionRunsOnThePcAndItsToolsRunOnThePhone() = runTest {
        server.start()
        server.enqueue(sse(
            JSONObject().put("type", "run").put("id", "r1"),
            JSONObject().put("type", "text").put("delta", "Checking. "),
            JSONObject().put("type", "server_tool").put("name", "WebSearch").put("input", "{\"query\":\"btc\"}"),
            JSONObject().put("type", "tool").put("id", "c1").put("name", "echo").put("input", JSONObject().put("x", "hi")),
            JSONObject().put("type", "tool").put("id", "c2").put("name", "echo").put("input", JSONObject().put("y", 1)),
            JSONObject().put("type", "done").put("ending", "done").put("answer", "It said echo: hi.").put("turns", 2)
                .put("usage", JSONObject().put("input", 10).put("output", 20).put("cache_read", 30).put("cache_write", 40).put("searches", 1))
                .put("api_price_usd", 0.12).put("limits", JSONObject().put("five_hour", 0.63).put("seven_day", 0.39)),
        ))
        server.enqueue(MockResponse.Builder().code(200).body("{\"ok\":true}").build())
        server.enqueue(MockResponse.Builder().code(200).body("{\"ok\":true}").build())
        val deltas = ArrayList<String>()
        val r = analyst(StandIn()).run(RunKind.CHAT, "How is Breakout doing?") { if (it is RunEvent.Text) deltas.add(it.delta) }

        assertEquals("It said echo: hi.", r.answer)
        assertEquals(listOf("Checking. "), deltas)
        assertEquals(RunLog.ROUTE_PC, r.log.route)
        assertEquals(0.0, r.log.costUsd, "a run on the PC is on the owner's plan, not on the API key's budget")
        assertEquals(0.0, SpendMeter(ledger.dao()).month())
        assertEquals("Plan limits used: 63% of five hours, 39% of the week · about $0.12 at API prices", r.log.note)
        assertEquals(listOf("WebSearch", "echo", "echo"), r.log.steps.map { it.name })
        assertEquals("echo: hi", r.log.steps[1].output)
        assertTrue(r.log.steps[2].isError, "a tool call with the wrong arguments is refused on the phone")
        assertEquals(20, r.log.usage.output)

        val run = server.takeRequest()
        assertEquals("/runs", run.url.encodedPath)
        assertEquals("Bearer ABCDE23456", run.headers["Authorization"])
        val spec = JSONObject(assertNotNull(run.body).utf8())
        assertEquals("claude-opus-5-5", spec.getString("model"))
        assertTrue(spec.getString("system").contains("(strategy"))
        val tool = spec.getJSONArray("tools").getJSONObject(0)
        assertEquals("echo", tool.getString("name"))
        assertEquals("x", tool.getJSONObject("input_schema").getJSONArray("required").getString(0))
        val answers = listOf(server.takeRequest(), server.takeRequest()).associateBy { it.url.encodedPath }
        assertEquals("echo: hi", JSONObject(assertNotNull(answers.getValue("/runs/r1/tools/c1").body).utf8()).getString("text"))
        assertTrue(JSONObject(assertNotNull(answers.getValue("/runs/r1/tools/c2").body).utf8()).getBoolean("is_error"))

        val chat = AnalystStore(dir).chat()
        assertEquals(listOf(Turn.USER, Turn.ASSISTANT), chat.map { it.role })
        assertNull(chat[1].costUsd)
    }

    @Test
    fun aPcThatStopsAnsweringEndsTheRunWithWords() = runTest {
        server.start()
        server.enqueue(sse(JSONObject().put("type", "run").put("id", "r1"), JSONObject().put("type", "text").put("delta", "Half")))
        val r = analyst(StandIn()).run(RunKind.CHAT, "Anything")
        assertTrue(r.log.ending.startsWith("failed"), r.log.ending)
        assertTrue(r.answer.contains("The analyst could not be reached"), r.answer)
        server.enqueue(MockResponse.Builder().code(409).body("{\"error\":\"busy\"}").build())
        assertTrue(analyst(StandIn()).run(RunKind.CHAT, "Again").log.ending.contains("another question"))
    }

    @Test
    fun withThePcOffTheKeyIsUsedAndWithoutOneTheOwnerIsTold() = runTest {
        server.start()
        val off = StandIn(up = false)
        assertEquals("Your PC is not answering. Start the analyst host on it, or ask in the Claude app.", analyst(off).blocked())
        assertNull(analyst(StandIn()).blocked())
        // With a key as well, the API is used while the PC is off.
        val event = { type: String, data: String -> "event: $type\ndata: $data\n\n" }
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
            event("message_start", """{"type":"message_start","message":{"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1}}}""") +
                event("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +
                event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"From the API."}}""") +
                event("content_block_stop", """{"type":"content_block_stop","index":0}""") +
                event("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":3}}""") +
                event("message_stop", """{"type":"message_stop"}"""),
        ).build())
        val r = analyst(off, key = "sk-ant-test-0000000000000000000000").run(RunKind.CHAT, "Hi")
        assertEquals("From the API.", r.answer)
        assertEquals(RunLog.ROUTE_API, r.log.route)
    }

    @Test
    fun pairingPinsThePcsCertificateAndNoOther() = runTest {
        val cert = HeldCertificate.Builder().commonName("Signal Lab analyst host").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
        val pairing = HostPairing(ledger.dao(), File(dir, HostPairing.FILE_NAME), Flip)
        val address = "127.0.0.1:${server.port}"

        server.enqueue(MockResponse.Builder().code(200).body("{\"app\":\"Signal Lab analyst host\",\"protocol\":1,\"claude\":\"2.1.296 (Claude Code)\"}").build())
        server.enqueue(MockResponse.Builder().code(200).body("{\"ok\":true}").build())
        val probe = pairing.probe(address, " ABCDE-23456 ")
        assertEquals(HostClient.fingerprint(cert.certificate), probe.fingerprint)
        assertEquals(probe.fingerprint.takeLast(6), probe.shortFingerprint)
        assertEquals("2.1.296 (Claude Code)", probe.claude)
        assertNull(pairing.paired(), "nothing is kept before the owner confirms")

        pairing.confirm(probe)
        val paired = assertNotNull(pairing.paired())
        assertEquals(probe.fingerprint, paired.pin)
        assertTrue(!String(File(dir, HostPairing.FILE_NAME).readBytes()).contains("ABCDE"), "the code is sealed")
        server.enqueue(MockResponse.Builder().code(200).body("{\"ok\":true}").build())
        assertNotNull(pairing.reachable())

        // Another machine at the same address, with another certificate, is not the paired PC.
        val port = server.port
        server.close()
        val other = HeldCertificate.Builder().commonName("someone else").build()
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(other).build().sslSocketFactory())
        server.start(port)
        pairing.recheck()
        server.enqueue(MockResponse.Builder().code(200).body("{\"ok\":true}").build())
        assertNull(pairing.reachable())

        pairing.forget()
        assertNull(pairing.paired())
        assertTrue(!File(dir, HostPairing.FILE_NAME).exists())
    }

    @Test
    fun aWrongCodeOrAddressIsSaidPlainly() = runTest {
        val cert = HeldCertificate.Builder().build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
        val pairing = HostPairing(ledger.dao(), File(dir, HostPairing.FILE_NAME), Flip)
        server.enqueue(MockResponse.Builder().code(200).body("{\"app\":\"Signal Lab analyst host\"}").build())
        server.enqueue(MockResponse.Builder().code(401).body("{\"error\":\"Wrong pairing code.\"}").build())
        val wrong = assertFailsWith<HostException> { pairing.probe("127.0.0.1:${server.port}", "WRONG") }
        assertTrue(wrong.message!!.contains("refused the pairing code"), wrong.message)
        server.enqueue(MockResponse.Builder().code(200).body("{\"app\":\"Signal Lab analyst host\"}").build())
        server.enqueue(MockResponse.Builder().code(500).body("{}").build())
        val broken = assertFailsWith<HostException> { pairing.probe("127.0.0.1:${server.port}", "A") }
        assertEquals("The PC answered 500.", broken.message)
        server.enqueue(MockResponse.Builder().code(200).body("{\"app\":\"Signal Lab analyst host\"}").build())
        server.enqueue(MockResponse.Builder().code(200).body("{\"ok\":true}").headersDelay(6, java.util.concurrent.TimeUnit.SECONDS).build())
        val slow = assertFailsWith<HostException> { pairing.probe("127.0.0.1:${server.port}", "A") }
        assertTrue(slow.message!!.contains("did not answer"), slow.message)
        assertTrue(assertFailsWith<HostException> { pairing.probe("not an address/x", "A") }.message!!.contains("not an address"))
        assertEquals("https://192.168.1.20:8765", HostClient.baseUrl("192.168.1.20"))
        assertEquals("https://my-pc.local:9000", HostClient.baseUrl(" https://my-pc.local:9000/ "))
        assertNull(HostClient.baseUrl("192.168.1.20:99999"))
    }
}
