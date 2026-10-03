package com.ikverse.signallab.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** A published release that can be installed over this app. */
data class Release(val version: AppVersion, val notes: String, val apkUrl: String, val apkBytes: Long)

sealed interface CheckResult {
    /** The newest release is this version or older. */
    data object UpToDate : CheckResult
    data class Newer(val release: Release) : CheckResult
    data class Failed(val message: String) : CheckResult
}

/** Where the app asks whether a newer version exists. Behind an interface so the rest can be tried without a network. */
interface UpdateSource {
    suspend fun check(installed: AppVersion): CheckResult
}

/**
 * Reads the latest release of the app's own GitHub repository. Draft and pre-release builds are not offered
 * (GitHub's "latest" never returns them), and a download is only ever followed to GitHub's own hosts.
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val latestUrl: String = LATEST_URL,
    private val trustedHost: (HttpUrl) -> Boolean = ::isGitHubHost,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateSource {

    override suspend fun check(installed: AppVersion): CheckResult = withContext(io) {
        try {
            val request = Request.Builder().url(latestUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SignalLab-Android")
                .build()
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> CheckResult.Failed("No release of Signal Lab has been published yet.")
                    response.code == 403 || response.code == 429 ->
                        CheckResult.Failed("GitHub is limiting requests from this network. Try again in an hour.")
                    !response.isSuccessful -> CheckResult.Failed("GitHub answered with error ${response.code}. Try again later.")
                    else -> read(response.body.string(), installed)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            CheckResult.Failed("Could not reach GitHub. Check the connection and try again.")
        }
    }

    private fun read(body: String, installed: AppVersion): CheckResult {
        val json = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return CheckResult.Failed("GitHub's answer could not be read.")
        }
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return CheckResult.UpToDate
        val tag = json.optString("tag_name")
        val version = AppVersion.parse(tag) ?: return CheckResult.Failed("The newest release is named \"$tag\", which is not a version number, so it was skipped.")
        if (version <= installed) return CheckResult.UpToDate

        val assets = json.optJSONArray("assets")
        val apks = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }.filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        val apk = apks.firstOrNull { it.optString("name").startsWith("signal-lab", ignoreCase = true) } ?: apks.firstOrNull()
            ?: return CheckResult.Failed("Version $version is published but has no app file attached yet. Try again later.")

        val url = parseUrl(apk.optString("browser_download_url"))
        if (url == null || !trustedHost(url)) return CheckResult.Failed("The download for version $version points outside GitHub, so it was refused.")
        val size = apk.optLong("size", -1L)
        if (size <= 0L) return CheckResult.Failed("Version $version has an app file of unknown size, so it was refused.")
        return CheckResult.Newer(Release(version, json.optString("body").trim(), url.toString(), size))
    }

    private fun parseUrl(text: String): HttpUrl? = try {
        text.toHttpUrl()
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/ikverse/Signal-Lab-Android/releases/latest"

        /** HTTPS, and a host GitHub serves release files from. */
        fun isGitHubHost(url: HttpUrl): Boolean =
            url.isHttps && (url.host == "github.com" || url.host.endsWith(".github.com") || url.host.endsWith(".githubusercontent.com"))
    }
}
