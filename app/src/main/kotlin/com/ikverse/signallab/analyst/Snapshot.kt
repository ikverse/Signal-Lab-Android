package com.ikverse.signallab.analyst

import com.ikverse.signallab.data.CostModel
import com.ikverse.signallab.data.LiveTrade
import com.ikverse.signallab.data.PatternLabels
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.Watchlist
import com.ikverse.signallab.ui.ScorecardUi
import java.math.BigDecimal
import java.math.MathContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What the data file is made from, read from the record by the caller. [trades] is every paper trade, newest first. */
class SnapshotInput(
    val now: Long,
    val appVersion: String,
    val costs: CostModel,
    val lists: List<Watchlist>,
    val trades: List<LiveTrade>,
    val scorecard: ScorecardUi,
    /** The lab patterns, running or stopped, newest first. */
    val lab: List<LabInfo> = emptyList(),
)

/** A lab pattern as the data shows it: its number, its title, its rule in words, and when its forward test began and ended. */
class LabInfo(val id: Long, val title: String, val summary: String, val startedAt: Long, val stoppedAt: Long?)

/** One trade to explain, with the stored candles of its chart from shortly before its signal on ([candles] is null when none are kept). */
class TradeFocus(val trade: LiveTrade, val candles: Candles?)

/**
 * The data handed to Claude below a question: the record in Markdown tables, with a short account of how the numbers are made, so the
 * answer can rest on numbers the app produced instead of on guesses. Everything is in UTC, as the signals are. A plain function of its
 * input, so a test can read exactly what would be sent.
 */
object Snapshot {
    /**
     * A hard ceiling on the data, above what the row limits below usually produce. Android limits how much one hand-off between apps can
     * carry, so a record of any size must stay well under it.
     */
    const val MAX_CHARS = 100_000
    const val LATEST_CLOSED = 100
    const val OPEN_SHOWN = 50
    const val COINS_SHOWN = 40
    const val CANDLES_BEFORE = 30
    const val CANDLES_AFTER = 5
    const val MAX_CANDLES = 150
    const val OTHER_TRADES_SHOWN = 20

    private const val DAY_MS = 86_400_000L
    private const val MISSING = "–"

    fun build(input: SnapshotInput, focus: TradeFocus? = null): String {
        val sb = StringBuilder(32_000)
        val closed = input.trades.filter { it.exit != null }
        val open = input.trades.filter { it.exit == null }
        sb.append("# Signal Lab data file\n\n")
        sb.append("Made ${utc(input.now)} UTC by Signal Lab ${input.appVersion}. Every trade here is a paper trade: no real money was traded. ")
            .append("${closed.size} closed and ${open.size} open trades in all.\n\n")
        method(sb, input)
        lists(sb, input.lists)
        scorecard(sb, input.scorecard)
        lab(sb, input.lab)
        val groups = closed.groupBy { it.trade.variant to it.trade.tf }.toSortedMap(compareBy({ it.first }, { it.second }))
        recent(sb, groups, input.now)
        regimes(sb, groups)
        exits(sb, groups)
        coins(sb, closed)
        openTrades(sb, open)
        latestClosed(sb, closed)
        if (focus != null) focus(sb, focus, closed)
        if (sb.length > MAX_CHARS) {
            sb.setLength(MAX_CHARS)
            sb.append("\n\n(The file was cut here at $MAX_CHARS characters.)\n")
        }
        return sb.toString()
    }

    private fun method(sb: StringBuilder, input: SnapshotInput) {
        val c = input.costs
        sb.append("## How the numbers are made\n\n")
        sb.append("- A signal is detected when its candle closes. The paper trade enters at the next candle's open.\n")
        sb.append("- Costs per round trip: the exchange fee of ${pct(c.feePerSide, false)} each way (${pct(2 * c.feePerSide, false)}), plus an extra ")
            .append("${pct(c.extraMajors, false)} on ${EngineConfig.MAJORS.joinToString(" and ") { it.removeSuffix("USDT") }} and ${pct(c.extraOthers, false)} on other coins. ")
            .append("Every result below is after costs.\n")
        sb.append("- Each trade is compared with ${EngineConfig.RANDOM_DRAWS_PER_TRADE} random entries on the same coin, close in time (")
            .append(Timeframe.entries.joinToString(", ") { "${it.label}: ${EngineConfig.randomWindowDays(it)} days" })
            .append(" either side), in the same BTC regime (Bitcoin's daily close above or below its ${EngineConfig.REGIME_MA_DAYS}-day average), with the same exit. ")
            .append("\"Vs random\" is the trade's result minus the average of its random entries.\n")
        sb.append("- Verdicts need at least ${EngineConfig.VERDICT_TIERS[0].first} closed trades. \"Edge\" needs a t-statistic of at least ${EngineConfig.EDGE_T} ")
            .append("and a p-value under ${pct(EngineConfig.ALPHA, false)} after a correction for every pattern ever tested (${input.scorecard.patternsTested} so far). ")
            .append("\"Losing\" means t at or below ${EngineConfig.LOSING_T}. Trades in the same calendar week count as one piece of evidence (t is clustered by week).\n")
        sb.append("- Firmness by closed trades: under ${EngineConfig.VERDICT_TIERS[0].first} none, then ${EngineConfig.VERDICT_TIERS.joinToString(", ") { "${it.second} below ${it.first}" }}, ")
            .append("and ${EngineConfig.VERDICT_TOP} from ${EngineConfig.VERDICT_TIERS.last().first}.\n")
        sb.append("- Forward-only patterns were defined after looking at a backtest, so only live trades can judge them: ")
            .append(EngineConfig.FORWARD_ONLY_VARIANTS.sorted().joinToString(", "))
            .append(", and every lab pattern (named lab, its number and its chart, such as lab3_1h).\n")
        sb.append("- Exit styles: \"trail\" follows the price with a stop under the highest close, \"learned\" uses a target and time limit learned from past signals, ")
            .append("\"held\" holds for a fixed number of candles, \"classic\" uses a target of ${EngineConfig.TARGET_ATR} ATR and a stop of ${EngineConfig.STOP_ATR} ATR. ")
            .append("\"Best rise\" and \"worst dip\" are the most the trade showed above and below its entry while open.\n\n")
    }

    private fun lists(sb: StringBuilder, lists: List<Watchlist>) {
        sb.append("## Watchlists\n\n")
        if (lists.isEmpty()) {
            sb.append("None.\n\n")
            return
        }
        table(sb, listOf("List", "On", "Charts", "Coins"), lists.map { l ->
            listOf(l.name, if (l.active) "on" else "off", l.timeframes.sorted().joinToString(", ") { it.label },
                "${l.symbols.size}: " + l.symbols.joinToString(", ") { it.removeSuffix("USDT") })
        })
    }

    private fun scorecard(sb: StringBuilder, card: ScorecardUi) {
        sb.append("## Scorecard\n\n")
        val traded = card.rows.filter { it.open + it.closed > 0 }.sortedWith(compareByDescending<com.ikverse.signallab.ui.ScoreRowUi> { it.closed }.thenBy { it.variant })
        if (traded.isEmpty()) {
            sb.append("No pattern has a trade yet.\n\n")
            return
        }
        table(sb, listOf("Pattern", "What it is", "Chart", "Closed", "Open", "Win rate", "Average", "Random average", "Vs random", "t (by week)", "Verdict", "Firmness"),
            traded.map { r ->
                listOf(r.variant, r.label, r.timeframe, "${r.closed}", "${r.open}", share(r.hitRate), pct(r.meanNet), pct(r.randomMean), pct(r.excess),
                    r.tCluster?.let { String.format(Locale.ROOT, "%.2f", it) } ?: MISSING, r.verdict, r.firmness)
            })
        val quiet = card.rows.size - traded.size
        if (quiet > 0) sb.append("$quiet more patterns are watched and have no trades yet.\n\n")
    }

    private fun lab(sb: StringBuilder, lab: List<LabInfo>) {
        sb.append("## Lab patterns\n\n")
        if (lab.isEmpty()) {
            sb.append("None has been forward-tested yet.\n\n")
            return
        }
        table(sb, listOf("Lab", "Title", "Rule", "Forward test began (UTC)", "Stopped (UTC)"), lab.map { l ->
            listOf("lab${l.id}", l.title, l.summary, utc(l.startedAt), l.stoppedAt?.let(::utc) ?: "running")
        })
    }

    /** "n=12, +0.31%": how many trades and their average result vs random. */
    private fun cell(trades: List<LiveTrade>): String {
        val ex = trades.mapNotNull { it.exit?.excess }.filter { !it.isNaN() }
        return if (trades.isEmpty()) MISSING else "n=${trades.size}, " + if (ex.isEmpty()) "vs random $MISSING" else "vs random ${pct(ex.average())}"
    }

    private fun recent(sb: StringBuilder, groups: Map<Pair<String, Timeframe>, List<LiveTrade>>, now: Long) {
        sb.append("## Recent against earlier (by when trades closed)\n\n")
        if (groups.isEmpty()) {
            sb.append("No closed trades yet.\n\n")
            return
        }
        table(sb, listOf("Pattern", "Chart", "Last 7 days", "8 to 30 days ago", "Earlier"), groups.map { (k, ts) ->
            fun age(t: LiveTrade) = now - t.exit!!.exitTime
            listOf(k.first, k.second.label, cell(ts.filter { age(it) <= 7 * DAY_MS }),
                cell(ts.filter { age(it) > 7 * DAY_MS && age(it) <= 30 * DAY_MS }), cell(ts.filter { age(it) > 30 * DAY_MS }))
        })
    }

    private fun regimes(sb: StringBuilder, groups: Map<Pair<String, Timeframe>, List<LiveTrade>>) {
        sb.append("## By BTC regime at the signal\n\n")
        if (groups.isEmpty()) {
            sb.append("No closed trades yet.\n\n")
            return
        }
        table(sb, listOf("Pattern", "Chart", "BTC above ${EngineConfig.REGIME_MA_DAYS}d", "BTC below ${EngineConfig.REGIME_MA_DAYS}d", "Regime unknown"), groups.map { (k, ts) ->
            listOf(k.first, k.second.label, cell(ts.filter { it.trade.regime == 1 }), cell(ts.filter { it.trade.regime == 0 }), cell(ts.filter { it.trade.regime !in setOf(0, 1) }))
        })
    }

    private fun exits(sb: StringBuilder, groups: Map<Pair<String, Timeframe>, List<LiveTrade>>) {
        sb.append("## How trades ended\n\n")
        if (groups.isEmpty()) {
            sb.append("No closed trades yet.\n\n")
            return
        }
        table(sb, listOf("Pattern", "Chart", "Exit style", "Target", "Stop", "Time", "Average candles held", "Average best rise", "Average worst dip", "Average candles to peak"),
            groups.map { (k, ts) ->
                val x = ts.map { it.exit!! }
                val style = ts.groupingBy { it.trade.exitMode ?: "older trade" }.eachCount().maxByOrNull { it.value }?.key ?: MISSING
                listOf(k.first, k.second.label, style,
                    "${x.count { it.reason.label == "target" }}", "${x.count { it.reason.label == "stop" }}", "${x.count { it.reason.label == "time" }}",
                    String.format(Locale.ROOT, "%.1f", x.map { it.barsHeld }.average()),
                    pct(x.mapNotNull { it.maxUp }.averageOrNull()), pct(x.mapNotNull { it.maxDown }.averageOrNull()),
                    x.mapNotNull { it.barsToPeak?.toDouble() }.averageOrNull()?.let { String.format(Locale.ROOT, "%.1f", it) } ?: MISSING)
            })
    }

    private fun coins(sb: StringBuilder, closed: List<LiveTrade>) {
        sb.append("## By coin (closed trades, every pattern)\n\n")
        if (closed.isEmpty()) {
            sb.append("No closed trades yet.\n\n")
            return
        }
        val byCoin = closed.groupBy { it.trade.symbol }.entries.sortedWith(compareByDescending<Map.Entry<String, List<LiveTrade>>> { it.value.size }.thenBy { it.key })
        table(sb, listOf("Coin", "Closed", "Win rate", "Average", "Vs random"), byCoin.take(COINS_SHOWN).map { (sym, ts) ->
            val nets = ts.map { it.exit!!.net }
            listOf(sym.removeSuffix("USDT"), "${ts.size}", share(nets.count { it > 0 }.toDouble() / nets.size), pct(nets.average()),
                pct(ts.mapNotNull { it.exit!!.excess }.filter { !it.isNaN() }.averageOrNull()))
        })
        if (byCoin.size > COINS_SHOWN) sb.append("${byCoin.size - COINS_SHOWN} more coins with fewer trades are left out.\n\n")
    }

    private fun openTrades(sb: StringBuilder, open: List<LiveTrade>) {
        sb.append("## Open trades (${open.size})\n\n")
        if (open.isEmpty()) {
            sb.append("None.\n\n")
            return
        }
        table(sb, listOf("Entered (UTC)", "Coin", "Pattern", "Chart", "Entry", "Stop", "Target", "Exit style", "BTC regime"), open.take(OPEN_SHOWN).map { t ->
            listOf(utc(t.trade.entryTime), t.trade.symbol.removeSuffix("USDT"), t.trade.variant, t.trade.tf.label, price(t.trade.entryPrice),
                price(t.trade.stop), price(t.trade.target), t.trade.exitMode ?: MISSING, regime(t.trade.regime))
        })
        if (open.size > OPEN_SHOWN) sb.append("${open.size - OPEN_SHOWN} older open trades are left out.\n\n")
    }

    private fun latestClosed(sb: StringBuilder, closed: List<LiveTrade>) {
        sb.append("## Latest closed trades (${minOf(closed.size, LATEST_CLOSED)} of ${closed.size})\n\n")
        if (closed.isEmpty()) {
            sb.append("None yet.\n\n")
            return
        }
        val latest = closed.sortedByDescending { it.exit!!.exitTime }.take(LATEST_CLOSED)
        table(sb, listOf("Closed (UTC)", "Coin", "Pattern", "Chart", "Exit", "Candles", "Result", "Random", "Vs random", "Best rise", "Worst dip", "BTC regime"),
            latest.map { t ->
                val x = t.exit!!
                listOf(utc(x.exitTime), t.trade.symbol.removeSuffix("USDT"), t.trade.variant, t.trade.tf.label, x.reason.label, "${x.barsHeld}",
                    pct(x.net), pct(x.randomMean), pct(x.excess), pct(x.maxUp), pct(x.maxDown), regime(t.trade.regime))
            })
    }

    private fun focus(sb: StringBuilder, focus: TradeFocus, closed: List<LiveTrade>) {
        val t = focus.trade
        val n = t.trade
        val x = t.exit
        sb.append("## The trade to explain\n\n")
        table(sb, listOf("Field", "Value"), listOfNotNull(
            listOf("Trade number", "${t.id}"),
            listOf("Coin", n.symbol),
            listOf("Pattern", "${n.variant}: ${PatternLabels.describe(n.variant)}"),
            listOf("Chart", n.tf.label),
            listOf("Signal candle opened (UTC)", utc(n.barTime)),
            listOf("Entered (UTC)", utc(n.entryTime)),
            listOf("Entry price", price(n.entryPrice)),
            listOf("Stop", price(n.stop)),
            listOf("Target", price(n.target)),
            listOf("Exit style", n.exitMode ?: MISSING),
            listOf("Candle size at the signal (ATR)", price(n.atr)),
            listOf("Cost charged", n.cost?.let { pct(it, false) } ?: MISSING),
            listOf("BTC regime", regime(n.regime)),
            x?.let { listOf("Closed (UTC)", utc(it.exitTime)) },
            x?.let { listOf("Exit price", price(it.exitPrice)) },
            x?.let { listOf("Why it closed", it.reason.label) },
            x?.let { listOf("Candles held", "${it.barsHeld}") },
            x?.let { listOf("Result after costs", pct(it.net)) },
            x?.let { listOf("Random entries' average", pct(it.randomMean)) },
            x?.let { listOf("Vs random", pct(it.excess)) },
            x?.let { listOf("Best rise while open", pct(it.maxUp)) },
            x?.let { listOf("Worst dip while open", pct(it.maxDown)) },
            x?.let { listOf("Candles to its best rise", it.barsToPeak?.toString() ?: MISSING) },
            if (x == null) listOf("Status", "still open") else null,
        ))
        candles(sb, focus)
        val others = closed.filter { it.id != t.id && it.trade.variant == n.variant && it.trade.tf == n.tf && it.trade.symbol == n.symbol }
            .sortedByDescending { it.exit!!.exitTime }
        sb.append("## This pattern's other closed trades on ${n.symbol.removeSuffix("USDT")} (${n.tf.label})\n\n")
        if (others.isEmpty()) {
            sb.append("None.\n\n")
        } else {
            table(sb, listOf("Closed (UTC)", "Exit", "Candles", "Result", "Vs random", "Best rise", "Worst dip"), others.take(OTHER_TRADES_SHOWN).map { o ->
                val ox = o.exit!!
                listOf(utc(ox.exitTime), ox.reason.label, "${ox.barsHeld}", pct(ox.net), pct(ox.excess), pct(ox.maxUp), pct(ox.maxDown))
            })
        }
    }

    private fun candles(sb: StringBuilder, focus: TradeFocus) {
        val n = focus.trade.trade
        val c = focus.candles
        sb.append("## Candles around it (${n.tf.label}, UTC)\n\n")
        if (c == null || c.size == 0) {
            sb.append("The candles of this stretch are no longer kept on the phone.\n\n")
            return
        }
        val ms = n.tf.ms
        val signal = c.t.indexOfFirst { it >= n.barTime }.takeIf { it >= 0 } ?: 0
        val exitTime = focus.trade.exit?.exitTime
        val exitIdx = if (exitTime == null) c.size - 1 else c.t.indexOfLast { it <= exitTime }.coerceAtLeast(signal)
        val from = (signal - CANDLES_BEFORE).coerceAtLeast(0)
        val to = minOf(exitIdx + CANDLES_AFTER, c.size - 1, from + MAX_CANDLES - 1)
        table(sb, listOf("Opened", "Open", "High", "Low", "Close", "Volume", "Note"), (from..to).map { i ->
            val note = buildList {
                if (c.t[i] == n.barTime) add("signal candle")
                if (c.t[i] <= n.entryTime && n.entryTime < c.t[i] + ms) add("entry at its open")
                if (exitTime != null && c.t[i] <= exitTime && exitTime < c.t[i] + ms) add("exit")
            }.joinToString(", ")
            listOf(utc(c.t[i]), price(c.open[i]), price(c.high[i]), price(c.low[i]), price(c.close[i]), price(c.volume[i]), note)
        })
        if (to < exitIdx) sb.append("The trade lasted longer than the ${MAX_CANDLES} candles shown.\n\n")
    }

    private fun regime(r: Int): String = when (r) {
        1 -> "above ${EngineConfig.REGIME_MA_DAYS}d"
        0 -> "below ${EngineConfig.REGIME_MA_DAYS}d"
        else -> "unknown"
    }

    private fun table(sb: StringBuilder, header: List<String>, rows: List<List<String>>) {
        sb.append("| ").append(header.joinToString(" | ")).append(" |\n")
        sb.append("|").append(header.joinToString("|") { "---" }).append("|\n")
        for (r in rows) sb.append("| ").append(r.joinToString(" | ") { it.replace('|', '/').replace('\n', ' ') }).append(" |\n")
        sb.append('\n')
    }

    private fun List<Double>.averageOrNull(): Double? = filter { !it.isNaN() }.takeIf { it.isNotEmpty() }?.average()

    /** A fraction as a percentage with two decimals, signed unless [signed] is false; a dash when there is none. */
    internal fun pct(f: Double?, signed: Boolean = true): String =
        if (f == null || f.isNaN() || f.isInfinite()) MISSING else String.format(Locale.ROOT, if (signed) "%+.2f%%" else "%.2f%%", f * 100)

    private fun share(f: Double?): String = if (f == null || f.isNaN()) MISSING else String.format(Locale.ROOT, "%.0f%%", f * 100)

    /** A price or a volume to eight significant digits, without trailing zeros or an exponent. */
    internal fun price(p: Double?): String =
        if (p == null || p.isNaN() || p.isInfinite()) MISSING else BigDecimal(p).round(MathContext(8)).stripTrailingZeros().toPlainString()

    internal fun utc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
