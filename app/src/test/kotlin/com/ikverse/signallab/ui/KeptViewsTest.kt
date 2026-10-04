package com.ikverse.signallab.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The rules for views that are kept between visits: made once, never released while someone has them on show, always released when idle. */
class KeptViewsTest {
    private class Thing(val name: String)

    private val created = mutableListOf<String>()
    private val destroyed = mutableListOf<String>()
    private val views = KeptViews<String, Thing>({ k -> created += k; Thing(k) }, { destroyed += it.name })

    @Test
    fun `a view is made once and the same one comes back every time`() {
        val a = views.keep("chart")
        assertSame(a, views.keep("chart"))
        assertSame(a, views.use("chart"))
        assertEquals(listOf("chart"), created)
        assertTrue(views.has("chart"))
        assertFalse(views.has("learn"))
        assertEquals(listOf("chart"), created, "asking whether one exists makes nothing")
    }

    @Test
    fun `keeping a view is not using it, and using it is counted`() {
        views.keep("chart")
        assertFalse(views.inUse("chart"))
        views.use("chart")
        views.use("chart")
        assertTrue(views.inUse("chart"))
        assertFalse(views.stopUsing("chart"), "one of two users let go: it is still on show")
        assertTrue(views.inUse("chart"))
        assertTrue(views.stopUsing("chart"), "the last one let go")
        assertFalse(views.inUse("chart"))
    }

    @Test
    fun `letting go of a view nobody had never leaves it owing a user`() {
        assertTrue(views.stopUsing("chart"))
        assertTrue(views.stopUsing("chart"))
        views.use("chart")
        assertTrue(views.inUse("chart"), "one use is one use, whatever came before")
        assertTrue(views.stopUsing("chart"))
    }

    @Test
    fun `releasing the idle ones destroys exactly those, once, and leaves the ones on show`() {
        views.use("chart")
        views.keep("learn")
        assertEquals(1, views.releaseIdle())
        assertEquals(listOf("learn"), destroyed)
        assertTrue(views.has("chart"))
        assertFalse(views.has("learn"))
        assertEquals(0, views.releaseIdle(), "nothing left to give back")
        assertEquals(listOf("learn"), destroyed, "and nothing destroyed twice")
    }

    @Test
    fun `a view released while idle is made again, as a new one, the next time it is wanted`() {
        val first = views.keep("learn")
        views.releaseIdle()
        val second = views.use("learn")
        assertNotSame(first, second)
        assertEquals(listOf("learn", "learn"), created)
    }

    @Test
    fun `a screen that is rebuilt takes the view before the old one lets go, and the view is never idle in between`() {
        val v = views.use("chart")
        val again = views.use("chart") // the new screen
        assertSame(v, again)
        assertFalse(views.stopUsing("chart"), "the old screen lets go, the new one still has it")
        assertEquals(0, views.releaseIdle(), "so a release at that moment takes nothing from it")
        assertTrue(destroyed.isEmpty())
        assertTrue(views.stopUsing("chart"))
        assertEquals(1, views.releaseIdle())
    }

    @Test
    fun `releasing everything destroys what is on show too and forgets who was using it`() {
        views.use("chart")
        views.keep("learn")
        views.releaseAll()
        assertEquals(setOf("chart", "learn"), destroyed.toSet())
        assertEquals(2, destroyed.size)
        assertFalse(views.has("chart"))
        assertFalse(views.inUse("chart"))
        views.use("chart")
        assertEquals(listOf("chart", "learn", "chart"), created, "after it is all gone a view is made afresh")
    }

    @Test
    fun `each key has its own view and its own users`() {
        views.use("chart")
        views.use("learn")
        views.stopUsing("learn")
        assertTrue(views.inUse("chart"))
        assertFalse(views.inUse("learn"))
        assertEquals(1, views.releaseIdle())
        assertEquals(listOf("learn"), destroyed)
    }
}
