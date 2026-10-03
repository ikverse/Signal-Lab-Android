package com.ikverse.signallab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Things the PC's Java accepts and Android refuses. The tests run on the PC, so a mistake of this kind passes every test and then
 * crashes on the phone (the Learn tab did, on a regular expression ending in a bare "}").
 */
class AndroidSafetyTest {
    /** The ways a pattern is written for Android's regex engine that the PC's does not enforce: a "}" or "{" that is neither escaped nor a repeat count. */
    private fun problems(pattern: String): List<String> {
        val found = ArrayList<String>()
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++ // the next character is escaped, whatever it is
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> {
                    val repeat = Regex("""\{\d+(,\d*)?\}""").matchAt(pattern, i)
                    if (repeat == null) found += "bare { at $i" else i += repeat.value.length - 1
                }
                c == '}' -> found += "bare } at $i"
            }
            i++
        }
        return found
    }

    @Test
    fun `the checker itself finds the mistake that crashed Learn and accepts good patterns`() {
        assertTrue(problems("""\{\{([A-Z0-9_]+)}}""").first().startsWith("bare }"))
        assertTrue(problems("""\{\{([A-Z0-9_]+)\}\}""").isEmpty())
        assertTrue(problems("""a{2}b{1,3}c{4,}""").isEmpty())
        assertTrue(problems("""[{}]+""").isEmpty())
        assertTrue(problems("""\d+(\.\d\d)0%$""").isEmpty())
        assertEquals(1, problems("""a{b""").size)
    }

    @Test
    fun `every regular expression in the app and the engine is written the way Android needs`() {
        val roots = listOf(File("src/main/kotlin"), File("../engine/src/main/kotlin"))
        val literal = Regex("""Regex\(\s*(?:\"\"\"(.*?)\"\"\"|\"((?:[^\"\\]|\\.)*)\")""", RegexOption.DOT_MATCHES_ALL)
        var checked = 0
        for (root in roots) {
            assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
            root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                for (m in literal.findAll(f.readText())) {
                    val raw = m.groups[1]?.value
                    val pattern = raw ?: m.groups[2]!!.value.replace("\\\\", "\\").replace("\\\"", "\"")
                    checked++
                    val bad = problems(pattern)
                    assertTrue("${f.name}: $pattern -> $bad", bad.isEmpty())
                }
            }
        }
        assertTrue("found only $checked patterns; the scan is not looking in the right place", checked >= 5)
    }
}
