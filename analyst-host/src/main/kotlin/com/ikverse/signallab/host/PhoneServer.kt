package com.ikverse.signallab.host

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The phone's way in, on this PC's local network:
 * - `GET /hello` says what this is (no code needed; the phone reads the certificate here when it pairs);
 * - `POST /runs` asks one question and streams the run back as server-sent events;
 * - `POST /runs/<id>/tools/<call>` is the phone's answer to a tool call it was sent;
 * - `POST /runs/<id>/stop` stops a run at once (a dropped connection is noticed at the next keep-alive).
 * Every request but `/hello` carries the pairing code. One question runs at a time; a second gets 409 until it ends.
 */
class PhoneServer(
    private val pairingCode: () -> String,
    private val runner: ClaudeRunner,
    private val bridge: ToolBridge,
    private val tempDir: File,
    private val claudeVersion: String?,
    private val log: (String) -> Unit = {},
) {
    private var server: HttpServer? = null
    private val busy = Semaphore(1)
    private val runs = java.util.concurrent.ConcurrentHashMap<String, Run>()
    private val failures = java.util.concurrent.atomic.AtomicInteger()

    /** Starts on [port], over HTTPS with [tls], or plain HTTP when it is null (tests only). */
    fun start(port: Int, tls: SSLContext?, bind: String = "0.0.0.0"): Int {
        val s = if (tls != null) HttpsServer.create(InetSocketAddress(bind, port), 0).apply { httpsConfigurator = HttpsConfigurator(tls) }
        else HttpServer.create(InetSocketAddress(bind, port), 0)
        s.executor = Executors.newCachedThreadPool { r -> Thread(r, "phone").apply { isDaemon = true } }
        s.createContext("/") { ex ->
            try {
                route(ex)
            } catch (e: Exception) {
                runCatching { reply(ex, 500, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) }
            } finally {
                runCatching { ex.close() }
            }
        }
        s.start()
        server = s
        return s.address.port
    }

    fun stop() = server?.stop(0)

    private fun authorized(ex: HttpExchange): Boolean {
        val sent = ex.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ").trim()
        val ok = Secrets.same(Secrets.normalize(sent), Secrets.normalize(pairingCode()))
        if (!ok) {
            // A wrong code waits a little longer each time, so guessing one is slow.
            Thread.sleep(minOf(5_000L, 250L * failures.incrementAndGet()))
        } else failures.set(0)
        return ok
    }

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path.trimEnd('/')
        when {
            ex.requestMethod == "GET" && path == "/hello" -> reply(ex, 200, JSONObject().put("app", APP).put("protocol", PROTOCOL).putOpt("claude", claudeVersion))
            !authorized(ex) -> reply(ex, 401, JSONObject().put("error", "Wrong pairing code."))
            ex.requestMethod == "GET" && path == "/check" -> reply(ex, 200, JSONObject().put("ok", true).put("busy", busy.availablePermits() == 0))
            ex.requestMethod == "POST" && path == "/runs" -> startRun(ex)
            ex.requestMethod == "POST" && Regex("/runs/[^/]+/stop").matches(path) -> {
                val run = runs[path.split('/')[2]]
                run?.close()
                reply(ex, if (run != null) 200 else 404, JSONObject().put("ok", run != null))
            }
            ex.requestMethod == "POST" && Regex("/runs/[^/]+/tools/[^/]+").matches(path) -> {
                val (_, _, id, _, call) = path.split('/')
                val o = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
                val ok = runs[id]?.answer(call, o.optString("text"), o.optBoolean("is_error")) == true
                reply(ex, if (ok) 200 else 404, JSONObject().put("ok", ok))
            }
            else -> reply(ex, 404, JSONObject().put("error", "Nothing here."))
        }
    }

    private fun startRun(ex: HttpExchange) {
        val spec = try {
            RunSpec.parse(JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8)))
        } catch (e: Exception) {
            return reply(ex, 400, JSONObject().put("error", e.message ?: "Bad request."))
        }
        if (!busy.tryAcquire()) return reply(ex, 409, JSONObject().put("error", "The PC is answering another question."))
        val run = Run(UUID.randomUUID().toString(), spec)
        runs[run.id] = run
        bridge.register(run)
        val config = File(tempDir, "mcp-${run.id}.json").apply { writeText(bridge.configFor(run)) }
        log("Question from the phone (${spec.model}, ${spec.tools.size} tools).")
        val worker = Thread({
            try {
                runner.execute(run, config.path)
            } catch (e: Exception) {
                run.emit(ClaudeRunner.done("failed: ${e.message ?: e.javaClass.simpleName}", "", null, null, 0, null))
            }
        }, "run-${run.id}").apply { isDaemon = true; start() }
        try {
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.responseHeaders.add("Cache-Control", "no-cache")
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            fun send(o: JSONObject) {
                out.write("data: $o\n\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
            send(JSONObject().put("type", "run").put("id", run.id))
            while (true) {
                if (run.closed) {
                    send(ClaudeRunner.done("stopped", "", null, null, 0, null))
                    break
                }
                val e = run.next(KEEPALIVE_MS)
                if (e == null) {
                    out.write(": still working\n\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    continue
                }
                send(e)
                if (e.optString("type") == "done") {
                    log("Answered: ${e.optString("ending")}.")
                    break
                }
            }
        } catch (_: IOException) {
            log("The phone went away; the run is stopped.")
        } finally {
            run.close()
            worker.join(15_000)
            bridge.unregister(run)
            runs.remove(run.id)
            config.delete()
            busy.release()
        }
    }

    private fun reply(ex: HttpExchange, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val APP = "Signal Lab analyst host"
        const val PROTOCOL = 1
        const val KEEPALIVE_MS = 5_000L

        fun tls(config: HostConfig): SSLContext {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(config.loadKeystore(), config.keystorePassword)
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }
    }
}
