package com.ikverse.signallab.engine

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signal Lab 2's strategy and evaluation code must not come to depend on Signal Lab 1's signals, lab
 * language, live scan, labels or learned exit: those are deleted with the old app in milestone 6. This
 * reads the source of the new packages and fails on any mention of an old piece.
 */
class LayeringTest {
    private val engineSource = File("src/main/kotlin/com/ikverse/signallab/engine")

    /** Signal Lab 1's pieces, by the names code would use to reach them. */
    private val signalLab1 = Regex(
        """\b(Signals|Candlesticks|LabPatterns|LabPattern|LabBlock|LabCompare|LabCondition|LabOperand|LabExit|LiveScan|VariantLabels|LearnedExit)\b""",
    )

    /** The lines of [file] that mention an old piece, comments left out. */
    private fun offending(file: File): List<String> =
        file.readLines()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("/*") }
            .filter { signalLab1.containsMatchIn(it) }

    @Test
    fun theNewStrategyAndEvaluationCodeDoesNotUseSignalLab1() {
        val newPackages = listOf("strategy", "eval").map { File(engineSource, it) }.filter { it.isDirectory }
        val found = newPackages.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f -> offending(f).map { "${f.name}: $it" } }.toList()
        }
        assertEquals(emptyList(), found, "new code reaches into Signal Lab 1")
    }

    @Test
    fun theCheckCatchesAFileThatDoesUseSignalLab1() {
        // The live scan is Signal Lab 1 code that calls the old signals, so the check must flag it.
        assertTrue(offending(File(engineSource, "LiveScan.kt")).isNotEmpty(), "the check found nothing in a file that uses the old signals")
    }
}
