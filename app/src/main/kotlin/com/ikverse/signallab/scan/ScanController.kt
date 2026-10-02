package com.ikverse.signallab.scan

import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.TradeLog
import com.ikverse.signallab.data.binance.MarketData
import com.ikverse.signallab.engine.CandleClock
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Decides when to scan, scans, and sets the next alarm. It does not know about Android: the alarm is
 * an interface, and so is the pause, which makes the whole schedule testable with a fake clock.
 *
 * What is due is read from the scanner's cursor rather than from which alarm fired, so a late alarm,
 * a reboot, and the app being opened all do the same thing: scan whatever has closed since the last
 * complete scan, and set the next alarm. Charts of an hour and longer are woken by that alarm; charts
 * under an hour close too often for an alarm, so [runFast] keeps watching them while the service is awake.
 * Charts scan at the same time, each on its own.
 */
class ScanController(
    private val scanner: Scanner,
    private val market: MarketData,
    private val alarms: AlarmScheduler,
    private val log: TradeLog,
    private val sink: AlertSink,
    private val health: ScanHealth,
    /** Whether anything is being watched or still open, so there is a reason to wake at all. */
    private val hasWork: suspend () -> Boolean,
    /** How far the phone's clock is from Binance's. */
    private val skewMs: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** Once-a-day tidying, such as deleting candles that are no longer needed. It decides for itself whether it is due. */
    private val housekeeping: suspend () -> Unit = {},
    /** Whether the user lets the service follow charts under an hour. */
    private val followFast: suspend () -> Boolean = { true },
) {
    private val startedAt = market.nowMs()

    /** Scans every chart in use (or just [only]) whose newest candle has not been handled yet, then sets the next alarm. */
    suspend fun scanDue(only: Collection<Timeframe>? = null) {
        try {
            settle()
            val inUse = scanner.timeframesInUse().filter { only == null || it in only }
            val due = inUse.filter { !scanner.isUpToDate(it) }
            coroutineScope { due.map { async { run(it) } }.awaitAll() }
            watchStalls(inUse)
            watchClock()
            if (only == null) housekeeping()
        } finally {
            arm()
        }
    }

    /** Scans every chart in use now, whether or not it is due. Safe to repeat: a scan never opens the same trade twice. */
    suspend fun scanAll() {
        try {
            settle()
            val inUse = scanner.timeframesInUse()
            coroutineScope { inUse.map { async { run(it) } }.awaitAll() }
        } finally {
            arm()
        }
    }

    /**
     * Stays with the 1-minute to 30-minute charts: waits for each close, scans right after it, and goes on for as long as
     * any list is watched on one. Returns when none is, so the service knows it can let the phone sleep. The caller keeps the
     * phone awake and cancels this to stop it.
     */
    suspend fun runFast() {
        health.fast(true)
        try {
            while (hasWork() && followFast()) {
                val fast = scanner.timeframesInUse().filter { it.isFast }
                if (fast.isEmpty()) return
                val next = CandleClock.nextClose(market.nowMs(), fast) + DataConfig.SCAN_SETTLE_MS
                val wait = next - market.nowMs()
                if (wait > 0) pause(wait)
                scanDue(only = fast)
            }
        } finally {
            health.fast(false)
        }
    }

    /** Sets the next alarm (for the charts of an hour and longer), or none when there is nothing to watch. */
    suspend fun arm() {
        if (hasWork()) {
            val slow = scanner.timeframesInUse().filter { !it.isFast }.ifEmpty { listOf(Timeframe.H1) }
            val next = CandleClock.nextClose(market.nowMs(), slow)
            alarms.armAt(next + DataConfig.SCAN_SETTLE_MS)
            health.armed(next)
        } else {
            alarms.cancel()
            health.armed(null)
        }
    }

    /** Gives the exchange a few seconds after a close to publish the candle and the next one's first price. */
    private suspend fun settle() {
        val now = market.nowMs()
        val sinceClose = now - CandleClock.lastClose(Timeframe.M1, now)
        if (sinceClose < DataConfig.SCAN_SETTLE_MS) pause(DataConfig.SCAN_SETTLE_MS - sinceClose)
    }

    /** Scans one timeframe, trying again while something is waiting and the entry candle has not ended. */
    private suspend fun run(tf: Timeframe) {
        val candleEnd = CandleClock.lastClose(tf, market.nowMs()) + tf.ms
        // A minute chart has a minute: retry quickly and give up early. An hourly one can afford to wait.
        val retryEvery = minOf(DataConfig.SCAN_RETRY_MS, tf.ms / 4)
        val margin = minOf(60_000L, tf.ms / 5)
        var only: Set<String>? = null
        var attempt = 0
        while (true) {
            health.started(tf)
            val r = try {
                scanner.scan(tf, only)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                health.failed(tf, "${tf.label} scan failed: ${e.message ?: e.javaClass.simpleName}")
                return
            }
            health.finished(r)
            val outOfTime = market.nowMs() + retryEvery >= candleEnd - margin
            if (!r.waiting || attempt >= DataConfig.SCAN_RETRIES || outOfTime) {
                raiseProblems(r)
                return
            }
            only = r.retryCoins
            attempt++
            pause(retryEvery)
        }
    }

    private suspend fun raiseProblems(r: ScanResult) {
        when {
            r.blocked -> raise(AlertText.blocked())
            r.failures.isNotEmpty() && r.failures.size * 2 >= r.coins.coerceAtLeast(1) -> {
                raise(AlertText.unreachable(r.failures.size, r.coins, r.failures.values.first()))
            }
        }
    }

    /** No complete scan of a chart for two of its candles, while something is being watched on it. */
    private suspend fun watchStalls(inUse: Collection<Timeframe>) {
        if (!hasWork()) return
        val now = market.nowMs()
        for (tf in inUse) {
            val since = maxOf(health.snapshot.value.lastSuccess[tf] ?: 0L, startedAt)
            if (now - since > DataConfig.STALL_CANDLES * tf.ms + DataConfig.SCAN_SETTLE_MS) raise(AlertText.stalled(tf, now - since))
        }
    }

    private suspend fun watchClock() {
        val skew = skewMs()
        if (kotlin.math.abs(skew) > DataConfig.CLOCK_SKEW_WARN_MS) raise(AlertText.clockSkew(skew))
    }

    /** Saves a problem and nudges once per cooldown, however many scans find it. */
    private suspend fun raise(text: AlertText.Text) {
        health.problem(text.title)
        if (log.now() - log.lastAlertTimeTitled(AlertText.KIND_PROBLEM, text.title) < DataConfig.PROBLEM_COOLDOWN_MS) return
        sink.deliver(listOf(log.record(AlertText.KIND_PROBLEM, text.title, text.body, link = text.link)))
    }
}
