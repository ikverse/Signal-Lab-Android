package com.ikverse.signallab.ui

import kotlinx.coroutines.flow.StateFlow

/*
 * What the screens may see and do. Everything here is plain data and small interfaces: the screens import nothing from the
 * data layer (a test holds them to it), so they can be tried with made-up data and cannot reach into a database by accident.
 * The `state` package implements these on top of the real thing.
 */

data class CoinUi(
    val symbol: String,
    val base: String,
    /** The last closed price from what is stored; the screen swaps in the live price while the stream runs. */
    val price: Double?,
    /** Change over the last 24 hours, as a fraction; null when there is not enough stored to say. */
    val changeFraction: Double?,
    val openTrades: Int,
    /** The chart sizes this coin is watched on, shortest first, as the labels the lists use ("15m", "1h"). */
    val timeframes: List<String>,
)

data class CandleUi(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

enum class LevelKind { ENTRY, STOP, TARGET }

data class LevelUi(val kind: LevelKind, val label: String, val price: Double)

data class ChartUi(val symbol: String, val timeframe: String, val candles: List<CandleUi>, val levels: List<LevelUi>)

data class ListUi(val id: Long, val name: String, val active: Boolean, val coins: List<String>, val timeframes: List<String>)

data class OfferUi(val symbol: String, val base: String, val quoteVolume: Double)

data class DownloadUi(val total: Int = 0, val ready: Int = 0, val current: String? = null, val running: Boolean = false, val failures: Map<String, String> = emptyMap())

/** The answer to a change the user asked for: done, or refused with the reason in plain words. */
sealed interface Outcome {
    data object Done : Outcome
    data class Refused(val message: String) : Outcome
}

data class ClosedUi(
    val exitTime: Long,
    val exitPrice: Double,
    /** "target", "stop" or "time". */
    val reason: String,
    val net: Double,
    val randomMean: Double?,
    val maxUp: Double?,
    val maxDown: Double?,
    val barsHeld: Int,
)

data class TradeUi(
    val id: Long,
    val variant: String,
    val label: String,
    val symbol: String,
    val timeframe: String,
    val openedAt: Long,
    val entryPrice: Double,
    val stop: Double?,
    val target: Double?,
    /** How the trade exits: "trail", "learned", "held", "classic", or null for a trade from before exits were chosen. */
    val exitMode: String?,
    val closed: ClosedUi?,
)

data class ScoreRowUi(
    val variant: String,
    val label: String,
    val timeframe: String,
    val open: Int,
    val closed: Int,
    val hitRate: Double?,
    val meanNet: Double?,
    val randomMean: Double?,
    /** The average of each trade's result minus what random entries did over the same stretch. */
    val excess: Double?,
    val tCluster: Double?,
    /** "No verdict", "No edge", "Edge", "Losing", or the forward-only wording. */
    val verdict: String,
    /** How firm that is, by the number of trades behind it: "No verdict", "Early read", "Provisional" or "Meaningful". */
    val firmness: String,
    val forwardOnly: Boolean,
)

data class ScorecardUi(val rows: List<ScoreRowUi> = emptyList(), val patternsTested: Int = 0)

data class AlertUi(val id: Long, val time: Long, val kind: String, val title: String, val body: String, val symbol: String?, val timeframe: String?)

data class LearnPageUi(val id: String, val group: String, val title: String, val markdown: String)

data class PermissionsUi(val alerts: Boolean, val exactAlarms: Boolean, val batteryExempt: Boolean)

data class SettingsUi(
    /** One way, as a fraction: 0.001 is Binance's entry tier. */
    val feePerSide: Double,
    val extraMajors: Double,
    val extraOthers: Double,
    val backgroundScanning: Boolean,
    val followFastCharts: Boolean,
    val binanceUs: Boolean,
    val permissions: PermissionsUi,
    val version: String,
    /** Where the data lives, in plain words. */
    val dataNote: String,
)

interface ListsModel {
    val lists: StateFlow<List<ListUi>>

    /** False until the first read of the saved lists finishes, so "no lists yet" is not shown while they are still loading. */
    val loaded: StateFlow<Boolean>
    val download: StateFlow<DownloadUi>

    /** The coins the picker offers for [query], biggest 24-hour volume first. Empty query: the biggest. */
    suspend fun offers(query: String): List<OfferUi>

    suspend fun create(name: String, symbols: List<String>, timeframes: Set<String>, activate: Boolean): Outcome
    suspend fun rename(id: Long, name: String): Outcome
    suspend fun delete(id: Long): Outcome
    suspend fun setActive(id: Long, active: Boolean): Outcome
    suspend fun addCoin(id: Long, symbol: String): Outcome
    suspend fun removeCoin(id: Long, symbol: String): Outcome
    suspend fun setTimeframes(id: Long, timeframes: Set<String>): Outcome

    /** The chart sizes a list may be watched on, shortest first, with a short plain-words note for each. */
    val allTimeframes: List<Pair<String, String>>
}

interface MarketsModel {
    val coins: StateFlow<List<CoinUi>>

    /** The live price of every coin the stream is quoting. */
    val prices: StateFlow<Map<String, Double>>

    /** Goes up when stored candles or trades change, so the chart reloads. */
    val changes: StateFlow<Long>

    suspend fun chart(symbol: String, timeframe: String): ChartUi?

    /** Asks for live prices of [symbols] until the handle is closed (the stream only runs while a screen wants it). */
    fun watchPrices(symbols: Set<String>): AutoCloseable
}

interface TradesModel {
    val trades: StateFlow<List<TradeUi>>
}

interface ScorecardModel {
    val scorecard: StateFlow<ScorecardUi>
}

interface AlertsModel {
    val alerts: StateFlow<List<AlertUi>>
}

interface LearnModel {
    val pages: List<LearnPageUi>

    /** The page that explains [variant] (a pattern's name such as "donchian20_1d"), or null. */
    fun pageForVariant(variant: String): String?
}

interface SettingsModel {
    val settings: StateFlow<SettingsUi>
    suspend fun setFee(feePerSide: Double): Outcome
    suspend fun setExtraCosts(majors: Double, others: Double): Outcome
    suspend fun setBackgroundScanning(on: Boolean)
    suspend fun setFollowFastCharts(on: Boolean)
    suspend fun setBinanceUs(on: Boolean)

    /** Opens Android's own screen for one of the permissions. */
    fun openSystemScreen(prompt: PermissionPrompt)
}

/** Where a notification or a link points: a coin, and optionally one of its charts. */
data class Link(val symbol: String, val timeframe: String?)

/** Reads "signallab://coin/SOLUSDT?tf=1h". Anything else is not a link to a coin. */
fun parseLink(text: String?): Link? {
    if (text == null || !text.startsWith("signallab://coin/")) return null
    val rest = text.removePrefix("signallab://coin/")
    val symbol = rest.substringBefore('?').takeIf { it.isNotBlank() } ?: return null
    val tf = rest.substringAfter("tf=", "").substringBefore('&').takeIf { it.isNotBlank() }
    return Link(symbol, tf)
}

interface AppModel {
    val lists: ListsModel
    val markets: MarketsModel
    val trades: TradesModel
    val scorecard: ScorecardModel
    val alerts: AlertsModel
    val learn: LearnModel
    val settings: SettingsModel
    val prompts: PermissionPrompts

    /** A link the app was opened with (a notification tap), until the screens have used it. */
    val pendingLink: StateFlow<Link?>
    fun linkUsed()

    /** The temporary debug page; null in release builds. */
    val debug: DebugState?
}
