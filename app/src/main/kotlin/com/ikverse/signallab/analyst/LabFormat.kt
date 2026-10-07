package com.ikverse.signallab.analyst

import com.ikverse.signallab.engine.LabBlock
import com.ikverse.signallab.engine.LabCompare
import com.ikverse.signallab.engine.LabCondition
import com.ikverse.signallab.engine.LabExit
import com.ikverse.signallab.engine.LabOperand
import com.ikverse.signallab.engine.LabPattern
import com.ikverse.signallab.engine.LabPatterns
import com.ikverse.signallab.engine.Timeframe
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One suggested pattern as read from an answer: the [pattern] when it can be run, otherwise the [problem] with it, in words. */
class LabSuggestion(val title: String, val reason: String?, val pattern: LabPattern?, val problem: String?) {
    /** The written form the record keeps and compares; null for a suggestion that cannot be run. */
    val definition: String? get() = pattern?.let(LabFormat::definition)
}

/**
 * The written form of a lab pattern, as Claude is asked to give it and as the record keeps it:
 *
 * ```
 * {"title": "...", "reason": "...", "charts": ["1h"], "when": [{"left": "rsi(14)", "is": "crosses_above", "right": "30"}], "exit": "trail"}
 * ```
 *
 * Reading is strict: a name outside the vocabulary, a length out of range or anything else the engine could not run is refused with the
 * reason, never guessed at.
 */
object LabFormat {
    /** The language name of the code block an answer puts its suggestions in. */
    const val TAG = "signal-lab-patterns"

    /** The most suggestions read from one answer. */
    const val MAX_SUGGESTIONS = 10
    private const val MAX_TITLE = 60
    private const val MAX_REASON = 300

    private val fence = Regex("""(?s)```[ \t]*([A-Za-z0-9_-]*)[^\n]*\n(.*?)\n[ \t]*```""")
    private val withLength = Regex("""([a-z_]+)\((\d+)\)""")
    private val plainName = Regex("""[a-z_]+""")
    private val number = Regex("""-?(\d+(\.\d*)?|\.\d+)""")
    private val hold = Regex("""hold\((\d+)\)""")

    private class Refused(message: String) : Exception(message)

    /**
     * The suggestions in [text]: the code blocks marked [TAG], or, when there are none, plain JSON blocks that hold conditions. A block
     * that is not JSON at all becomes one suggestion that says so, so a broken answer is never silently empty.
     */
    fun suggestions(text: String): List<LabSuggestion> {
        val blocks = fence.findAll(text.replace("\r\n", "\n")).toList()
        val chosen = blocks.filter { it.groupValues[1] == TAG }
            .ifEmpty { blocks.filter { it.groupValues[1] in setOf("json", "") && "\"when\"" in it.groupValues[2] } }
        return chosen.flatMap { block(it.groupValues[2]) }.take(MAX_SUGGESTIONS)
    }

    private fun block(json: String): List<LabSuggestion> {
        val items = try {
            when (val v = org.json.JSONTokener(json.trim()).nextValue()) {
                is JSONArray -> List(v.length()) { v.opt(it) }
                is JSONObject -> v.optJSONArray("patterns")?.let { a -> List(a.length()) { a.opt(it) } } ?: listOf(v)
                else -> return listOf(unreadable("it is not a list of patterns"))
            }
        } catch (e: JSONException) {
            return listOf(unreadable(e.message ?: "it is not valid JSON"))
        }
        return items.map { if (it is JSONObject) read(it) else unreadable("an item is not a pattern") }
    }

    private fun unreadable(why: String) = LabSuggestion("A suggestion that could not be read", null, null, "The block could not be read: $why.")

    /** One suggestion from its JSON form. */
    fun read(o: JSONObject): LabSuggestion {
        val title = o.optString("title").trim().ifEmpty { "Untitled pattern" }.let { if (it.length > MAX_TITLE) it.take(MAX_TITLE - 1) + "…" else it }
        val reason = o.optString("reason").trim().takeIf { it.isNotEmpty() }?.take(MAX_REASON)
        return try {
            val pattern = LabPattern(0, charts(o.opt("charts")), conditions(o.opt("when")), exit(o.opt("exit")))
            val problem = LabPatterns.problem(pattern)
            LabSuggestion(title, reason, pattern.takeIf { problem == null }, problem)
        } catch (e: Refused) {
            LabSuggestion(title, reason, null, e.message)
        }
    }

    private fun charts(raw: Any?): Set<Timeframe> {
        val a = raw as? JSONArray ?: throw Refused("\"charts\" must be a list such as [\"1h\", \"4h\"].")
        return (0 until a.length()).map { i ->
            val label = a.opt(i)?.toString()?.trim().orEmpty()
            Timeframe.entries.firstOrNull { it.label == label }
                ?: throw Refused("\"$label\" is not a chart size; use ${Timeframe.entries.joinToString(", ") { it.label }}.")
        }.toSet()
    }

    private fun conditions(raw: Any?): List<LabCondition> {
        val a = raw as? JSONArray ?: throw Refused("\"when\" must be a list of conditions.")
        return (0 until a.length()).map { i ->
            val c = a.opt(i) as? JSONObject ?: throw Refused("Condition ${i + 1} is not of the form {\"left\", \"is\", \"right\"}.")
            val compare = c.optString("is").trim()
            LabCondition(
                operand(c.opt("left")),
                LabCompare.of(compare) ?: throw Refused("\"$compare\" is not a comparison; use ${LabCompare.entries.joinToString(", ") { it.id }}."),
                operand(c.opt("right")),
            )
        }
    }

    /** `close`, `sma(50)`, `30` or the number 30, read into an operand. */
    fun operand(raw: Any?): LabOperand {
        if (raw is Number) return LabOperand(LabBlock.NUMBER, value = raw.toDouble())
        val s = raw?.toString()?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) throw Refused("A condition is missing a side.")
        if (number.matches(s)) return LabOperand(LabBlock.NUMBER, value = s.toDouble())
        if (s.endsWith("%") && number.matches(s.dropLast(1).trim())) throw Refused("Write $s as a fraction: ${s.dropLast(1).trim().toDouble() / 100}.")
        withLength.matchEntire(s)?.let { m ->
            val block = LabBlock.of(m.groupValues[1]) ?: throw Refused("\"${m.groupValues[1]}\" is not a building block.")
            if (!block.takesN) throw Refused("\"${block.id}\" takes no length; write it alone.")
            return LabOperand(block, m.groupValues[2].toIntOrNull() ?: throw Refused("The length in \"$s\" is too large."))
        }
        if (plainName.matches(s)) {
            val block = LabBlock.of(s) ?: throw Refused("\"$s\" is not a building block.")
            if (block.takesN) throw Refused("\"$s\" needs a length, such as $s(14).")
            return LabOperand(block)
        }
        throw Refused("\"$s\" is not a building block or a number.")
    }

    private fun exit(raw: Any?): LabExit {
        val s = raw?.toString()?.trim()?.lowercase().orEmpty()
        return when {
            s == LabExit.Trail.text -> LabExit.Trail
            s == LabExit.Learned.text -> LabExit.Learned
            hold.matches(s) -> LabExit.Hold(hold.matchEntire(s)!!.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE)
            else -> throw Refused("\"exit\" must be \"trail\", \"learned\" or \"hold(N)\", not \"$s\".")
        }
    }

    /**
     * The written form the record keeps: the rule alone (no title or reason), in a fixed order, so the same pattern is always written the
     * same way and two suggestions of one pattern are recognised as one.
     */
    fun definition(p: LabPattern): String = buildString {
        append("{\"charts\":[").append(p.timeframes.sorted().joinToString(",") { "\"${it.label}\"" }).append("],\"when\":[")
        append(p.conditions.joinToString(",") { "{\"left\":\"${it.left.text}\",\"is\":\"${it.compare.id}\",\"right\":\"${it.right.text}\"}" })
        append("],\"exit\":\"").append(p.exit.text).append("\"}")
    }

    /** Pattern [id] from its written form; null when the form no longer reads (it was written by this code, so it should). */
    fun pattern(id: Long, definition: String): LabPattern? = try {
        read(JSONObject(definition)).pattern?.copy(id = id)
    } catch (_: JSONException) {
        null
    }

    /** The form and the building blocks, for the question that asks for suggestions. */
    val guide: String = buildString {
        append("Put every suggestion in one code block that starts with ```").append(TAG).append(" and holds a JSON array in exactly this form:\n\n")
        append("[{\"title\": \"A short name, at most $MAX_TITLE characters\", \"reason\": \"One sentence: what in the data suggests it\", ")
        append("\"charts\": [\"1h\"], \"when\": [{\"left\": \"rsi(14)\", \"is\": \"crosses_above\", \"right\": \"30\"}], \"exit\": \"trail\"}]\n\n")
        append("- \"charts\": any of ").append(Timeframe.entries.joinToString(", ") { it.label }).append(".\n")
        append("- \"when\": 1 to ${LabPatterns.MAX_CONDITIONS} conditions. A signal is the first candle on which all of them hold; the paper trade ")
        append("enters at the next candle's open.\n")
        append("- \"left\" and \"right\": a plain number, or one of these (N is a whole number from ${LabPatterns.MIN_N} to ${LabPatterns.MAX_N}):\n")
        for (b in LabBlock.entries) {
            if (b == LabBlock.NUMBER) continue
            append("  - ").append(if (b.takesN) "${b.id}(N)" else b.id).append(": ").append(b.words).append('\n')
        }
        append("- \"is\": ").append(LabCompare.entries.joinToString(", ") { it.id }).append(".\n")
        append("- \"exit\": \"trail\" (a trailing stop, as the trend patterns use), \"learned\" (a target and time limit learned from the pattern's ")
        append("earlier signals, as the fast patterns use) or \"hold(N)\" (held N candles, 1 to ${LabPatterns.MAX_HOLD}).\n")
        append("Anything outside this form is refused by the app.\n")
    }
}
