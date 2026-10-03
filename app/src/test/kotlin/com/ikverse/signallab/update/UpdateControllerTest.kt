package com.ikverse.signallab.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    private val installedVersion = AppVersion(0, 1, 0)
    private val installedFacts = ApkFacts("com.ikverse.signallab", installedVersion.code, setOf("aa11"))
    private val newer = Release(AppVersion(0, 2, 0), "Notes", "https://github.com/x/a.apk", 10)

    private class Source(var result: CheckResult) : UpdateSource {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun check(installed: AppVersion): CheckResult {
            calls++
            gate?.await()
            return result
        }
    }

    private class Downloader : UpdateDownloader {
        var failure: UpdateException? = null
        var calls = 0
        var cleared = 0
        var gate: CompletableDeferred<Unit>? = null
        val dir: File = File.createTempFile("upd", "").also { it.delete(); it.mkdirs() }
        override suspend fun download(release: Release, onProgress: (Long) -> Unit): File {
            calls++
            onProgress(5)
            gate?.await()
            failure?.let { throw it }
            return File(dir, "signal-lab-${release.version}.apk").also { it.writeBytes(ByteArray(release.apkBytes.toInt())) }
        }

        override fun clear() {
            cleared++
        }
    }

    private class Inspector(var archive: ApkFacts?, val installed: ApkFacts) : ApkInspector {
        override fun installed() = installed
        override fun inspect(file: File) = archive
    }

    private class Launcher(var allowed: Boolean = true) : InstallLauncher {
        val log = ArrayList<String>()
        var failure: UpdateException? = null
        override fun canInstall() = allowed
        override fun openPermission() { log += "permission" }
        override fun launch(file: File) {
            failure?.let { throw it }
            log += "launch ${file.name}"
        }
    }

    private class Memory(var last: Long = 0) : UpdateMemory {
        override suspend fun lastCheck() = last
        override suspend fun saveLastCheck(time: Long) { last = time }
    }

    private class Rig(val c: UpdateController, val source: Source, val downloader: Downloader, val inspector: Inspector, val launcher: Launcher, val memory: Memory, val clock: LongArray)

    private fun rig(
        result: CheckResult = CheckResult.Newer(newer), last: Long = 0, now: Long = 10 * UpdateController.DAY_MS,
        archive: ApkFacts? = ApkFacts("com.ikverse.signallab", newer.version.code, setOf("aa11")), canInstall: Boolean = true,
    ): Rig {
        val source = Source(result)
        val downloader = Downloader()
        val inspector = Inspector(archive, installedFacts)
        val launcher = Launcher(canInstall)
        val memory = Memory(last)
        val clock = longArrayOf(now)
        return Rig(UpdateController(installedVersion, source, downloader, inspector, launcher, memory) { clock[0] }, source, downloader, inspector, launcher, memory, clock)
    }

    // --- checking --------------------------------------------------------------------------------

    @Test
    fun `a newer release shows as available and the check is remembered`() = runTest {
        val r = rig()
        r.c.checkNow()
        val s = assertIs<UpdateState.Available>(r.c.state.value)
        assertEquals(newer, s.release)
        assertEquals(r.clock[0], r.memory.last)
    }

    @Test
    fun `up to date is remembered, and clears an old download`() = runTest {
        val r = rig(CheckResult.UpToDate)
        r.c.checkNow()
        assertIs<UpdateState.UpToDate>(r.c.state.value)
        assertEquals(r.clock[0], r.memory.last)
        assertEquals(1, r.downloader.cleared)
    }

    @Test
    fun `the daily check does nothing inside a day`() = runTest {
        val r = rig(last = 10 * UpdateController.DAY_MS - 1_000)
        r.c.checkIfDue()
        assertEquals(0, r.source.calls)
        assertIs<UpdateState.Idle>(r.c.state.value)
    }

    @Test
    fun `the daily check asks once a day has passed`() = runTest {
        val r = rig(last = 10 * UpdateController.DAY_MS - UpdateController.DAY_MS)
        r.c.checkIfDue()
        assertEquals(1, r.source.calls)
        assertIs<UpdateState.Available>(r.c.state.value)
    }

    @Test
    fun `a daily check that fails is silent and is tried again next time`() = runTest {
        val r = rig(CheckResult.Failed("Could not reach GitHub."))
        r.c.checkIfDue()
        assertIs<UpdateState.Idle>(r.c.state.value)
        assertEquals(0L, r.memory.last)
        r.c.checkIfDue()
        assertEquals(2, r.source.calls)
    }

    @Test
    fun `a check the user asked for that fails says why, and does not count as a check`() = runTest {
        val r = rig(CheckResult.Failed("Could not reach GitHub."), last = 5)
        r.c.checkNow()
        val s = assertIs<UpdateState.Failed>(r.c.state.value)
        assertEquals("Could not reach GitHub.", s.message)
        assertNull(s.release)
        assertEquals(5L, r.memory.last)
    }

    @Test
    fun `check now asks even when the daily check just ran`() = runTest {
        val r = rig(last = 10 * UpdateController.DAY_MS)
        r.c.checkNow()
        assertEquals(1, r.source.calls)
    }

    @Test
    fun `a daily check that fails keeps an update that was already on offer`() = runTest {
        val r = rig()
        r.c.checkNow()
        r.source.result = CheckResult.Failed("down")
        r.memory.last = 0
        r.c.checkIfDue()
        assertIs<UpdateState.Available>(r.c.state.value)
    }

    @Test
    fun `a second check while one is running is ignored`() = runTest(StandardTestDispatcher()) {
        val r = rig()
        r.source.gate = CompletableDeferred()
        val first = launch { r.c.checkNow() }
        advanceUntilIdle()
        assertIs<UpdateState.Checking>(r.c.state.value)
        r.c.checkNow()
        assertEquals(1, r.source.calls)
        r.source.gate!!.complete(Unit)
        advanceUntilIdle()
        first.join()
        assertIs<UpdateState.Available>(r.c.state.value)
    }

    // --- installing --------------------------------------------------------------------------------

    @Test
    fun `install downloads, checks, and opens the installer`() = runTest {
        val r = rig()
        r.c.checkNow()
        r.c.install()
        assertIs<UpdateState.Handed>(r.c.state.value)
        assertEquals(listOf("launch signal-lab-0.2.0.apk"), r.launcher.log)
        assertEquals(1, r.downloader.calls)
    }

    @Test
    fun `install does nothing when no update is on offer`() = runTest {
        val r = rig()
        r.c.install()
        assertEquals(0, r.downloader.calls)
        r.c.checkNow()
        r.source.result = CheckResult.UpToDate
        r.c.checkNow()
        r.c.install()
        assertEquals(0, r.downloader.calls)
        assertTrue(r.launcher.log.isEmpty())
    }

    @Test
    fun `a failed download keeps the update on offer so it can be tried again`() = runTest {
        val r = rig()
        r.c.checkNow()
        r.downloader.failure = UpdateException("The download stopped early.")
        r.c.install()
        val failed = assertIs<UpdateState.Failed>(r.c.state.value)
        assertEquals("The download stopped early.", failed.message)
        assertEquals(newer, failed.release)
        assertTrue(r.launcher.log.isEmpty())

        r.downloader.failure = null
        r.c.install()
        assertIs<UpdateState.Handed>(r.c.state.value)
        assertEquals(2, r.downloader.calls)
    }

    @Test
    fun `a file signed with another key is never handed to the installer, and cannot be retried`() = runTest {
        val r = rig(archive = ApkFacts("com.ikverse.signallab", newer.version.code, setOf("bb22")))
        r.c.checkNow()
        r.c.install()
        val failed = assertIs<UpdateState.Failed>(r.c.state.value)
        assertTrue("different key" in failed.message, failed.message)
        assertNull(failed.release)
        assertTrue(r.launcher.log.isEmpty())
        assertTrue(r.downloader.cleared >= 1)
        r.c.install()
        assertEquals(1, r.downloader.calls)
    }

    @Test
    fun `a file that is not the version its release promised is refused`() = runTest {
        val r = rig(archive = ApkFacts("com.ikverse.signallab", newer.version.code + 100, setOf("aa11")))
        r.c.checkNow()
        r.c.install()
        assertTrue("not the version" in assertIs<UpdateState.Failed>(r.c.state.value).message)
        assertTrue(r.launcher.log.isEmpty())
    }

    @Test
    fun `a file Android cannot read is refused`() = runTest {
        val r = rig(archive = null)
        r.c.checkNow()
        r.c.install()
        assertIs<UpdateState.Failed>(r.c.state.value)
        assertTrue(r.launcher.log.isEmpty())
    }

    @Test
    fun `without permission to install, the settings page opens and the file is kept for the next tap`() = runTest {
        val r = rig(canInstall = false)
        r.c.checkNow()
        r.c.install()
        val s = assertIs<UpdateState.NeedsPermission>(r.c.state.value)
        assertEquals(listOf("permission"), r.launcher.log)

        r.launcher.allowed = true
        r.c.install()
        assertIs<UpdateState.Handed>(r.c.state.value)
        assertEquals(1, r.downloader.calls, "the file was already downloaded and checked")
        assertEquals("launch ${s.file.name}", r.launcher.log.last())
    }

    @Test
    fun `if the installer cannot be opened, the update stays on offer`() = runTest {
        val r = rig()
        r.launcher.failure = UpdateException("This phone has no installer that can open the update.")
        r.c.checkNow()
        r.c.install()
        val failed = assertIs<UpdateState.Failed>(r.c.state.value)
        assertEquals(newer, failed.release)
    }

    @Test
    fun `after the installer is opened and the user backs out, install can be tapped again`() = runTest {
        val r = rig()
        r.c.checkNow()
        r.c.install()
        r.c.install()
        assertIs<UpdateState.Handed>(r.c.state.value)
        assertEquals(2, r.downloader.calls)
        assertEquals(2, r.launcher.log.size)
    }

    @Test
    fun `download progress is reported as it arrives`() = runTest(StandardTestDispatcher()) {
        val r = rig()
        r.c.checkNow()
        r.downloader.gate = CompletableDeferred()
        val job = launch { r.c.install() }
        advanceUntilIdle()
        val s = assertIs<UpdateState.Downloading>(r.c.state.value)
        assertEquals(5L, s.bytes)
        r.downloader.gate!!.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertFalse(r.c.state.value is UpdateState.Downloading)
    }

    @Test
    fun `cancelling a download puts the update back on offer`() = runTest(StandardTestDispatcher()) {
        val r = rig()
        r.c.checkNow()
        r.downloader.gate = CompletableDeferred()
        val job = launch { r.c.install() }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        assertIs<UpdateState.Available>(r.c.state.value)
        // and it is not stuck busy
        r.downloader.gate = null
        r.c.install()
        assertIs<UpdateState.Handed>(r.c.state.value)
    }
}
