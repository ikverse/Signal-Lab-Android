package com.ikverse.signallab.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Something went wrong that the user can be told about in one sentence. */
class UpdateException(message: String) : Exception(message)

interface UpdateDownloader {
    /** Downloads [release]'s app file and returns it once it is complete; throws [UpdateException] otherwise. */
    suspend fun download(release: Release, onProgress: (Long) -> Unit): File

    /** Deletes whatever an earlier download left behind. */
    fun clear()
}

/**
 * Saves the release's file under [dir]. It is written under a temporary name and renamed only when every byte the release
 * said it has has arrived, so a file that exists under its real name is always complete.
 */
class ApkDownloader(
    private val http: OkHttpClient,
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateDownloader {

    override suspend fun download(release: Release, onProgress: (Long) -> Unit): File = withContext(io) {
        if (release.apkBytes <= 0L || release.apkBytes > MAX_BYTES) throw UpdateException("The update file is an unexpected size, so it was not downloaded.")
        clear()
        if (!dir.mkdirs() && !dir.isDirectory) throw UpdateException("There is nowhere on this phone to save the update.")
        val part = File(dir, "signal-lab-${release.version}.apk.part")
        val done = File(dir, "signal-lab-${release.version}.apk")
        try {
            val request = Request.Builder().url(release.apkUrl).header("User-Agent", "SignalLab-Android").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException("GitHub answered the download with error ${response.code}.")
                var total = 0L
                response.body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > release.apkBytes) throw UpdateException("The download is larger than the release says, so it was stopped.")
                            output.write(buffer, 0, n)
                            onProgress(total)
                        }
                    }
                }
                if (total != release.apkBytes) throw UpdateException("The download stopped early ($total of ${release.apkBytes} bytes). Try again.")
            }
            if (!part.renameTo(done)) throw UpdateException("The downloaded file could not be saved.")
            done
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: UpdateException) {
            part.delete()
            throw e
        } catch (_: IOException) {
            part.delete()
            throw UpdateException("The download was interrupted. Check the connection and try again.")
        }
    }

    override fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** An app this size is not what this release process makes; refuse before filling the phone. */
        const val MAX_BYTES = 200L * 1024 * 1024
    }
}
