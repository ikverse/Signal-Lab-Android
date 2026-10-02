package com.ikverse.signallab.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Made-up data behind the screens, so a test can see exactly what they do with it. */
class FakeLists(initial: List<ListUi> = emptyList(), loaded: Boolean = true) : ListsModel {
    val state = MutableStateFlow(initial)
    val ready = MutableStateFlow(loaded)
    val progress = MutableStateFlow(DownloadUi())
    override val lists: StateFlow<List<ListUi>> = state
    override val loaded: StateFlow<Boolean> = ready
    override val download: StateFlow<DownloadUi> = progress

    var offered = listOf(OfferUi("BTCUSDT", "BTC", 2e9), OfferUi("ETHUSDT", "ETH", 1e9), OfferUi("SOLUSDT", "SOL", 5e8))
    var refuse: String? = null
    val log = mutableListOf<String>()
    private var nextId = 1L

    override suspend fun offers(query: String) = offered.filter { query.isBlank() || it.base.contains(query, ignoreCase = true) }

    private fun answer(what: String): Outcome? {
        log += what
        return refuse?.let { Outcome.Refused(it) }
    }

    override suspend fun create(name: String, symbols: List<String>, timeframes: Set<String>, activate: Boolean): Outcome {
        answer("create $name ${symbols.joinToString(",")} ${timeframes.sorted().joinToString(",")} $activate")?.let { return it }
        state.value = state.value + ListUi(nextId++, name, activate, symbols, timeframes.sortedBy { allTimeframes.indexOfFirst { p -> p.first == it } })
        return Outcome.Done
    }

    override suspend fun rename(id: Long, name: String): Outcome {
        answer("rename $id $name")?.let { return it }
        state.value = state.value.map { if (it.id == id) it.copy(name = name) else it }
        return Outcome.Done
    }

    override suspend fun delete(id: Long): Outcome {
        answer("delete $id")?.let { return it }
        state.value = state.value.filter { it.id != id }
        return Outcome.Done
    }

    override suspend fun setActive(id: Long, active: Boolean): Outcome {
        answer("active $id $active")?.let { return it }
        state.value = state.value.map { if (it.id == id) it.copy(active = active) else it }
        return Outcome.Done
    }

    override suspend fun addCoin(id: Long, symbol: String): Outcome {
        answer("add $id $symbol")?.let { return it }
        state.value = state.value.map { if (it.id == id) it.copy(coins = it.coins + symbol) else it }
        return Outcome.Done
    }

    override suspend fun removeCoin(id: Long, symbol: String): Outcome {
        answer("remove $id $symbol")?.let { return it }
        state.value = state.value.map { if (it.id == id) it.copy(coins = it.coins - symbol) else it }
        return Outcome.Done
    }

    override suspend fun setTimeframes(id: Long, timeframes: Set<String>): Outcome {
        answer("charts $id ${timeframes.sorted().joinToString(",")}")?.let { return it }
        state.value = state.value.map { if (it.id == id) it.copy(timeframes = timeframes.sortedBy { t -> allTimeframes.indexOfFirst { p -> p.first == t } }) else it }
        return Outcome.Done
    }

    override val allTimeframes = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d").map { it to "note for $it" }
}

class FakeMarkets(initial: List<CoinUi> = emptyList()) : MarketsModel {
    val coinState = MutableStateFlow(initial)
    val priceState = MutableStateFlow<Map<String, Double>>(emptyMap())
    val changeState = MutableStateFlow(0L)
    override val coins: StateFlow<List<CoinUi>> = coinState
    override val prices: StateFlow<Map<String, Double>> = priceState
    override val changes: StateFlow<Long> = changeState

    /** Every chart that was asked for, and every price watch that was opened or closed. */
    val charts = mutableListOf<Pair<String, String>>()
    val watches = mutableListOf<Set<String>>()
    var open = 0

    override suspend fun chart(symbol: String, timeframe: String): ChartUi? {
        charts += symbol to timeframe
        return ChartUi(symbol, timeframe, List(5) { CandleUi(it * 60_000L, 1.0, 2.0, 0.5, 1.5, 10.0) }, emptyList())
    }

    override fun watchPrices(symbols: Set<String>): AutoCloseable {
        watches += symbols
        open++
        return AutoCloseable { open-- }
    }
}

class FakeTrades(initial: List<TradeUi> = emptyList()) : TradesModel {
    val state = MutableStateFlow(initial)
    override val trades: StateFlow<List<TradeUi>> = state
}

class FakeScorecard(initial: ScorecardUi = ScorecardUi()) : ScorecardModel {
    val state = MutableStateFlow(initial)
    override val scorecard: StateFlow<ScorecardUi> = state
}

class FakeAlerts(initial: List<AlertUi> = emptyList()) : AlertsModel {
    val state = MutableStateFlow(initial)
    override val alerts: StateFlow<List<AlertUi>> = state
}

class FakeLearn(override val pages: List<LearnPageUi> = listOf(
    LearnPageUi("trend", "Patterns", "Trend", "# Trend\nText of the trend page."),
    LearnPageUi("scorecard", "How it works", "Scorecard", "# Scorecard\nText of the scorecard page."),
)) : LearnModel {
    override fun pageForVariant(variant: String): String? = if (variant.startsWith("trend")) "trend" else null
}

class FakeSettings(initial: SettingsUi = defaultSettings()) : SettingsModel {
    val state = MutableStateFlow(initial)
    override val settings: StateFlow<SettingsUi> = state
    val log = mutableListOf<String>()
    var refuse: String? = null

    override suspend fun setFee(feePerSide: Double): Outcome {
        log += "fee $feePerSide"
        refuse?.let { return Outcome.Refused(it) }
        state.value = state.value.copy(feePerSide = feePerSide)
        return Outcome.Done
    }

    override suspend fun setExtraCosts(majors: Double, others: Double): Outcome {
        log += "extra $majors $others"
        state.value = state.value.copy(extraMajors = majors, extraOthers = others)
        return Outcome.Done
    }

    override suspend fun setBackgroundScanning(on: Boolean) {
        log += "background $on"
        state.value = state.value.copy(backgroundScanning = on)
    }

    override suspend fun setFollowFastCharts(on: Boolean) {
        log += "fast $on"
        state.value = state.value.copy(followFastCharts = on)
    }

    override suspend fun setBinanceUs(on: Boolean) {
        log += "us $on"
        state.value = state.value.copy(binanceUs = on)
    }

    override fun openSystemScreen(prompt: PermissionPrompt) {
        log += "open ${prompt.name}"
    }

    companion object {
        fun defaultSettings() = SettingsUi(
            feePerSide = 0.001, extraMajors = 0.0, extraOthers = 0.0, backgroundScanning = true, followFastCharts = true, binanceUs = false,
            permissions = PermissionsUi(alerts = true, exactAlarms = false, batteryExempt = false), version = "9.9.9", dataNote = "Stays on this phone.",
        )
    }
}

class FakePrompts : PermissionPrompts {
    val state = MutableStateFlow<PermissionPrompt?>(null)
    override val pending: StateFlow<PermissionPrompt?> = state
    val log = mutableListOf<String>()
    override fun accept(prompt: PermissionPrompt) { log += "accept ${prompt.name}"; state.value = null }
    override fun decline(prompt: PermissionPrompt) { log += "decline ${prompt.name}"; state.value = null }
}

class FakeDebug : DebugState {
    override val lines = MutableStateFlow(listOf("debug line"))
    val log = mutableListOf<String>()
    override fun refreshPairList() { log += "pairs" }
    override fun createTestList() { log += "test list" }
    override fun createFastTestList() { log += "fast list" }
    override fun scanNow() { log += "scan" }
    override fun sendTestAlert() { log += "alert" }
}

class FakeApp(
    override val lists: FakeLists = FakeLists(),
    override val markets: FakeMarkets = FakeMarkets(),
    override val trades: FakeTrades = FakeTrades(),
    override val scorecard: FakeScorecard = FakeScorecard(),
    override val alerts: FakeAlerts = FakeAlerts(),
    override val learn: FakeLearn = FakeLearn(),
    override val settings: FakeSettings = FakeSettings(),
    override val prompts: FakePrompts = FakePrompts(),
    override val debug: FakeDebug? = FakeDebug(),
) : AppModel {
    val link = MutableStateFlow<Link?>(null)
    override val pendingLink: StateFlow<Link?> = link
    override fun linkUsed() { link.value = null }

    companion object {
        val btc = CoinUi("BTCUSDT", "BTC", 65_000.0, 0.012, 1, listOf("15m", "1h", "4h"))
        val eth = CoinUi("ETHUSDT", "ETH", 3_200.5, -0.02, 0, listOf("1h"))
        val sol = CoinUi("SOLUSDT", "SOL", 150.0, null, 0, listOf("1d"))
        val list = ListUi(1, "My coins", true, listOf("BTCUSDT", "ETHUSDT", "SOLUSDT"), listOf("15m", "1h", "4h", "1d"))

        fun trade(
            id: Long, symbol: String = "BTCUSDT", tf: String = "1h", variant: String = "donchian20_1h", net: Double? = null, mode: String? = "trail",
        ) = TradeUi(
            id, variant, "Breakout: close above the 20-candle high", symbol, tf, 1_700_000_000_000L + id * 3_600_000L, 100.0, 95.0, null, mode,
            net?.let { ClosedUi(1_700_000_000_000L + id * 3_600_000L + 7_200_000L, 100.0 * (1 + it), if (it > 0) "target" else "stop", it, 0.001, 0.02, -0.01, 3) },
        )

        fun alert(id: Long, kind: String, symbol: String? = "BTCUSDT", tf: String? = "1h") =
            AlertUi(id, 1_700_000_000_000L + id * 1_000L, kind, "Title $id", "Body $id", symbol, tf)

        /** A fully filled app: one list, three coins, trades, a scorecard and alerts. */
        fun full() = FakeApp(
            lists = FakeLists(listOf(list)),
            markets = FakeMarkets(listOf(btc, eth, sol)),
            trades = FakeTrades(listOf(trade(1), trade(2, net = 0.03), trade(3, "ETHUSDT", net = -0.01))),
            scorecard = FakeScorecard(
                ScorecardUi(
                    listOf(ScoreRowUi("donchian20_1h", "Breakout: close above the 20-candle high", "1h", 1, 12, 0.5, 0.004, 0.001, 0.003, 1.2, "No verdict", "No verdict", false)), 20,
                ),
            ),
            alerts = FakeAlerts(listOf(alert(1, "signal"), alert(2, "exit"), alert(3, "warning"), alert(4, "missed"))),
        )
    }
}
