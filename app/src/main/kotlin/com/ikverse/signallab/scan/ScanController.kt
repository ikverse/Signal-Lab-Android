package com.ikverse.signallab.scan

import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.TradeLog
import com.ikverse.signallab.data.binance.MarketData
import com.ikverse.signallab.engine.CandleClock
import com.ikverse.signallab.engine.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Decides when to scan, scans, and sets the next alarm. It does not know about Android: the alarm is
 * an interface, and so is the pause, which makes the whole schedule testable with a fake clock.
 *
 * What is due is read from the scanner's cursor rather than from which alarm fired, so a late alarm,
 * a reboot, and the app being opened all do the same thing: scan whatever has closed since the last
 * complete scan, and set the next alarm.
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
) {
    private val startedAt = market.nowMs()

    /** Scans every timeframe whose newest candle has not been handled, then sets the next alarm. */
    suspend fun scanDue() {
        try {
            settle()
            for (tf in Timeframe.entries) {
                if (scanner.isUpToDate(tf)) continue
                run(tf)
            }
            watchStalls()
            watchClock()
        } finally {
            arm()
        }
    }

    /** Scans every timeframe now, whether or not it is due. Safe to repeat: a scan never opens the same trade twice. */
    suspend fun scanAll() {
        try {
            settle()
            for (tf in Timeframe.entries) run(tf)
        } finally {
            arm()
        }
    }

    /** Sets the next alarm, or none when there is nothing to watch. */
    suspend fun arm() {
        if (hasWork()) {
            val next = CandleClock.nextClose(market.nowMs())
            alarms.armAt(next + DataConfig.SCAN_SETTLE_MS)
            health.armed(next)
        } else {
            alarms.cancel()
            health.armed(null)
        }
    }

    /** Gives the exchange a few seconds after a close to publish the candle and the next one's first price. */
    private suspend fun settle() {
        val sinceClose = market.nowMs() - CandleClock.lastClose(Timeframe.H1, market.nowMs())
        if (sinceClose < DataConfig.SCAN_SETTLE_MS) pause(DataConfig.SCAN_SETTLE_MS - sinceClose)
    }

    /** Scans one timeframe, trying again while something is waiting and the entry candle has not ended. */
    private suspend fun run(tf: Timeframe) {
        val candleEnd = CandleClock.lastClose(tf, market.nowMs()) + tf.ms
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
            val outOfTime = market.nowMs() + DataConfig.SCAN_RETRY_MS >= candleEnd - 60_000
            if (!r.waiting || attempt >= DataConfig.SCAN_RETRIES || outOfTime) {
                raiseProblems(r)
                return
            }
            only = r.retryCoins
            attempt++
            pause(DataConfig.SCAN_RETRY_MS)
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

    /** No complete scan of a timeframe for two of its candles, while something is being watched. */
    private suspend fun watchStalls() {
        if (!hasWork()) return
        val now = market.nowMs()
        for (tf in Timeframe.entries) {
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
