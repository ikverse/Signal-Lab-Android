package com.ikverse.signallab.engine

/** A named list of up to 30 coins, and the chart sizes it is watched on. Only active lists are analysed. */
data class Watchlist(
    val id: Long,
    val name: String,
    val symbols: List<String>,
    val active: Boolean,
    val timeframes: Set<Timeframe> = Timeframe.NEW_LIST_DEFAULT,
)

/** Why a watchlist change was refused. The app words each one for the user. */
enum class Refusal {
    NAME_BLANK, NAME_TOO_LONG, NAME_TAKEN, LIST_NOT_FOUND, LIST_FULL, DUPLICATE_COIN, UNKNOWN_COIN, OVER_ACTIVE_CAP,
    /** Too many coins on 1-minute or 5-minute charts, which cost the most data and battery. */
    OVER_FAST_CAP,
    NO_TIMEFRAME,
}

/**
 * The watchlist rules, as pure functions so they are tested in milliseconds and cannot drift from the
 * screens: at most 30 coins in a list, no coin twice in a list, and at most 150 distinct coins across
 * every active list, because each active coin costs storage, downloads and battery.
 */
object WatchlistRules {
    const val MAX_COINS_PER_LIST = 30
    const val MAX_ACTIVE_COINS = 150
    const val MAX_NAME_LENGTH = 40

    /** Coins that may be watched on 1-minute and 5-minute charts, across every active list. Slower charts only have the overall cap. */
    fun maxCoinsOn(tf: Timeframe): Int = when (tf) {
        Timeframe.M1 -> 10
        Timeframe.M5 -> 30
        else -> MAX_ACTIVE_COINS
    }

    /** The distinct coins across every active list, in the order they first appear. A coin in two lists counts once. */
    fun activeCoins(lists: List<Watchlist>): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        for (l in lists) if (l.active) out.addAll(l.symbols)
        return out
    }

    /** The distinct coins watched on chart [tf]: those in an active list that includes it. */
    fun activeCoinsOn(lists: List<Watchlist>, tf: Timeframe): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        for (l in lists) if (l.active && tf in l.timeframes) out.addAll(l.symbols)
        return out
    }

    /** For each coin in an active list, every chart size it is watched on. */
    fun timeframesByCoin(lists: List<Watchlist>): Map<String, Set<Timeframe>> {
        val out = LinkedHashMap<String, MutableSet<Timeframe>>()
        for (l in lists) if (l.active) for (s in l.symbols) out.getOrPut(s) { LinkedHashSet() }.addAll(l.timeframes)
        return out
    }

    /** Whether the active lists, as they would stand, keep within every chart's cap. */
    private fun overCap(lists: List<Watchlist>): Refusal? {
        for (tf in Timeframe.entries) {
            if (activeCoinsOn(lists, tf).size > maxCoinsOn(tf)) return if (tf == Timeframe.M1 || tf == Timeframe.M5) Refusal.OVER_FAST_CAP else Refusal.OVER_ACTIVE_CAP
        }
        return if (activeCoins(lists).size > MAX_ACTIVE_COINS) Refusal.OVER_ACTIVE_CAP else null
    }

    /** A name trimmed and collapsed to single spaces, or the reason it cannot be used. Case-insensitive uniqueness is checked against [others]. */
    fun cleanName(raw: String, others: Collection<String>): Pair<String?, Refusal?> {
        val name = raw.trim().replace(Regex("\\s+"), " ")
        return when {
            name.isEmpty() -> null to Refusal.NAME_BLANK
            name.length > MAX_NAME_LENGTH -> null to Refusal.NAME_TOO_LONG
            others.any { it.equals(name, ignoreCase = true) } -> null to Refusal.NAME_TAKEN
            else -> name to null
        }
    }

    /** Whether [symbol] can join [list], given every list. Adding to an active list also has to respect the caps. */
    fun checkAdd(lists: List<Watchlist>, list: Watchlist, symbol: String): Refusal? {
        if (symbol in list.symbols) return Refusal.DUPLICATE_COIN
        if (list.symbols.size >= MAX_COINS_PER_LIST) return Refusal.LIST_FULL
        if (!list.active) return null
        return overCap(lists.map { if (it.id == list.id) it.copy(symbols = it.symbols + symbol) else it })
    }

    /** Whether [list] can be switched on without any chart going over its cap. */
    fun checkActivate(lists: List<Watchlist>, list: Watchlist): Refusal? {
        if (list.active) return null
        return overCap(lists.map { if (it.id == list.id) it.copy(active = true) else it })
    }

    /** Whether [list] can be watched on [timeframes]: at least one, and the caps of an active list still hold. */
    fun checkTimeframes(lists: List<Watchlist>, list: Watchlist, timeframes: Set<Timeframe>): Refusal? {
        if (timeframes.isEmpty()) return Refusal.NO_TIMEFRAME
        if (!list.active) return null
        return overCap(lists.map { if (it.id == list.id) it.copy(timeframes = timeframes) else it })
    }
}

/**
 * Which coins the picker offers. Stablecoins and wrapped assets only add noise to a price scanner, and
 * leveraged tokens decay by design, so none of them are offered. Names catch the known ones; the price
 * checks catch the rest, whatever they are called.
 */
object CoinFilter {
    /** Stablecoins, fiat and coins that just track something else. */
    val PEGGED_BASES: Set<String> = setOf(
        "USDC", "FDUSD", "TUSD", "DAI", "USDP", "BUSD", "USDE", "USD1", "RLUSD", "PYUSD", "USDS", "BFUSD", "USDD",
        "EUR", "GBP", "TRY", "BRL", "AEUR", "EURI", "XUSD",
        "WBTC", "WBETH", "BETH", "PAXG", "XAUT", "STETH",
    )

    private val LEVERAGED_SUFFIXES = listOf("UP", "DOWN", "BULL", "BEAR")

    /** Stablecoin band: every close inside it means the coin is pegged to a dollar. */
    const val STABLE_LOW = 0.97
    const val STABLE_HIGH = 1.03
    const val STABLE_DAYS = 30

    fun isLeveraged(base: String, allBases: Set<String>): Boolean =
        LEVERAGED_SUFFIXES.any { base.endsWith(it) && base.length > it.length && base.removeSuffix(it) in allBases }

    /** The last day's range sat inside the stablecoin band. A cheap check from the 24-hour ticker alone. */
    fun rangeLooksStable(high24h: Double, low24h: Double): Boolean =
        low24h >= STABLE_LOW && high24h <= STABLE_HIGH

    /** The last [STABLE_DAYS] daily closes all sat inside the band. Needs the whole window; fewer closes prove nothing. */
    fun looksStable(dailyCloses: DoubleArray): Boolean =
        dailyCloses.size >= STABLE_DAYS &&
            dailyCloses.takeLast(STABLE_DAYS).all { it in STABLE_LOW..STABLE_HIGH }

    /** Whether the picker should offer this pair, from what the pair list and the 24-hour ticker say. */
    fun offered(base: String, allBases: Set<String>, high24h: Double, low24h: Double): Boolean =
        base !in PEGGED_BASES && !isLeveraged(base, allBases) && !rangeLooksStable(high24h, low24h)
}
