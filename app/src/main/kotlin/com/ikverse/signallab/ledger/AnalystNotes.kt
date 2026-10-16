package com.ikverse.signallab.ledger

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a call the analyst suggested stands. */
@Serializable
enum class SuggestionState {
    /** Waiting for the owner on Today. */
    SUGGESTED,

    /** Recorded by the owner; the next daily pass opens it at the next daily open. */
    ACCEPTED,

    /** Turned down by the owner. */
    DISMISSED,

    /** Open in the ledger as a call of the Analyst strategy. */
    OPENED,

    /** Never opened: left too long, or the coin had no daily history. */
    EXPIRED,
}

/**
 * A call the analyst suggested. It enters the ledger only after the owner records it on Today, and then at the next daily
 * open, so the analyst's judgment is tested on prices nobody had seen when it was made. Kept as a setting (`analyst_call.<id>`).
 */
@Serializable
data class Suggestion(
    val id: String,
    val symbol: String,
    /** The stop as a fraction below the entry, 0.06 = 6%; null for none. */
    val stopPct: Double? = null,
    /** The target as a fraction above the entry; null for none. */
    val targetPct: Double? = null,
    val days: Int,
    /** Why, in the analyst's words. */
    val thesis: String,
    val createdAt: Long,
    val state: SuggestionState = SuggestionState.SUGGESTED,
    val decidedAt: Long? = null,
    val callId: Long? = null,
    /** Why it expired, when it did. */
    val note: String? = null,
)

/** A dated line of context on Today, such as "FOMC on Wednesday", put there by the analyst. Kept as `context.<id>`. */
@Serializable
data class ContextLine(val id: String, val text: String, val createdAt: Long, /** The last day it is shown, as UTC midnight in ms. */ val until: Long)

object AnalystNotes {
    const val SUGGESTION_PREFIX = "analyst_call."
    const val CONTEXT_PREFIX = "context."

    /** A suggestion not recorded within this long is no longer offered. */
    const val SUGGESTION_LIFETIME_MS = 3 * 86_400_000L

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun suggestion(text: String): Suggestion? = runCatching { json.decodeFromString(Suggestion.serializer(), text) }.getOrNull()

    fun context(text: String): ContextLine? = runCatching { json.decodeFromString(ContextLine.serializer(), text) }.getOrNull()

    fun encode(s: Suggestion): String = json.encodeToString(Suggestion.serializer(), s)

    fun encode(c: ContextLine): String = json.encodeToString(ContextLine.serializer(), c)
}
