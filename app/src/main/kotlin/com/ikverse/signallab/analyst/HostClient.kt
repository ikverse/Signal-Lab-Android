package com.ikverse.signallab.analyst

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** The analyst host on the owner's PC could not be reached or refused, in a sentence. */
class HostException(message: String) : IOException(message)

/**
 * The phone's side of the analyst host (analyst-host/ in this repository): Claude Code on the owner's PC, signed in with the
 * owner's own account. The host speaks HTTPS with a certificate it made itself; the phone trusts exactly the one it pinned
 * when it paired, whatever address it is reached at, and sends the pairing code with every request.
 */
class HostClient(private val base: String, private val code: String, private val http: OkHttpClient) {
    private fun request(path: String) = Request.Builder().url(base + path).header("Authorization", "Bearer $code")

    private suspend fun call(r: Request, client: OkHttpClient = http): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(r).execute().use { resp ->
            val text = resp.body.string()
            if (resp.code == 401) throw HostException("The PC refused the pairing code. Pair again.")
            if (!resp.isSuccessful) throw HostException(runCatching { JSONObject(text).getString("error") }.getOrNull() ?: "The PC answered ${resp.code}.")
            JSONObject(text)
        }
    }

    /** What the host says it is; needs no code. */
    suspend fun hello(): JSONObject = call(Request.Builder().url("$base/hello").build())

    /** Whether the host is there and takes the code, within a few seconds. */
    suspend fun check(): Boolean = runCatching {
        call(request("/check").build(), http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()).optBoolean("ok")
    }.getOrDefault(false)

    /**
     * Asks one question ([spec]) and hands each event the host streams to [onEvent], until the run is done. Cancelling the
     * coroutine stops the run on the PC too.
     */
    suspend fun run(spec: JSONObject, onEvent: suspend (JSONObject) -> Unit) {
        val r = request("/runs").post(spec.toString().toRequestBody(JSON)).build()
        val call: Call = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build().newCall(r)
        var runId: String? = null
        try {
            val resp = withContext(Dispatchers.IO) { call.execute() }
            resp.use {
                if (resp.code == 401) throw HostException("The PC refused the pairing code. Pair again.")
                if (resp.code == 409) throw HostException("The PC is answering another question.")
                if (!resp.isSuccessful) throw HostException("The PC answered ${resp.code}.")
                val source = resp.body.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                    if (!line.startsWith("data: ")) continue
                    val e = JSONObject(line.removePrefix("data: "))
                    if (e.optString("type") == "run") runId = e.optString("id")
                    onEvent(e)
                    if (e.optString("type") == "done") return
                }
            }
            throw HostException("The PC stopped answering.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            runId?.let { id -> withContext(NonCancellable) { runCatching { stop(id) } } }
            throw e
        }
    }

    /** The phone's answer to a tool call of run [runId]. */
    suspend fun answer(runId: String, callId: String, text: String, isError: Boolean) {
        call(request("/runs/$runId/tools/$callId").post(JSONObject().put("text", text).put("is_error", isError).toString().toRequestBody(JSON)).build())
    }

    suspend fun stop(runId: String) {
        call(request("/runs/$runId/stop").post("{}".toRequestBody(JSON)).build())
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val PORT = 8765

        /** "192.168.1.20" or "192.168.1.20:8765" or "my-pc.local" as the host's address, or null when it is not one. */
        fun baseUrl(address: String, scheme: String = "https"): String? {
            val a = address.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (a.isEmpty() || a.any { it.isWhitespace() || it == '/' || it == '@' }) return null
            val withPort = if (a.contains(':')) a else "$a:$PORT"
            val port = withPort.substringAfterLast(':').toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return "$scheme://$withPort"
        }

        fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

        /**
         * A client that trusts exactly the certificate whose SHA-256 is [pin]; with no pin (first contact, while pairing) it
         * trusts any certificate and tells [seen] which one it got, for the owner to compare with what the PC shows.
         */
        fun pinned(pin: String?, seen: (String) -> Unit = {}): OkHttpClient {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("Not a client.")
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    val fp = fingerprint(chain.firstOrNull() ?: throw CertificateException("No certificate."))
                    seen(fp)
                    if (pin != null && fp != pin) throw CertificateException("This is not the PC this phone paired with.")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            return OkHttpClient.Builder()
                .sslSocketFactory(tls.socketFactory, trust)
                // The pinned certificate is the identity; the address it is reached at can change with the network.
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
        }
    }
}
