package com.ikverse.signallab.analyst

import com.ikverse.signallab.engine.strategy.AnalystCall
import com.ikverse.signallab.ledger.AnalystNotes
import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.PreferenceStore
import com.ikverse.signallab.ledger.SettingRow
import com.ikverse.signallab.ledger.Suggestion
import com.ikverse.signallab.ledger.SuggestionState
import com.ikverse.signallab.runner.PendingCall
import com.ikverse.signallab.runner.PendingCalls

/**
 * The analyst's suggested calls on their way to the ledger: the owner records or dismisses each on Today, and the runner
 * opens the recorded ones at the next daily open.
 */
class Suggestions(private val dao: LedgerDao, private val prefs: PreferenceStore, private val now: () -> Long = System::currentTimeMillis) : PendingCalls {
    private suspend fun all(): List<Suggestion> = prefs.current().suggestions

    private suspend fun put(s: Suggestion) = dao.putSetting(SettingRow(AnalystNotes.SUGGESTION_PREFIX + s.id, AnalystNotes.encode(s), now()))

    private suspend fun change(id: String, from: Set<SuggestionState>, block: (Suggestion) -> Suggestion): Boolean {
        val s = all().firstOrNull { it.id == id && it.state in from } ?: return false
        put(block(s))
        return true
    }

    /** The owner records it: from the next daily open it is a call of the Analyst strategy, shown on Today. */
    suspend fun record(id: String): Boolean {
        val ok = change(id, setOf(SuggestionState.SUGGESTED)) { it.copy(state = SuggestionState.ACCEPTED, decidedAt = now()) }
        // The owner asked for these calls, so they show on Today unless the owner has switched the Analyst off by hand.
        if (ok && AnalystCall.id !in prefs.current().switches) prefs.switch(AnalystCall.id, true)
        return ok
    }

    suspend fun dismiss(id: String): Boolean =
        change(id, setOf(SuggestionState.SUGGESTED, SuggestionState.ACCEPTED)) { it.copy(state = SuggestionState.DISMISSED, decidedAt = now()) }

    override suspend fun accepted(): List<PendingCall> {
        val t = now()
        for (s in all().filter { it.state == SuggestionState.SUGGESTED && t - it.createdAt >= AnalystNotes.SUGGESTION_LIFETIME_MS }) {
            put(s.copy(state = SuggestionState.EXPIRED, decidedAt = t, note = "Not recorded within three days."))
        }
        return all().filter { it.state == SuggestionState.ACCEPTED }
            .map { PendingCall(it.id, it.symbol, it.stopPct, it.targetPct, it.days, it.decidedAt ?: it.createdAt) }
    }

    override suspend fun opened(id: String, callId: Long, now: Long) {
        change(id, setOf(SuggestionState.ACCEPTED)) { it.copy(state = SuggestionState.OPENED, callId = callId) }
    }

    override suspend fun expired(id: String, why: String, now: Long) {
        change(id, setOf(SuggestionState.ACCEPTED, SuggestionState.SUGGESTED)) { it.copy(state = SuggestionState.EXPIRED, note = why) }
    }
}
