package com.ikverse.signallab.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The parts of the updater that are Android's: what the manifest asks for, the file provider, and the installer's intents.
 *
 * The two tests that make a content link for a real file skip themselves on Windows: the file provider matches paths with "/"
 * only, so it cannot match a Windows path. Android and the CI runner use "/", and run them.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidUpdateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the manifest asks to hand files to the installer`() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue("android.permission.REQUEST_INSTALL_PACKAGES" in info.requestedPermissions.orEmpty())
    }

    @Test
    fun `the provider is declared for the download folder only, private to the app`() {
        val info = app.packageManager.resolveContentProvider(AndroidInstallLauncher.authority(app), PackageManager.GET_META_DATA)!!
        assertEquals("androidx.core.content.FileProvider", info.name)
        assertEquals(false, info.exported)
        assertEquals(true, info.grantUriPermissions)
        val xml = info.loadXmlMetaData(app.packageManager, "android.support.FILE_PROVIDER_PATHS")
        val roots = ArrayList<Triple<String, String?, String?>>()
        var event = xml.next()
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name != "paths") {
                roots += Triple(xml.name, xml.getAttributeValue(null, "name"), xml.getAttributeValue(null, "path"))
            }
            event = xml.next()
        }
        // One shared folder, in the cache, named for what it is: never the whole cache, the database folder, or the file system.
        assertEquals(listOf(Triple<String, String?, String?>("cache-path", "updates", "updates/")), roots)
        assertEquals("updates", AndroidInstallLauncher.downloadDir(app).name)
    }

    @Test
    fun `the provider hands out files from the download folder and from nowhere else`() {
        assumeTrue("the file provider only matches '/' paths", File.separatorChar == '/')
        val dir = AndroidInstallLauncher.downloadDir(app).also { it.mkdirs() }
        val file = File(dir, "signal-lab-0.2.0.apk").also { it.writeBytes(byteArrayOf(1)) }
        val uri = FileProvider.getUriForFile(app, AndroidInstallLauncher.authority(app), file)
        assertEquals(AndroidInstallLauncher.authority(app), uri.authority)
        assertTrue(uri.path!!.endsWith("signal-lab-0.2.0.apk"))

        // The record database, the candles and the rest of the app's private files are not shared.
        val private = app.getDatabasePath("signal_lab.db")
        assertFailsWith<IllegalArgumentException> { FileProvider.getUriForFile(app, AndroidInstallLauncher.authority(app), private) }
        val otherCache = File(app.cacheDir, "other.bin").also { it.writeBytes(byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { FileProvider.getUriForFile(app, AndroidInstallLauncher.authority(app), otherCache) }
    }

    @Test
    fun `the installer is opened on the file, as an app package, with permission to read it`() {
        assumeTrue("the file provider only matches '/' paths", File.separatorChar == '/')
        val dir = AndroidInstallLauncher.downloadDir(app).also { it.mkdirs() }
        val file = File(dir, "signal-lab-0.2.0.apk").also { it.writeBytes(byteArrayOf(1)) }
        AndroidInstallLauncher(app).launch(file)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals(AndroidInstallLauncher.authority(app), intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `the page for allowing installs is this app's own`() {
        AndroidInstallLauncher(app).openPermission()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${app.packageName}", intent.data.toString())
    }

    @Test
    fun `downloads go to the cache, which Android may reclaim`() {
        assertEquals(File(app.cacheDir, "updates"), AndroidInstallLauncher.downloadDir(app))
    }

    @Test
    fun `a file that is not an app is not readable as one`() {
        val junk = File(app.cacheDir, "junk.apk").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(null, AndroidApkInspector(app).inspect(junk))
    }
}
