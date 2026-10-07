package com.ikverse.signallab.engine

import java.math.BigDecimal

/**
 * What a lab condition can compare, candle by candle. Each is built from the indicators the built-in patterns use, so each uses only
 * candles up to the one it is read on. [takesN] says whether it needs a length, as in `sma(50)`.
 */
enum class LabBlock(val id: String, val takesN: Boolean, val words: String) {
    OPEN("open", false, "the open"),
    HIGH("high", false, "the high"),
    LOW("low", false, "the low"),
    CLOSE("close", false, "the close"),
    VOLUME("volume", false, "the volume"),
    SMA("sma", true, "the N-candle average close"),
    EMA("ema", true, "the N-candle exponential average close"),
    RSI("rsi", true, "the N-candle RSI (0 to 100)"),
    ATR("atr", true, "the N-candle average true range"),
    HIGHEST("highest", true, "the highest high of the N candles before"),
    LOWEST("lowest", true, "the lowest low of the N candles before"),
    RETURN("return", true, "the change in close over the last N candles, as a fraction (0.05 is 5%)"),
    VOLUME_RATIO("volume_ratio", true, "the volume divided by the average volume of the N candles before"),
    NUMBER("number", false, "a plain number");

    companion object {
        fun of(id: String): LabBlock? = entries.firstOrNull { it.id == id && it != NUMBER }
    }
}

/** One side of a condition: a block with its length, or a plain [value] for [LabBlock.NUMBER]. */
data class LabOperand(val block: LabBlock, val n: Int = 0, val value: Double = 0.0) {
    /** As written in a pattern: `close`, `sma(50)` or `30`. */
    val text: String
        get() = when {
            block == LabBlock.NUMBER -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            block.takesN -> "${block.id}($n)"
            else -> block.id
        }

    /** As said in a sentence: `close`, `SMA(50)`, `volume ratio(20)`, `30`. */
    val label: String
        get() = when (block) {
            LabBlock.SMA, LabBlock.EMA, LabBlock.RSI, LabBlock.ATR -> "${block.id.uppercase()}($n)"
            LabBlock.VOLUME_RATIO -> "volume ratio($n)"
            else -> text
        }
}

enum class LabCompare(val id: String, val words: String) {
    ABOVE("above", "above"), BELOW("below", "below"), CROSSES_ABOVE("crosses_above", "crosses above"), CROSSES_BELOW("crosses_below", "crosses below");

    companion object {
        fun of(id: String): LabCompare? = entries.firstOrNull { it.id == id }
    }
}

data class LabCondition(val left: LabOperand, val compare: LabCompare, val right: LabOperand) {
    val words: String get() = "${left.label} ${compare.words} ${right.label}"
}

/** How a lab pattern's paper trades end: the trend patterns' trailing stop, the fast patterns' learned target, or a fixed hold. */
sealed interface LabExit {
    val text: String
    val words: String

    data object Trail : LabExit {
        override val text = "trail"
        override val words = "a trailing stop"
    }

    data object Learned : LabExit {
        override val text = "learned"
        override val words = "a learned target and time limit"
    }

    data class Hold(val bars: Int) : LabExit {
        override val text get() = "hold($bars)"
        override val words get() = "held $bars candles"
    }
}

/** A pattern written as data: every condition must hold on a candle, on any of [timeframes]. [id] is its number in the record (0 while untested). */
data class LabPattern(val id: Long, val timeframes: Set<Timeframe>, val conditions: List<LabCondition>, val exit: LabExit) {
    /** In a sentence: "RSI(14) crosses above 30, and close above SMA(200), on 1h and 4h; held 12 candles". */
    val summary: String
        get() = conditions.joinToString(", and ") { it.words } + " on " +
            timeframes.sorted().map { it.label }.let { if (it.size == 1) it[0] else it.dropLast(1).joinToString(", ") + " and " + it.last() } +
            "; " + exit.words
}

/**
 * Lab patterns: ideas suggested from the data (by Claude, or anyone), written in a small fixed vocabulary instead of code, so the app
 * never runs anything it was handed. A signal is the first candle of a run on which every condition holds, as for the built-in patterns,
 * and every block uses only candles up to that one, so cutting history at a candle never changes what was flagged before it.
 *
 * A lab pattern is always forward-only: it was shaped after seeing the data, so its backtest is shown for reference and only live paper
 * trades can give it a verdict.
 */
object LabPatterns {
    const val FAMILY = "lab"

    /** The parameter that marks a lab pattern whose trades use the learned exit. */
    const val LEARNED = "learned"
    const val MIN_N = 2
    const val MAX_N = 200
    const val MAX_CONDITIONS = 4
    const val MAX_HOLD = 200

    private val nameRe = Regex("""lab(\d+)_(1m|5m|15m|30m|1h|4h|1d)""")

    fun isLab(variant: String): Boolean = nameRe.matches(variant)

    /** The record number of the lab pattern a variant name belongs to; null for any other name. */
    fun idOf(variant: String): Long? = nameRe.matchEntire(variant)?.groupValues?.get(1)?.toLongOrNull()

    /** The variant a lab pattern is on one chart: `lab3_1h`, with what its exit needs. */
    fun key(p: LabPattern, tf: Timeframe): SignalKey {
        val params = linkedMapOf("lab_id" to p.id.toDouble())
        when (val x = p.exit) {
            is LabExit.Hold -> params["hold_bars"] = x.bars.toDouble()
            LabExit.Learned -> params[LEARNED] = 1.0
            LabExit.Trail -> {}
        }
        return SignalKey("lab${p.id}_${tf.label}", FAMILY, params)
    }

    /** What is wrong with [p], in words; null when it can be run. */
    fun problem(p: LabPattern): String? {
        if (p.timeframes.isEmpty()) return "It names no chart size."
        if (p.conditions.isEmpty()) return "It has no conditions."
        if (p.conditions.size > MAX_CONDITIONS) return "It has ${p.conditions.size} conditions; at most $MAX_CONDITIONS are allowed."
        for (c in p.conditions) {
            for (o in listOf(c.left, c.right)) {
                if (o.block.takesN && o.n !in MIN_N..MAX_N) return "${o.block.id} needs a length from $MIN_N to $MAX_N, not ${o.n}."
                if (o.block == LabBlock.NUMBER && !o.value.isFinite()) return "A number in it is not a number."
            }
            if (c.left.block == LabBlock.NUMBER && c.right.block == LabBlock.NUMBER) return "\"${c.words}\" compares two numbers, which never changes."
            if (c.left == c.right) return "\"${c.words}\" compares a value with itself."
        }
        val hold = p.exit
        if (hold is LabExit.Hold && hold.bars !in 1..MAX_HOLD) return "A hold must be from 1 to $MAX_HOLD candles, not ${hold.bars}."
        return null
    }

    /** The value of [o] on every candle of [c]; NaN where there is not yet enough history. */
    fun series(c: Candles, o: LabOperand): DoubleArray = when (o.block) {
        LabBlock.OPEN -> c.open
        LabBlock.HIGH -> c.high
        LabBlock.LOW -> c.low
        LabBlock.CLOSE -> c.close
        LabBlock.VOLUME -> c.volume
        LabBlock.SMA -> Indicators.sma(c.close, o.n)
        LabBlock.EMA -> Indicators.ema(c.close, o.n)
        LabBlock.RSI -> Indicators.rsi(c.close, o.n)
        LabBlock.ATR -> Indicators.atr(c.high, c.low, c.close, o.n)
        LabBlock.HIGHEST -> Indicators.rollingMaxPrior(c.high, o.n)
        LabBlock.LOWEST -> Indicators.rollingMinPrior(c.low, o.n)
        LabBlock.RETURN -> Indicators.pctReturn(c.close, o.n)
        LabBlock.VOLUME_RATIO -> {
            // The candle's volume against the average of the ones before it, so a spike is not diluted by itself.
            val avg = Indicators.sma(c.volume, o.n)
            DoubleArray(c.size) { i -> if (i == 0 || !(avg[i - 1] > 0)) Double.NaN else c.volume[i] / avg[i - 1] }
        }
        LabBlock.NUMBER -> DoubleArray(c.size) { o.value }
    }

    /** True on every candle where [cond] holds. A comparison with a missing value is false, so nothing fires before there is history. */
    fun holds(c: Candles, cond: LabCondition): BooleanArray {
        val a = series(c, cond.left)
        val b = series(c, cond.right)
        return BooleanArray(c.size) { i ->
            when (cond.compare) {
                LabCompare.ABOVE -> a[i] > b[i]
                LabCompare.BELOW -> a[i] < b[i]
                LabCompare.CROSSES_ABOVE -> i > 0 && a[i] > b[i] && a[i - 1] <= b[i - 1]
                LabCompare.CROSSES_BELOW -> i > 0 && a[i] < b[i] && a[i - 1] >= b[i - 1]
            }
        }
    }

    /** The signals of [p] on [c]: the first candle of each run on which every condition holds. */
    fun flags(c: Candles, p: LabPattern): BooleanArray {
        val all = BooleanArray(c.size) { true }
        for (cond in p.conditions) {
            val h = holds(c, cond)
            for (i in all.indices) all[i] = all[i] && h[i]
        }
        return Signals.firstBarOf(all)
    }

    /** Every pattern of [patterns] that runs on [c]'s chart size, with its signals there. */
    fun perCoin(c: Candles, patterns: List<LabPattern>): Map<SignalKey, BooleanArray> =
        patterns.filter { c.tf in it.timeframes }.associate { key(it, c.tf) to flags(c, it) }
}
