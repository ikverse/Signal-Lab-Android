package com.ikverse.signallab.update

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun `a tag with or without v, and a debug suffix, reads as the same version`() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("1.2.3"))
        assertEquals(AppVersion(0, 1, 0), AppVersion.parse("0.1.0-debug"))
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("  V1.2.3 "))
    }

    @Test
    fun `anything that is not major dot minor dot patch is not a version`() {
        for (bad in listOf(null, "", "v", "1.2", "1.2.3.4", "latest", "v1.2.x", "1..3", "-1.2.3", "1.2.3beta")) {
            assertNull(AppVersion.parse(bad), "should not parse: $bad")
        }
    }

    @Test
    fun `a minor or patch of 100 or more is refused, because it would collide in the version code`() {
        assertNull(AppVersion.parse("1.100.0"))
        assertNull(AppVersion.parse("1.0.100"))
        assertEquals(AppVersion(1, 99, 99), AppVersion.parse("1.99.99"))
    }

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(AppVersion(0, 10, 0) > AppVersion(0, 9, 0))
        assertTrue(AppVersion(0, 1, 10) > AppVersion(0, 1, 9))
        assertTrue(AppVersion(1, 0, 0) > AppVersion(0, 99, 99))
        assertTrue(AppVersion(0, 1, 0) == AppVersion(0, 1, 0))
        assertTrue(AppVersion(0, 1, 0) < AppVersion(0, 1, 1))
    }

    @Test
    fun `the code is the one app build gradle derives from the version`() {
        assertEquals(10_203L, AppVersion(1, 2, 3).code)
        assertEquals(100L, AppVersion(0, 1, 0).code)
        // The formula in app/build.gradle.kts, kept here as an independent copy: if either changes, this fails.
        val (major, minor, patch) = "3.14.15".split(".").map { it.toInt() }
        assertEquals((major * 10_000 + minor * 100 + patch).toLong(), AppVersion.parse("3.14.15")!!.code)
    }

    @Test
    fun `it prints as a plain version`() {
        assertEquals("0.2.0", AppVersion(0, 2, 0).toString())
    }
}
