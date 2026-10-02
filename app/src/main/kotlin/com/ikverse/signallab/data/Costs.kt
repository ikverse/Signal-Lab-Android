package com.ikverse.signallab.data

import com.ikverse.signallab.engine.EngineConfig

/**
 * What a paper trade costs, round trip, as a fraction of the trade. By default only Binance's own fee, once to buy and once to
 * sell (0.10% each way at the entry tier, so 0.20%). BNB and VIP rates lower it. An extra cost for thin coins, where the
 * price can move before an order fills, is a separate setting and starts switched off.
 *
 * A trade stores the cost it was charged when it opened, so changing a setting never rewrites results already recorded.
 */
class CostModel(
    val feePerSide: Double = EngineConfig.DEFAULT_FEE_PER_SIDE,
    val extraMajors: Double = 0.0,
    val extraOthers: Double = 0.0,
) {
    fun costFor(symbol: String): Double = 2 * feePerSide + if (symbol in EngineConfig.MAJORS) extraMajors else extraOthers

    /** True when anything beyond the exchange fee is being charged. */
    val includesExtra: Boolean get() = extraMajors > 0 || extraOthers > 0

    companion object {
        suspend fun load(settings: SettingsStore) = CostModel(
            settings.getDouble(SettingsStore.FEE_PER_SIDE, EngineConfig.DEFAULT_FEE_PER_SIDE),
            settings.getDouble(SettingsStore.EXTRA_COST_MAJORS, 0.0),
            settings.getDouble(SettingsStore.EXTRA_COST_OTHERS, 0.0),
        )
    }
}
