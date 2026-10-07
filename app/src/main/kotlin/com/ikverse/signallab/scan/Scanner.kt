package com.ikverse.signallab.scan

import com.ikverse.signallab.data.Alert
import com.ikverse.signallab.data.CandleStore
import com.ikverse.signallab.data.CandleSync
import com.ikverse.signallab.data.CostModel
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.LiveTrade
import com.ikverse.signallab.data.NewTrade
import com.ikverse.signallab.data.SettingsStore
import com.ikverse.signallab.data.TradeExit
import com.ikverse.signallab.data.TradeLog
import com.ikverse.signallab.data.TradeStatus
import com.ikverse.signallab.data.binance.BinanceException
import com.ikverse.signallab.data.binance.Kline
import com.ikverse.signallab.data.binance.MarketData
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.CandleClock
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.ExitMode
import com.ikverse.signallab.engine.Indicators
import com.ikverse.signallab.engine.LabPattern
import com.ikverse.signallab.engine.LiveScan
import com.ikverse.signallab.engine.PaperTrading
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.Warnings
import com.ikverse.signallab.engine.Watchlist
import com.ikverse.signallab.engine.WatchlistRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one scan of one timeframe did. */
class ScanResult(
    val tf: Timeframe,
    /** Binance's clock when the scan began. */
    val at: Long,
    /** Coins whose candles were checked. */
    val coins: Int,
    /** Signals that fired on the candles examined, traded or not. */
    val signals: Int,
    val opened: Int,
    val closed: Int,
    val missed: Int,
    /** Coins whose candles could not be downloaded, with why. */
    val failures: Map<String, String>,
    /** Something could not be done yet (a first price, a fresh BTC regime, a failed download) and is worth another try before the candle ends. */
    val waiting: Boolean,
    /** The coins a retry has to download again. */
    val retryCoins: Set<String>,
    val blocked: Boolean,
    /** Set when there was nothing to scan. */
    val note: String? = null,
    /** Market warnings raised (a pump, a volume spike). */
    val warnings: Int = 0,
) {
    /** Nothing to scan counts as done: there was nothing to do. */
    val completed: Boolean get() = !waiting && failures.isEmpty()
}

/**
 * One pass over a timeframe, run after its candles close: bring the candles up to date, close the
 * paper trades that have ended, open the ones that signals have just started, and write an alert for
 * each. Closing comes first, so a trade that ends on a candle frees its coin for a signal on that
 * same candle, as the backtest does.
 *
 * Running it twice for the same candle changes nothing: a trade is unique by variant, coin, candle
 * and list, and a closed trade cannot close again. A cursor remembers the last candle handled, so a
 * signal on a candle the phone slept through is reported as missed, once, instead of traded late.
 *
 * Different timeframes scan at the same time, each under its own lock, so a long hourly scan never
 * makes a signal on a minute chart late.
 *
 * [lab] gives the lab patterns being forward-tested, which run beside the built-in ones.
 */
class Scanner(
    private val market: MarketData,
    private val sync: CandleSync,
    private val candles: CandleStore,
    private val log: TradeLog,
    private val settings: SettingsStore,
    private val lists: () -> List<Watchlist>,
    private val sink: AlertSink,
    private val lab: suspend () -> List<LabPattern> = { emptyList() },
) {
    private val locks = Timeframe.entries.associateWith { Mutex() }

    private fun cursorKey(tf: Timeframe) = "scan_cursor_${tf.label}"

    /** The close time of the newest candle that has been handled, or null if none has. */
    suspend fun cursor(tf: Timeframe): Long? = settings.get(cursorKey(tf))?.toLongOrNull()

    /** True when the newest closed candle of [tf] has been handled completely. */
    suspend fun isUpToDate(tf: Timeframe): Boolean = cursor(tf) == CandleClock.lastClose(tf, market.nowMs()) - 1

    /** The charts that have a reason to be scanned: one an active list is watched on, or one with a paper trade still open. */
    suspend fun timeframesInUse(): Set<Timeframe> {
        val out = LinkedHashSet<Timeframe>()
        for (l in lists()) if (l.active) out.addAll(l.timeframes)
        for (t in log.trades(TradeStatus.OPEN, limit = Int.MAX_VALUE)) out.add(t.trade.tf)
        return Timeframe.entries.filterTo(LinkedHashSet()) { it in out }
    }

    /**
     * Scans [tf]. [only] limits which coins are downloaded again (a retry's failures); everything
     * else is read from what is stored.
     */
    suspend fun scan(tf: Timeframe, only: Set<String>? = null): ScanResult = locks.getValue(tf).withLock { scanLocked(tf, only) }

    private suspend fun scanLocked(tf: Timeframe, only: Set<String>?): ScanResult {
        // Everything is judged at the moment the scan begins: the candles it needs were closed by then,
        // and the sync below can only be later than that.
        val now = market.nowMs()
        val allLists = lists()
        val active = allLists.filter { it.active && tf in it.timeframes }
        val watched = candles.analysable(WatchlistRules.activeCoinsOn(allLists, tf)).toList()
        val open = log.trades(TradeStatus.OPEN, limit = Int.MAX_VALUE).filter { it.trade.tf == tf }.sortedBy { it.id }
        if (watched.isEmpty() && open.isEmpty()) {
            // Nothing is being watched on this chart. Forget the cursor, so switching a list on later is not
            // mistaken for a phone that slept through the time in between.
            settings.set(cursorKey(tf), "")
            return ScanResult(tf, now, 0, 0, 0, 0, 0, emptyMap(), false, emptySet(), false, note = "nothing to scan")
        }

        val costs = CostModel.load(settings)
        val since = now - DataConfig.historyDays(tf) * DAY_MS
        val regimeSince = now - DataConfig.historyDays(Timeframe.D1) * DAY_MS
        val failures = LinkedHashMap<String, String>()
        var blocked = false
        val coins = (watched + open.map { it.trade.symbol }).distinct()
        val forming = HashMap<String, Kline>()

        suspend fun download(symbol: String, timeframe: Timeframe, from: Long): Kline? {
            if (blocked) return null
            return try {
                sync.syncCoin(symbol, timeframe, from).forming
            } catch (e: CancellationException) {
                throw e
            } catch (e: BinanceException.Blocked) {
                blocked = true
                failures[symbol] = e.message ?: "blocked"
                null
            } catch (e: Exception) {
                failures[symbol] = e.message ?: e.javaClass.simpleName
                null
            }
        }

        download(DataConfig.REGIME_COIN, Timeframe.D1, regimeSince)
        for (symbol in coins) {
            if (only != null && symbol !in only) continue
            download(symbol, tf, since)?.let { forming[symbol] = it }
        }
        if (blocked) for (symbol in coins) failures.putIfAbsent(symbol, "blocked")

        val windows = HashMap<String, Candles>()
        for (symbol in coins) candles.window(symbol, tf, since)?.let { windows[symbol] = it }
        val btc = candles.window(DataConfig.REGIME_COIN, Timeframe.D1, regimeSince)
        val regimeOk = LiveScan.regimeIsCurrent(btc, now)

        val regimes = HashMap<String, IntArray>()
        fun regimeOf(c: Candles): IntArray = regimes.getOrPut(c.symbol) {
            if (btc == null) IntArray(c.size) { -1 } else PaperTrading.regimeSeries(btc, c.closeTime)
        }
        val atrs = HashMap<String, DoubleArray>()
        fun atrOf(c: Candles): DoubleArray = atrs.getOrPut(c.symbol) { Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW) }

        val alerts = ArrayList<Alert>()
        val notify = ArrayList<Alert>()
        val pending = LinkedHashSet<String>()
        var opened = 0
        var closed = 0
        var missed = 0
        var warned = 0

        // 1. Trades that have ended. They need the regime for their random baseline, so they wait for a current one.
        if (regimeOk) {
            for (lt in open) {
                val c = windows[lt.trade.symbol] ?: continue
                val result = LiveScan.close(openTradeOf(lt), c, atrOf(c), regimeOf(c)) ?: continue
                val exit = TradeExit(
                    exitTime = result.exitTime, exitPrice = result.exitPrice, reason = result.reason, barsHeld = result.barsHeld,
                    gross = result.gross, net = result.net,
                    randomMean = result.randomMean.takeUnless { it.isNaN() }, excess = result.excess.takeUnless { it.isNaN() },
                    maxUp = result.maxUp, maxDown = result.maxDown, barsToPeak = result.barsToPeak,
                )
                if (!log.close(lt.id, exit)) continue
                closed++
                val text = AlertText.closed(lt.trade.variant, lt.trade.symbol, tf, result.reason, result.net, result.randomMean, lt.id)
                log.record(AlertText.KIND_EXIT, text.title, text.body, lt.trade.symbol, tf.label, text.link, text.facts).also { alerts.add(it); notify.add(it) }
            }
        }

        // 2. What fired on the candles that closed.
        val panel = LinkedHashMap<String, Candles>()
        for (symbol in watched) windows[symbol]?.let { panel[symbol] = it }
        val labPatterns = try {
            lab()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The lab could not be read: the built-in patterns still run, and the lab runs again on the next scan.
            emptyList()
        }
        val scan = LiveScan.scan(tf, panel, active.associate { it.id to it.symbols }, cursor(tf), now, labPatterns)
        for (key in scan.variants) log.registerVariant(key, tf)
        val found = scan.found.sortedWith(compareBy({ it.barTime }, { it.key.name }, { it.symbol }, { it.listId }))
        for (f in found) {
            val c = windows.getValue(f.symbol)
            if (!f.tradeable) {
                missed++
                val text = AlertText.missed(f.key.name, f.symbol, tf)
                alerts.add(log.record(AlertText.KIND_MISSED, text.title, text.body, f.symbol, tf.label, text.link))
                continue
            }
            if (!regimeOk) {
                pending.add(f.symbol)
                continue
            }
            if (log.hasOpen(f.key.name, f.symbol, f.listId)) continue
            val entryOpen = entryOpen(c, c.t[f.barIdx] + tf.ms, forming[f.symbol])
            if (entryOpen == null) {
                pending.add(f.symbol)
                continue
            }
            val plan = LiveScan.plan(f, c, atrOf(c), regimeOf(c), entryOpen, costs.costFor(f.symbol), scan.ruleFor(f, panel)) ?: continue
            val trade = NewTrade(
                variant = plan.variant, family = plan.family, symbol = plan.symbol, tf = plan.tf, listId = plan.listId,
                barTime = plan.barTime, detectedAt = plan.detectedAt, regime = plan.regime, entryTime = plan.entryTime,
                entryPrice = plan.entryPrice, target = plan.target, stop = plan.stop, holdBars = plan.limit, exitDue = plan.exitDue,
                cost = plan.cost, exitMode = plan.mode.label, atr = plan.atr,
            )
            val tradeId = log.open(trade) ?: continue
            opened++
            val text = AlertText.opened(plan, isNewCoin(plan.symbol, now), tradeId)
            log.record(AlertText.KIND_SIGNAL, text.title, text.body, plan.symbol, tf.label, text.link, text.facts).also { alerts.add(it); notify.add(it) }
        }

        // 3. Warnings: shown, never traded. Only for the candle that just closed, and not again within a cooldown.
        val newest = LiveScan.newestClosedOpen(tf, now)
        for (symbol in watched) {
            val c = windows[symbol] ?: continue
            if (c.size == 0 || c.t[c.size - 1] != newest) continue
            val text = when {
                tf == Timeframe.M1 || tf == Timeframe.M5 -> Warnings.pump(c)?.let { AlertText.pump(symbol, tf, it, isNewCoin(symbol, now)) }
                tf == Timeframe.D1 -> Warnings.volumeSpike(c)?.let { AlertText.volumeSpike(symbol, it, isNewCoin(symbol, now)) }
                else -> null
            } ?: continue
            val cooldown = if (tf == Timeframe.D1) DataConfig.VOLUME_SPIKE_COOLDOWN_MS else DataConfig.PUMP_COOLDOWN_MS
            if (log.now() - log.lastAlertTimeTitled(AlertText.KIND_WARNING, text.title) < cooldown) continue
            warned++
            log.record(AlertText.KIND_WARNING, text.title, text.body, symbol, tf.label, text.link).also { alerts.add(it); notify.add(it) }
        }

        val retryCoins = LinkedHashSet<String>().apply { addAll(failures.keys); addAll(pending) }
        val waiting = !blocked && (failures.isNotEmpty() || pending.isNotEmpty() || !regimeOk)

        // Everything up to the newest candle is handled, except that while something is waiting that candle is
        // kept open, so the next pass looks at it again and a signal on it is still tradeable.
        val newestClose = CandleClock.lastClose(tf, now) - 1
        settings.set(cursorKey(tf), (if (waiting) newestClose - tf.ms else newestClose).toString())

        if (notify.isNotEmpty()) sink.deliver(notify)
        return ScanResult(tf, now, coins.size, found.size, opened, closed, missed, failures, waiting, retryCoins, blocked, warnings = warned)
    }

    /** True when Binance listed [symbol] less than a month ago, as far as is known. */
    private suspend fun isNewCoin(symbol: String, now: Long): Boolean {
        val listed = candles.listedAt(symbol) ?: return false
        return now - listed < DataConfig.NEW_COIN_DAYS * DAY_MS
    }

    /** The price a trade entering at [entryTime] gets: that candle's open, from the store if it has closed, else as Binance served it while forming. */
    private fun entryOpen(c: Candles, entryTime: Long, forming: Kline?): Double? {
        val i = c.t.binarySearch(entryTime)
        if (i >= 0) return c.open[i]
        return forming?.takeIf { it.openTime == entryTime }?.open
    }

    private fun openTradeOf(t: LiveTrade): LiveScan.OpenTrade {
        val tr = t.trade
        return LiveScan.OpenTrade(
            variant = tr.variant, symbol = tr.symbol, tf = tr.tf, entryTime = tr.entryTime, entryPrice = tr.entryPrice,
            mode = ExitMode.fromStored(tr.exitMode, hasTarget = tr.target != null),
            target = tr.target, stop = tr.stop, atr = tr.atr ?: Double.NaN, limit = tr.holdBars,
            // A trade from before the cost was kept was charged the research's costs, and still is.
            cost = tr.cost ?: EngineConfig.costFor(tr.symbol),
        )
    }

    companion object {
        private const val DAY_MS = 86_400_000L
    }
}
