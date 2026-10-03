package com.ikverse.signallab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sums and the saved form behind resizable panels. */
class PanesTest {
    private val d = PaneMath.DIVIDER

    @Test
    fun `usual sizes are kept when they fit`() {
        assertEquals(280f to 320f, PaneMath.fit(1000f, 280f, 320f, coinsHidden = false, detailsHidden = false))
    }

    @Test
    fun `sizes too big for the screen are cut back and the chart keeps its minimum`() {
        val (c, w) = PaneMath.fit(820f, 600f, 600f, coinsHidden = false, detailsHidden = false)
        assertTrue(c >= PaneMath.MIN_SIDE && w >= PaneMath.MIN_SIDE)
        assertEquals(820f - 2 * d - PaneMath.MIN_CHART, c + w, 0.001f)
    }

    @Test
    fun `a hidden panel takes nothing and frees its room for the other`() {
        assertEquals(0f to 320f, PaneMath.fit(1000f, 280f, 320f, coinsHidden = true, detailsHidden = false))
        assertEquals(280f to 0f, PaneMath.fit(1000f, 280f, 320f, coinsHidden = false, detailsHidden = true))
        assertEquals(0f to 0f, PaneMath.fit(1000f, 280f, 320f, coinsHidden = true, detailsHidden = true))
        // With the other one hidden, a big panel may use all the room that leaves the chart its minimum.
        assertEquals(0f to 1000f - 2 * d - PaneMath.MIN_CHART, PaneMath.fit(1000f, 280f, 900f, coinsHidden = true, detailsHidden = false))
    }

    @Test
    fun `a panel below its minimum is raised to it, even on a screen too narrow for everything`() {
        assertEquals(PaneMath.MIN_SIDE to PaneMath.MIN_SIDE, PaneMath.fit(1000f, 50f, 10f, coinsHidden = false, detailsHidden = false))
        val (c, w) = PaneMath.fit(300f, 280f, 320f, coinsHidden = false, detailsHidden = false)
        assertEquals(PaneMath.MIN_SIDE, c, 0f)
        assertEquals(PaneMath.MIN_SIDE, w, 0f)
    }

    @Test
    fun `one panel next to a chart`() {
        assertEquals(220f, PaneMath.fitOne(700f, 220f, false, PaneMath.MIN_SIDE, PaneMath.MIN_CHART, d), 0f)
        assertEquals(0f, PaneMath.fitOne(700f, 220f, true, PaneMath.MIN_SIDE, PaneMath.MIN_CHART, d), 0f)
        assertEquals(700f - d - PaneMath.MIN_CHART, PaneMath.fitOne(700f, 900f, false, PaneMath.MIN_SIDE, PaneMath.MIN_CHART, d), 0f)
        assertEquals(PaneMath.MIN_SIDE, PaneMath.fitOne(700f, 10f, false, PaneMath.MIN_SIDE, PaneMath.MIN_CHART, d), 0f)
    }

    @Test
    fun `a drag moves a panel by exactly the distance, held between its limits`() {
        assertEquals(300f, PaneMath.dragged(280f, 20f, max = 500f), 0f)
        assertEquals(260f, PaneMath.dragged(280f, -20f, max = 500f), 0f)
        assertEquals(PaneMath.MIN_SIDE, PaneMath.dragged(280f, -999f, max = 500f), 0f)
        assertEquals(500f, PaneMath.dragged(280f, 999f, max = 500f), 0f)
        // A limit below the minimum cannot push a panel under it.
        assertEquals(PaneMath.MIN_SIDE, PaneMath.dragged(200f, 50f, max = 10f), 0f)
    }

    @Test
    fun `nothing changed means nothing stored`() {
        val l = PaneLayout()
        assertEquals("", l.encode())
        assertEquals(280f, l.size("wide.coins", 280f), 0f)
        assertFalse(l.isHidden("wide.coins"))
    }

    @Test
    fun `sizes and hidden panels are written out and read back exactly`() {
        val l = PaneLayout()
        l.set("wide.coins", 301.5f)
        l.set("wide.details", 222f)
        l.toggle("medium.coins")
        val text = l.encode()
        assertEquals("wide.coins=301.5;wide.details=222.0;!medium.coins", text)
        val back = PaneLayout()
        back.restore(text)
        assertEquals(301.5f, back.size("wide.coins", 0f), 0f)
        assertEquals(222f, back.size("wide.details", 0f), 0f)
        assertTrue(back.isHidden("medium.coins"))
        assertFalse(back.isHidden("wide.coins"))
        assertEquals(text, back.encode())
    }

    @Test
    fun `hiding twice shows again, and a size is not lost by hiding`() {
        val l = PaneLayout()
        l.set("wide.coins", 250f)
        l.toggle("wide.coins")
        assertTrue(l.isHidden("wide.coins"))
        assertEquals(250f, l.size("wide.coins", 0f), 0f)
        l.toggle("wide.coins")
        assertFalse(l.isHidden("wide.coins"))
        assertEquals(250f, l.size("wide.coins", 0f), 0f)
    }

    @Test
    fun `damaged text is ignored piece by piece and null changes nothing`() {
        val l = PaneLayout()
        l.set("wide.coins", 250f)
        l.restore(null)
        assertEquals(250f, l.size("wide.coins", 0f), 0f)
        l.restore(";;=;a=abc;b=-5;c=0;d=NaN;e=Infinity;!;ok=100.0;!gone")
        assertEquals(100f, l.size("ok", 0f), 0f)
        assertTrue(l.isHidden("gone"))
        for (k in listOf("a", "b", "c", "d", "e")) assertEquals(-1f, l.size(k, -1f), 0f)
        // Restoring replaces what was there.
        assertEquals(-1f, l.size("wide.coins", -1f), 0f)
        l.restore("")
        assertEquals("", l.encode())
    }
}
