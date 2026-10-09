package com.ikverse.signallab.sync

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A file in the shared folder: its id, its name, and when it last changed (Drive's RFC 3339 time, compared as text). */
data class RemoteFile(val id: String, val name: String, val modified: String)

/** Where devices leave their records for each other. Drive in the app; a map in tests. */
interface RemoteFolder {
    suspend fun list(): List<RemoteFile>
    suspend fun download(id: String): ByteArray
    /** Makes the file and returns its id. */
    suspend fun create(name: String, bytes: ByteArray): String
    suspend fun update(id: String, bytes: ByteArray)
    /** The Google account the folder belongs to, for Settings to show. */
    suspend fun accountEmail(): String?
}

/** Drive said no to the token: the user signed out of Google, or took the app's access away. */
class SyncAuthException(message: String) : IOException(message)

/**
 * The app's hidden folder in the user's Google Drive (`appDataFolder`), through Drive's REST API. Only this app can see the folder,
 * and the token only reaches this folder, never the user's own files. [token] gives the current access token; [refreshToken] is asked
 * once when Drive turns a token down, and returns a new one or throws.
 */
class DriveFolder(
    private val http: OkHttpClient,
    private val token: suspend () -> String,
    private val refreshToken: suspend () -> String = token,
    private val api: String = "https://www.googleapis.com",
) : RemoteFolder {

    override suspend fun list(): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val url = "$api/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("fields", "nextPageToken,files(id,name,modifiedTime)")
                .addQueryParameter("pageSize", "100")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val o = JSONObject(String(call { Request.Builder().url(url).get() }))
            val files = o.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out += RemoteFile(f.getString("id"), f.getString("name"), f.optString("modifiedTime"))
            }
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    override suspend fun download(id: String): ByteArray =
        call { Request.Builder().url("$api/drive/v3/files/$id?alt=media").get() }

    override suspend fun create(name: String, bytes: ByteArray): String {
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder")).toString()
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody(JSON))
            .addPart(bytes.toRequestBody(BINARY))
            .build()
        val o = JSONObject(String(call { Request.Builder().url("$api/upload/drive/v3/files?uploadType=multipart&fields=id").post(body) }))
        return o.getString("id")
    }

    override suspend fun update(id: String, bytes: ByteArray) {
        call { Request.Builder().url("$api/upload/drive/v3/files/$id?uploadType=media").patch(bytes.toRequestBody(BINARY)) }
    }

    override suspend fun accountEmail(): String? {
        val o = JSONObject(String(call { Request.Builder().url("$api/drive/v3/about?fields=user(emailAddress)").get() }))
        return o.optJSONObject("user")?.optString("emailAddress")?.ifEmpty { null }
    }

    /** Sends the request with the token, once more with a fresh one if Drive turns it down, and returns the body of a success. */
    private suspend fun call(request: () -> Request.Builder): ByteArray {
        var bearer = token()
        repeat(2) { attempt ->
            http.newCall(request().header("Authorization", "Bearer $bearer").build()).await().use { r ->
                if (r.code == 401 && attempt == 0) {
                    bearer = refreshToken()
                    return@repeat
                }
                val bytes = r.body.bytes()
                if (r.isSuccessful) return bytes
                val message = runCatching { JSONObject(String(bytes)).getJSONObject("error").getString("message") }.getOrNull() ?: "HTTP ${r.code}"
                if (r.code == 401 || r.code == 403 && "insufficient" in message.lowercase()) throw SyncAuthException("Google Drive refused access: $message")
                throw IOException("Google Drive answered ${r.code}: $message")
            }
        }
        throw SyncAuthException("Google Drive refused access.")
    }

    private companion object {
        val JSON = "application/json; charset=UTF-8".toMediaType()
        val BINARY = "application/octet-stream".toMediaType()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
