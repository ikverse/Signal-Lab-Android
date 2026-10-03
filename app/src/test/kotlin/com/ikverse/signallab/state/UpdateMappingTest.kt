package com.ikverse.signallab.state

import com.ikverse.signallab.ui.UpdateStatus
import com.ikverse.signallab.ui.updateHeadline
import com.ikverse.signallab.update.AppVersion
import com.ikverse.signallab.update.Release
import com.ikverse.signallab.update.UpdateState
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateMappingTest {
    private val release = Release(AppVersion(0, 2, 0), "Adds the updater.", "https://github.com/x/a.apk", 1_000)

    @Test
    fun `each state maps to what the screen needs, and only an update on offer can be installed`() {
        val cases = listOf(
            UpdateState.Idle(null) to (UpdateStatus.IDLE to false),
            UpdateState.Checking to (UpdateStatus.CHECKING to false),
            UpdateState.UpToDate(5) to (UpdateStatus.UP_TO_DATE to false),
            UpdateState.Available(release, 5) to (UpdateStatus.AVAILABLE to true),
            UpdateState.Downloading(release, 10) to (UpdateStatus.DOWNLOADING to false),
            UpdateState.NeedsPermission(release, File("x")) to (UpdateStatus.NEEDS_PERMISSION to true),
            UpdateState.Handed(release) to (UpdateStatus.INSTALLER_OPEN to true),
            UpdateState.Failed("It broke.", release) to (UpdateStatus.FAILED to true),
            UpdateState.Failed("It broke.", null) to (UpdateStatus.FAILED to false),
        )
        for ((state, expected) in cases) {
            val ui = state.toUi()
            assertEquals(expected.first, ui.status, "$state")
            assertEquals(expected.second, ui.canInstall, "$state")
        }
    }

    @Test
    fun `an available update carries its version and notes`() {
        val ui = UpdateState.Available(release, 5).toUi()
        assertEquals("0.2.0", ui.version)
        assertEquals("Adds the updater.", ui.notes)
        assertEquals(5L, ui.checkedAt)
    }

    @Test
    fun `progress is the share downloaded, and never outside 0 to 1`() {
        assertEquals(0.25f, UpdateState.Downloading(release, 250).toUi().progress)
        assertEquals(1f, UpdateState.Downloading(release, 5_000).toUi().progress)
        assertEquals(0f, UpdateState.Downloading(release, 0).toUi().progress)
        assertNull(UpdateState.Available(release, 5).toUi().progress)
    }

    @Test
    fun `a failure shows its own sentence, and the permission and installer states explain what to do`() {
        assertEquals("It broke.", UpdateState.Failed("It broke.", null).toUi().message)
        assertTrue("Allow installs" in UpdateState.NeedsPermission(release, File("x")).toUi().message!!)
        assertTrue("installer is open" in UpdateState.Handed(release).toUi().message!!)
        assertNull(UpdateState.UpToDate(5).toUi().message)
    }

    @Test
    fun `the headline says what is happening in plain words`() {
        assertEquals("Not checked yet.", updateHeadline(UpdateState.Idle(null).toUi()))
        assertEquals("Checking GitHub…", updateHeadline(UpdateState.Checking.toUi()))
        assertEquals("You have the newest version.", updateHeadline(UpdateState.UpToDate(5).toUi()))
        assertEquals("Version 0.2.0 is available.", updateHeadline(UpdateState.Available(release, 5).toUi()))
        assertEquals("Downloading version 0.2.0: 25%", updateHeadline(UpdateState.Downloading(release, 250).toUi()))
        assertEquals("Version 0.2.0 is ready to install.", updateHeadline(UpdateState.Handed(release).toUi()))
        assertEquals("Version 0.2.0 could not be installed.", updateHeadline(UpdateState.Failed("x", release).toUi()))
        assertEquals("The update check did not finish.", updateHeadline(UpdateState.Failed("x", null).toUi()))
        assertFalse(updateHeadline(UpdateState.Idle(1_700_000_000_000L).toUi()).startsWith("Not checked"))
        assertTrue(updateHeadline(UpdateState.Idle(1_700_000_000_000L).toUi()).startsWith("Last checked "))
    }
}
