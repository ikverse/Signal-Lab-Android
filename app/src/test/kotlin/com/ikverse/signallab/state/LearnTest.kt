package com.ikverse.signallab.state

import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Signals
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.VariantLabels
import com.ikverse.signallab.engine.WatchlistRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Learn pages: every pattern the scan can produce has one, and every number a page quotes is the number the engine uses. */
class LearnTest {
    private val dir = File("src/main/assets/learn/pages")
    private val catalog = LearnCatalog { path -> File("src/main/assets/$path").readText() }

    /** A short run of candles for any chart, just enough for `perCoin` to name every pattern that chart runs. */
    private fun candles(tf: Timeframe): Candles {
        val n = 40
        val t = LongArray(n) { it * tf.ms }
        val v = DoubleArray(n) { 100.0 + it }
        return Candles("TESTUSDT", tf, t, v, v, v, v, v, LongArray(n) { t[it] + tf.ms - 1 })
    }

    @Test
    fun `every page the index lists exists, and no page is left out of the index`() {
        assertTrue(dir.absolutePath, dir.isDirectory)
        val onDisk = dir.listFiles { f -> f.extension == "md" }!!.map { it.nameWithoutExtension }.toSet()
        assertEquals(LearnIndex.entries.map { it.id }.toSet(), onDisk)
        assertEquals(LearnIndex.entries.size, LearnIndex.entries.map { it.id }.toSet().size)
        assertEquals(LearnIndex.entries.size, LearnIndex.entries.map { it.title }.toSet().size)
    }

    @Test
    fun `every pattern the scan can produce, on every chart, has a page that exists`() {
        val ids = LearnIndex.entries.map { it.id }.toSet()
        for (tf in Timeframe.entries) {
            val names = Signals.perCoin(candles(tf)).keys.map { it.name }
            assertTrue(names.isNotEmpty())
            for (name in names) {
                val page = LearnIndex.pageFor(name)
                assertNotNull("$name has no page", page)
                assertTrue("$name -> $page", page in ids)
                assertEquals(page, catalog.pageForVariant(name))
            }
        }
        // The ranking pattern comes from the list, not from one coin.
        val ranking = Signals.compute(mapOf("A" to candles(Timeframe.D1))).keys.map { it.name } + "xsmom2w_1h" + "xsmom3w_4h"
        for (name in ranking) assertNotNull(name, LearnIndex.pageFor(name))
        assertNull(LearnIndex.pageFor("something_else_1h"))
    }

    @Test
    fun `every pattern also has a plain-words description for notifications and rows`() {
        for (tf in Timeframe.entries) {
            for (k in Signals.perCoin(candles(tf)).keys) assertTrue(k.name, VariantLabels.describe(k.name) != k.name)
        }
    }

    @Test
    fun `no page is left with a placeholder, a hole or a missing number`() {
        assertEquals(LearnIndex.entries.size, catalog.pages.size)
        for (p in catalog.pages) {
            assertFalse("${p.id} still has {{", "{{" in p.markdown)
            assertFalse("${p.id} still has }}", "}}" in p.markdown)
            assertFalse("${p.id} shows a null", "null" in p.markdown)
            assertFalse("${p.id} shows NaN", "NaN" in p.markdown)
            assertTrue("${p.id} is empty", p.markdown.length > 300)
            assertTrue("${p.id} must start with its title line", p.markdown.startsWith("# "))
            assertEquals("${p.id} must have exactly one top heading", 1, Regex("(?m)^# ").findAll(p.markdown).count())
        }
    }

    @Test
    fun `every placeholder in a page has a value, and every value is used somewhere`() {
        val values = LearnValues.map()
        val used = HashSet<String>()
        for (e in LearnIndex.entries) {
            for (m in Regex("""\{\{([A-Z0-9_]+)}}""").findAll(File(dir, "${e.id}.md").readText())) {
                assertTrue("${e.id} uses ${m.groupValues[1]}, which has no value", m.groupValues[1] in values)
                used.add(m.groupValues[1])
            }
        }
        val unused = values.keys - used
        assertTrue("values no page uses: $unused", unused.isEmpty())
    }

    @Test
    fun `a placeholder with no value fails loudly instead of printing a hole`() {
        val failure = runCatching { LearnValues.resolve("Costs {{NO_SUCH_THING}}") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("NO_SUCH_THING"))
    }

    @Test
    fun `the numbers a page quotes are the numbers the scan uses`() {
        val v = LearnValues.map()
        assertEquals("20 and 50", v["TREND_WINDOWS"])
        assertEquals("10, 20, 55 and 100", v["DONCHIAN_LOOKBACKS"])
        assertEquals("1, 2 and 4", v["TSMOM_WEEKS"])
        assertEquals("20", v["TSMOM_QUANTILE_PCT"])
        assertEquals("365", v["TSMOM_HISTORY_DAYS"])
        assertEquals("2 and 3", v["XS_WEEKS"])
        assertEquals("20", v["XS_TOP_PCT"])
        assertEquals("8", v["XS_MIN_COINS"])
        assertEquals("1, 2 and 4", v["FADE_HOURS"])
        assertEquals("2.5", v["FADE_SIGMA"])
        assertEquals("30", v["FADE_VOL_DAYS"])
        assertEquals("20", v["DAY_MOM_TOP_PCT"])
        assertEquals("90", v["DAY_MOM_HISTORY_DAYS"])
        assertEquals("14", v["BREAKOUT_DAYS"])
        assertEquals("14", v["ATR_WINDOW"])
        assertEquals("2.0", v["TRAIL_STOP_ATR"])
        assertEquals("1.0", v["TRAIL_ACTIVATE_ATR"])
        assertEquals("2.0", v["TRAIL_DISTANCE_ATR"])
        assertEquals("3.0", v["LEARNED_STOP_ATR"])
        assertEquals("20", v["LEARN_MIN_SIGNALS"])
        assertEquals("6", v["LEARN_FALLBACK_BARS"])
        assertEquals("0.10", v["FEE_PCT"])
        assertEquals("0.20", v["ROUND_TRIP_PCT"])
        assertEquals("20", v["RANDOM_DRAWS"])
        assertEquals("200", v["REGIME_MA_DAYS"])
        assertEquals("3.0", v["EDGE_T"])
        assertEquals("-2.0", v["LOSING_T"])
        assertEquals("5", v["ALPHA_PCT"])
        assertEquals(listOf("30", "100", "300"), listOf(v["TIER_1"], v["TIER_2"], v["TIER_3"]))
        assertEquals("5", v["PUMP_RISE_PCT"])
        assertEquals("5", v["PUMP_MINUTES"])
        assertEquals("10", v["PUMP_VOLUME_MULTIPLE"])
        assertEquals("4", v["PUMP_NORMAL_HOURS"])
        assertEquals("3", v["VOLUME_SPIKE_MULTIPLE"])
        assertEquals("30", v["VOLUME_SPIKE_DAYS"])
        assertEquals("30", v["NEW_COIN_DAYS"])
        assertEquals("30", v["MAX_COINS_LIST"])
        assertEquals("150", v["MAX_COINS_ACTIVE"])
        assertEquals("10", v["MAX_1M"])
        assertEquals("30", v["MAX_5M"])
        // And the long ones are built from the engine's own tables, one entry per chart.
        for (tf in Timeframe.entries) {
            assertTrue(v.getValue("TRAIL_CAPS").contains("${tf.label}: ${EngineConfig.trailCapBars(tf)} candles"))
            assertTrue(v.getValue("RANDOM_WINDOWS").contains("${tf.label}: ${EngineConfig.randomWindowDays(tf)} days"))
            assertTrue(v.getValue("HISTORY_KEPT").contains("${tf.label}: ${DataConfig.historyDays(tf)} days"))
        }
        assertEquals(WatchlistRules.MAX_ACTIVE_COINS.toString(), v["MAX_COINS_ACTIVE"])
    }

    @Test
    fun `the default fee a page quotes is what the settings screen and the engine start from`() {
        assertEquals(0.001, EngineConfig.DEFAULT_FEE_PER_SIDE, 0.0)
        assertTrue(catalog.pages.single { it.id == "costs" }.markdown.contains("0.10% each way"))
    }

    @Test
    fun `a link from one page goes to a page that exists`() {
        val ids = LearnIndex.entries.map { it.id }.toSet()
        for (p in catalog.pages) {
            for (m in Regex("""\(learn:([a-z-]+)\)""").findAll(p.markdown)) assertTrue("${p.id} links to ${m.groupValues[1]}", m.groupValues[1] in ids)
        }
    }

    @Test
    fun `every page says it is research and not advice, and the pattern pages name how a trade ends`() {
        for (p in catalog.pages) assertTrue("${p.id} has no not-advice line", p.markdown.contains("Research, not financial advice"))
        for (e in LearnIndex.entries.filter { it.group == LearnIndex.PATTERNS }) {
            val text = catalog.pages.single { it.id == e.id }.markdown
            for (heading in listOf("## What it looks for", "## Where it runs", "## How a paper trade ends", "## Honest limits")) {
                assertTrue("${e.id} lacks $heading", heading in text)
            }
            assertTrue("${e.id} gives no evidence rating", Regex("""\*\*Evidence: [^*]+\*\*""").containsMatchIn(text))
        }
    }

    @Test
    fun `a page names only the charts its pattern really runs on`() {
        // The chart table on the sizes page must say what the engine does: one row per pattern, and the engine runs it on those charts only.
        fun runsOn(prefix: String): Set<String> =
            Timeframe.entries.filter { tf -> Signals.perCoin(candles(tf)).keys.any { it.name.startsWith(prefix) } }.map { it.label }.toSet()
        val text = catalog.pages.single { it.id == "chart-sizes" }.markdown
        fun row(name: String) = text.lines().first { it.startsWith("| $name |") }.split("|")[2].trim()
        assertEquals("every size", row("Trend"))
        assertEquals(Timeframe.entries.map { it.label }.toSet(), runsOn("trend_ma"))
        assertEquals(Timeframe.entries.map { it.label }.toSet(), runsOn("donchian"))
        assertEquals(Timeframe.entries.map { it.label }.toSet(), runsOn("bullish_harami"))
        assertEquals(Timeframe.entries.map { it.label }.toSet(), runsOn("bullish_hikkake"))
        assertEquals(setOf("4h", "1d"), runsOn("tsmom"))
        assertEquals("4h and 1d", row("Momentum (1 to 4 weeks)"))
        assertEquals(setOf("1h"), runsOn("fade"))
        assertEquals("1h", row("Drop fade"))
        assertEquals(setOf("30m", "1h"), runsOn("intraday_mom_"))
        assertEquals("30m and 1h", row("Day momentum"))
        assertEquals(setOf("15m", "30m", "1h"), runsOn("intraday_breakout_"))
        assertEquals("15m, 30m and 1h", row("Intraday breakout"))
        // Ranking works on charts of an hour or more.
        val ranked = Timeframe.entries.filter { tf -> Signals.xsMomentum(mapOf("A" to candles(tf))).isNotEmpty() }.map { it.label }
        assertEquals(listOf("1h", "4h", "1d"), ranked)
        assertEquals("1h, 4h and 1d", row("Ranking within your list"))
    }
}
