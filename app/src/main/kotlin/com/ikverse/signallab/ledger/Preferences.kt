package com.ikverse.signallab.ledger

import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.strategy.Strategy
import com.ikverse.signallab.engine.strategy.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The person's choices, as the screens and the runner read them. Every value has a default, so a fresh install works. */
data class Preferences(
    /** The paper account's starting size in USDT. */
    val accountSize: Double = 10_000.0,
    /** The owner's own Binance money in USDT, which calls size their buy amounts against; null until set. */
    val tradeMoney: Double? = null,
    /** Round-trip cost charged on BTC and ETH, as a fraction. */
    val costMajors: Double = EngineConfig.COST_MAJORS,
    /** Round-trip cost charged on every other coin, as a fraction. */
    val costOthers: Double = EngineConfig.COST_OTHERS,
    val notifications: Boolean = true,
    /** Binance.US instead of Binance.com. */
    val binanceUs: Boolean = false,
    val futuresData: Boolean = true,
    /** Strategies switched on or off by hand, by id; the rest follow their tier. */
    val switches: Map<String, Boolean> = emptyMap(),
    /** When Today was last looked at, so calls since then are "new". */
    val todaySeenAt: Long = 0,
    /** Each strategy's tier as its evidence last set it, by id; a strategy not judged yet has its launch tier. */
    val tiers: Map<String, Tier> = emptyMap(),
    /** Why each strategy has its tier, in a sentence. */
    val tierReasons: Map<String, String> = emptyMap(),
    /** The Claude model the analyst runs on. */
    val analystModel: String = MODEL_OPUS,
    /** The most the analyst may spend in a calendar month, in US dollars. */
    val analystCapUsd: Double = 20.0,
    /** A short brief after each daily pass. */
    val analystBrief: Boolean = false,
    /** A research review every Sunday. */
    val analystReview: Boolean = false,
    /** Custom strategies the owner has set running, by id. */
    val customLive: Set<String> = emptySet(),
    /** Calls the analyst suggested, newest first. */
    val suggestions: List<Suggestion> = emptyList(),
    /** Dated context lines for Today. */
    val contextLines: List<ContextLine> = emptyList(),
) {
    fun tierOf(s: Strategy): Tier = tiers[s.id] ?: s.launchTier

    /** Whether [s] puts its calls on Today and notifies: as switched by hand, else whether its current tier makes calls. */
    fun isOn(s: Strategy): Boolean = switches[s.id] ?: tierOf(s).makesCalls

    fun costFor(symbol: String): Double = if (symbol in EngineConfig.MAJORS) costMajors else costOthers

    companion object {
        const val ACCOUNT_SIZE = "account_size"
        const val TRADE_MONEY = "trade_money"
        const val COST_MAJORS = "cost_majors"
        const val COST_OTHERS = "cost_others"
        const val NOTIFICATIONS = "alert_notifications"
        const val DATA_HOST = "data_host"
        const val FUTURES = "futures_data"
        const val SWITCH_PREFIX = "strategy_on."
        const val TODAY_SEEN = "today_seen_at"
        const val HOST_US = "https://api.binance.us"
        const val TIER_PREFIX = "tier."
        const val TIER_REASON_PREFIX = "tier_reason."
        const val ANALYST_MODEL = "analyst_model"
        const val ANALYST_CAP = "analyst_cap_usd"
        const val ANALYST_BRIEF = "analyst_brief"
        const val ANALYST_REVIEW = "analyst_review"
        const val CUSTOM_LIVE_PREFIX = "custom_live."
        const val MODEL_OPUS = "claude-opus-5-5"
        const val MODEL_SONNET = "claude-sonnet-5-5"

        fun from(rows: List<SettingRow>): Preferences {
            val m = rows.associate { it.key to it.value }
            fun bool(key: String, default: Boolean) = m[key]?.let { it == "1" || it == "true" } ?: default
            return Preferences(
                accountSize = m[ACCOUNT_SIZE]?.toDoubleOrNull()?.takeIf { it > 0 } ?: 10_000.0,
                tradeMoney = m[TRADE_MONEY]?.toDoubleOrNull()?.takeIf { it > 0 },
                costMajors = m[COST_MAJORS]?.toDoubleOrNull() ?: EngineConfig.COST_MAJORS,
                costOthers = m[COST_OTHERS]?.toDoubleOrNull() ?: EngineConfig.COST_OTHERS,
                notifications = bool(NOTIFICATIONS, true),
                binanceUs = m[DATA_HOST] == HOST_US,
                futuresData = bool(FUTURES, true),
                switches = m.filterKeys { it.startsWith(SWITCH_PREFIX) }.map { (k, v) -> k.removePrefix(SWITCH_PREFIX) to (v == "1") }.toMap(),
                todaySeenAt = m[TODAY_SEEN]?.toLongOrNull() ?: 0,
                tiers = m.filterKeys { it.startsWith(TIER_PREFIX) }.mapNotNull { (k, v) ->
                    Tier.entries.firstOrNull { it.name == v }?.let { k.removePrefix(TIER_PREFIX) to it }
                }.toMap(),
                tierReasons = m.filterKeys { it.startsWith(TIER_REASON_PREFIX) }.mapKeys { it.key.removePrefix(TIER_REASON_PREFIX) },
                analystModel = m[ANALYST_MODEL]?.takeIf { it == MODEL_OPUS || it == MODEL_SONNET } ?: MODEL_OPUS,
                analystCapUsd = m[ANALYST_CAP]?.toDoubleOrNull()?.takeIf { it >= 0 } ?: 20.0,
                analystBrief = bool(ANALYST_BRIEF, false),
                analystReview = bool(ANALYST_REVIEW, false),
                customLive = m.filter { (k, v) -> k.startsWith(CUSTOM_LIVE_PREFIX) && v == "1" }.keys.map { it.removePrefix(CUSTOM_LIVE_PREFIX) }.toSet(),
                suggestions = m.filterKeys { it.startsWith(AnalystNotes.SUGGESTION_PREFIX) }.values.mapNotNull(AnalystNotes::suggestion)
                    .sortedByDescending { it.createdAt },
                contextLines = m.filterKeys { it.startsWith(AnalystNotes.CONTEXT_PREFIX) }.values.mapNotNull(AnalystNotes::context)
                    .sortedBy { it.until },
            )
        }
    }
}

/** Reading and changing [Preferences] in the ledger's settings table. */
class PreferenceStore(private val dao: LedgerDao, private val now: () -> Long = System::currentTimeMillis) {
    val preferences: Flow<Preferences> = dao.watchSettings().map(Preferences::from)

    suspend fun current(): Preferences = Preferences.from(dao.watchSettingsOnce())

    suspend fun set(key: String, value: String) = dao.putSetting(SettingRow(key, value, now()))

    suspend fun setBool(key: String, value: Boolean) = set(key, if (value) "1" else "0")

    suspend fun switch(strategyId: String, on: Boolean) = setBool(Preferences.SWITCH_PREFIX + strategyId, on)
}
