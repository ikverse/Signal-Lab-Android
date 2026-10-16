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
) {
    fun tierOf(s: Strategy): Tier = tiers[s.id] ?: s.launchTier

    /** Whether [s] puts its calls on Today and notifies: as switched by hand, else whether its current tier makes calls. */
    fun isOn(s: Strategy): Boolean = switches[s.id] ?: tierOf(s).makesCalls

    fun costFor(symbol: String): Double = if (symbol in EngineConfig.MAJORS) costMajors else costOthers

    companion object {
        const val ACCOUNT_SIZE = "account_size"
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

        fun from(rows: List<SettingRow>): Preferences {
            val m = rows.associate { it.key to it.value }
            fun bool(key: String, default: Boolean) = m[key]?.let { it == "1" || it == "true" } ?: default
            return Preferences(
                accountSize = m[ACCOUNT_SIZE]?.toDoubleOrNull()?.takeIf { it > 0 } ?: 10_000.0,
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
