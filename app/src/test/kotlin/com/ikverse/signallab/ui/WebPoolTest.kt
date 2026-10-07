package com.ikverse.signallab.ui

import android.content.Context
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The kept web views on real web views (Robolectric's): a view is built once, can be put into a screen again after it was taken out of
 * the last one without Android complaining that it still has a parent, and is released only when idle.
 */
@RunWith(RobolectricTestRunner::class)
class WebPoolTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val made = mutableListOf<WebPage>()
    private fun pool() = WebPool(context) { c, p -> made += p; KeptWeb(c, p) }

    @Test
    fun `a page's view is built once and the same one is handed out every time`() {
        val pool = pool()
        val first = pool.attach(WebPage.Learn)
        pool.detach(WebPage.Learn)
        val second = pool.attach(WebPage.Learn)
        assertSame(first, second)
        assertEquals(listOf(WebPage.Learn), made)
    }

    @Test
    fun `a view that was in one screen can go into the next without a parent in the way`() {
        val pool = pool()
        val oldScreen = FrameLayout(context)
        oldScreen.addView(pool.attach(WebPage.Chart))
        val view = oldScreen.getChildAt(0)
        // The new screen asks for it before the old one has let go: it is taken out of the old parent first.
        val newScreen = FrameLayout(context)
        newScreen.addView(pool.attach(WebPage.Chart))
        assertSame(view, newScreen.getChildAt(0))
        assertEquals(0, oldScreen.childCount)
        // The old screen lets go late: the view stays where the new screen has it.
        pool.detach(WebPage.Chart)
        assertSame(newScreen, view.parent)
        assertTrue(pool.inUse(WebPage.Chart))
    }

    @Test
    fun `when the last screen lets go the view is taken out of it and kept, ready to be put into another`() {
        val pool = pool()
        val screen = FrameLayout(context)
        screen.addView(pool.attach(WebPage.Learn))
        val view = screen.getChildAt(0)
        pool.detach(WebPage.Learn)
        assertNull(view.parent)
        assertEquals(0, screen.childCount)
        assertTrue(pool.has(WebPage.Learn))
        assertFalse(pool.inUse(WebPage.Learn))
        val another = FrameLayout(context)
        another.addView(pool.attach(WebPage.Learn))
        assertSame(view, another.getChildAt(0))
    }

    @Test
    fun `leaving the screen gives back the views not on show and keeps the ones that are`() {
        val pool = pool()
        pool.attach(WebPage.Chart)
        pool.attach(WebPage.Learn)
        pool.detach(WebPage.Learn)
        assertEquals(1, pool.releaseIdle())
        assertTrue(pool.has(WebPage.Chart))
        assertFalse(pool.has(WebPage.Learn))
        val rebuilt = pool.attach(WebPage.Learn)
        assertNotNull(rebuilt)
        assertEquals("a released view is built again only when it is next wanted", listOf(WebPage.Chart, WebPage.Learn, WebPage.Learn), made)
    }

    @Test
    fun `destroying the screen releases everything`() {
        val pool = pool()
        pool.attach(WebPage.Chart)
        pool.keep(WebPage.Learn)
        pool.releaseAll()
        assertFalse(pool.has(WebPage.Chart))
        assertFalse(pool.has(WebPage.Learn))
    }

    @Test
    fun `a view letting go after everything was released does nothing and does not fail`() {
        val pool = pool()
        pool.attach(WebPage.Chart)
        pool.releaseAll()
        pool.detach(WebPage.Chart)
        assertFalse(pool.has(WebPage.Chart))
    }

    @Test
    fun `a new view has not loaded yet, a page for each kind, and only the chart has a bridge to the app`() {
        val pool = pool()
        assertFalse(pool.keep(WebPage.Chart).loaded)
        assertEquals("file:///android_asset/chart/chart.html", WebPage.Chart.url)
        assertEquals("file:///android_asset/learn/page.html", WebPage.Learn.url)
    }

    @Test
    fun `warming the web engine up is harmless, and happens once`() {
        val pool = pool()
        pool.warmUp()
        pool.warmUp()
        assertTrue("it makes nothing the pool keeps", made.isEmpty())
    }
}

/** The same, through the screens: Learn's web view is built the first time it is opened and put back on every later visit. */
@RunWith(RobolectricTestRunner::class)
class WebPoolScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val made = mutableListOf<WebPage>()

    private fun click(t: String) {
        // A place behind More is reached through it.
        if (t.startsWith("nav-") && rule.onAllNodesWithTag(t).fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithTag("nav-More").performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithTag(t).performClick()
        rule.waitForIdle()
    }

    private fun exists(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()

    @Config(qualifiers = "w1200dp-h700dp")
    @Test
    fun `the chart and Learn are each built once however often the tabs are changed`() {
        val pool = WebPool(rule.activity) { c, p -> made += p; KeptWeb(c, p) }
        rule.setContent { SignalLabApp(FakeApp.full(), debug = false, webViews = true, webPool = pool) }
        rule.waitForIdle()
        assertTrue(exists("chart"))
        assertEquals(listOf(WebPage.Chart), made)
        repeat(3) {
            click("nav-Learn")
            assertTrue(exists("learn"))
            assertTrue(pool.inUse(WebPage.Learn))
            assertFalse("the chart is not on show on Learn", pool.inUse(WebPage.Chart))
            click("nav-Markets")
            assertTrue(exists("chart"))
            assertTrue(pool.inUse(WebPage.Chart))
            assertFalse(pool.inUse(WebPage.Learn))
        }
        assertEquals("three visits each, and one view each", listOf(WebPage.Chart, WebPage.Learn), made)
        // Leaving the app: Learn, which is not on show, is given back; the chart, which is, stays.
        assertEquals(1, pool.releaseIdle())
        assertTrue(pool.has(WebPage.Chart))
        assertFalse(pool.has(WebPage.Learn))
        click("nav-Learn")
        assertEquals("rebuilt when it is next wanted", listOf(WebPage.Chart, WebPage.Learn, WebPage.Learn), made)
    }

    @Config(qualifiers = "w1200dp-h700dp")
    @Test
    fun `without a pool from the activity a screen still works, with one of its own`() {
        rule.setContent { SignalLabApp(FakeApp.full(), debug = false, webViews = true) }
        rule.waitForIdle()
        assertTrue(exists("chart"))
        click("nav-Learn")
        assertTrue(exists("learn"))
        click("nav-Markets")
        assertTrue(exists("chart"))
    }
}
