package com.ikverse.signallab.engine.eval

/**
 * What copying one strategy's calls did to an account of 10,000 in the whole-account test (docs/strategy-research.md §8):
 * Binance's daily history from [AccountRecords.FROM] to [AccountRecords.TO], on the 40 busiest coins of each day (delisted
 * coins included), sized by the sizing engine, never holding more than the account.
 */
class AccountRecord(
    val end: Double,
    /** Growth a year, compounded. */
    val perYear: Double,
    /** The deepest fall from a high, as a negative fraction. */
    val worstFall: Double,
    val sharpe: Double,
    /** Each calendar year's return, oldest first. */
    val byYear: List<Pair<Int, Double>>,
) {
    /**
     * True when the account grew, fell less at its worst than [other] and earned more for its swings: the bar a strategy has
     * to clear against simply holding Bitcoin.
     */
    fun steadierThan(other: AccountRecord): Boolean = end > AccountRecords.START && worstFall > other.worstFall && sharpe > other.sharpe
}

/**
 * The results of the whole-account test, written here by hand from the output of `gradlew :research:run`. The phone cannot
 * run the test (it has no history of delisted coins), so it carries the answers; run the test again and replace them when a
 * strategy changes or a year has passed.
 */
object AccountRecords {
    const val START = 10_000.0
    const val FROM = "2020-01-01"
    const val TO = "2026-11-13"

    /** Buying Bitcoin on the first day and never selling. */
    val BITCOIN = AccountRecord(94943.0, 0.3874, -0.7663, 0.85, listOf(2020 to 3.0198, 2021 to 0.5979, 2022 to -0.6421, 2023 to 1.5561, 2024 to 1.2131, 2025 to -0.0633, 2026 to -0.2206))

    private val byStrategy: Map<String, AccountRecord> = mapOf(
        "breakout50_1d" to AccountRecord(16150.0, 0.0722, -0.5184, 0.38, listOf(2020 to 0.3732, 2021 to 0.6005, 2022 to -0.3339, 2023 to 0.5096, 2024 to -0.1037, 2025 to -0.2208, 2026 to 0.0463)),
        "trend50_1d" to AccountRecord(66982.0, 0.3187, -0.3041, 1.08, listOf(2020 to 0.8746, 2021 to 0.6465, 2022 to -0.2214, 2023 to 0.8491, 2024 to 0.4382, 2025 to 0.0613, 2026 to -0.0123)),
        "mom2w_1d" to AccountRecord(15499.0, 0.0658, -0.3996, 0.39, listOf(2020 to 0.5098, 2021 to 0.1631, 2022 to -0.0267, 2023 to 0.0772, 2024 to -0.0208, 2025 to -0.1681, 2026 to 0.0333)),
        "leaders3w_1d" to AccountRecord(15721.0, 0.0680, -0.4047, 0.40, listOf(2020 to 0.3614, 2021 to 0.1008, 2022 to -0.0376, 2023 to 0.1233, 2024 to -0.1285, 2025 to -0.0150, 2026 to 0.1303)),
        "nearhigh7_1d" to AccountRecord(19373.0, 0.1010, -0.3521, 0.59, listOf(2020 to 0.3694, 2021 to 0.2800, 2022 to 0.0003, 2023 to -0.0476, 2024 to 0.0249, 2025 to 0.0912, 2026 to 0.0373)),
        "quietdip_1d" to AccountRecord(3046.0, -0.1588, -0.7759, -0.55, listOf(2020 to 0.0777, 2021 to 0.0592, 2022 to -0.4892, 2023 to 0.0338, 2024 to -0.0047, 2025 to -0.2978, 2026 to -0.2769)),
        "hatedrally_1d" to AccountRecord(25199.0, 0.1439, -0.3692, 0.65, listOf(2020 to 0.4761, 2021 to 1.0420, 2022 to -0.3240, 2023 to 0.1592, 2024 to 0.2711, 2025 to -0.0333, 2026 to -0.1318)),
        "trendleaders_1d" to AccountRecord(7604.0, -0.0391, -0.5883, 0.01, listOf(2020 to 0.1398, 2021 to 0.2804, 2022 to -0.4397, 2023 to 0.2318, 2024 to 0.1795, 2025 to -0.2467, 2026 to -0.1504)),
    )

    /** The record of the strategy with this id; null for one the test has not run (a custom strategy, an hourly one). */
    fun of(strategyId: String): AccountRecord? = byStrategy[strategyId]
}
