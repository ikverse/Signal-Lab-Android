package com.ikverse.signallab.engine

/** The three candle sizes the app watches. */
enum class Timeframe(val label: String, val hours: Int) {
    H1("1h", 1),
    H4("4h", 4),
    D1("1d", 24);

    val ms: Long get() = hours * 3_600_000L

    companion object {
        fun of(label: String): Timeframe = entries.first { it.label == label }
    }
}

/**
 * Closed candles for one coin and timeframe, as parallel arrays. Only closed candles ever go in:
 * the one still forming is never stored, so nothing downstream can see a price that later changes.
 */
class Candles(
    val symbol: String,
    val tf: Timeframe,
    val t: LongArray,
    val open: DoubleArray,
    val high: DoubleArray,
    val low: DoubleArray,
    val close: DoubleArray,
    val volume: DoubleArray,
    val closeTime: LongArray,
) {
    init {
        require(open.size == t.size && high.size == t.size && low.size == t.size &&
            close.size == t.size && volume.size == t.size && closeTime.size == t.size) {
            "$symbol ${tf.label}: candle arrays differ in length"
        }
    }

    val size: Int get() = t.size

    /** The candles from index [from] up to but not including [toExclusive]. */
    fun slice(from: Int, toExclusive: Int): Candles = Candles(
        symbol, tf, t.copyOfRange(from, toExclusive), open.copyOfRange(from, toExclusive),
        high.copyOfRange(from, toExclusive), low.copyOfRange(from, toExclusive),
        close.copyOfRange(from, toExclusive), volume.copyOfRange(from, toExclusive),
        closeTime.copyOfRange(from, toExclusive),
    )
}

internal const val DAY_MS = 86_400_000L
internal const val HOUR_MS = 3_600_000L

/** First index whose value is >= [v]; the array must be sorted. numpy's searchsorted(side="left"). */
internal fun lowerBound(a: LongArray, v: Long): Int {
    var lo = 0
    var hi = a.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (a[mid] < v) lo = mid + 1 else hi = mid
    }
    return lo
}

/** First index whose value is > [v]; numpy's searchsorted(side="right"). */
internal fun upperBound(a: LongArray, v: Long): Int {
    var lo = 0
    var hi = a.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (a[mid] <= v) lo = mid + 1 else hi = mid
    }
    return lo
}

internal fun lowerBound(a: IntArray, v: Int): Int {
    var lo = 0
    var hi = a.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (a[mid] < v) lo = mid + 1 else hi = mid
    }
    return lo
}

internal fun upperBound(a: IntArray, v: Int): Int {
    var lo = 0
    var hi = a.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (a[mid] <= v) lo = mid + 1 else hi = mid
    }
    return lo
}
