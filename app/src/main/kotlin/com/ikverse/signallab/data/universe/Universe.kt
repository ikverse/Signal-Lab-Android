package com.ikverse.signallab.data.universe

import com.ikverse.signallab.data.CoinRow
import com.ikverse.signallab.data.DataConfig

/** A coin Signal Lab 2 watches, and why. */
data class UniverseCoin(
    val symbol: String,
    /** 1 for the busiest; 0 for a pinned coin outside the busiest. */
    val rank: Int,
    /** Its turnover in USDT over the last 30 days. */
    val quoteVolume30d: Double,
    val pinned: Boolean,
)

/**
 * Which coins Signal Lab 2 watches: the [DataConfig.UNIVERSE_SIZE] busiest by 30-day turnover among coins worth
 * watching, plus any the person pinned, minus any they excluded. A coin is worth watching when it trades against USDT
 * on Binance now, passed the coin filter (no stablecoins, wrapped assets or leveraged tokens), and has been listed for at
 * least [DataConfig.UNIVERSE_MIN_AGE_DAYS] days. This replaces hand-made watchlists.
 */
object Universe {
    private const val DAY_MS = 86_400_000L

    /**
     * [coins] is the stored pair list; [volume30d] each coin's 30-day turnover; [firstDaily] each coin's first stored
     * daily candle, the fallback for a listing day not looked up yet. A coin whose age is unknown is left out until it is
     * known.
     */
    fun select(
        coins: List<CoinRow>,
        volume30d: Map<String, Double>,
        firstDaily: Map<String, Long>,
        now: Long,
        pins: Set<String> = emptySet(),
        excludes: Set<String> = emptySet(),
        size: Int = DataConfig.UNIVERSE_SIZE,
    ): List<UniverseCoin> {
        val trading = coins.filter { it.status == "TRADING" && !it.delisted && it.symbol.endsWith(DataConfig.QUOTE) }
        fun oldEnough(c: CoinRow): Boolean {
            val listed = c.listedAt ?: firstDaily[c.symbol] ?: return false
            return now - listed >= DataConfig.UNIVERSE_MIN_AGE_DAYS * DAY_MS
        }
        val ranked = trading
            .filter { it.offered && !it.stable && it.symbol !in excludes && oldEnough(it) && (volume30d[it.symbol] ?: 0.0) > 0 }
            .sortedWith(compareByDescending<CoinRow> { volume30d.getValue(it.symbol) }.thenBy { it.symbol })
            .take(size)
        val picked = ranked.mapIndexed { i, c -> UniverseCoin(c.symbol, i + 1, volume30d.getValue(c.symbol), c.symbol in pins) }
        val inIt = picked.map { it.symbol }.toSet()
        // A pin overrides the ranking and the age rule, but never brings back a coin Binance no longer trades.
        val extra = trading.filter { it.symbol in pins && it.symbol !in inIt && it.symbol !in excludes }
            .sortedBy { it.symbol }
            .map { UniverseCoin(it.symbol, 0, volume30d[it.symbol] ?: 0.0, pinned = true) }
        return picked + extra
    }
}
