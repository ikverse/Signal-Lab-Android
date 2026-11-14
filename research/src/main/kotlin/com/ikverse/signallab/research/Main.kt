package com.ikverse.signallab.research

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.eval.Account
import com.ikverse.signallab.engine.eval.Backtest
import com.ikverse.signallab.engine.eval.BacktestTrade
import com.ikverse.signallab.engine.strategy.Builtins
import com.ikverse.signallab.engine.strategy.FundingSeries
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.TrendTimer
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * The whole-account test (docs/strategy-research.md §8): every daily built-in strategy over Binance's daily history, on the
 * coins the app would have watched at the time, followed as one account. It runs on a PC, never on the phone.
 *
 *     python tools/download_history.py klines          (once; public data, no key)
 *     gradlew :research:run --args="top12"             (which coins need funding history)
 *     python tools/download_history.py funding         (reads research/data/top12.txt)
 *     gradlew :research:run                            (the test)
 *     gradlew :research:run --args="timer"             (Trend timer at other settings and fees)
 */
private const val DAY = 86_400_000L
private val FROM = LocalDate.parse("2020-01-01").toEpochDay() * DAY

/** One coin's daily candles with its turnover in USDT over the 30 candles ending at each one. */
private class Coin(val c: Candles, quote: DoubleArray) {
    val turnover30 = DoubleArray(c.size).also { out ->
        var sum = 0.0
        for (i in 0 until c.size) {
            sum += quote[i]
            if (i >= 30) sum -= quote[i - 30]
            out[i] = sum
        }
    }
}

/** The app's universe as it was after the close of the candle opening at a time: the 40 busiest coins at least 180 days old. */
private class Universes(private val coins: Map<String, Coin>) {
    private val cache = HashMap<Long, Map<String, Int>>()

    fun at(t: Long): Map<String, Int> = cache.getOrPut(t) {
        coins.entries.mapNotNull { (sym, coin) ->
            val i = coin.c.t.binarySearch(t)
            if (i < 0 || t - coin.c.t[0] < 180 * DAY || !(coin.turnover30[i] > 0)) null else sym to coin.turnover30[i]
        }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(40)
            .mapIndexed { r, p -> p.first to r + 1 }.toMap()
    }
}

private class Trade(val strategy: Strategy, val t: BacktestTrade, val rank: Int)

private fun load(dir: File): Map<String, Coin> {
    val out = sortedMapOf<String, Coin>()
    for (f in File(dir, "d1").listFiles().orEmpty().sortedBy { it.name }) {
        val rows = f.readLines().filter { it.isNotBlank() }.map { it.split(',') }
        if (rows.size < 60) continue
        val n = rows.size
        fun col(k: Int) = DoubleArray(n) { rows[it][k].toDouble() }
        val sym = f.name.removeSuffix(".csv")
        out[sym] = Coin(Candles(sym, Timeframe.D1, LongArray(n) { rows[it][0].toLong() }, col(1), col(2), col(3), col(4), col(5), LongArray(n) { rows[it][6].toLong() }), col(7))
    }
    return out
}

private fun pct(x: Double) = String.format(Locale.US, "%+.2f%%", 100 * x)
private fun year(t: Long) = LocalDate.ofEpochDay(t / DAY).year

fun main(args: Array<String>) {
    val dir = File(args.firstOrNull { it.contains('/') || it.contains('\\') } ?: "research/data")
    val mode = args.firstOrNull { it == "top12" || it == "timer" }
    val coins = load(dir)
    val btc = coins["BTCUSDT"]?.c ?: error("No history in $dir: run tools/download_history.py klines first.")
    val uni = Universes(coins)
    val panel = coins.mapValues { it.value.c }
    val last = LocalDate.ofEpochDay(btc.t.last() / DAY)
    println("${coins.size} coins, Bitcoin from ${LocalDate.ofEpochDay(btc.t[0] / DAY)} to $last")

    if (mode == "top12") {
        val ever = sortedSetOf<String>()
        for (t in btc.t) if (t >= FROM - 400 * DAY) ever.addAll(uni.at(t).filterValues { it <= 12 }.keys)
        File(dir, "top12.txt").writeText(ever.joinToString(" "))
        println("${ever.size} coins were ever among the 12 busiest; written to top12.txt")
        return
    }

    fun follow(name: String, trades: List<Trade>, sizing: Account.Sizing = Account.Sizing.ENGINE, maxHeld: Double = 1.0): Account.Summary? {
        val order = Builtins.all.map { it.id }
        val entries = trades.map { Account.Entry(it.t, it.strategy.trend, it.rank * 100 + order.indexOf(it.strategy.id)) }
        return Account.summarize(Account.run(entries, panel, btc, FROM, sizing, maxHeld = maxHeld))?.also { line(name, it) }
    }
    println(String.format(Locale.US, "%-58s %8s %7s %6s %6s %5s  %s", "account", "ends at", "a year", "worst", "sharpe", "held", "by year"))

    if (mode == "timer") {
        for (days in listOf(20, 30, 50, 75, 100, 150, 200)) for (mult in listOf(1.0, 2.0)) {
            val s = TrendTimer(days)
            val trades = Backtest.run(s, panel, btc) { mult * EngineConfig.costFor(it) }.mapNotNull { tr ->
                uni.at(tr.signalTime)[tr.symbol]?.takeIf { it in 1..10 }?.let { Trade(s, tr, it) }
            }.filter { it.t.entryTime >= FROM }
            follow("Trend timer, $days-day average, fees x${mult.toInt()} (${trades.size} calls)", trades)
        }
        return
    }

    Builtins.funding.update(File(dir, "funding").listFiles().orEmpty().mapNotNull { f ->
        val rows = f.readLines().filter { it.isNotBlank() }.map { it.split(',') }
        if (rows.isEmpty()) null
        else f.name.removeSuffix(".csv") to FundingSeries(LongArray(rows.size) { rows[it][0].toLong() }, DoubleArray(rows.size) { rows[it][1].toDouble() })
    }.toMap())

    val all = LinkedHashMap<Strategy, List<Trade>>()
    for (s in Builtins.all.filter { it.timeframe == Timeframe.D1 }) {
        all[s] = (if (s.crossSectional) ranked(s, panel, btc, uni) else Backtest.run(s, panel, btc).mapNotNull { tr ->
            val rank = uni.at(tr.signalTime)[tr.symbol] ?: return@mapNotNull null
            if (s.ranks != null && rank !in s.ranks!!) null else Trade(s, tr, rank)
        }).filter { it.t.entryTime >= FROM }
    }

    val summaries = LinkedHashMap<String, Account.Summary>()
    for ((s, trades) in all) follow(s.name, trades)?.let { summaries[s.id] = it }
    follow("Every strategy", all.values.flatten())
    follow("Every strategy, plain size, borrowing allowed", all.values.flatten(), Account.Sizing.PLAIN, Double.MAX_VALUE)
    val bitcoin = Account.summarize(Account.holding(btc, FROM))!!.also { line("Just holding Bitcoin", it) }

    println("\nAgainst random entries with the same exits (the app's own yardstick)")
    for ((s, trades) in all) {
        println(s.name)
        for ((label, list) in listOf(
            "2020 to now" to trades, "2020-2021" to trades.filter { year(it.t.entryTime) <= 2021 }, "2022" to trades.filter { year(it.t.entryTime) == 2022 },
            "2023-2024" to trades.filter { year(it.t.entryTime) in 2023..2024 }, "2025 on" to trades.filter { year(it.t.entryTime) >= 2025 },
        )) {
            if (list.isEmpty()) continue
            val sum = Backtest.summarize(list.map { it.t }, s.id + label)
            println(String.format(Locale.US, "  %-12s %5d calls, %2.0f%% won, %s a call, %s against random, t %.2f", label, sum.n, 100 * sum.hit, pct(sum.meanNet), pct(sum.meanExcess), sum.tCluster))
        }
    }

    println("\nFor engine/.../eval/AccountRecord.kt:")
    fun source(a: Account.Summary) = String.format(Locale.US, "AccountRecord(%.1f, %.4f, %.4f, %.2f, listOf(%s))", a.end, a.perYear, a.worstFall, a.sharpe,
        a.byYear.joinToString { (y, r) -> String.format(Locale.US, "%d to %.4f", y, r) })
    println("    const val TO = \"$last\"")
    println("    val BITCOIN = ${source(bitcoin)}")
    for ((id, a) in summaries) println("        \"$id\" to ${source(a)},")
}

/** A ranking is made among the coins watched at the time: one panel per quarter, cut a month after the quarter's end. */
private fun ranked(s: Strategy, panel: Map<String, Candles>, btc: Candles, uni: Universes): List<Trade> {
    val out = ArrayList<Trade>()
    val seen = HashSet<String>()
    var q = LocalDate.parse("2020-01-01")
    val end = LocalDate.ofEpochDay(btc.t.last() / DAY)
    while (q <= end) {
        val from = q.toEpochDay() * DAY
        val to = q.plusMonths(3).toEpochDay() * DAY
        val sub = uni.at(from - DAY).keys.associateWith { sym ->
            val c = panel.getValue(sym)
            c.slice(0, c.t.indexOfFirst { it >= to + 30 * DAY }.let { if (it < 0) c.size else it })
        }
        for (tr in Backtest.run(s, sub, btc)) {
            if (tr.signalTime < from - DAY || tr.signalTime >= to - DAY || !seen.add(tr.symbol + tr.signalTime)) continue
            out.add(Trade(s, tr, uni.at(tr.signalTime)[tr.symbol] ?: 40))
        }
        q = q.plusMonths(3)
    }
    return out
}

private fun line(name: String, a: Account.Summary) {
    val years = a.byYear.joinToString(" ") { (y, r) -> "$y:${String.format(Locale.US, "%+.0f%%", 100 * r)}" }
    println(String.format(Locale.US, "%-58s %8.0f %6.1f%% %5.0f%% %6.2f %4.0f%%  %s", name, a.end, 100 * a.perYear, 100 * a.worstFall, a.sharpe, 100 * a.avgHeld, years))
}
