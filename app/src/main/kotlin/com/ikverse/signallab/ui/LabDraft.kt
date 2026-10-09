package com.ikverse.signallab.ui

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What a lab idea can be built from, in the words a person uses. The ids are exactly what the engine reads (see the lab's written form),
 * so a draft made here is a pattern the engine runs; the engine still checks every draft and refuses what it cannot run.
 */
object LabVocab {
    /** One building block. [takesN] says it needs a length; [learn] is the Learn page that explains it, when there is one. */
    class Block(val id: String, val takesN: Boolean, val menu: String, val phrase: String, val defaultN: Int = 14, val learn: String? = null)

    const val MIN_N = 2
    const val MAX_N = 200
    const val MAX_CONDITIONS = 4
    const val MAX_HOLD = 200
    const val DEFAULT_HOLD = 24

    val blocks: List<Block> = listOf(
        Block("close", false, "The price (the close)", "the price"),
        Block("open", false, "The opening price", "the opening price"),
        Block("high", false, "The candle's highest price", "the candle's high"),
        Block("low", false, "The candle's lowest price", "the candle's low"),
        Block("volume", false, "The trading volume", "the volume"),
        Block("sma", true, "Average price over N candles", "the N-candle average price", 50, "average"),
        Block("ema", true, "Fast average price over N candles", "the N-candle fast average", 20, "average"),
        Block("rsi", true, "RSI over N candles (0 to 100)", "RSI(N)", 14, "rsi"),
        Block("atr", true, "Normal move size over N candles", "the normal move over N candles", 14, "plan"),
        Block("highest", true, "Highest price of the last N candles", "the highest price of the last N candles", 20, "highest"),
        Block("lowest", true, "Lowest price of the last N candles", "the lowest price of the last N candles", 20, "highest"),
        Block("return", true, "Change over the last N candles", "the change over the last N candles", 24, "change"),
        Block("volume_ratio", true, "Volume compared with normal (N candles)", "volume compared with normal (N candles)", 20, "volratio"),
    )

    /** How a comparison reads in a sentence, and the Learn page that explains the difference between them. */
    val compares: List<Pair<String, String>> = listOf(
        "above" to "is above", "below" to "is below", "crosses_above" to "crosses above", "crosses_below" to "crosses below",
    )

    /** The chart sizes a lab idea can run on. */
    val charts: List<String> = listOf("5m", "15m", "30m", "1h", "4h", "1d")

    fun block(id: String): Block? = blocks.firstOrNull { it.id == id }

    private val withLength = Regex("""([a-z_]+)\((\d+)\)""")

    /** An operand split into its block and length: `sma(50)` is (sma, 50), `close` is (close, null), a number is (null, null). */
    fun split(operand: String): Pair<Block?, Int?> {
        withLength.matchEntire(operand.trim().lowercase())?.let { m -> return block(m.groupValues[1]) to m.groupValues[2].toIntOrNull() }
        return block(operand.trim().lowercase()) to null
    }

    fun isNumber(operand: String): Boolean = split(operand).first == null && operand.trim().toDoubleOrNull() != null

    /** An operand in a sentence: `rsi(14)` as "RSI(14)", `sma(200)` as "the 200-candle average price", `30` as "30". */
    fun phrase(operand: String): String {
        val (b, n) = split(operand)
        if (b == null) return operand.trim()
        return if (b.takesN) b.phrase.replace("N", (n ?: b.defaultN).toString()) else b.phrase
    }

    /** An operand on a button: the same words, short. */
    fun label(operand: String): String = phrase(operand)

    fun compareWords(id: String): String = compares.firstOrNull { it.first == id }?.second ?: id

    fun chartName(label: String): String = Fmt.chartAdjective(label)

    /** How a trade ends, in a few words. */
    fun exitWords(exit: String): String = when {
        exit == "trail" -> "a trailing stop (it follows the price up)"
        exit == "learned" -> "a profit goal and time limit learned from the pattern's earlier setups"
        exit.startsWith("hold(") -> "after ${exit.removePrefix("hold(").removeSuffix(")")} candles"
        else -> exit
    }

    fun exitName(exit: String): String = when {
        exit == "trail" -> "A trailing stop"
        exit == "learned" -> "A learned goal and time limit"
        exit.startsWith("hold(") -> "After ${exit.removePrefix("hold(").removeSuffix(")")} candles"
        else -> exit
    }

    fun holdOf(exit: String): Int? = if (exit.startsWith("hold(")) exit.removePrefix("hold(").removeSuffix(")").toIntOrNull() else null
}

/** One line of an idea: "RSI(14) crosses above 30". The three parts are written as the engine reads them. */
data class DraftCondition(val left: String, val compare: String, val right: String) {
    val words: String get() = "${LabVocab.phrase(left)} ${LabVocab.compareWords(compare)} ${LabVocab.phrase(right)}"
}

/** An idea being built: what must hold, on which charts, and how a trade ends. */
data class LabDraft(
    val conditions: List<DraftCondition> = listOf(DraftCondition("rsi(14)", "crosses_above", "30")),
    val charts: List<String> = listOf("1h"),
    val exit: String = "trail",
    val title: String = "",
) {
    /** The idea as one sentence a person can read. */
    val sentence: String
        get() {
            val charts = charts.sortedBy { LabVocab.charts.indexOf(it) }.map { LabVocab.chartName(it) }
            val on = if (charts.size <= 1) charts.joinToString() else charts.dropLast(1).joinToString(", ") + " and " + charts.last()
            return "Enter when " + conditions.joinToString(" and ") { it.words } + ", on $on charts. Leave " +
                (if (exit.startsWith("hold(")) "" else "with ") + LabVocab.exitWords(exit) + "."
        }

    /** The written form the app saves and the engine reads. */
    val definition: String
        get() = buildString {
            // Written by hand, in a fixed order, so the same idea is always the same text and two copies of it are recognised as one.
            append("{\"charts\":[").append(charts.sortedBy { LabVocab.charts.indexOf(it) }.joinToString(",") { JSONObject.quote(it) }).append("],\"when\":[")
            append(
                conditions.joinToString(",") {
                    "{\"left\":${JSONObject.quote(plain(it.left))},\"is\":${JSONObject.quote(it.compare)},\"right\":${JSONObject.quote(plain(it.right))}}"
                },
            )
            append("],\"exit\":").append(JSONObject.quote(exit)).append("}")
        }

    /** What is wrong with the draft that can be seen without the engine; null when it looks complete. */
    val problem: String?
        get() = when {
            conditions.isEmpty() -> "Add at least one condition."
            charts.isEmpty() -> "Choose at least one chart size."
            conditions.any { LabVocab.split(it.left).first == null && LabVocab.split(it.right).first == null } -> "A condition cannot compare two plain numbers."
            conditions.any { it.left == it.right } -> "A condition cannot compare something with itself."
            else -> null
        }

    /** A name for the idea when its maker did not give one: the first condition in a few words. */
    val name: String get() = title.ifBlank { conditions.firstOrNull()?.words?.replaceFirstChar { it.uppercase() } ?: "My idea" }.take(60)

    fun withCondition(i: Int, c: DraftCondition) = copy(conditions = conditions.mapIndexed { j, old -> if (j == i) c else old })

    companion object {
        /** A plain number written the way the engine writes it (1.50 as 1.5); anything else as it is. */
        private fun plain(operand: String): String =
            if (LabVocab.isNumber(operand)) java.math.BigDecimal(operand.trim()).stripTrailingZeros().toPlainString() else operand.trim()

        /** Reads an idea from its written form (the engine's form, with an optional title); null when it is not one. */
        fun read(definition: String, title: String = ""): LabDraft? = try {
            val o = JSONObject(definition)
            val when_ = o.getJSONArray("when")
            val conds = (0 until when_.length()).map { i ->
                val c = when_.getJSONObject(i)
                DraftCondition(c.get("left").toString().trim().lowercase(), c.getString("is").trim(), c.get("right").toString().trim().lowercase())
            }
            val charts = o.getJSONArray("charts").let { a -> (0 until a.length()).map { a.getString(it) } }
            LabDraft(conds, charts, o.getString("exit").trim().lowercase(), title.ifBlank { o.optString("title") })
        } catch (_: JSONException) {
            null
        }

        /** Starting points for "Start from an example": each is a complete idea that can be changed. */
        val examples: List<LabDraft> = listOf(
            LabDraft(listOf(DraftCondition("rsi(14)", "crosses_above", "30"), DraftCondition("close", "above", "sma(200)")), listOf("1h"), "trail", "RSI bounce in an uptrend"),
            LabDraft(listOf(DraftCondition("close", "crosses_above", "highest(20)"), DraftCondition("volume_ratio(20)", "above", "1.5")), listOf("1h"), "trail", "Breakout with volume"),
            LabDraft(listOf(DraftCondition("return(24)", "below", "-0.05"), DraftCondition("close", "above", "sma(200)")), listOf("4h"), "hold(24)", "Dip in an uptrend"),
            LabDraft(listOf(DraftCondition("volume_ratio(20)", "above", "2"), DraftCondition("close", "above", "ema(20)")), listOf("15m"), "learned", "Volume jump above the fast average"),
        )

        /** Keeps a draft across rotation as text. */
        val Saver = androidx.compose.runtime.saveable.Saver<LabDraft, String>(
            save = { JSONObject(it.definition).put("title", it.title).toString() },
            restore = { read(it) },
        )
    }
}

/**
 * How a lab idea is doing, from the scorecard rows of its charts: the ideas run on one variant per chart (`lab3_1h`), so this joins them.
 * [closed] is the trades finished across all its charts, [verdict] the scorecard's verdict on the chart with the most, and [average] their
 * mean result weighted by trades.
 */
class LabProgress(val id: Long, val closed: Int, val open: Int, val verdict: String, val average: Double?, val rows: List<ScoreRowUi>) {
    /** The part of the way to a verdict, 0 to 1. */
    val fraction: Float get() = (closed.toFloat() / PlainWords.MIN_VERDICT).coerceIn(0f, 1f)

    /** The state as a short phrase for a status tag. */
    val status: String
        get() = when {
            closed == 0 -> "Waiting for its first trade"
            verdict == "Edge" -> "Working"
            verdict == "Losing" -> "Not working"
            verdict == "No edge" -> "No better than guessing"
            average != null && average > 0 -> "Slightly ahead so far"
            average != null -> "Behind so far"
            else -> "Too early to tell"
        }

    /** The result so far, as one line. */
    val result: String
        get() = when {
            closed == 0 -> if (open > 0) "$open open, none finished yet" else "No trades yet"
            else -> "${PlainWords.count(closed, "trade")} finished" + (average?.let { ", average ${Fmt.signedPercent(it, 1)} per trade" } ?: "")
        }

    companion object {
        fun of(id: Long, rows: List<ScoreRowUi>): LabProgress {
            val mine = rows.filter { it.variant.startsWith("lab${id}_") }
            val closed = mine.sumOf { it.closed }
            val lead = mine.maxByOrNull { it.closed }
            val weighted = mine.filter { it.meanNet != null && it.closed > 0 }
            val average = if (weighted.isEmpty()) null else weighted.sumOf { it.meanNet!! * it.closed } / weighted.sumOf { it.closed }
            return LabProgress(id, closed, mine.sumOf { it.open }, lead?.verdict ?: "No verdict", average, mine)
        }
    }
}
