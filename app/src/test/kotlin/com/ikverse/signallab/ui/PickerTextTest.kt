package com.ikverse.signallab.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The words under each coin in the picker, and under each ranking: plain functions, so every wording is held exactly. */
class PickerTextTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private fun offer(value: Double?, volume: Double = 500e6) = OfferUi("SOLUSDT", "SOL", volume, value)

    @Test
    fun `how long ago a coin was listed is said in whole days`() {
        assertEquals("listed today", Fmt.listedAgo(now, now))
        assertEquals("listed today", Fmt.listedAgo(now - day + 1, now))
        assertEquals("listed 1 day ago", Fmt.listedAgo(now - day, now))
        assertEquals("listed 1 day ago", Fmt.listedAgo(now - 2 * day + 1, now))
        assertEquals("listed 12 days ago", Fmt.listedAgo(now - 12 * day - 5_000, now))
        assertEquals("listed 30 days ago", Fmt.listedAgo(now - 30 * day, now))
        assertEquals("listed today", Fmt.listedAgo(now + day, now), "a phone clock a little behind never says a negative age")
    }

    @Test
    fun `each ranking words its number first and the volume after it`() {
        val vol = "volume 500.0M USDT"
        assertEquals("24h $vol", Fmt.offerLine(offer(null), PickSource.VOLUME, PickWindow.H24, now))
        assertEquals("24h $vol", Fmt.offerLine(offer(0.5), PickSource.VOLUME, PickWindow.H24, now), "volume ignores any number it is handed")
        assertEquals("+18.30% in 24h · $vol", Fmt.offerLine(offer(0.183), PickSource.GAINERS, PickWindow.H24, now))
        assertEquals("+3.00% in 1h · $vol", Fmt.offerLine(offer(0.03), PickSource.GAINERS, PickWindow.H1, now))
        assertEquals("-7.25% in 7d · $vol", Fmt.offerLine(offer(-0.0725), PickSource.LOSERS, PickWindow.D7, now))
        assertEquals("12.3K trades in 24h · $vol", Fmt.offerLine(offer(12_345.0), PickSource.ACTIVE, PickWindow.H24, now))
        assertEquals("950 trades in 24h · $vol", Fmt.offerLine(offer(950.0), PickSource.ACTIVE, PickWindow.H24, now))
        assertEquals("44.4% range in 24h · $vol", Fmt.offerLine(offer(0.444), PickSource.VOLATILE, PickWindow.H24, now))
        assertEquals("listed 10 days ago · $vol", Fmt.offerLine(offer((now - 10 * day).toDouble()), PickSource.NEW, PickWindow.H24, now))
    }

    @Test
    fun `a coin with no number under a ranking falls back to its volume rather than showing nothing`() {
        for (s in PickSource.entries) assertEquals("24h volume 2.00B USDT", Fmt.offerLine(offer(null, 2e9), s, PickWindow.H24, now))
    }

    @Test
    fun `every ranking is explained in a sentence, and the gainers and losers name their window`() {
        for (s in PickSource.entries) assertTrue(Fmt.sourceNote(s, PickWindow.H24).endsWith("."), s.name)
        assertEquals(PickSource.entries.size, PickSource.entries.map { Fmt.sourceNote(it, PickWindow.H24) }.toSet().size)
        for (w in PickWindow.entries) {
            assertTrue(Fmt.sourceNote(PickSource.GAINERS, w).contains("over ${w.label}"))
            assertTrue(Fmt.sourceNote(PickSource.LOSERS, w).contains("over ${w.label}"))
        }
        assertTrue(Fmt.sourceNote(PickSource.GAINERS, PickWindow.H24).contains("1M USDT"), "the volume floor is said where it applies")
        assertTrue(Fmt.sourceNote(PickSource.NEW, PickWindow.H24).contains("30 days"))
    }

    @Test
    fun `the rankings keep their order and labels, and the windows are the three asked for`() {
        assertEquals(listOf("Volume", "Gainers", "Losers", "Most trades", "Volatile", "New"), PickSource.entries.map { it.label })
        assertEquals(listOf("1h", "24h", "7d"), PickWindow.entries.map { it.label })
    }

    @Test
    fun `the dim levels go from darkest to softest and none is ever zero`() {
        assertEquals(listOf("Very dim", "Dim", "Soft"), DimLevel.entries.map { it.label })
        val b = DimLevel.entries.map { it.brightness }
        assertEquals(b.sorted(), b)
        assertTrue(b.all { it > 0f && it < 1f }, "0 turns some screens off, and 1 is not dimmed")
    }
}
