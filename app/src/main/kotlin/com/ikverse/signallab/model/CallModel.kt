package com.ikverse.signallab.model

import com.ikverse.signallab.catalog.StrategyCatalog
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.view.chart.ChartData
import com.ikverse.signallab.view.chart.Level
import com.ikverse.signallab.view.common.Words

/** One call's page. */
data class CallPageUi(
    val call: CallUi,
    val chart: ChartData?,
    /** The latest close; for a finished call, its exit price. */
    val price: Double?,
    /** The line a daily close must stay above, as of now; null for a finished call or a strategy without one. */
    val exitLevel: Double?,
    /** The plan, as label and value rows. */
    val plan: List<Pair<String, String>>,
    /** How it ended, when it has. */
    val ended: ClosedUi?,
    /** Earlier calls of the same strategy on the same coin, and how many ended up. */
    val earlier: Int,
    val earlierUp: Int,
    /** The owner's own copy of the call on Binance; null when they have not said they took it. */
    val copy: CopyUi? = null,
    /** The price "I sold" starts from: the paper exit, or the latest close. */
    val sellPrice: Double? = null,
)

/** The owner's copy of a call, beside the paper call. */
data class CopyUi(
    val boughtPrice: Double,
    val soldPrice: Double?,
    /** How far the real buy sat from the paper entry; positive is paying more. */
    val entryGap: Double,
    /** The real result after the call's assumed fees, once sold. */
    val net: Double?,
    /** The paper result, once the paper call ended. */
    val paperNet: Double?,
) {
    val boughtLine: String get() = "Bought at ${Words.price(boughtPrice)}, ${gapWords(entryGap)} the paper entry."
    val soldLine: String?
        get() = soldPrice?.let { p ->
            "Sold at ${Words.price(p)}: ${Words.pct(net ?: 0.0)} after fees" + (paperNet?.let { ", paper ${Words.pct(it)}" } ?: "") + "."
        }

    companion object {
        fun gapWords(g: Double): String = when {
            Math.abs(g) < 0.0005 -> "the same as"
            g > 0 -> "${Words.pct(g).removePrefix("+")} above"
            else -> "${Words.pct(-g).removePrefix("+")} below"
        }

        fun of(copy: com.ikverse.signallab.ledger.CopyRow, entry: Double, cost: Double, paperNet: Double?) = CopyUi(
            boughtPrice = copy.boughtPrice, soldPrice = copy.soldPrice, entryGap = copy.boughtPrice / entry - 1,
            net = copy.soldPrice?.let { it / copy.boughtPrice - 1 - cost }, paperNet = paperNet,
        )
    }
}

class CallModel(
    private val dao: LedgerDao,
    private val candles: CandleStore,
    private val strategies: () -> List<Strategy>,
    private val preferences: suspend () -> Preferences = { Preferences() },
) {
    suspend fun page(id: Long, account: Double): CallPageUi? {
        val row = dao.allCalls().firstOrNull { it.id == id } ?: return null
        val s = StrategyCatalog.forCall(row, strategies().associateBy { it.id }) ?: return null
        val c = candles.window(row.symbol, Timeframe.D1)
        val p = preferences()
        val ui = TodayModel.callUi(row, s, c, account, p.tierOf(s), p.suggestions.firstOrNull { it.callId == row.id }?.thesis,
            p.tradeMoney, p.tradeMoney?.let { candles.lotRule(row.symbol) })
        val exit = dao.exitOf(id)
        val ended = exit?.let { ClosedUi(ui, it.exitTime, it.exitPrice, it.reason, it.net, it.net * ui.sizeUsd) }
        val level = if (exit == null && c != null) StrategyTexts.exitLevel(s, c) else null
        val levels = buildList {
            add(Level(Level.ENTRY, "entry ${Words.price(row.entryPrice)}", row.entryPrice))
            row.stop?.let { add(Level(Level.STOP, "safety stop ${Words.price(it)}", it)) }
            row.target?.let { add(Level(Level.EXIT, "target ${Words.price(it)}", it)) }
            level?.let { add(Level(Level.EXIT, "exit below ${Words.price(it)}", it)) }
        }
        val chart = c?.let { all ->
            val from = maxOf(0, all.t.indexOfFirst { it >= row.signalTime - 90 * DAY }.coerceAtLeast(0))
            ChartData(row.symbol, "1d", all.slice(from, all.size), levels)
        }
        val plan = buildList {
            add("Enter" to "${Words.price(row.entryPrice)} at the open of ${Words.day(row.entryTime)}")
            add("Exit when" to (level?.let { "a daily close is below ${Words.price(it)}, or by ${Words.day(ui.lastDay)}" }
                ?: "${StrategyTexts.exitShort(s)}, or by ${Words.day(ui.lastDay)}"))
            row.stop?.let { add("Safety stop" to "${Words.price(it)} (${Words.pct(it / row.entryPrice - 1)})") }
            row.target?.let { add("Target" to "${Words.price(it)} (${Words.pct(it / row.entryPrice - 1)})") }
            ui.buyLine?.let { add("On Binance" to it) }
            add("Size" to when {
                ui.veto != null -> "skipped: ${ui.veto}"
                ui.sizeFraction > 0 -> "${Words.usd(ui.sizeUsd)} of a ${Words.usd(account)} paper account"
                else -> "tracked only: the account was full"
            })
            if (row.plainSize != null && ui.veto == null && Math.abs(row.plainSize - ui.sizeFraction) > 0.005) {
                add("Plain size" to "${Words.share(row.plainSize)} before the sizing engine")
            }
            if (ui.sizeFraction > 0 && row.stop != null) add("Worst case at the stop" to Words.usd((row.stop / row.entryPrice - 1) * ui.sizeUsd, signed = true))
            add("Fees" to "${Words.pct(row.cost).removePrefix("+")} round trip")
        }
        val earlierCalls = dao.allCalls().filter { it.strategyId == row.strategyId && it.symbol == row.symbol && it.signalTime < row.signalTime }
        val earlierUp = earlierCalls.count { (dao.exitOf(it.id)?.net ?: 0.0) > 0 }
        val copy = dao.copyOf(id)?.let { CopyUi.of(it, row.entryPrice, row.cost, exit?.net) }
        return CallPageUi(ui, chart, exit?.exitPrice ?: c?.close?.lastOrNull(), level, plan, ended, earlierCalls.size, earlierUp,
            copy, exit?.exitPrice ?: c?.close?.lastOrNull())
    }

    /** The owner's copies, so a page showing one is drawn again when it changes. */
    val copies get() = dao.watchCopies()

    /** The owner bought this call on Binance at [price]. */
    suspend fun took(id: Long, price: Double, now: Long = System.currentTimeMillis()) {
        if (!(price > 0)) return
        val old = dao.copyOf(id)
        dao.putCopy(com.ikverse.signallab.ledger.CopyRow(id, price, old?.boughtAt ?: now, old?.soldPrice, old?.soldAt, now))
    }

    /** The owner sold their copy at [price]. Nothing happens unless they said they bought it. */
    suspend fun sold(id: Long, price: Double, now: Long = System.currentTimeMillis()) {
        if (!(price > 0)) return
        val old = dao.copyOf(id) ?: return
        dao.putCopy(old.copy(soldPrice = price, soldAt = old.soldAt ?: now, updatedAt = now))
    }

    /** The owner did not take it after all. */
    suspend fun forget(id: Long) = dao.removeCopy(id)

    private companion object {
        const val DAY = 86_400_000L
    }
}
