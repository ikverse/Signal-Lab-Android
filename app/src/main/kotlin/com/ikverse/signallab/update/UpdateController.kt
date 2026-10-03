package com.ikverse.signallab.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File

sealed interface UpdateState {
    /** Nothing asked yet this run. [lastCheckedAt] is when the last successful check was, if there was one. */
    data class Idle(val lastCheckedAt: Long?) : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: Release, val checkedAt: Long) : UpdateState
    data class Downloading(val release: Release, val bytes: Long) : UpdateState

    /** Downloaded and checked; waiting for the user to allow installs from Signal Lab in Android's settings. */
    data class NeedsPermission(val release: Release, val file: File) : UpdateState

    /** Android's installer is open on the file. If the user backs out, the update is still on offer. */
    data class Handed(val release: Release) : UpdateState

    /** [release] is kept when trying again could work (a download that broke), and null when it could not (a file that failed the check). */
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/** Opens Android's own screens for installing. */
interface InstallLauncher {
    /** Whether Android lets this app hand a file to the installer. */
    fun canInstall(): Boolean

    /** Opens the page where the user allows installs from this app. */
    fun openPermission()

    /** Opens Android's installer on [file]; throws [UpdateException] if it cannot be opened. */
    fun launch(file: File)
}

/** The one thing the updater remembers between runs: when it last asked GitHub. */
interface UpdateMemory {
    suspend fun lastCheck(): Long
    suspend fun saveLastCheck(time: Long)
}

/**
 * Looks for a newer release about once a day and, when the user asks, downloads it, checks it and hands it to Android's
 * installer. It never installs by itself: Android's installer always asks first.
 */
class UpdateController(
    private val installed: AppVersion,
    private val source: UpdateSource,
    private val downloader: UpdateDownloader,
    private val inspector: ApkInspector,
    private val launcher: InstallLauncher,
    private val memory: UpdateMemory,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val busy = Mutex()
    private val current = MutableStateFlow<UpdateState>(UpdateState.Idle(null))
    val state: StateFlow<UpdateState> = current

    /** The quiet daily check: skipped if one succeeded in the last day, and a failure leaves no message (the next run tries again). */
    suspend fun checkIfDue() {
        if (now() - memory.lastCheck() < DAY_MS) return
        check(quiet = true)
    }

    /** The user asked. Always asks GitHub, and says so if that fails. */
    suspend fun checkNow() = check(quiet = false)

    private suspend fun check(quiet: Boolean) {
        if (!busy.tryLock()) return
        val before = current.value
        try {
            current.value = UpdateState.Checking
            when (val result = source.check(installed)) {
                is CheckResult.Newer -> {
                    memory.saveLastCheck(now())
                    current.value = UpdateState.Available(result.release, now())
                }
                CheckResult.UpToDate -> {
                    memory.saveLastCheck(now())
                    downloader.clear()
                    current.value = UpdateState.UpToDate(now())
                }
                is CheckResult.Failed -> current.value = if (quiet) restore(before) else UpdateState.Failed(result.message, null)
            }
        } catch (e: CancellationException) {
            current.value = restore(before)
            throw e
        } finally {
            busy.unlock()
        }
    }

    /** Download (unless already downloaded), check, and open Android's installer. Does nothing when no update is on offer. */
    suspend fun install() {
        val start = current.value
        val release = when (start) {
            is UpdateState.Available -> start.release
            is UpdateState.NeedsPermission -> start.release
            is UpdateState.Handed -> start.release
            is UpdateState.Failed -> start.release ?: return
            else -> return
        }
        if (!busy.tryLock()) return
        try {
            val file = (start as? UpdateState.NeedsPermission)?.file?.takeIf { it.exists() } ?: download(release) ?: return
            val problem = ApkCheck.problem(inspector.inspect(file), inspector.installed(), release.version.code)
            if (problem != null) {
                downloader.clear()
                current.value = UpdateState.Failed(problem, null)
                return
            }
            if (!launcher.canInstall()) {
                launcher.openPermission()
                current.value = UpdateState.NeedsPermission(release, file)
                return
            }
            try {
                launcher.launch(file)
                current.value = UpdateState.Handed(release)
            } catch (e: UpdateException) {
                current.value = UpdateState.Failed(e.message ?: "Android's installer could not be opened.", release)
            }
        } catch (e: CancellationException) {
            current.value = UpdateState.Available(release, now())
            throw e
        } finally {
            busy.unlock()
        }
    }

    /** The file, or null with [current] already showing why not. */
    private suspend fun download(release: Release): File? {
        current.value = UpdateState.Downloading(release, 0)
        return try {
            downloader.download(release) { bytes -> current.value = UpdateState.Downloading(release, bytes) }
        } catch (e: UpdateException) {
            current.value = UpdateState.Failed(e.message ?: "The download failed.", release)
            null
        }
    }

    private fun restore(before: UpdateState): UpdateState = if (before is UpdateState.Checking) UpdateState.Idle(null) else before

    companion object {
        const val DAY_MS = 86_400_000L
    }
}
