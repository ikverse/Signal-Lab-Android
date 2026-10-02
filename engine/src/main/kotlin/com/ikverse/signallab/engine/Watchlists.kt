package com.ikverse.signallab.engine

/** A named list of up to 30 coins. Only active lists are analysed. */
data class Watchlist(val id: Long, val name: String, val symbols: List<String>, val active: Boolean)

/** Why a watchlist change was refused. The app words each one for the user. */
enum class Refusal {
    NAME_BLANK, NAME_TOO_LONG, NAME_TAKEN, LIST_NOT_FOUND, LIST_FULL, DUPLICATE_COIN, UNKNOWN_COIN, OVER_ACTIVE_CAP,
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

    /** The distinct coins across every active list, in the order they first appear. A coin in two lists counts once. */
    fun activeCoins(lists: List<Watchlist>): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        for (l in lists) if (l.active) out.addAll(l.symbols)
        return out
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

    /** Whether [symbol] can join [list], given every list. Adding to an active list also has to respect the 150 cap. */
    fun checkAdd(lists: List<Watchlist>, list: Watchlist, symbol: String): Refusal? = when {
        symbol in list.symbols -> Refusal.DUPLICATE_COIN
        list.symbols.size >= MAX_COINS_PER_LIST -> Refusal.LIST_FULL
        list.active && symbol !in activeCoins(lists) && activeCoins(lists).size + 1 > MAX_ACTIVE_COINS -> Refusal.OVER_ACTIVE_CAP
        else -> null
    }

    /** Whether [list] can be switched on without the active coins passing 150. */
    fun checkActivate(lists: List<Watchlist>, list: Watchlist): Refusal? {
        if (list.active) return null
        val after = LinkedHashSet(activeCoins(lists)).also { it.addAll(list.symbols) }
        return if (after.size > MAX_ACTIVE_COINS) Refusal.OVER_ACTIVE_CAP else null
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
