package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Indicators
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What copying a strategy's calls would have done to one account (docs/strategy-research.md §8). The random baseline asks
 * whether an entry is better than a random one with the same exit; this asks the owner's question instead: had I taken
 * every call, sized as the app sizes it, what would I have now, and how far would it have fallen on the way?
 */
object Account {
    private const val DAY_MS = 86_400_000L

    /** A backtest trade to follow. [order] decides who gets room first among calls entering at the same open. */
    class Entry(val trade: BacktestTrade, val trend: Boolean, val order: Int)

    /** The account at one daily close: what it is worth, and the share of it held in coins. */
    class Day(val time: Long, val equity: Double, val held: Double)

    enum class Sizing {
        /** One average true range is [Live.RISK_PER_ATR] of the account, at most [Live.MAX_SIZE]. */
        PLAIN,

        /** The sizing engine of [Risk], without the leverage heat and the live-record cap, which have no history. */
        ENGINE,
    }

    private class Position(val entry: Entry, val notional: Double, val weight: Double, val exitDay: Long)

    /**
     * Follows [entries] day by day from [from] on Bitcoin's calendar, the way the runner does: a call is sized from the
     * account at the last close, at most [maxOpen] calls hold room at once, the calls together never hold more than
     * [maxHeld] of the account, and a call is paid out at its backtest result (fees included) on the day it leaves. Open
     * calls are valued at each daily close.
     */
    fun run(
        entries: List<Entry>,
        candles: Map<String, Candles>,
        btc: Candles,
        from: Long,
        sizing: Sizing,
        start: Double = 10_000.0,
        maxOpen: Int = 8,
        maxHeld: Double = 1.0,
    ): List<Day> {
        val byEntry = entries.groupBy { it.trade.entryTime }
        val atrs = HashMap<String, DoubleArray>()
        var cash = start
        var equity = start
        val open = ArrayList<Position>()
        val out = ArrayList<Day>()
        for (bi in btc.t.indices) {
            val t = btc.t[bi]
            if (t < from || bi == 0) continue
            val leaving = open.filter { it.exitDay <= t }
            for (p in leaving) cash += p.notional * (1 + p.entry.trade.net)
            open.removeAll(leaving.toSet())
            val btcBefore = btc.close.copyOfRange(0, bi)
            val btcVar = Risk.btcVariance(btcBefore)
            for (e in byEntry[t].orEmpty().sortedBy { it.order }) {
                if (open.size >= maxOpen) break
                val c = candles[e.trade.symbol] ?: continue
                val s = c.t.binarySearch(e.trade.signalTime)
                if (s < 0) continue
                val atr = atrs.getOrPut(e.trade.symbol) { Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW) }[s]
                var w = Live.sizeFor(e.trade.entryPrice, atr)
                if (sizing == Sizing.ENGINE) {
                    val book = open.mapNotNull { p ->
                        val pc = candles.getValue(p.entry.trade.symbol)
                        val pi = pc.t.binarySearch(t - DAY_MS)
                        if (pi < 0) null else Risk.exposure(pc.close.copyOfRange(0, pi + 1), btcBefore)?.let { Holding(p.weight, it) }
                    }
                    val exposure = Risk.exposure(c.close.copyOfRange(0, s + 1), btcBefore)
                    w = Risk.size(w, if (e.trend) Risk.trendFactor(c, s) else 1.0, Heat.NONE, exposure, book, btcVar).size
                }
                w = Risk.fit(w, open.sumOf { it.weight }, maxHeld)
                if (!(w > 0)) continue
                val notional = w * equity
                cash -= notional
                open.add(Position(e, notional, w, Math.floorDiv(e.trade.exitTime, DAY_MS) * DAY_MS))
            }
            var held = 0.0
            for (p in open) {
                val c = candles.getValue(p.entry.trade.symbol)
                val i = c.t.binarySearch(t)
                held += p.notional * (if (i >= 0) c.close[i] else p.entry.trade.entryPrice) / p.entry.trade.entryPrice
            }
            equity = cash + held
            out.add(Day(t, equity, if (equity > 0) held / equity else 0.0))
        }
        return out
    }

    /** Buying Bitcoin at the first open on or after [from] and never selling, for comparison. */
    fun holding(btc: Candles, from: Long, start: Double = 10_000.0): List<Day> {
        val first = btc.t.indexOfFirst { it >= from }
        if (first < 0) return emptyList()
        return (first until btc.size).map { Day(btc.t[it], start * btc.close[it] / btc.open[first], 1.0) }
    }

    /** An account's run in a few numbers. */
    class Summary(
        val start: Double,
        val end: Double,
        /** Growth a year, compounded. */
        val perYear: Double,
        /** The deepest fall from a high, as a negative fraction. */
        val worstFall: Double,
        /** Daily Sharpe ratio, annualised over 365 days. */
        val sharpe: Double,
        /** Each calendar year's return, oldest first. */
        val byYear: List<Pair<Int, Double>>,
        val avgHeld: Double,
        val maxHeld: Double,
    )

    fun summarize(days: List<Day>, start: Double = 10_000.0): Summary? {
        if (days.size < 2) return null
        var peak = start
        var worst = 0.0
        for (d in days) {
            peak = maxOf(peak, d.equity)
            worst = minOf(worst, d.equity / peak - 1)
        }
        val r = DoubleArray(days.size) { i -> days[i].equity / (if (i == 0) start else days[i - 1].equity) - 1 }
        val mean = r.average()
        val sd = sqrt(r.sumOf { (it - mean) * (it - mean) } / (r.size - 1))
        val byYear = ArrayList<Pair<Int, Double>>()
        var before = start
        for ((year, list) in days.groupBy { LocalDate.ofEpochDay(it.time / DAY_MS).year }.toSortedMap()) {
            byYear.add(year to list.last().equity / before - 1)
            before = list.last().equity
        }
        val end = days.last().equity
        return Summary(
            start = start, end = end, perYear = if (end > 0) (end / start).pow(365.0 / days.size) - 1 else -1.0, worstFall = worst,
            sharpe = if (sd > 0) mean / sd * sqrt(365.0) else 0.0, byYear = byYear,
            avgHeld = days.sumOf { it.held } / days.size, maxHeld = days.maxOf { it.held },
        )
    }
}
