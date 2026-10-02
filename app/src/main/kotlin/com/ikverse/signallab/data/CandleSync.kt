package com.ikverse.signallab.data

import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.data.binance.MarketData
import com.ikverse.signallab.engine.Timeframe

class SyncResult(
    /** Candles newly stored. */
    val inserted: Int,
    /** Holes inside the stored history that were refilled. */
    val healedHoles: Int,
    /** The coin has less history than the window, because it listed more recently. */
    val youngerThanWindow: Boolean,
    /**
     * The candle that was still forming, as Binance served it, or null if the fetch did not reach it.
     * It is never stored; its open is the price a paper trade enters at the moment the candle begins.
     */
    val forming: Kline? = null,
)

/**
 * Brings one coin's candles for one timeframe up to date.
 *
 * It fetches forward from where stored history stops, so an interrupted download resumes where it
 * was cut off, and it refills holes inside what is stored. EGX Analyzer once fetched a fixed recent
 * range and stepped over a gap for good; that cannot happen here. A stretch Binance genuinely has no
 * candles for (maintenance, a halt) is remembered, so it is not asked for again on every sync.
 * Only closed candles are stored, judged by Binance's clock.
 */
class CandleSync(private val market: MarketData, private val store: CandleStore) {

    suspend fun syncCoin(symbol: String, tf: Timeframe, since: Long): SyncResult {
        var inserted = 0
        var healed = 0
        val now = market.nowMs()
        val forming = Forming()

        // 1. Forward from the newest stored candle (or the start of the window, the first time).
        val last = store.lastOpen(symbol, tf)
        inserted += fetchRange(symbol, tf, if (last == null) since else last + tf.ms, null, now, forming)

        // 2. Holes inside what is stored, including any in the candles just fetched.
        val known = store.knownGaps(symbol, tf)
        for (hole in holesIn(store.openTimes(symbol, tf, since), tf)) {
            if (known.any { hole.first >= it.first && hole.last <= it.last }) continue
            inserted += fetchRange(symbol, tf, hole.first, hole.last, now)
            val remaining = holesIn(store.openTimes(symbol, tf, hole.first - tf.ms), tf)
                .filter { it.first <= hole.last && it.last >= hole.first }
            // What Binance still does not have is a real gap: remember it rather than asking again every sync.
            if (remaining.isEmpty()) healed++ else remaining.forEach { store.addKnownGap(symbol, tf, it) }
        }

        // 3. The window starts earlier than what is stored (it grew, or the coin listed later than the window starts).
        val first = store.firstOpen(symbol, tf)
        if (first != null && first > since + tf.ms && store.knownGaps(symbol, tf).none { it.last >= first - tf.ms }) {
            inserted += fetchRange(symbol, tf, since, first - tf.ms, now)
            // Nothing earlier appeared: the coin simply was not listed yet. Remembered, so this is asked once.
            if (store.firstOpen(symbol, tf) == first) store.addKnownGap(symbol, tf, since..(first - tf.ms))
        }

        val firstNow = store.firstOpen(symbol, tf)
        return SyncResult(inserted, healed, youngerThanWindow = firstNow != null && firstNow > since + tf.ms, forming = forming.kline)
    }

    /** Remembers the forming candle seen while paging, which is the last one of the last page. */
    private class Forming(var kline: Kline? = null)

    /**
     * Fetches pages from [from] until a short page, or past [untilInclusive] when given. Stores only
     * candles that had closed by [now]. Returns how many were new.
     */
    private suspend fun fetchRange(symbol: String, tf: Timeframe, from: Long, untilInclusive: Long?, now: Long, forming: Forming? = null): Int {
        var next = from
        var total = 0
        while (true) {
            val page = market.klines(symbol, tf, next, DataConfig.KLINE_PAGE)
            if (page.isEmpty()) break
            if (forming != null) page.lastOrNull { it.closeTime >= now }?.let { forming.kline = it }
            val closed = page.filter { it.closeTime < now && (untilInclusive == null || it.openTime <= untilInclusive) }
            if (closed.isNotEmpty()) total += store.insertKlines(symbol, tf, closed)
            val lastOpen = page.last().openTime
            if (page.size < DataConfig.KLINE_PAGE) break
            if (untilInclusive != null && lastOpen >= untilInclusive) break
            if (lastOpen + 1 <= next) break // no progress: never loop forever on an odd answer
            next = lastOpen + 1
        }
        return total
    }

    companion object {
        /** The candle open times missing between consecutive stored ones, as inclusive ranges. */
        fun holesIn(openTimes: LongArray, tf: Timeframe): List<LongRange> = buildList {
            for (i in 1 until openTimes.size) {
                if (openTimes[i] - openTimes[i - 1] > tf.ms) add((openTimes[i - 1] + tf.ms)..(openTimes[i] - tf.ms))
            }
        }
    }
}
