package com.ikverse.signallab.analyst

import com.ikverse.signallab.data.CostModel
import com.ikverse.signallab.data.LiveTrade
import com.ikverse.signallab.data.NewTrade
import com.ikverse.signallab.data.TradeExit
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.ExitReason
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.Watchlist
import com.ikverse.signallab.state.Scoring
import com.ikverse.signallab.ui.ScoreRowUi
import com.ikverse.signallab.ui.ScorecardUi
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/** A UTC midnight, so candle times read cleanly. */
private const val T0 = 1_700_006_400_000L

/** The data file, the questions and the reading of a shared answer: plain functions, checked on exactly what they produce. */
class AnalystTextTest {
    private fun trade(
        id: Long,
        variant: String = "donchian20_1h",
        symbol: String = "BTCUSDT",
        tf: Timeframe = Timeframe.H1,
        bar: Long = T0 + id * HOUR,
        regime: Int = 1,
        net: Double? = 0.01,
        excess: Double? = 0.004,
        reason: ExitReason = ExitReason.TARGET,
        closedAt: Long = bar + 6 * HOUR,
    ): LiveTrade {
        val t = NewTrade(variant, "donchian", symbol, tf, 0, bar, bar + tf.ms, regime, bar + tf.ms, 100.0, 104.0, 98.0, 24, bar + 25 * tf.ms,
            cost = 0.002, exitMode = "trail", atr = 1.5)
        val x = net?.let { TradeExit(closedAt, 100.0 * (1 + it), reason, 5, it + 0.002, it, it - (excess ?: 0.0), excess, 0.03, -0.01, 2) }
        return LiveTrade(id, t, bar + tf.ms, x, x?.let { closedAt })
    }

    private fun input(trades: List<LiveTrade>, now: Long = T0 + 400 * DAY, scorecard: ScorecardUi? = null) = SnapshotInput(
        now, "9.9.9", CostModel(), listOf(Watchlist(1, "My coins", listOf("BTCUSDT", "ETHUSDT"), true, setOf(Timeframe.H1, Timeframe.H4))),
        trades.sortedByDescending { it.trade.detectedAt },
        scorecard ?: Scoring.build(trades, trades.map { it.trade.variant to it.trade.tf }.distinct(), 12),
    )

    /** The lines of the table under [heading]. */
    private fun section(file: String, heading: String): List<String> =
        file.substringAfter("## $heading").substringBefore("\n## ").lines().filter { it.startsWith("| ") }

    private fun assertClean(file: String) {
        assertFalse("NaN" in file, "a NaN shows")
        assertFalse(Regex("""\bnull\b""").containsMatchIn(file), "a null shows")
    }

    // --- the data file

    @Test
    fun `an empty record still makes a whole file that says so`() {
        val file = Snapshot.build(input(emptyList()))
        assertTrue(file.startsWith("# Signal Lab data file\n"))
        for (h in listOf("How the numbers are made", "Watchlists", "Scorecard", "Recent against earlier", "By BTC regime", "How trades ended",
            "By coin", "Open trades (0)", "Latest closed trades (0 of 0)")) assertTrue("## $h" in file, h)
        assertTrue("No pattern has a trade yet." in file)
        assertTrue("12 so far" in file, "the bar's count of patterns tested")
        assertTrue("| My coins | on | 1h, 4h | 2: BTC, ETH |" in file)
        assertClean(file)
    }

    @Test
    fun `the scorecard, regimes, exits and coins carry the numbers of the trades`() {
        val trades = listOf(
            trade(1, net = 0.02, excess = 0.01, regime = 1),
            trade(2, net = -0.01, excess = -0.005, regime = 1, reason = ExitReason.STOP),
            trade(3, net = 0.005, excess = 0.002, regime = 0, reason = ExitReason.TIME),
            trade(4, net = null),
        )
        val file = Snapshot.build(input(trades))
        val row = section(file, "Scorecard").single { it.startsWith("| donchian20_1h ") }
        assertTrue("| 1h | 3 | 1 | 67% | +0.50% |" in row, row)
        assertEquals("| donchian20_1h | 1h | n=2, vs random +0.25% | n=1, vs random +0.20% | – |", section(file, "By BTC regime").single { "donchian" in it })
        val ended = section(file, "How trades ended").single { "donchian" in it }
        assertTrue("| trail | 1 | 1 | 1 | 5.0 | +3.00% | -1.00% | 2.0 |" in ended, ended)
        assertEquals("| BTC | 3 | 67% | +0.50% | +0.23% |", section(file, "By coin").single { it.startsWith("| BTC ") })
        assertTrue("## Open trades (1)" in file)
        assertTrue("## Latest closed trades (3 of 3)" in file)
        assertClean(file)
    }

    @Test
    fun `recent against earlier splits trades by when they closed`() {
        val now = T0 + 100 * DAY
        val trades = listOf(
            trade(1, closedAt = now - 2 * DAY), trade(2, closedAt = now - 3 * DAY),
            trade(3, closedAt = now - 10 * DAY),
            trade(4, closedAt = now - 40 * DAY), trade(5, closedAt = now - 50 * DAY), trade(6, closedAt = now - 60 * DAY),
        )
        val line = section(Snapshot.build(input(trades, now)), "Recent against earlier").single { "donchian" in it }
        assertEquals("| donchian20_1h | 1h | n=2, vs random +0.40% | n=1, vs random +0.40% | n=3, vs random +0.40% |", line)
    }

    @Test
    fun `only the latest closed trades are listed, newest first`() {
        val trades = (1L..130L).map { trade(it, closedAt = T0 + it * DAY) }
        val file = Snapshot.build(input(trades))
        assertTrue("## Latest closed trades (${Snapshot.LATEST_CLOSED} of 130)" in file)
        val rows = section(file, "Latest closed trades").drop(1)
        assertEquals(Snapshot.LATEST_CLOSED, rows.size)
        assertTrue(rows.first().startsWith("| ${Snapshot.utc(T0 + 130 * DAY)} "), rows.first())
    }

    private fun candles(n: Int, tf: Timeframe = Timeframe.H1): Candles {
        val t = LongArray(n) { T0 + it * tf.ms }
        val v = DoubleArray(n) { 100.0 + it }
        return Candles("BTCUSDT", tf, t, v, v, v, v, v, LongArray(n) { t[it] + tf.ms - 1 })
    }

    @Test
    fun `a trade to explain brings its candles with the signal, the entry and the exit marked`() {
        val t = trade(40, closedAt = T0 + 45 * HOUR + 10 * 60_000L)
        val other = trade(20, net = -0.02, excess = -0.01)
        val file = Snapshot.build(input(listOf(t, other)), TradeFocus(t, candles(100)))
        assertTrue("| Trade number | 40 |" in file)
        assertTrue("| Pattern | donchian20_1h: Breakout: close above the 20-candle high |" in file)
        val rows = section(file, "Candles around it").drop(1)
        assertEquals(Snapshot.CANDLES_BEFORE + 1 + 5 + Snapshot.CANDLES_AFTER, rows.size, "30 before the signal, the signal, to the exit, then 5 more")
        assertTrue(rows.single { "signal candle" in it }.startsWith("| ${Snapshot.utc(T0 + 40 * HOUR)} "))
        assertTrue(rows.single { "entry at its open" in it }.startsWith("| ${Snapshot.utc(T0 + 41 * HOUR)} "))
        assertTrue(rows.single { "exit" in it }.startsWith("| ${Snapshot.utc(T0 + 45 * HOUR)} "))
        assertEquals(1, section(file, "This pattern's other closed trades").drop(1).size)
        assertClean(file)
    }

    @Test
    fun `a trade whose candles are gone says so, and a long one shows a capped stretch`() {
        val t = trade(40)
        assertTrue("no longer kept" in Snapshot.build(input(listOf(t)), TradeFocus(t, null)))
        val long = trade(40, closedAt = T0 + 400 * HOUR)
        val file = Snapshot.build(input(listOf(long)), TradeFocus(long, candles(500)))
        assertEquals(Snapshot.MAX_CANDLES, section(file, "Candles around it").drop(1).size)
        assertTrue("lasted longer than the ${Snapshot.MAX_CANDLES} candles shown" in file)
    }

    @Test
    fun `the file never grows past its ceiling`() {
        val rows = (1..3_000).map { ScoreRowUi("v$it", "x".repeat(100), "1h", 0, 1, 0.5, 0.01, 0.0, 0.01, 1.0, "No verdict", "No verdict", false) }
        val file = Snapshot.build(input(emptyList(), scorecard = ScorecardUi(rows, 3_000)))
        assertTrue(file.length <= Snapshot.MAX_CHARS + 80, "${file.length}")
        assertTrue(file.endsWith("(The file was cut here at ${Snapshot.MAX_CHARS} characters.)\n"))
    }

    @Test
    fun `numbers read the same on every phone`() {
        assertEquals("+1.23%", Snapshot.pct(0.0123))
        assertEquals("-0.50%", Snapshot.pct(-0.005))
        assertEquals("0.10%", Snapshot.pct(0.001, signed = false))
        assertEquals("–", Snapshot.pct(Double.NaN))
        assertEquals("–", Snapshot.pct(null))
        assertEquals("65000", Snapshot.price(65_000.0))
        assertEquals("0.000012345678", Snapshot.price(0.000012345678))
        assertEquals("3200.5", Snapshot.price(3_200.5))
        assertEquals("2023-11-15 00:00", Snapshot.utc(T0))
    }

    // --- the questions

    private val tradeWords = Regex("""\b(buy|buys|buying|sell|sells|selling|bought|sold)\b""", RegexOption.IGNORE_CASE)

    @Test
    fun `every question asks for the marker line with its own title, and points at the data and the bar`() {
        for (c in PromptCards.all) {
            val p = PromptCards.prompt(c, 37)
            assertTrue("Begin your answer with this exact line: ${PromptCards.MARKER} ${c.title}\n" in p, c.id)
            assertTrue("The data below, from the heading \"Signal Lab data file\" on" in p, c.id)
            assertTrue("(37 so far)" in p, c.id)
            assertTrue(c.task in p, c.id)
            assertFalse("attached" in p, c.id)
            assertNull(tradeWords.find(p + c.title + c.description), c.id)
        }
    }

    @Test
    fun `the draft is the question with the data below it, whose heading the question names`() {
        val card = PromptCards.byId("weekly-review")!!
        val data = Snapshot.build(input(listOf(trade(1))))
        val draft = PromptCards.draft(card, 12, data)
        assertTrue(draft.startsWith(PromptCards.prompt(card, 12)))
        assertTrue(draft.endsWith(data))
        assertEquals(1, Regex("""(?m)^# Signal Lab data file$""").findAll(draft).count())
    }

    @Test
    fun `questions have their own ids and titles, and only explaining a trade needs one`() {
        assertEquals(PromptCards.all.size, PromptCards.all.map { it.id }.toSet().size)
        assertEquals(PromptCards.all.size, PromptCards.all.map { it.title }.toSet().size)
        assertEquals(listOf("explain-trade"), PromptCards.all.filter { it.needsTrade }.map { it.id })
        assertEquals("Weekly review", PromptCards.byId("weekly-review")!!.title)
        assertNull(PromptCards.byId("nothing"))
    }

    // --- reading an answer shared back

    @Test
    fun `an answer that starts with the marker is filed under its question, without the marker line`() {
        for (first in listOf("Signal Lab report: Weekly review", "**Signal Lab report: Weekly review**", "# Signal Lab report: Weekly review", "signal lab report: weekly review")) {
            val r = assertNotNull(ReportText.read("$first\n\n## Summary\nThree patterns helped."), first)
            assertEquals("weekly-review", r.card, first)
            assertEquals("## Summary\nThree patterns helped.", r.body, first)
        }
        val late = assertNotNull(ReportText.read("Here is the review.\n\nSignal Lab report: What's fading?\nBody"))
        assertEquals("fading", late.card, "a short preface before the marker is fine")
        assertEquals("What's fading?", late.title)
        val unknown = assertNotNull(ReportText.read("Signal Lab report: Something else\nBody"))
        assertNull(unknown.card)
        assertEquals("Something else", unknown.title)
    }

    @Test
    fun `any other text is kept whole, titled by its first line`() {
        val r = assertNotNull(ReportText.read("\r\n## A follow-up answer\r\nMore text"))
        assertNull(r.card)
        assertEquals("A follow-up answer", r.title)
        assertEquals("## A follow-up answer\nMore text", r.body)
        val long = assertNotNull(ReportText.read("y".repeat(200)))
        assertEquals(80, long.title.length)
        assertTrue(long.title.endsWith("…"))
        assertNull(ReportText.read("  \n \n"))
    }

    @Test
    fun `shared text is shown with its HTML made harmless and its code left alone`() {
        assertEquals("&lt;script>alert(1)&lt;/script>", ReportText.safe("<script>alert(1)</script>"))
        assertEquals("Under &lt;30 trades, `a<b` stays code", ReportText.safe("Under <30 trades, `a<b` stays code"))
        assertEquals("an open ` then &lt;img src=x>", ReportText.safe("an open ` then <img src=x>"), "a backtick that never closes is not code")
        val fenced = "```html\n<b>kept</b>\n```\n<b>escaped</b>"
        assertEquals("```html\n<b>kept</b>\n```\n&lt;b>escaped&lt;/b>", ReportText.safe(fenced))
        assertEquals("~~~\n<i>\n~~~", ReportText.safe("~~~\n<i>\n~~~"))
        assertEquals("[x](#alert(1))", ReportText.safe("[x](javascript:alert(1))"))
        val table = "| Pattern | Vs random |\n|---|---|\n| trend | +0.20% |"
        assertEquals(table, ReportText.safe(table))
    }
}
