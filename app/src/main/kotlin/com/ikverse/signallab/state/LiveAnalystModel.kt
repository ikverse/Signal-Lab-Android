package com.ikverse.signallab.state

import android.content.Context
import com.ikverse.signallab.BuildConfig
import com.ikverse.signallab.analyst.ClaudeHandoff
import com.ikverse.signallab.analyst.Handoff
import com.ikverse.signallab.analyst.LabFormat
import com.ikverse.signallab.analyst.LabInfo
import com.ikverse.signallab.analyst.PromptCards
import com.ikverse.signallab.analyst.ReportText
import com.ikverse.signallab.analyst.Snapshot
import com.ikverse.signallab.analyst.SnapshotInput
import com.ikverse.signallab.analyst.TradeFocus
import com.ikverse.signallab.data.CostModel
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.LabRecord
import com.ikverse.signallab.data.Report
import com.ikverse.signallab.data.TradeStatus
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.LabPattern
import com.ikverse.signallab.engine.LabPatterns
import com.ikverse.signallab.engine.PaperTrading
import com.ikverse.signallab.engine.Scorecard
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.Watchlist
import com.ikverse.signallab.ui.AnalystCardUi
import com.ikverse.signallab.ui.AnalystModel
import com.ikverse.signallab.ui.LabBacktestUi
import com.ikverse.signallab.ui.LabBudgetUi
import com.ikverse.signallab.ui.LabPatternUi
import com.ikverse.signallab.ui.LabRowUi
import com.ikverse.signallab.ui.LabSuggestionUi
import com.ikverse.signallab.ui.Outcome
import com.ikverse.signallab.ui.ReportUi
import com.ikverse.signallab.ui.unwatchedText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** The charts a pattern can trade on: those an active list with coins in it is watched on. */
internal fun watchedCharts(lists: List<Watchlist>): Set<Timeframe> = lists.filter { it.active && it.symbols.isNotEmpty() }.flatMapTo(HashSet()) { it.timeframes }

/** [p]'s charts that no list in [watched] covers, shortest first, and whether that is all of them. */
private fun unwatched(p: LabPattern?, watched: Set<Timeframe>): Pair<List<String>, Boolean> {
    val missing = p?.timeframes.orEmpty().filter { it !in watched }.sorted().map { it.label }
    return missing to (p != null && missing.size == p.timeframes.size)
}

/**
 * [this] report as the screens show it: made safe to show, with the patterns it suggests, which of those already run in [labs], and which
 * of their charts are not in [watched].
 */
internal fun Report.toUi(labs: List<LabRecord> = emptyList(), watched: Set<Timeframe> = Timeframe.entries.toSet()) = ReportUi(
    id, receivedAt, card, title, ReportText.safe(body),
    LabFormat.suggestions(body).map { s ->
        val definition = s.definition
        val (missing, never) = unwatched(s.pattern, watched)
        LabSuggestionUi(
            s.title, s.reason, s.pattern?.summary, s.problem, definition, labs.firstOrNull { it.running && it.definition == definition }?.id,
            missing, never,
        )
    },
)

internal fun LabRecord.toUi(watched: Set<Timeframe> = Timeframe.entries.toSet()): LabPatternUi {
    val p = LabFormat.pattern(id, definition)
    val (missing, never) = unwatched(p, watched)
    return LabPatternUi(id, title, p?.summary ?: definition, startedAt, stoppedAt, missing, never, definition, reason, reportId)
}

/**
 * How much of the lab's allowance [labs] leave at [now]. [traded] is how many trades each lab pattern has had; a pattern stopped before
 * it had [EngineConfig.LAB_COUNTS_AFTER_TRADES] gives its place in the month's new ideas back. A pattern missing from [traded] counts.
 */
internal fun budgetOf(labs: List<LabRecord>, now: Long, traded: Map<Long, Int> = emptyMap()) = LabBudgetUi(
    running = labs.count { it.running }, maxRunning = EngineConfig.LAB_MAX_RUNNING,
    newLeft = (
        EngineConfig.LAB_MAX_NEW - labs.count {
            now - it.startedAt < EngineConfig.LAB_NEW_WINDOW_DAYS * DAY_MS &&
                (it.running || (traded[it.id] ?: Int.MAX_VALUE) >= EngineConfig.LAB_COUNTS_AFTER_TRADES)
        }
        ).coerceAtLeast(0),
    maxNew = EngineConfig.LAB_MAX_NEW, windowDays = EngineConfig.LAB_NEW_WINDOW_DAYS,
)

private const val DAY_MS = 86_400_000L

/**
 * The Analyst on top of the record. A question becomes one draft, the question above the record's data, handed through [handoffs] to the
 * activity, which opens the Claude app; an answer shared back becomes a report; a pattern it suggests can be backtested and forward-tested.
 */
class LiveAnalystModel(
    private val graph: AppGraph,
    private val context: Context,
    scope: CoroutineScope,
    private val handoffs: MutableSharedFlow<Handoff>,
    private val clock: () -> Long = System::currentTimeMillis,
) : AnalystModel {
    override val cards: List<AnalystCardUi> = PromptCards.all.map { AnalystCardUi(it.id, it.title, it.description, it.needsTrade) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val reports: StateFlow<List<ReportUi>> = combine(graph.reports.version, graph.labStore.version, graph.watchlists.lists) { _, _, lists -> lists }
        .mapLatest { lists ->
            val labs = graph.labStore.all()
            val watched = watchedCharts(lists)
            graph.reports.all().map { it.toUi(labs, watched) }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val labRecords: StateFlow<List<LabRecord>> = graph.labStore.version
        .mapLatest { graph.labStore.all() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    override val lab: StateFlow<List<LabPatternUi>> = combine(labRecords, graph.watchlists.lists) { all, lists -> all to watchedCharts(lists) }
        .mapLatest { (all, watched) -> all.sortedWith(compareBy<LabRecord> { !it.running }.thenByDescending { it.id }).map { it.toUi(watched) } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    override val budget: StateFlow<LabBudgetUi> = combine(labRecords, graph.tradeLog.version) { labs, _ -> labs }
        .mapLatest { budgetOf(it, clock(), tradedByLab(it)) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), LabBudgetUi())

    /** How many trades each of [labs] has had, open or finished, by its number; a pattern that never traded is in it with 0. */
    private suspend fun tradedByLab(labs: List<LabRecord>): Map<Long, Int> {
        val counts = graph.tradeLog.trades(limit = Int.MAX_VALUE).mapNotNull { LabPatterns.idOf(it.trade.variant) }.groupingBy { it }.eachCount()
        return labs.associate { it.id to (counts[it.id] ?: 0) }
    }

    override val claudeInstalled: Boolean get() = ClaudeHandoff.installed(context)

    private val noticeState = MutableStateFlow<String?>(null)
    override val notice: StateFlow<String?> = noticeState

    override fun noticeSeen() {
        noticeState.value = null
    }

    override suspend fun ask(card: String, tradeId: Long?): Outcome {
        val question = PromptCards.byId(card) ?: return Outcome.Refused("That question no longer exists.")
        if (question.needsTrade && tradeId == null) return Outcome.Refused("Pick a trade for this question first.")
        return try {
            val trades = graph.tradeLog.trades(TradeStatus.ALL, limit = Int.MAX_VALUE)
            val focus = tradeId?.let { id ->
                val t = trades.firstOrNull { it.id == id } ?: return Outcome.Refused("That trade is no longer in the record.")
                TradeFocus(t, graph.candles.window(t.trade.symbol, t.trade.tf, since = t.trade.barTime - Snapshot.CANDLES_BEFORE * t.trade.tf.ms))
            }
            val lists = graph.watchlists.lists.value
            val scorecard = currentScorecard(graph, lists, trades)
            val lab = graph.labStore.all().map { r -> r.toUi().let { LabInfo(it.id, it.title, it.summary, it.startedAt, it.stoppedAt) } }
            val input = SnapshotInput(clock(), BuildConfig.VERSION_NAME, CostModel.load(graph.settings), lists, trades, scorecard, lab)
            val data = withContext(Dispatchers.Default) { Snapshot.build(input, focus) }
            handoffs.emit(Handoff(PromptCards.draft(question, scorecard.patternsTested, data)))
            Outcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Refused("The question could not be prepared: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override suspend fun keep(text: String): Long? {
        if (text.length > ReportText.MAX_CHARS) {
            noticeState.value = "That text is too long to keep as a report (over ${ReportText.MAX_CHARS} characters)."
            return null
        }
        if (ReportText.isLink(text)) {
            noticeState.value = "That is a link to the chat, not the answer. In Claude, press and hold the answer, tap Copy, then come back and tap Paste an answer."
            return null
        }
        val report = ReportText.read(text)
        if (report == null) {
            noticeState.value = "The text was empty, so there was nothing to keep."
            return null
        }
        return try {
            graph.reports.add(report.card, report.title, report.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noticeState.value = "The report could not be saved: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    override suspend fun backtest(definition: String): LabBacktestUi {
        val pattern = LabFormat.pattern(0, definition) ?: return LabBacktestUi(emptyList(), "This pattern could not be read.")
        return try {
            LabBacktestUi(backtestRows(pattern), BACKTEST_NOTE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LabBacktestUi(emptyList(), "The backtest failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Runs [pattern] over everything stored for the coins of the active lists, chart by chart, through the same paper trades, exits, costs
     * and random entries as the live scan and the scorecard.
     */
    private suspend fun backtestRows(pattern: LabPattern): List<LabRowUi> {
        val coins = graph.watchlists.lists.value.filter { it.active }.flatMap { it.symbols }.distinct()
        val costs = CostModel.load(graph.settings)
        val btc = graph.candles.window(DataConfig.REGIME_COIN, Timeframe.D1)
        return pattern.timeframes.sorted().map { tf ->
            val panel = LinkedHashMap<String, com.ikverse.signallab.engine.Candles>()
            for (s in coins) graph.candles.window(s, tf)?.takeIf { it.size > 0 }?.let { panel[s] = it }
            if (panel.isEmpty()) {
                LabRowUi(tf.label, 0, 0, null, null, null, null)
            } else {
                withContext(Dispatchers.Default) {
                    val key = LabPatterns.key(pattern, tf)
                    val flags = panel.mapValues { LabPatterns.flags(it.value, pattern) }
                    val regimes = panel.mapValues { (_, c) -> if (btc == null) IntArray(c.size) { -1 } else PaperTrading.regimeSeries(btc, c.closeTime) }
                    val run = Scorecard.runVariant(key, flags, panel, tf, regimes, costs::costFor, perPatternExits = true)
                    val s = Scorecard.score(key, key.family, tf, run)
                    fun Double.orNull() = takeIf { !it.isNaN() }
                    LabRowUi(tf.label, panel.size, s.n, s.hit.orNull(), s.mean.orNull(), s.excess.orNull(), s.tCluster.orNull())
                }
            }
        }
    }

    override suspend fun startLab(definition: String, title: String, reason: String?, report: Long?): Outcome {
        val pattern = LabFormat.pattern(0, definition) ?: return Outcome.Refused("This pattern could not be read.")
        val written = LabFormat.definition(pattern)
        val labs = graph.labStore.all()
        labs.firstOrNull { it.running && it.definition == written }?.let { return Outcome.Refused("This pattern is already being forward-tested as lab ${it.id}.") }
        // It would take a slot and raise the bar for every verdict without ever trading.
        val (missing, never) = unwatched(pattern, watchedCharts(graph.watchlists.lists.value))
        if (never) return Outcome.Refused(unwatchedText(missing, true)!!)
        val budget = budgetOf(labs, clock(), tradedByLab(labs))
        if (budget.running >= budget.maxRunning) return Outcome.Refused("${budget.maxRunning} lab patterns are already running. Stop one first.")
        if (budget.newLeft <= 0) {
            val next = labs.filter { clock() - it.startedAt < budget.windowDays * DAY_MS }.minOf { it.startedAt } + budget.windowDays * DAY_MS
            return Outcome.Refused("${budget.maxNew} lab patterns were started in the last ${budget.windowDays} days. The next can start on ${Snapshot.utc(next).take(10)}.")
        }
        val id = graph.labStore.start(title, reason, written, report)
        // It counts among the patterns tested from the moment it starts, before its first scan.
        for (tf in pattern.timeframes) graph.tradeLog.registerVariant(LabPatterns.key(pattern.copy(id = id), tf), tf)
        return Outcome.Done
    }

    override suspend fun stopLab(id: Long): Outcome =
        if (graph.labStore.stop(id)) Outcome.Done else Outcome.Refused("Lab $id is not running.")

    private companion object {
        const val BACKTEST_NOTE = "For reference only: the pattern was shaped after seeing this history, so only its live paper trades can judge it."
    }
}
