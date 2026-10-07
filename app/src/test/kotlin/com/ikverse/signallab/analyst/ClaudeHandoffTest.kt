package com.ikverse.signallab.analyst

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Handing a question to the Claude app, and taking its answer back: what the manifest allows and what the share carries. */
@RunWith(RobolectricTestRunner::class)
class ClaudeHandoffTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun installClaude() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = ClaudeHandoff.CLAUDE_PACKAGE })
    }

    @Test
    fun `the app can see whether the Claude app is there`() {
        assertFalse(ClaudeHandoff.installed(app))
        installClaude()
        assertTrue(ClaudeHandoff.installed(app))
    }

    @Test
    fun `the manifest takes shared text in, so Claude's answer can be shared back`() {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName)
        val found = app.packageManager.queryIntentActivities(share, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(listOf("com.ikverse.signallab.MainActivity"), found.map { it.activityInfo.name })
    }

    @Test
    fun `with Claude installed the question and the data go straight to it as one draft, with no file`() {
        installClaude()
        val intent = ClaudeHandoff.intent(app, Handoff("The question\n# Signal Lab data file"))
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(ClaudeHandoff.CLAUDE_PACKAGE, intent.`package`)
        assertEquals("text/plain", intent.type)
        assertEquals("The question\n# Signal Lab data file", intent.getStringExtra(Intent.EXTRA_TEXT))
        // The Claude app drops the text of a share that carries a file, so none is sent.
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        assertNull(intent.clipData)
    }

    @Test
    fun `without Claude the question opens Android's share menu instead`() {
        val intent = ClaudeHandoff.intent(app, Handoff("The question"))
        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        @Suppress("DEPRECATION")
        val inner = assertNotNull(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertNull(inner.`package`)
        assertEquals("The question", inner.getStringExtra(Intent.EXTRA_TEXT))
    }
}
