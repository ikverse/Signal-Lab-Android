package com.ikverse.signallab.model

import com.ikverse.signallab.analyst.Analyst
import com.ikverse.signallab.analyst.ClaudeModel as Model
import com.ikverse.signallab.analyst.AnalystPrompt
import com.ikverse.signallab.analyst.AnalystStore
import com.ikverse.signallab.analyst.ApiKeyStore
import com.ikverse.signallab.analyst.HostPairing
import com.ikverse.signallab.analyst.HostProbe
import com.ikverse.signallab.analyst.RunEvent
import com.ikverse.signallab.analyst.RunKind
import com.ikverse.signallab.analyst.RunLog
import com.ikverse.signallab.analyst.SpendMeter
import com.ikverse.signallab.analyst.ToolSpec
import com.ikverse.signallab.analyst.Turn
import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.PreferenceStore
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.view.common.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A report the analyst wrote: a daily brief, a weekly review, or a proposal. */
data class ReportUi(val id: Long, val title: String, val body: String, val at: Long)

data class AnalystUi(
    val loaded: Boolean = false,
    val keySet: Boolean = false,
    /** The saved key's last four characters. */
    val keyHint: String? = null,
    val model: String = Model.OPUS.label,
    val modelId: String = Model.OPUS.id,
    val monthUsd: Double = 0.0,
    val capUsd: Double = 20.0,
    val brief: Boolean = false,
    val review: Boolean = false,
    val turns: List<Turn> = emptyList(),
    /** A run is in progress. */
    val running: Boolean = false,
    /** The answer so far, while a run streams. */
    val streaming: String = "",
    /** What the analyst is doing right now, such as "Reading the ledger…". */
    val working: String? = null,
    /** Why a run cannot start or did not finish. */
    val problem: String? = null,
    val reports: List<ReportUi> = emptyList(),
    /** The PC this phone is paired with, by its address; null when none. */
    val pcAddress: String? = null,
    /** Whether the paired PC answered the last check; null before one. */
    val pcAnswering: Boolean? = null,
    /** A pairing waiting for the owner to compare fingerprints. */
    val pairing: HostProbe? = null,
    val pairingBusy: Boolean = false,
    val pairingProblem: String? = null,
) {
    val pcPaired: Boolean get() = pcAddress != null

    /** The analyst can be asked: through the PC or on an API key. */
    val canAsk: Boolean get() = pcPaired || keySet

    val spendLine: String
        get() = when {
            pcPaired && keySet -> "Through your PC on your Claude plan, or this month ${Words.usd(monthUsd)} of ${Words.usd(capUsd)} on your key · $model"
            pcPaired -> "Through your PC, on your Claude plan · $model"
            else -> "This month ${Words.usd(monthUsd)} of ${Words.usd(capUsd)} · $model"
        }
}

/** The Analyst tab: the conversation, the reports, the key and the budget. */
class AnalystModel(
    private val analyst: Analyst,
    private val store: AnalystStore,
    private val keys: ApiKeyStore,
    private val spend: SpendMeter,
    private val prefs: PreferenceStore,
    dao: LedgerDao,
    private val scope: CoroutineScope,
    /** The analyst's read-only tools, for the snapshot handed to the Claude app when there is no key. */
    private val tools: List<ToolSpec> = emptyList(),
    private val host: HostPairing? = null,
) {
    private class Pc(val address: String? = null, val answering: Boolean? = null, val probe: HostProbe? = null, val busy: Boolean = false, val problem: String? = null)

    private val pc = MutableStateFlow(Pc())
    private class Live(
        val turns: List<Turn> = emptyList(),
        val running: Boolean = false,
        val streaming: String = "",
        val working: String? = null,
        val problem: String? = null,
        val month: Double = 0.0,
        val keySet: Boolean = false,
        val hint: String? = null,
    )

    private val live = MutableStateFlow(Live())
    private var job: Job? = null

    val state: StateFlow<AnalystUi> = combine(live, prefs.preferences, dao.watchReports(20), pc) { l, p, reports, h ->
        val m = Model.of(p.analystModel)
        AnalystUi(
            pcAddress = h.address, pcAnswering = h.answering, pairing = h.probe, pairingBusy = h.busy, pairingProblem = h.problem,
            loaded = true, keySet = l.keySet, keyHint = l.hint, model = m.label, modelId = m.id, monthUsd = l.month,
            capUsd = p.analystCapUsd, brief = p.analystBrief, review = p.analystReview, turns = l.turns, running = l.running,
            streaming = l.streaming, working = l.working, problem = l.problem,
            reports = reports.filter { it.legacyId == null }.map { ReportUi(it.id, it.title, it.body, it.receivedAt) },
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), AnalystUi())

    init {
        refresh()
    }

    /** Re-reads the conversation, the month's spend, the key and the paired PC, after anything outside this model changed them. */
    fun refresh() {
        scope.launch {
            val paired = runCatching { host?.paired() }.getOrNull()
            pc.value = Pc(paired?.address, pc.value.answering.takeIf { paired != null }, pc.value.probe, pc.value.busy, pc.value.problem)
            val month = spend.month()
            live.value = Live(store.chat(), live.value.running, live.value.streaming, live.value.working, live.value.problem, month, keys.isSet,
                runCatching { keys.hint() }.getOrNull())
        }
    }

    private fun update(block: (Live) -> Live) {
        live.value = block(live.value)
    }

    private fun Live.copy(
        turns: List<Turn> = this.turns, running: Boolean = this.running, streaming: String = this.streaming, working: String? = this.working,
        problem: String? = this.problem, month: Double = this.month, keySet: Boolean = this.keySet, hint: String? = this.hint,
    ) = Live(turns, running, streaming, working, problem, month, keySet, hint)

    /** Asks the analyst [text] in the conversation. */
    fun send(text: String) = start(RunKind.CHAT, text.trim())

    /** Asks about one call, from its page. */
    fun explain(symbol: String, strategy: String, entryDay: String) = start(RunKind.EXPLAIN, AnalystPrompt.explain(symbol, strategy, entryDay))

    private fun start(kind: RunKind, text: String) {
        if (text.isEmpty() || live.value.running) return
        job = scope.launch {
            analyst.blocked()?.let { why ->
                update { it.copy(problem = why) }
                return@launch
            }
            val pending = Turn(Turn.USER, text, System.currentTimeMillis())
            update { it.copy(turns = it.turns + pending, running = true, streaming = "", working = "Thinking…", problem = null) }
            try {
                val result = analyst.run(kind, text) { e ->
                    when (e) {
                        is RunEvent.Text -> update { it.copy(streaming = it.streaming + e.delta, working = null) }
                        is RunEvent.Working -> update { it.copy(working = e.what) }
                    }
                }
                val failed = result.log.ending.startsWith("failed")
                update { it.copy(problem = if (failed) Analyst.endingWords(result.log.ending) else null) }
            } finally {
                val month = runCatching { spend.month() }.getOrDefault(live.value.month)
                update { it.copy(turns = store.chat(), running = false, streaming = "", working = null, month = month) }
                refresh()
            }
        }
    }

    /** Stops the run in progress; what was said so far is kept. */
    fun stop() {
        job?.cancel()
    }

    fun clearChat() {
        if (live.value.running) return
        store.clearChat()
        update { it.copy(turns = emptyList(), problem = null) }
    }

    /** Saves a pasted key; returns why not when it does not look like one. */
    fun saveKey(key: String): String? {
        val problem = keys.save(key)
        if (problem == null) refresh()
        return problem
    }

    fun removeKey() {
        keys.clear()
        refresh()
    }

    fun setModel(id: String) {
        scope.launch { prefs.set(Preferences.ANALYST_MODEL, id) }
    }

    fun setCap(usd: Double) {
        if (usd >= 0) scope.launch { prefs.set(Preferences.ANALYST_CAP, usd.toString()) }
    }

    fun setBrief(on: Boolean) {
        scope.launch { prefs.setBool(Preferences.ANALYST_BRIEF, on) }
    }

    fun setReview(on: Boolean) {
        scope.launch { prefs.setBool(Preferences.ANALYST_REVIEW, on) }
    }

    /** The text to share with the Claude app: the instructions, a snapshot of the app's data, and [question]. */
    suspend fun handoffText(question: String): String {
        val snapshot = LinkedHashMap<String, String>()
        for (name in listOf("market_state", "ledger", "list_strategies")) {
            val spec = tools.firstOrNull { it.name == name } ?: continue
            snapshot[name] = runCatching { spec.run(kotlinx.serialization.json.JsonObject(emptyMap())).text }.getOrElse { "unavailable" }
        }
        return AnalystPrompt.handoff(snapshot, question)
    }

    /** First contact with a PC: shows its fingerprint for the owner to compare. */
    fun pair(address: String, code: String) {
        val h = host ?: return
        pc.value = Pc(pc.value.address, pc.value.answering, null, busy = true)
        scope.launch {
            pc.value = try {
                Pc(pc.value.address, pc.value.answering, h.probe(address, code))
            } catch (e: Exception) {
                Pc(pc.value.address, pc.value.answering, problem = e.message ?: "The PC could not be reached.")
            }
        }
    }

    /** The fingerprints matched: the phone trusts that PC from now on. */
    fun confirmPairing() {
        val h = host ?: return
        val probe = pc.value.probe ?: return
        scope.launch {
            h.confirm(probe)
            pc.value = Pc(probe.address, answering = true)
        }
    }

    fun cancelPairing() {
        pc.value = Pc(pc.value.address, pc.value.answering)
    }

    fun forgetPc() {
        val h = host ?: return
        scope.launch {
            h.forget()
            pc.value = Pc()
        }
    }

    /** Asks the paired PC whether it is answering now. */
    fun checkPc() {
        val h = host ?: return
        scope.launch {
            h.recheck()
            val ok = runCatching { h.reachable() }.getOrNull() != null
            pc.value = Pc(pc.value.address, ok, pc.value.probe, problem = if (ok) null else "Your PC is not answering. Is the analyst host running, and is the phone on the same network?")
        }
    }

    /** The last runs, newest first, for the "what was sent" page. */
    fun runs(): List<RunLog> = store.runs()
}
