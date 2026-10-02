package com.ikverse.signallab.state

import android.content.Context
import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.WatchlistRules
import com.ikverse.signallab.ui.LearnModel
import com.ikverse.signallab.ui.LearnPageUi
import java.util.Locale
import kotlin.math.roundToInt

/** Which pages exist and which pattern each explains. */
object LearnIndex {
    class Entry(val id: String, val group: String, val title: String)

    const val HOW = "How it works"
    const val PATTERNS = "Patterns"
    const val WARNINGS = "Warnings"

    val entries: List<Entry> = listOf(
        Entry("paper-trade", HOW, "What a paper trade is"),
        Entry("scorecard", HOW, "How the scorecard judges"),
        Entry("costs", HOW, "What costs are charged"),
        Entry("chart-sizes", HOW, "Chart sizes"),
        Entry("trend", PATTERNS, "Trend: price crosses above its average"),
        Entry("breakout", PATTERNS, "Breakout: close above the recent high"),
        Entry("momentum", PATTERNS, "Momentum: a coin's own strong week"),
        Entry("ranking", PATTERNS, "Ranking: the strongest coins in your list"),
        Entry("drop-fade", PATTERNS, "Drop fade: a sharp fall that may bounce"),
        Entry("day-momentum", PATTERNS, "Day momentum: a strong start to the day"),
        Entry("intraday-breakout", PATTERNS, "Intraday breakout: leaving the usual range"),
        Entry("harami", PATTERNS, "Bullish Harami"),
        Entry("hikkake", PATTERNS, "Hikkake"),
        Entry("pump", WARNINGS, "A pump in progress"),
        Entry("new-listing", WARNINGS, "A new coin"),
        Entry("volume-spike", WARNINGS, "A volume spike"),
    )

    /** The page that explains a pattern, by the start of its name; null for a name nothing explains. */
    fun pageFor(variant: String): String? = when {
        variant.startsWith("trend_ma") -> "trend"
        variant.startsWith("donchian") -> "breakout"
        variant.startsWith("tsmom") -> "momentum"
        variant.startsWith("xsmom") -> "ranking"
        variant.startsWith("fade") -> "drop-fade"
        variant.startsWith("intraday_mom_") -> "day-momentum"
        variant.startsWith("intraday_breakout_") -> "intraday-breakout"
        variant.startsWith("bullish_harami_") -> "harami"
        variant.startsWith("bullish_hikkake_") -> "hikkake"
        else -> null
    }
}

/** The numbers the pages quote, taken from the engine and data settings so a page can never say something the scan does not do. */
object LearnValues {
    private fun list(a: IntArray): String = a.map { it.toString() }.let { if (it.size == 1) it[0] else it.dropLast(1).joinToString(", ") + " and " + it.last() }

    private fun pct(fraction: Double): String = String.format(Locale.ROOT, "%.2f", fraction * 100)

    private fun whole(fraction: Double): String = (fraction * 100).roundToInt().toString()

    fun map(): Map<String, String> = mapOf(
        "TREND_WINDOWS" to list(EngineConfig.TREND_MA_WINDOWS),
        "DONCHIAN_LOOKBACKS" to list(EngineConfig.DONCHIAN_LOOKBACKS),
        "TSMOM_WEEKS" to list(EngineConfig.TS_MOMENTUM_WEEKS),
        "TSMOM_QUANTILE_PCT" to whole(1 - EngineConfig.TS_MOMENTUM_TOP_QUANTILE),
        "TSMOM_HISTORY_DAYS" to EngineConfig.TS_MOMENTUM_HISTORY_DAYS.toString(),
        "XS_WEEKS" to list(EngineConfig.XS_MOMENTUM_WEEKS),
        "XS_TOP_PCT" to whole(EngineConfig.XS_MOMENTUM_TOP_FRACTION),
        "XS_MIN_COINS" to EngineConfig.XS_MOMENTUM_MIN_COINS.toString(),
        "FADE_HOURS" to list(EngineConfig.FADE_HOURS),
        "FADE_SIGMA" to EngineConfig.FADE_SIGMA.toString(),
        "FADE_VOL_DAYS" to EngineConfig.FADE_VOL_DAYS.toString(),
        "DAY_MOM_TOP_PCT" to whole(1 - EngineConfig.INTRADAY_MOM_TOP_QUANTILE),
        "DAY_MOM_HISTORY_DAYS" to EngineConfig.INTRADAY_MOM_HISTORY_DAYS.toString(),
        "BREAKOUT_DAYS" to EngineConfig.INTRADAY_BREAKOUT_DAYS.toString(),
        "ATR_WINDOW" to EngineConfig.ATR_WINDOW.toString(),
        "TRAIL_STOP_ATR" to EngineConfig.TRAIL_STOP_ATR.toString(),
        "TRAIL_ACTIVATE_ATR" to EngineConfig.TRAIL_ACTIVATE_ATR.toString(),
        "TRAIL_DISTANCE_ATR" to EngineConfig.TRAIL_DISTANCE_ATR.toString(),
        "TRAIL_CAPS" to Timeframe.entries.joinToString(", ") { "${it.label}: ${EngineConfig.trailCapBars(it)} candles" },
        "LEARNED_STOP_ATR" to EngineConfig.LEARNED_STOP_ATR.toString(),
        "LEARN_MIN_SIGNALS" to EngineConfig.LEARN_MIN_SIGNALS.toString(),
        "LEARN_FALLBACK_BARS" to EngineConfig.LEARN_FALLBACK_HOLD_BARS.toString(),
        "FEE_PCT" to pct(EngineConfig.DEFAULT_FEE_PER_SIDE),
        "ROUND_TRIP_PCT" to pct(2 * EngineConfig.DEFAULT_FEE_PER_SIDE),
        "RANDOM_DRAWS" to EngineConfig.RANDOM_DRAWS_PER_TRADE.toString(),
        "RANDOM_WINDOWS" to Timeframe.entries.joinToString(", ") { "${it.label}: ${EngineConfig.randomWindowDays(it)} days" },
        "REGIME_MA_DAYS" to EngineConfig.REGIME_MA_DAYS.toString(),
        "EDGE_T" to EngineConfig.EDGE_T.toString(),
        "LOSING_T" to EngineConfig.LOSING_T.toString(),
        "ALPHA_PCT" to whole(EngineConfig.ALPHA),
        "TIER_1" to EngineConfig.VERDICT_TIERS[0].first.toString(),
        "TIER_2" to EngineConfig.VERDICT_TIERS[1].first.toString(),
        "TIER_3" to EngineConfig.VERDICT_TIERS[2].first.toString(),
        "PUMP_RISE_PCT" to whole(EngineConfig.PUMP_RISE),
        "PUMP_MINUTES" to EngineConfig.PUMP_MINUTES.toString(),
        "PUMP_VOLUME_MULTIPLE" to EngineConfig.PUMP_VOLUME_MULTIPLE.roundToInt().toString(),
        "PUMP_NORMAL_HOURS" to (EngineConfig.PUMP_NORMAL_MINUTES / 60).toString(),
        "VOLUME_SPIKE_MULTIPLE" to EngineConfig.VOLUME_SPIKE_MULTIPLE.roundToInt().toString(),
        "VOLUME_SPIKE_DAYS" to EngineConfig.VOLUME_SPIKE_DAYS.toString(),
        "NEW_COIN_DAYS" to DataConfig.NEW_COIN_DAYS.toString(),
        "HISTORY_KEPT" to Timeframe.entries.joinToString(", ") { "${it.label}: ${DataConfig.historyDays(it)} days" },
        "MAX_COINS_LIST" to WatchlistRules.MAX_COINS_PER_LIST.toString(),
        "MAX_COINS_ACTIVE" to WatchlistRules.MAX_ACTIVE_COINS.toString(),
        "MAX_1M" to WatchlistRules.maxCoinsOn(Timeframe.M1).toString(),
        "MAX_5M" to WatchlistRules.maxCoinsOn(Timeframe.M5).toString(),
    )

    private val placeholder = Regex("""\{\{([A-Z0-9_]+)}}""")

    /** Fills every `{{NAME}}` in [text]. A name with no value is a mistake in the page, so it fails loudly instead of showing a hole. */
    fun resolve(text: String, values: Map<String, String> = map()): String =
        placeholder.replace(text) { m -> values[m.groupValues[1]] ?: error("Learn page uses {{${m.groupValues[1]}}}, which has no value") }
}

/** The Learn pages, read from the app's assets with their numbers filled in. [read] returns the text of an asset path. */
class LearnCatalog(private val read: (String) -> String) : LearnModel {
    constructor(context: Context) : this({ path -> context.assets.open(path).bufferedReader().use { it.readText() } })

    override val pages: List<LearnPageUi> by lazy {
        val values = LearnValues.map()
        LearnIndex.entries.map { e -> LearnPageUi(e.id, e.group, e.title, LearnValues.resolve(read("learn/pages/${e.id}.md"), values)) }
    }

    override fun pageForVariant(variant: String): String? = LearnIndex.pageFor(variant)
}
