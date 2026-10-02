package com.ikverse.signallab.engine

/** The candle sizes the app can watch, shortest first. Every one is aligned to the Unix epoch, as Binance serves it. */
enum class Timeframe(val label: String, val minutes: Int) {
    M1("1m", 1),
    M5("5m", 5),
    M15("15m", 15),
    M30("30m", 30),
    H1("1h", 60),
    H4("4h", 240),
    D1("1d", 1440);

    val ms: Long get() = minutes * 60_000L

    val barsPerDay: Int get() = 1440 / minutes

    /** Shorter than an hour: scanned by a service that stays awake, not by an alarm. */
    val isFast: Boolean get() = minutes < 60

    companion object {
        fun of(label: String): Timeframe = entries.first { it.label == label }

        /** What a new list starts with. */
        val NEW_LIST_DEFAULT: Set<Timeframe> = setOf(M15, H1, H4)

        /** What lists that existed before timeframes could be chosen keep. */
        val LEGACY_DEFAULT: Set<Timeframe> = setOf(H1, H4, D1)

        /** Parses "15m,1h" into a set; unknown labels are ignored. */
        fun parseSet(text: String): Set<Timeframe> =
            text.split(',').mapNotNull { l -> entries.firstOrNull { it.label == l.trim() } }.toCollection(LinkedHashSet())

        fun formatSet(set: Collection<Timeframe>): String = entries.filter { it in set }.joinToString(",") { it.label }
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
