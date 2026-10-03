package com.ikverse.signallab.state

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The Learn pages read the way the phone reads them: out of the app's own assets, through a real context. */
@RunWith(RobolectricTestRunner::class)
class LearnAssetsTest {
    @Test
    fun `every page loads from the app's assets with its numbers filled in`() {
        val fromAssets = LearnCatalog(ApplicationProvider.getApplicationContext<android.content.Context>())
        val fromFiles = LearnCatalog { path -> File("src/main/assets/$path").readText() }
        assertEquals(LearnIndex.entries.size, fromAssets.pages.size)
        for ((a, f) in fromAssets.pages.zip(fromFiles.pages)) {
            assertEquals(f.id, a.id)
            assertEquals("${a.id}: the assets and the source files differ", f.markdown, a.markdown)
            assertFalse("${a.id} still has a placeholder", "{{" in a.markdown)
            assertTrue("${a.id} is empty", a.markdown.length > 300)
        }
    }
}
