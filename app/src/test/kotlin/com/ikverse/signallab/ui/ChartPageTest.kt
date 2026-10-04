package com.ikverse.signallab.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.test.fail

/** What the app sends the chart page and keeps for it, and the page's own behaviour (run in Node, against a stand-in for the chart library). */
class ChartPageTest {
    private val chart = ChartUi("BTCUSDT", "1h", listOf(CandleUi(1000, 1.0, 2.0, 0.5, 1.5, 10.0)), emptyList())

    // --- what the page is sent

    @Test
    fun `the page is told which coin and chart size it is showing, so it can keep drawings apart`() {
        assertEquals("BTCUSDT|1h", JSONObject(ChartJson.build(chart)).getString("key"))
        assertEquals("ETHUSDT|15m", JSONObject(ChartJson.build(ChartUi("ETHUSDT", "15m", emptyList(), emptyList()))).getString("key"))
        assertEquals("BTCUSDT|1h", ChartMemory.key("BTCUSDT", "1h"))
    }

    @Test
    fun `the drawings for that chart travel with the data, as an array the page can read`() {
        assertEquals(0, JSONObject(ChartJson.build(chart)).getJSONArray("drawings").length())
        val drawn = """[{"name":"segment","points":[{"timestamp":1000,"value":1.25},{"timestamp":2000,"value":1.75}]}]"""
        val sent = JSONObject(ChartJson.build(chart, drawn)).getJSONArray("drawings")
        assertEquals(1, sent.length())
        assertEquals("segment", sent.getJSONObject(0).getString("name"))
        assertEquals(1.75, sent.getJSONObject(0).getJSONArray("points").getJSONObject(1).getDouble("value"), 0.0)
    }

    @Test
    fun `drawings that are not an array are never sent to the page`() {
        for (bad in listOf("", "nonsense", "{\"a\":1}", "[1,")) {
            assertEquals("for [$bad]", 0, JSONObject(ChartJson.build(chart, bad)).getJSONArray("drawings").length())
        }
    }

    // --- what is kept

    private val line = """[{"name":"rayLine","points":[{"timestamp":1,"value":2}]}]"""

    @Test
    fun `drawings are kept per coin and chart size and come back as they went in`() {
        val m = ChartMemory()
        assertEquals("[]", m.drawings("BTCUSDT|1h"))
        m.remember("BTCUSDT|1h", line)
        assertEquals(line, m.drawings("BTCUSDT|1h"))
        assertEquals("another chart size has its own", "[]", m.drawings("BTCUSDT|4h"))
        assertEquals("another coin has its own", "[]", m.drawings("ETHUSDT|1h"))
    }

    @Test
    fun `an empty report forgets the chart's drawings, and a report that does not read changes nothing`() {
        val m = ChartMemory()
        m.remember("K", line)
        m.remember("K", "not json")
        m.remember("K", "{\"x\":1}")
        assertEquals(line, m.drawings("K"))
        m.remember("K", "[]")
        assertEquals("[]", m.drawings("K"))
    }

    @Test
    fun `what the page reports is kept under the chart it names`() {
        val m = ChartMemory()
        m.rememberReport("""{"key":"BTCUSDT|1h","drawings":$line}""")
        assertEquals(JSONArray(line).toString(), m.drawings("BTCUSDT|1h"))
        m.rememberReport("""{"key":"BTCUSDT|1h","drawings":[]}""")
        assertEquals("[]", m.drawings("BTCUSDT|1h"))
        m.rememberReport("""{"key":"A|1h","drawings":$line}""")
        m.rememberReport("garbage"); m.rememberReport("""{"drawings":[]}""") ; m.rememberReport("""{"key":"A|1h"}""")
        assertEquals("reports that do not read are ignored", JSONArray(line).toString(), m.drawings("A|1h"))
    }

    // --- the page itself

    private fun node(): String? = try {
        val p = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) "node" else null
    } catch (_: java.io.IOException) {
        null
    }

    @Test
    fun `the chart page resizes with its box, builds its indicators and drawings, and keeps them per chart`() {
        val script = File("src/test/js/chart-page.test.mjs")
        assertTrue("run from the app module: ${script.absolutePath}", script.isFile)
        val node = node()
        // A machine without Node skips this; the build server has it, and fails rather than skips if it somehow does not.
        if (node == null && System.getenv("CI") != null) fail("Node is needed to test the chart page and was not found")
        assumeTrue("Node is not installed, so the chart page was not tested", node != null)
        val p = ProcessBuilder(node, "--test", script.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("the chart page tests did not finish", p.waitFor(120, TimeUnit.SECONDS))
        assertFalse("exit ${p.exitValue()}\n$out", p.exitValue() != 0)
    }
}
