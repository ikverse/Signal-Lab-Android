package com.ikverse.signallab.engine.strategy.dsl

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.strategy.ExitPlan
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.Tier
import com.ikverse.signallab.engine.strategy.firstOfRun

/**
 * The language a custom strategy is written in: a bracketed tree, one rule per strategy, written by a person or drafted by
 * the analyst. A whole definition reads like this:
 *
 * ```
 * (strategy
 *   (timeframe 1d)
 *   (entry (and (> close (highest high 20)) (< (ret 1) 0.15)))
 *   (exit (< close (sma close 20)))
 *   (max_bars 30)
 *   (stop_atr 3)
 *   (bull_only)
 *   (ranks 1 40))
 * ```
 *
 * Every operation looks only at candles up to the one it is evaluated on ([Ops] lists them), so no rule written in it can
 * look ahead: there is no operation that reads a later candle, and a lag cannot be negative. An entry condition that holds
 * for several candles in a row enters on the first of them only.
 */
sealed class Node {
    /** A number in the text, such as `50` or `-0.05`. */
    data class Num(val value: Double) : Node()

    /** A name, such as `close` or `1d`. */
    data class Name(val name: String) : Node()

    /** An operation and its arguments: `(op arg …)`. */
    data class Call(val op: String, val args: List<Node>) : Node()

    /** The node in the canonical text form, which [Rule.parse] reads back to an equal node. */
    final override fun toString(): String = when (this) {
        is Num -> if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        is Name -> name
        is Call -> if (args.isEmpty()) "($op)" else "($op ${args.joinToString(" ")})"
    }

    val size: Int get() = if (this is Call) 1 + args.sumOf { it.size } else 1
    val depth: Int get() = if (this is Call) 1 + (args.maxOfOrNull { it.depth } ?: 0) else 1
}

/** A definition the language cannot accept, with what is wrong in words a person can act on. */
class RuleError(message: String) : IllegalArgumentException(message)

/** A checked custom strategy definition. */
data class RuleDefinition(
    val timeframe: Timeframe,
    val entry: Node,
    val exit: Node?,
    val maxBars: Int,
    val stopAtr: Double?,
    val bullOnly: Boolean,
    val ranks: IntRange?,
) {
    /** The definition as canonical text; [Rule.parse] of it gives back an equal definition. */
    fun print(): String = buildString {
        append("(strategy (timeframe ${timeframe.label}) (entry $entry)")
        exit?.let { append(" (exit $it)") }
        append(" (max_bars $maxBars)")
        stopAtr?.let { append(" (stop_atr ${Node.Num(it)})") }
        if (bullOnly) append(" (bull_only)")
        ranks?.let { append(" (ranks ${it.first} ${it.last})") }
        append(')')
    }
}

/** What each operation takes and gives: a number series, a true/false series, or a whole number (a window). */
internal enum class Kind { NUM, COND, INT }

internal class Op(val name: String, val result: Kind, val args: List<Kind>, val variadic: Boolean = false, val doc: String)

/** Every operation the language has. Nothing outside this table can be written. */
object Ops {
    internal val all: Map<String, Op> = listOf(
        Op("open", Kind.NUM, emptyList(), doc = "the candle's open"),
        Op("high", Kind.NUM, emptyList(), doc = "the candle's high"),
        Op("low", Kind.NUM, emptyList(), doc = "the candle's low"),
        Op("close", Kind.NUM, emptyList(), doc = "the candle's close"),
        Op("volume", Kind.NUM, emptyList(), doc = "the candle's volume in the coin"),
        Op("sma", Kind.NUM, listOf(Kind.NUM, Kind.INT), doc = "simple average of x over the last n candles"),
        Op("ema", Kind.NUM, listOf(Kind.NUM, Kind.INT), doc = "exponential average of x with span n"),
        Op("highest", Kind.NUM, listOf(Kind.NUM, Kind.INT), doc = "highest x of the n candles before this one"),
        Op("lowest", Kind.NUM, listOf(Kind.NUM, Kind.INT), doc = "lowest x of the n candles before this one"),
        Op("median", Kind.NUM, listOf(Kind.NUM, Kind.INT), doc = "median of x over the last n candles"),
        Op("std", Kind.NUM, listOf(Kind.NUM, Kind.INT), doc = "standard deviation of x over the last n candles"),
        Op("lag", Kind.NUM, listOf(Kind.NUM, Kind.INT), doc = "x as it was n candles ago"),
        Op("atr", Kind.NUM, listOf(Kind.INT), doc = "average true range over n candles"),
        Op("rsi", Kind.NUM, listOf(Kind.INT), doc = "Wilder's RSI over n candles, 0 to 100"),
        Op("ret", Kind.NUM, listOf(Kind.INT), doc = "return of the close over the last n candles, 0.05 = 5%"),
        Op("+", Kind.NUM, listOf(Kind.NUM, Kind.NUM), doc = "a plus b"),
        Op("-", Kind.NUM, listOf(Kind.NUM, Kind.NUM), doc = "a minus b"),
        Op("*", Kind.NUM, listOf(Kind.NUM, Kind.NUM), doc = "a times b"),
        Op("/", Kind.NUM, listOf(Kind.NUM, Kind.NUM), doc = "a divided by b"),
        Op(">", Kind.COND, listOf(Kind.NUM, Kind.NUM), doc = "a above b"),
        Op("<", Kind.COND, listOf(Kind.NUM, Kind.NUM), doc = "a below b"),
        Op(">=", Kind.COND, listOf(Kind.NUM, Kind.NUM), doc = "a at or above b"),
        Op("<=", Kind.COND, listOf(Kind.NUM, Kind.NUM), doc = "a at or below b"),
        Op("cross_above", Kind.COND, listOf(Kind.NUM, Kind.NUM), doc = "a moves from at or below b to above it on this candle"),
        Op("cross_below", Kind.COND, listOf(Kind.NUM, Kind.NUM), doc = "a moves from at or above b to below it on this candle"),
        Op("and", Kind.COND, listOf(Kind.COND, Kind.COND), variadic = true, doc = "all of them"),
        Op("or", Kind.COND, listOf(Kind.COND, Kind.COND), variadic = true, doc = "any of them"),
        Op("not", Kind.COND, listOf(Kind.COND), doc = "the opposite"),
        Op("held", Kind.COND, listOf(Kind.COND, Kind.INT), doc = "c true on each of the last n candles"),
        Op("ago", Kind.COND, listOf(Kind.COND, Kind.INT), doc = "c as it was n candles ago"),
    ).associateBy { it.name }

    /** One line per operation, for the analyst's instructions and the builder's help. */
    val reference: String
        get() = all.values.joinToString("\n") { op ->
            val pair = op.args.count { it != Kind.INT } > 1
            val args = op.args.mapIndexed { i, k ->
                when {
                    k == Kind.INT -> "n"
                    pair -> "ab"[i].toString()
                    k == Kind.COND -> "c"
                    else -> "x"
                }
            }
            val shape = if (args.isEmpty()) op.name else "(${op.name} ${args.joinToString(" ")}${if (op.variadic) " …" else ""})"
            "$shape: ${op.doc}"
        }
}

object Rule {
    /** The longest window any operation may use, and the most candles a trade may last. */
    const val MAX_WINDOW = 500

    /** The most operations in one condition: enough for any sensible rule, few enough to read. */
    const val MAX_NODES = 120
    const val MAX_DEPTH = 12

    /** Calls are only made on the hourly chart and slower (docs/strategy-research.md §2). */
    val TIMEFRAMES = setOf(Timeframe.H1, Timeframe.H4, Timeframe.D1)

    /** Reads a whole definition, `(strategy …)`, and checks it; throws [RuleError] naming the first problem. */
    fun parse(text: String): RuleDefinition {
        val root = Reader(text).readAll()
        if (root !is Node.Call || root.op != "strategy") throw RuleError("A definition starts with (strategy …).")
        val parts = HashMap<String, Node.Call>()
        for (p in root.args) {
            if (p !is Node.Call) throw RuleError("Inside (strategy …) every part is in brackets, such as (entry …); found $p.")
            if (p.op !in PARTS) throw RuleError("Unknown part (${p.op} …). The parts are ${PARTS.joinToString(", ")}.")
            if (parts.put(p.op, p) != null) throw RuleError("(${p.op} …) is given twice.")
        }
        val tf = parts["timeframe"]?.let { p ->
            val label = (p.args.singleOrNull() as? Node.Name)?.name ?: throw RuleError("(timeframe …) takes one of 1h, 4h, 1d.")
            Timeframe.entries.firstOrNull { it.label == label && it in TIMEFRAMES } ?: throw RuleError("Timeframe $label is not allowed; use 1h, 4h or 1d.")
        } ?: Timeframe.D1
        val entry = parts["entry"]?.args?.singleOrNull() ?: throw RuleError("A definition needs one (entry condition).")
        check(entry, Kind.COND, "the entry")
        val exit = parts["exit"]?.let { p -> (p.args.singleOrNull() ?: throw RuleError("(exit …) takes one condition.")).also { check(it, Kind.COND, "the exit") } }
        val maxBars = parts["max_bars"]?.let { whole(it, 1, MAX_WINDOW) } ?: throw RuleError("A definition needs (max_bars n): the most candles a trade lasts.")
        val stop = parts["stop_atr"]?.let { p ->
            val v = (p.args.singleOrNull() as? Node.Num)?.value ?: throw RuleError("(stop_atr x) takes one number.")
            if (!(v > 0 && v <= 20)) throw RuleError("(stop_atr $v): the stop sits between 0 and 20 ATR below the entry.")
            v
        }
        val bull = parts["bull_only"]?.let { if (it.args.isNotEmpty()) throw RuleError("(bull_only) takes nothing.") else true } ?: false
        val ranks = parts["ranks"]?.let { p ->
            val a = p.args.map { (it as? Node.Num)?.value ?: throw RuleError("(ranks from to) takes two whole numbers.") }
            if (a.size != 2 || a.any { it != Math.rint(it) } || a[0] < 1 || a[1] < a[0]) throw RuleError("(ranks from to) takes two whole numbers, 1 ≤ from ≤ to.")
            a[0].toInt()..a[1].toInt()
        }
        return RuleDefinition(tf, entry, exit, maxBars, stop, bull, ranks)
    }

    /** Reads one condition on its own, such as `(> close (sma close 50))`. */
    fun parseCondition(text: String): Node = Reader(text).readAll().also { check(it, Kind.COND, "the condition") }

    private val PARTS = listOf("timeframe", "entry", "exit", "max_bars", "stop_atr", "bull_only", "ranks")

    private fun whole(p: Node.Call, min: Int, max: Int): Int {
        val v = (p.args.singleOrNull() as? Node.Num)?.value
        if (v == null || v != Math.rint(v) || v < min || v > max) throw RuleError("(${p.op} n) takes a whole number from $min to $max.")
        return v.toInt()
    }

    private fun check(n: Node, want: Kind, where: String) {
        if (n.size > MAX_NODES) throw RuleError("${where.replaceFirstChar { it.uppercase() }} has ${n.size} parts; at most $MAX_NODES.")
        if (n.depth > MAX_DEPTH) throw RuleError("${where.replaceFirstChar { it.uppercase() }} is nested ${n.depth} deep; at most $MAX_DEPTH.")
        val got = kindOf(n)
        if (got != want) throw RuleError("${where.replaceFirstChar { it.uppercase() }} must be a ${words(want)}, but $n is a ${words(got)}.")
    }

    private fun words(k: Kind) = when (k) {
        Kind.NUM -> "number"
        Kind.COND -> "true-or-false condition"
        Kind.INT -> "whole number"
    }

    /** The kind [n] gives, after checking every operation's arguments; throws [RuleError] on the first misuse. */
    internal fun kindOf(n: Node): Kind = when (n) {
        is Node.Num -> if (n.value == Math.rint(n.value) && n.value >= 1 && n.value <= MAX_WINDOW) Kind.INT else Kind.NUM
        is Node.Name -> {
            val op = Ops.all[n.name] ?: throw RuleError("Unknown name ${n.name}.")
            if (op.args.isNotEmpty()) throw RuleError("${n.name} needs arguments: write (${n.name} …).")
            op.result
        }
        is Node.Call -> {
            val op = Ops.all[n.op] ?: throw RuleError("Unknown operation (${n.op} …). Known: ${Ops.all.keys.joinToString(" ")}.")
            if (op.args.isEmpty()) throw RuleError("${n.op} takes nothing: write ${n.op}, not (${n.op}).")
            val expected = if (op.variadic) List(maxOf(n.args.size, op.args.size)) { op.args[0] } else op.args
            if (n.args.size != expected.size) {
                throw RuleError("(${n.op} …) takes ${if (op.variadic) "at least ${op.args.size}" else "${op.args.size}"} arguments, not ${n.args.size}, in $n.")
            }
            for ((i, a) in n.args.withIndex()) {
                val k = kindOf(a)
                val ok = k == expected[i] || (k == Kind.INT && expected[i] == Kind.NUM)
                if (!ok) {
                    val hint = if (expected[i] == Kind.INT) " (a window from 1 to $MAX_WINDOW)" else ""
                    throw RuleError("Argument ${i + 1} of (${n.op} …) must be a ${words(expected[i])}$hint, but $a is a ${words(k)}.")
                }
            }
            op.result
        }
    }

    /** The condition [n] on every candle of [c]. NaN compares false, so nothing fires before its windows fill. */
    fun evaluate(n: Node, c: Candles): BooleanArray = Eval(c).cond(n)

    /** The number series [n] on every candle of [c]. */
    fun evaluateNumber(n: Node, c: Candles): DoubleArray = Eval(c).num(n)
}

/** A custom strategy: a checked [RuleDefinition] under its stored id and name. It starts in Testing, like every new idea. */
class CustomStrategy(
    override val id: String,
    override val name: String,
    val definition: RuleDefinition,
) : Strategy {
    override val timeframe = definition.timeframe
    override val launchTier = Tier.TESTING
    override val bullOnly = definition.bullOnly
    override val exit = ExitPlan(maxBars = definition.maxBars, safetyStopAtr = definition.stopAtr)
    override val ranks: IntRange? = definition.ranks

    override fun entries(c: Candles): BooleanArray = firstOfRun(Rule.evaluate(definition.entry, c))

    override fun exitSignals(c: Candles): BooleanArray? = definition.exit?.let { Rule.evaluate(it, c) }

    companion object {
        /** Parses [text] into a strategy; throws [RuleError] when the definition is not valid. */
        fun of(id: String, name: String, text: String) = CustomStrategy(id, name, Rule.parse(text))
    }
}

private class Reader(private val s: String) {
    private var i = 0

    fun readAll(): Node {
        skip()
        if (i >= s.length) throw RuleError("The text is empty.")
        val n = read()
        skip()
        if (i < s.length) throw RuleError("Unexpected text after the end, at character ${i + 1}: ${s.substring(i).take(20)}")
        return n
    }

    private fun skip() {
        while (i < s.length) {
            when {
                s[i].isWhitespace() -> i++
                s[i] == ';' -> while (i < s.length && s[i] != '\n') i++
                else -> return
            }
        }
    }

    private fun read(): Node {
        skip()
        if (i >= s.length) throw RuleError("A bracket is not closed.")
        if (s[i] == ')') throw RuleError("Unexpected ) at character ${i + 1}.")
        if (s[i] == '(') {
            i++
            skip()
            val op = atom() ?: throw RuleError("An operation name must follow ( at character $i.")
            val args = ArrayList<Node>()
            while (true) {
                skip()
                if (i >= s.length) throw RuleError("($op … is not closed.")
                if (s[i] == ')') {
                    i++
                    return Node.Call(op, args)
                }
                args.add(read())
            }
        }
        val a = atom()!!
        return a.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { Node.Num(it) } ?: Node.Name(a)
    }

    private fun atom(): String? {
        val start = i
        while (i < s.length && !s[i].isWhitespace() && s[i] != '(' && s[i] != ')' && s[i] != ';') i++
        return if (i > start) s.substring(start, i) else null
    }
}

/** Evaluates a checked tree on one coin's candles, computing each distinct subtree once. */
private class Eval(private val c: Candles) {
    private val nums = HashMap<String, DoubleArray>()
    private val conds = HashMap<String, BooleanArray>()
    private val n = c.size

    fun num(node: Node): DoubleArray = when (node) {
        is Node.Num -> DoubleArray(n) { node.value }
        else -> nums.getOrPut(node.toString()) { computeNum(node) }
    }

    fun cond(node: Node): BooleanArray = conds.getOrPut(node.toString()) { computeCond(node as Node.Call) }

    private fun whole(node: Node) = (node as Node.Num).value.toInt()

    private fun computeNum(node: Node): DoubleArray {
        val name = if (node is Node.Name) node.name else (node as Node.Call).op
        val a = (node as? Node.Call)?.args.orEmpty()
        return when (name) {
            "open" -> c.open
            "high" -> c.high
            "low" -> c.low
            "close" -> c.close
            "volume" -> c.volume
            "sma" -> Indicators.sma(num(a[0]), whole(a[1]))
            "ema" -> Indicators.ema(num(a[0]), whole(a[1]))
            "highest" -> Indicators.rollingMaxPrior(num(a[0]), whole(a[1]))
            "lowest" -> Indicators.rollingMinPrior(num(a[0]), whole(a[1]))
            "median" -> rollingMedian(num(a[0]), whole(a[1]))
            "std" -> Indicators.rollingStd(num(a[0]), whole(a[1]))
            "lag" -> {
                val x = num(a[0])
                val k = whole(a[1])
                DoubleArray(n) { i -> if (i >= k) x[i - k] else Double.NaN }
            }
            "atr" -> Indicators.atr(c.high, c.low, c.close, whole(a[0]))
            "rsi" -> Indicators.rsi(c.close, whole(a[0]))
            "ret" -> Indicators.pctReturn(c.close, whole(a[0]))
            "+", "-", "*", "/" -> {
                val x = num(a[0])
                val y = num(a[1])
                DoubleArray(n) { i ->
                    when (name) {
                        "+" -> x[i] + y[i]
                        "-" -> x[i] - y[i]
                        "*" -> x[i] * y[i]
                        else -> if (y[i] == 0.0) Double.NaN else x[i] / y[i]
                    }
                }
            }
            else -> error("not a number operation: $name")
        }
    }

    private fun computeCond(node: Node.Call): BooleanArray {
        val a = node.args
        return when (node.op) {
            ">", "<", ">=", "<=" -> {
                val x = num(a[0])
                val y = num(a[1])
                BooleanArray(n) { i ->
                    when (node.op) {
                        ">" -> x[i] > y[i]
                        "<" -> x[i] < y[i]
                        ">=" -> x[i] >= y[i]
                        else -> x[i] <= y[i]
                    }
                }
            }
            "cross_above" -> Indicators.crossedAbove(num(a[0]), num(a[1]))
            "cross_below" -> Indicators.crossedAbove(num(a[1]), num(a[0]))
            "and" -> a.map(::cond).let { all -> BooleanArray(n) { i -> all.all { it[i] } } }
            "or" -> a.map(::cond).let { all -> BooleanArray(n) { i -> all.any { it[i] } } }
            "not" -> cond(a[0]).let { x -> BooleanArray(n) { !x[it] } }
            "held" -> {
                val x = cond(a[0])
                val k = whole(a[1])
                var run = 0
                BooleanArray(n) { i ->
                    run = if (x[i]) run + 1 else 0
                    run >= k
                }
            }
            "ago" -> {
                val x = cond(a[0])
                val k = whole(a[1])
                BooleanArray(n) { i -> i >= k && x[i - k] }
            }
            else -> error("not a condition: ${node.op}")
        }
    }

    /** The median of the last [w] values up to each candle; NaN until [w] values exist or when any is NaN. */
    private fun rollingMedian(x: DoubleArray, w: Int): DoubleArray {
        val out = DoubleArray(n) { Double.NaN }
        val buf = DoubleArray(w)
        for (i in w - 1 until n) {
            var ok = true
            for (j in 0 until w) {
                val v = x[i - w + 1 + j]
                if (v.isNaN()) {
                    ok = false
                    break
                }
                buf[j] = v
            }
            if (!ok) continue
            val sorted = buf.copyOf()
            sorted.sort()
            out[i] = if (w % 2 == 1) sorted[w / 2] else (sorted[w / 2 - 1] + sorted[w / 2]) / 2
        }
        return out
    }
}
