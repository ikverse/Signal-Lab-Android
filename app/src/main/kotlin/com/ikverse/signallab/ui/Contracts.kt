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
    /** Closes over the last day, oldest first, for the small line beside the price; empty when there are too few. */
    val spark: List<Double> = emptyList(),
)

data class CandleUi(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

enum class LevelKind { ENTRY, STOP, TARGET }

/** A price line on the chart for one open paper trade: [tradeId] says which, so the chart can show one trade's lines at a time. */
data class LevelUi(val kind: LevelKind, val label: String, val price: Double, val tradeId: Long = 0)

data class ChartUi(val symbol: String, val timeframe: String, val candles: List<CandleUi>, val levels: List<LevelUi>)

data class ListUi(val id: Long, val name: String, val active: Boolean, val coins: List<String>, val timeframes: List<String>)

/** How the coin picker ranks the coins it offers. */
enum class PickSource(val label: String) {
    VOLUME("Volume"), GAINERS("Gainers"), LOSERS("Losers"), ACTIVE("Most trades"), VOLATILE("Volatile"), NEW("New"),
}

/** Over how long gainers and losers are measured. */
enum class PickWindow(val label: String) { H1("1h"), H24("24h"), D7("7d") }

/**
 * A coin the picker offers. [value] is the number it was ranked by, and means what the source says: the change as a fraction
 * (gainers, losers), the day's high-to-low range as a fraction (volatile), the trades in a day (most trades), the listing
 * time in milliseconds (new). Null for plain volume.
 */
data class OfferUi(val symbol: String, val base: String, val quoteVolume: Double, val value: Double? = null)

/** What the picker got for a request: the coins, and a sentence if Binance could not answer. */
data class OffersUi(val coins: List<OfferUi>, val problem: String? = null)

/** How far the lookup of when each coin was listed has got, for the "New" source. */
data class ListingCheckUi(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val failed: String? = null)

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
    /** The pattern's name for a row ("Bullish hikkake"); [label] is the full sentence. */
    val short: String = label,
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
    /** The pattern's name for a row; [label] is the full sentence. */
    val short: String = label,
)

data class ScorecardUi(val rows: List<ScoreRowUi> = emptyList(), val patternsTested: Int = 0)

/** One alert. [link] is where touching it goes (see [parseLink]); null for one that goes nowhere. [facts] are its numbers, for one about a trade. */
data class AlertUi(
    val id: Long, val time: Long, val kind: String, val title: String, val body: String, val symbol: String?, val timeframe: String?,
    val link: String? = null, val facts: AlertFacts? = null,
)

/**
 * The numbers behind an alert about a trade: its pattern (as [variant] and its short name, [pattern]), the trade's id, its prices when it
 * opened (with [trails] for a stop that follows the price up), and how it ended when it closed ([reason] "target", "stop" or "time").
 */
data class AlertFacts(
    val variant: String,
    val pattern: String,
    val tradeId: Long? = null,
    val entry: Double? = null,
    val target: Double? = null,
    val stop: Double? = null,
    val trails: Boolean = false,
    val reason: String? = null,
    val net: Double? = null,
    val random: Double? = null,
)

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
    /** Keep the screen on, and dim it to [dimLevel], while Signal Lab is showing. */
    val dimScreen: Boolean = false,
    val dimLevel: DimLevel = DimLevel.DIM,
    /** Where the side bar sits when there is one: on the right edge instead of the left. */
    val railOnRight: Boolean = false,
)

/** How dim the screen goes when it is kept on. [brightness] is a fraction of full, never 0, because 0 turns some screens off. */
enum class DimLevel(val label: String, val brightness: Float) {
    VERY_DIM("Very dim", 0.02f), DIM("Dim", 0.1f), SOFT("Soft", 0.25f),
}

enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, NEEDS_PERMISSION, INSTALLER_OPEN, FAILED }

/** Where the app's own update stands, for the Updates section of Settings. */
data class UpdateUi(
    val status: UpdateStatus = UpdateStatus.IDLE,
    /** The version on offer, when there is one. */
    val version: String? = null,
    /** What the release says changed; empty when it says nothing. */
    val notes: String = "",
    /** From 0 to 1 while downloading, otherwise null. */
    val progress: Float? = null,
    /** A sentence that explains the state, such as why something failed. */
    val message: String? = null,
    /** When GitHub last answered, if it has. */
    val checkedAt: Long? = null,
    /** Whether tapping Install does something: an update is on offer, or the last try can be repeated. */
    val canInstall: Boolean = false,
)

interface ListsModel {
    val lists: StateFlow<List<ListUi>>

    /** False until the first read of the saved lists finishes, so "no lists yet" is not shown while they are still loading. */
    val loaded: StateFlow<Boolean>
    val download: StateFlow<DownloadUi>

    /**
     * The coins the picker offers for [query], ranked by [source] (plain volume lists the biggest first; [window] matters only to
     * gainers and losers). The day's figures are refreshed first if they are over five minutes old.
     */
    suspend fun offers(query: String, source: PickSource = PickSource.VOLUME, window: PickWindow = PickWindow.H24): OffersUi

    /** The lookup of listing days that "New" needs: idle until [checkListings] starts it, and the picker asks again as it progresses. */
    val listingCheck: StateFlow<ListingCheckUi>

    /** Starts the lookup of when each coin was listed, if some are missing. Does nothing while one is already running. */
    fun checkListings()

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

/** A question the Analyst offers. [needsTrade] means a closed trade is picked for it first. */
data class AnalystCardUi(val id: String, val title: String, val description: String, val needsTrade: Boolean = false)

/**
 * A pattern an answer suggests. [summary] is its rule in words and [definition] its written form, both null when it cannot be run, and then
 * [problem] says why. [runningAs] is the lab number it is already being forward-tested as. [unwatched] are its charts no active list
 * watches, where it cannot trade; [neverTrades] is true when that is every one of them.
 */
data class LabSuggestionUi(
    val title: String,
    val reason: String?,
    val summary: String?,
    val problem: String?,
    val definition: String?,
    val runningAs: Long? = null,
    val unwatched: List<String> = emptyList(),
    val neverTrades: Boolean = false,
)

/** An answer shared back from the Claude app. [card] is the question it answered, when it said; [markdown] is safe to show as it is. */
data class ReportUi(
    val id: Long,
    val receivedAt: Long,
    val card: String?,
    val title: String,
    val markdown: String,
    val suggestions: List<LabSuggestionUi> = emptyList(),
)

/** A lab pattern's backtest on one chart size: over [coins] coins, [trades] trades. Null where there is nothing to measure. */
data class LabRowUi(
    val chart: String,
    val coins: Int,
    val trades: Int,
    val hitRate: Double?,
    val meanNet: Double?,
    val excess: Double?,
    val tCluster: Double?,
)

data class LabBacktestUi(val rows: List<LabRowUi>, val note: String)

/** A lab pattern in the record; [stoppedAt] is null while it runs. [unwatched] and [neverTrades] are as for [LabSuggestionUi]. */
data class LabPatternUi(
    val id: Long,
    val title: String,
    val summary: String,
    val startedAt: Long,
    val stoppedAt: Long?,
    val unwatched: List<String> = emptyList(),
    val neverTrades: Boolean = false,
    /** The written form the record keeps (see the lab's format); the screens turn it into a sentence and into a draft to change. */
    val definition: String = "",
    /** What in the data suggested it, when whoever suggested it said. */
    val reason: String? = null,
    /** The report it was suggested in, when it came from one. */
    val reportId: Long? = null,
)

/** How many lab patterns run now and how many more may start in the current window. */
data class LabBudgetUi(val running: Int = 0, val maxRunning: Int = 5, val newLeft: Int = 5, val maxNew: Int = 5, val windowDays: Int = 30)

interface AnalystModel {
    val cards: List<AnalystCardUi>
    val reports: StateFlow<List<ReportUi>>

    /** Whether the Claude app is on this phone; without it a question opens Android's share menu instead. */
    val claudeInstalled: Boolean

    /** A sentence about the last text shared into the app that could not be kept, until [noticeSeen]. */
    val notice: StateFlow<String?>
    fun noticeSeen()

    /** Hands question [card] (about trade [tradeId], for a card that needs one) to the Claude app, with the record's data below it. */
    suspend fun ask(card: String, tradeId: Long? = null): Outcome

    /** Keeps [text] (an answer pasted in) as a report and returns its id; null, with [notice] saying why, when it cannot be kept. */
    suspend fun keep(text: String): Long?

    /** The lab patterns, running and stopped, newest first. */
    val lab: StateFlow<List<LabPatternUi>>
    val budget: StateFlow<LabBudgetUi>

    /** Backtests the pattern written as [definition] on the stored candles of the active lists. */
    suspend fun backtest(definition: String): LabBacktestUi

    /** Starts the forward test of the pattern written as [definition], suggested in report [report]. */
    suspend fun startLab(definition: String, title: String, reason: String?, report: Long?): Outcome

    /** Stops the forward test of lab pattern [id]; its record stays. */
    suspend fun stopLab(id: Long): Outcome
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
    suspend fun setDimScreen(on: Boolean)
    suspend fun setDimLevel(level: DimLevel)
    suspend fun setRailOnRight(on: Boolean)

    val update: StateFlow<UpdateUi>

    /** Asks GitHub whether a newer version exists. */
    suspend fun checkForUpdate()

    /** Downloads the update on offer, checks it, and opens Android's installer. */
    suspend fun installUpdate()

    /** Opens Android's own screen for one of the permissions. */
    fun openSystemScreen(prompt: PermissionPrompt)
}

/** Where a [Link] leads. */
enum class LinkPlace { CHART, DETAILS, TRADES, ALERTS, SETTINGS, ANALYST }

/**
 * Where a notification (or an alert in the inbox) leads. A coin's chart or its Details need [symbol] (and optionally the chart
 * size); the Trades tab takes a coin to filter by, a trade to open and a status; Alerts takes a group; the Analyst takes a report to
 * open; Settings takes nothing.
 */
data class Link(
    val symbol: String?,
    val timeframe: String? = null,
    val place: LinkPlace = LinkPlace.CHART,
    val tradeId: Long? = null,
    /** "open" or "closed", for the Trades tab. */
    val status: String? = null,
    /** An alerts group by name ("problems"), for the Alerts tab. */
    val group: String? = null,
    /** A report to open, for the Analyst. */
    val report: Long? = null,
)

private fun queryValue(query: String, name: String): String? =
    query.split('&').firstNotNullOfOrNull { part -> part.takeIf { it.substringBefore('=') == name }?.substringAfter('=', "")?.takeIf { it.isNotBlank() } }

/**
 * Reads the addresses alerts carry:
 * "signallab://coin/SOLUSDT?tf=1h" (the chart; add "&show=details" for the Details tab),
 * "signallab://trades?coin=SOLUSDT&id=12&status=closed", "signallab://alerts?group=problems", "signallab://analyst?report=3" and
 * "signallab://settings". Anything else is not a link.
 */
fun parseLink(text: String?): Link? {
    if (text == null || !text.startsWith("signallab://")) return null
    val rest = text.removePrefix("signallab://")
    val path = rest.substringBefore('?')
    val query = rest.substringAfter('?', "")
    return when {
        path.startsWith("coin/") -> {
            val symbol = path.removePrefix("coin/").takeIf { it.isNotBlank() } ?: return null
            Link(symbol, queryValue(query, "tf"), if (queryValue(query, "show") == "details") LinkPlace.DETAILS else LinkPlace.CHART)
        }
        path == "trades" -> Link(queryValue(query, "coin"), null, LinkPlace.TRADES, queryValue(query, "id")?.toLongOrNull(), queryValue(query, "status"))
        path == "alerts" -> Link(null, null, LinkPlace.ALERTS, group = queryValue(query, "group"))
        path == "analyst" -> Link(null, null, LinkPlace.ANALYST, report = queryValue(query, "report")?.toLongOrNull())
        path == "settings" -> Link(null, null, LinkPlace.SETTINGS)
        else -> null
    }
}

/** What a link inside a Learn page asks for: another page ("learn:scorecard") or a place in the app ("go:settings"). */
sealed interface WebLink {
    data class Page(val id: String) : WebLink
    data class Place(val name: String) : WebLink
}

/** Reads a link tapped inside a Learn page; anything else (a web address, an empty target) is null and goes nowhere. */
fun parseWebLink(url: String): WebLink? = when {
    url.startsWith("learn:") -> url.removePrefix("learn:").takeIf { it.isNotBlank() }?.let { WebLink.Page(it) }
    url.startsWith("go:") -> url.removePrefix("go:").takeIf { it.isNotBlank() }?.let { WebLink.Place(it) }
    else -> null
}

/** Where panel sizes are kept between runs: one piece of text per screen, and null until what was saved has been read. */
interface PanelPrefs {
    val saved: StateFlow<Map<String, String>?>

    /** Remembers [text] for [key]; [saved] shows it straight away. */
    fun save(key: String, text: String)
}

interface AppModel {
    val lists: ListsModel
    val markets: MarketsModel
    val trades: TradesModel
    val scorecard: ScorecardModel
    val alerts: AlertsModel
    val analyst: AnalystModel
    val learn: LearnModel
    val settings: SettingsModel
    val prompts: PermissionPrompts
    val panels: PanelPrefs

    /** A link the app was opened with (a notification tap), until the screens have used it. */
    val pendingLink: StateFlow<Link?>
    fun linkUsed()

    /** The temporary debug page; null in release builds. */
    val debug: DebugState?
}
