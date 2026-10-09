package com.ikverse.signallab.ui

/**
 * The app's everyday wording: what a setup says, in sentences a person who has never traded can read. Plain functions, so a test can
 * hold each one to its word. The proper terms stay in the Learn pages.
 */
object PlainWords {
    /**
     * "SOL has a setup." The app never tells anyone what to do with money (a test holds every screen to its wording), so a card says what
     * matched and leaves the decision to the reader.
     */
    fun headline(base: String, lab: Boolean): String = if (lab) "$base matched one of your lab ideas." else "$base has a setup."

    private val labName = Regex("""lab\d+_.*""")

    fun isLab(variant: String): Boolean = labName.matches(variant)

    /** Why the pattern fired, in a sentence, by the start of its variant name. */
    fun why(variant: String): String = when {
        variant.startsWith("trend_ma") -> "The price just moved above its recent average, which often marks the start of a rise."
        variant.startsWith("donchian") -> "The price closed above its highest point of the last few weeks."
        variant.startsWith("tsmom") -> "This coin has had a strong week while the rest of the market was calmer."
        variant.startsWith("xsmom") -> "This is one of the strongest coins in your list right now."
        variant.startsWith("fade") -> "The price fell sharply and often bounces back after a drop like this."
        variant.startsWith("intraday_mom_") -> "The day started strongly and strong starts often keep going."
        variant.startsWith("intraday_breakout_") -> "The price left the range it has stayed in all day."
        variant.startsWith("bullish_harami_") -> "A small candle after a big falling one, which can mean sellers are running out."
        variant.startsWith("bullish_hikkake_") -> "A false move down that reversed, which can mean the real move is up."
        isLab(variant) -> "One of your own lab ideas matched. It is still being tested, so treat it as unproven."
        else -> "A pattern the app watches for matched."
    }

    /** How long ago, for a card: "just now", "12 min ago", "3 hours ago", "2 days ago". */
    fun ago(time: Long, now: Long): String {
        val minutes = ((now - time) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 120 -> "1 hour ago"
            minutes < 24 * 60 -> "${minutes / 60} hours ago"
            minutes < 48 * 60 -> "1 day ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    /** What moving from [from] to [to] is, as a fraction; null when either is missing or [from] is not a price. */
    fun move(from: Double?, to: Double?): Double? =
        if (from == null || to == null || from <= 0.0 || from.isNaN() || to.isNaN()) null else to / from - 1.0

    /**
     * How the pattern has done so far: "Won 41 of 60 finished trades." from its scorecard row, or a sentence that says there is too little
     * to say. A pattern needs [MIN_FINISHED] finished trades before the app quotes how often it won.
     */
    fun record(closed: Int, hitRate: Double?): String = when {
        hitRate == null || closed < MIN_FINISHED -> if (closed == 0) "No finished trades yet, so no record to show." else "Only $closed finished so far, too few to say how often it wins."
        else -> "Won ${Math.round(hitRate * closed)} of $closed finished practice trades."
    }

    const val MIN_FINISHED = 5

    /** A price move as the card shows it: "+6.6%" with a true minus. */
    fun signed(fraction: Double?): String = Fmt.signedPercent(fraction, 1)

    /** "1 trade" / "3 trades". */
    fun count(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

    /** A line saying where the price stands against the entry: "Now 142.30, +0.2% since the practice entry." */
    fun since(entry: Double, now: Double?): String? {
        val m = move(entry, now) ?: return null
        return "Now ${Fmt.price(now)}, ${Fmt.signedPercent(m, 1)} since the practice entry."
    }

    /** How the practice trades have done in all: [n] finished, [wins] of them above zero, [total] their results added up, and the running total. */
    class Account(val n: Int, val wins: Int, val total: Double, val curve: List<Double>) {
        val average: Double get() = if (n == 0) 0.0 else total / n
    }

    /** The finished trades added up in the order they finished, after costs; null when none has finished. Each result is a fraction of its entry. */
    fun account(trades: List<TradeUi>): Account? {
        val done = trades.mapNotNull { t -> t.closed }.sortedBy { it.exitTime }
        if (done.isEmpty()) return null
        var running = 0.0
        val curve = done.map { running += it.net; running }
        return Account(done.size, done.count { it.net > 0 }, running, curve)
    }

    /** The verdict as a short word a person can act on. The scorecard's own wording stays in the numbers. */
    fun verdictWord(verdict: String): String = when (verdict) {
        "Edge" -> "Working"
        "Losing" -> "Not working"
        "No edge" -> "No better than guessing"
        else -> "Too early to tell"
    }

    /** One sentence for why a pattern has its verdict, from the number of trades behind it. */
    fun verdictReason(verdict: String, closed: Int): String {
        val trades = count(closed, "finished trade")
        return when (verdict) {
            "Edge" -> "Beat random entries over $trades, after fees and allowing for how many patterns are tested."
            "Losing" -> "Did worse than random entries over $trades."
            "No edge" -> "$trades so far, and nothing clearly better than random entries yet."
            else -> if (closed == 0) "Nothing finished yet. It needs about $MIN_VERDICT trades before we say anything." else "Only $closed finished so far. It needs about $MIN_VERDICT before we say anything."
        }
    }

    /** The fewest finished trades before the scorecard gives a verdict (the engine's first tier). */
    const val MIN_VERDICT = 30

    /** A coin's note in the list: what its own finished trades say, in a sentence. */
    fun coinNote(closed: Int, wins: Int): String = when {
        closed < MIN_FINISHED -> "Too few finished trades to judge yet."
        wins.toDouble() / closed >= GOOD_RATE -> "Signals on this coin have worked well."
        wins.toDouble() / closed <= BAD_RATE -> "Mostly losing. Consider removing it."
        else -> "Working about as well as the rest."
    }

    const val GOOD_RATE = 0.55
    const val BAD_RATE = 0.40

    /** Working first, then the ones still being judged, then no better than guessing, then not working. */
    fun verdictRank(verdict: String): Int = when (verdict) {
        "Edge" -> 0
        "No verdict", "Judged on live trades only" -> 1
        "No edge" -> 2
        else -> 3
    }
}
