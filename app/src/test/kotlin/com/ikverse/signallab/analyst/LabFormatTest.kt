package com.ikverse.signallab.analyst

import com.ikverse.signallab.engine.LabBlock
import com.ikverse.signallab.engine.LabCompare
import com.ikverse.signallab.engine.LabExit
import com.ikverse.signallab.engine.LabPatterns
import com.ikverse.signallab.engine.Timeframe
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading the patterns an answer suggests: what reads, what is refused and why, and the written form the record keeps. */
class LabFormatTest {
    private fun one(json: String) = LabFormat.read(JSONObject(json))

    private val good = """{"title": "RSI dip in an uptrend", "reason": "Dips bought above the 200 average beat random in 41 trades.",
        "charts": ["4h", "1h"], "when": [{"left": "rsi(14)", "is": "crosses_above", "right": "30"},
        {"left": "close", "is": "above", "right": "sma(200)"}], "exit": "hold(12)"}"""

    @Test
    fun `a well-formed suggestion becomes a pattern the engine can run`() {
        val s = one(good)
        val p = assertNotNull(s.pattern, s.problem)
        assertNull(s.problem)
        assertEquals("RSI dip in an uptrend", s.title)
        assertTrue(s.reason!!.startsWith("Dips"))
        assertEquals(setOf(Timeframe.H1, Timeframe.H4), p.timeframes)
        assertEquals(LabBlock.RSI, p.conditions[0].left.block)
        assertEquals(14, p.conditions[0].left.n)
        assertEquals(LabCompare.CROSSES_ABOVE, p.conditions[0].compare)
        assertEquals(30.0, p.conditions[0].right.value)
        assertEquals(LabExit.Hold(12), p.exit)
        assertEquals("RSI(14) crosses above 30, and close above SMA(200) on 1h and 4h; held 12 candles", p.summary)
    }

    @Test
    fun `the written form is fixed, so one pattern is always written one way and reads back the same`() {
        val written = assertNotNull(one(good).definition)
        assertEquals(
            """{"charts":["1h","4h"],"when":[{"left":"rsi(14)","is":"crosses_above","right":"30"},{"left":"close","is":"above","right":"sma(200)"}],"exit":"hold(12)"}""",
            written,
        )
        val reordered = one("""{"title": "Other name", "charts": ["1h", "4h"], "when": [{"left": "RSI(14)", "is": "crosses_above", "right": 30},
            {"left": "close", "is": "above", "right": "sma(200)"}], "exit": "hold(12)"}""")
        assertEquals(written, reordered.definition, "title, case, order of charts and a number written as a number do not change it")
        val back = assertNotNull(LabFormat.pattern(9, written))
        assertEquals(9L, back.id)
        assertEquals(written, LabFormat.definition(back))
        assertNull(LabFormat.pattern(9, "not json"))
    }

    @Test
    fun `everything outside the form is refused with the reason`() {
        fun problem(json: String): String = assertNotNull(one(json).problem, json).also { assertNull(one(json).pattern) }
        val cond = """{"left": "close", "is": "above", "right": "sma(50)"}"""
        assertTrue("chart size" in problem("""{"charts": ["2h"], "when": [$cond], "exit": "trail"}"""))
        assertTrue("\"charts\"" in problem("""{"charts": "1h", "when": [$cond], "exit": "trail"}"""))
        assertTrue("no chart" in problem("""{"charts": [], "when": [$cond], "exit": "trail"}"""))
        assertTrue("\"when\"" in problem("""{"charts": ["1h"], "exit": "trail"}"""))
        assertTrue("no conditions" in problem("""{"charts": ["1h"], "when": [], "exit": "trail"}"""))
        assertTrue("at most" in problem("""{"charts": ["1h"], "when": [$cond, $cond, $cond, $cond, $cond], "exit": "trail"}"""))
        assertTrue("not a building block" in problem("""{"charts": ["1h"], "when": [{"left": "macd(12)", "is": "above", "right": "0"}], "exit": "trail"}"""))
        assertTrue("not a building block" in problem("""{"charts": ["1h"], "when": [{"left": "price", "is": "above", "right": "0"}], "exit": "trail"}"""))
        assertTrue("needs a length" in problem("""{"charts": ["1h"], "when": [{"left": "sma", "is": "above", "right": "0"}], "exit": "trail"}"""))
        assertTrue("takes no length" in problem("""{"charts": ["1h"], "when": [{"left": "close(3)", "is": "above", "right": "0"}], "exit": "trail"}"""))
        assertTrue("from 2 to 200" in problem("""{"charts": ["1h"], "when": [{"left": "sma(500)", "is": "above", "right": "0"}], "exit": "trail"}"""))
        assertTrue("0.05" in problem("""{"charts": ["1h"], "when": [{"left": "return(24)", "is": "above", "right": "5%"}], "exit": "trail"}"""))
        assertTrue("not a comparison" in problem("""{"charts": ["1h"], "when": [{"left": "close", "is": ">", "right": "sma(50)"}], "exit": "trail"}"""))
        assertTrue("missing a side" in problem("""{"charts": ["1h"], "when": [{"left": "close", "is": "above"}], "exit": "trail"}"""))
        assertTrue("two numbers" in problem("""{"charts": ["1h"], "when": [{"left": "1", "is": "above", "right": "2"}], "exit": "trail"}"""))
        assertTrue("\"exit\"" in problem("""{"charts": ["1h"], "when": [$cond], "exit": "stop at 5%"}"""))
        assertTrue("1 to ${LabPatterns.MAX_HOLD}" in problem("""{"charts": ["1h"], "when": [$cond], "exit": "hold(0)"}"""))
    }

    @Test
    fun `suggestions are found in the marked code block, and a broken block says so`() {
        val answer = "Here are three ideas.\n\n```signal-lab-patterns\n[$good, {\"title\": \"Bad\", \"charts\": [\"9h\"], \"when\": [], \"exit\": \"trail\"}]\n```\n\nThat is all."
        val found = LabFormat.suggestions(answer)
        assertEquals(listOf("RSI dip in an uptrend", "Bad"), found.map { it.title })
        assertNotNull(found[0].pattern)
        assertNotNull(found[1].problem)

        assertEquals(1, LabFormat.suggestions("```json\n$good\n```").size, "a plain JSON block of conditions is read too")
        assertEquals(0, LabFormat.suggestions("```json\n{\"a\": 1}\n```").size, "but not any JSON")
        assertEquals(0, LabFormat.suggestions("No block here.").size)
        val broken = LabFormat.suggestions("```signal-lab-patterns\n[{\"title\": \n```").single()
        assertNull(broken.pattern)
        assertTrue(broken.problem!!.startsWith("The block could not be read"))
        val many = "```signal-lab-patterns\n[" + List(15) { good }.joinToString(",") + "]\n```"
        assertEquals(LabFormat.MAX_SUGGESTIONS, LabFormat.suggestions(many).size)
    }

    @Test
    fun `a long title is cut, and a missing one is named`() {
        val long = one("""{"title": "${"x".repeat(100)}", "charts": ["1h"], "when": [{"left": "close", "is": "above", "right": "sma(5)"}], "exit": "trail"}""")
        assertEquals(60, long.title.length)
        assertTrue(long.title.endsWith("…"))
        assertEquals("Untitled pattern", one("""{"charts": ["1h"], "when": [{"left": "close", "is": "above", "right": "sma(5)"}], "exit": "trail"}""").title)
    }

    @Test
    fun `the guide Claude is given names every building block and every comparison`() {
        for (b in LabBlock.entries.filter { it != LabBlock.NUMBER }) assertTrue((if (b.takesN) "${b.id}(N)" else b.id) in LabFormat.guide, b.id)
        for (c in LabCompare.entries) assertTrue(c.id in LabFormat.guide, c.id)
        assertTrue("```${LabFormat.TAG}" in LabFormat.guide)
        assertTrue("from ${LabPatterns.MIN_N} to ${LabPatterns.MAX_N}" in LabFormat.guide)
        assertTrue(LabFormat.guide in PromptCards.byId("suggest-patterns")!!.task)
    }
}
