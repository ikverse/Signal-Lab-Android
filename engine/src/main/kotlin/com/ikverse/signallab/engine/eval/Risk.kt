package com.ikverse.signallab.engine.eval

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.EngineConfig
import com.ikverse.signallab.engine.Indicators
import kotlin.math.sqrt

/**
 * How a coin moves with Bitcoin, from daily closes: its [beta] to Bitcoin over the last [BETA_DAYS] days, and the daily
 * variance of what Bitcoin does not explain ([idioVar]) over the last [VOL_DAYS] days.
 */
class Exposure(val beta: Double, val idioVar: Double)

/** An open call as the book holds it: its share of the account and how its coin moves. */
class Holding(val weight: Double, val exposure: Exposure)

/** How overheated leverage is, from the futures market (docs/strategy-research.md §7.2). */
enum class Heat(val factor: Double, val words: String?) {
    NONE(1.0, null),

    /** Implied volatility jumped: new calls at half size. */
    HALF(0.5, "implied volatility jumped this week, so half size"),

    /** Funding and the quarterly premium are both overheated: no new calls. */
    BLOCK(0.0, "leverage is overheated"),
}

/** The size the engine gives a new call, the plain size it started from, and what changed it. */
class Sized(val size: Double, val plain: Double, val reasons: List<String>)

/**
 * Signal Lab 3's sizing (docs/strategy-research.md §7.1). A call starts from its plain size, one average true range equal
 * to [Live.RISK_PER_ATR] of the account. Then:
 *
 * 1. A trend call is scaled by how strong its trend is: [trendFactor].
 * 2. Leverage heat halves it or blocks it: [Heat].
 * 3. The whole book is held near a volatility target: when the open calls and this one together would swing more than
 *    [targetVol] a year, this call shrinks until they would not, or as far as it can.
 * 4. The book's exposure to Bitcoin, the sum of each call's share times its coin's beta, stays under [maxBetaExposure].
 * 5. Once enough live calls exist, total exposure stays under half the Kelly fraction their results imply ([kellyCap]).
 *
 * The plain size is always kept beside the engine's size, so the ledger can tell whether the engine helps.
 */
object Risk {
    const val BETA_DAYS = 90
    const val VOL_DAYS = 20

    /** The book's target volatility, a year. */
    const val TARGET_VOL = 0.25

    /** The most beta-weighted exposure to Bitcoin the book holds, as a share of the account. */
    const val MAX_BETA_EXPOSURE = 1.5

    /** Live calls needed before their results cap the book. */
    const val KELLY_MIN_CALLS = 100

    private const val DAYS_A_YEAR = 365.0

    /** How a coin moves with Bitcoin, from the two coins' closes up to the same day; null with too little history. */
    fun exposure(coin: DoubleArray, btc: DoubleArray): Exposure? {
        val n = minOf(coin.size, btc.size) - 1
        if (n < VOL_DAYS) return null
        val rc = DoubleArray(n) { coin[coin.size - n + it] / coin[coin.size - n - 1 + it] - 1 }
        val rb = DoubleArray(n) { btc[btc.size - n + it] / btc[btc.size - n - 1 + it] - 1 }
        val m = minOf(n, BETA_DAYS)
        val bc = rc.copyOfRange(n - m, n)
        val bb = rb.copyOfRange(n - m, n)
        val mb = bb.average()
        val mc = bc.average()
        var cov = 0.0
        var varB = 0.0
        for (i in 0 until m) {
            cov += (bc[i] - mc) * (bb[i] - mb)
            varB += (bb[i] - mb) * (bb[i] - mb)
        }
        val beta = if (varB > 0) cov / varB else 1.0
        val resid = DoubleArray(VOL_DAYS) { rc[n - VOL_DAYS + it] - beta * rb[n - VOL_DAYS + it] }
        val mr = resid.average()
        val idio = resid.sumOf { (it - mr) * (it - mr) } / (VOL_DAYS - 1)
        if (beta.isNaN() || idio.isNaN()) return null
        return Exposure(beta, idio)
    }

    /** Bitcoin's daily variance over the last [VOL_DAYS] days; NaN with too little history. */
    fun btcVariance(btc: DoubleArray): Double {
        if (btc.size <= VOL_DAYS) return Double.NaN
        val r = DoubleArray(VOL_DAYS) { btc[btc.size - VOL_DAYS + it] / btc[btc.size - VOL_DAYS - 1 + it] - 1 }
        val m = r.average()
        return r.sumOf { (it - m) * (it - m) } / (VOL_DAYS - 1)
    }

    /** The book's volatility a year, from its holdings and Bitcoin's daily variance (one factor: Bitcoin). */
    fun bookVol(holdings: List<Holding>, btcVar: Double): Double {
        val beta = holdings.sumOf { it.weight * it.exposure.beta }
        val idio = holdings.sumOf { it.weight * it.weight * it.exposure.idioVar }
        return sqrt((beta * beta * btcVar + idio) * DAYS_A_YEAR)
    }

    /**
     * How strong a trend call's trend is, as a multiplier from 0.5 to 1.5: the close's distance above its 50-day average in
     * average true ranges. Two ranges above is 1; at the average, 0.5; six or more above, 1.5. 1 when it cannot be read.
     */
    fun trendFactor(c: Candles, i: Int): Double {
        val sma = Indicators.sma(c.close, 50)[i]
        val atr = Indicators.atr(c.high, c.low, c.close, EngineConfig.ATR_WINDOW)[i]
        if (sma.isNaN() || !(atr > 0)) return 1.0
        val dist = (c.close[i] - sma) / atr
        return (1 + (dist - 2) / 4).coerceIn(0.5, 1.5)
    }

    /**
     * Half the Kelly fraction implied by finished live calls' net results (mean over variance, per call), or null when there
     * are fewer than [KELLY_MIN_CALLS] or they do not have a positive mean. A losing record is the tiers' job to stop, not a
     * reason to size everything at nothing.
     */
    fun kellyCap(liveNet: DoubleArray): Double? {
        if (liveNet.size < KELLY_MIN_CALLS) return null
        val m = Moments.of(liveNet)
        if (!(m.mean > 0) || !(m.sd > 0)) return null
        return 0.5 * m.mean / (m.sd * m.sd)
    }

    /**
     * The size of a new call whose plain size is [plain], on a coin moving like [exposure], given the [book] already held.
     * [trend] is the trend factor (1 for a non-trend strategy), [heat] the leverage heat, [btcVar] Bitcoin's daily variance.
     * [kelly] caps the whole book's total share when known.
     */
    fun size(
        plain: Double,
        trend: Double,
        heat: Heat,
        exposure: Exposure?,
        book: List<Holding>,
        btcVar: Double,
        kelly: Double? = null,
        targetVol: Double = TARGET_VOL,
        maxBetaExposure: Double = MAX_BETA_EXPOSURE,
    ): Sized {
        val reasons = ArrayList<String>()
        if (!(plain > 0)) return Sized(0.0, 0.0, reasons)
        var w = plain * trend
        if (trend < 0.95) reasons.add("weak trend")
        if (trend > 1.05) reasons.add("strong trend")
        if (heat != Heat.NONE) {
            w *= heat.factor
            heat.words?.let(reasons::add)
        }
        if (w > 0 && exposure != null && !btcVar.isNaN()) {
            // Shrink this call until the book with it swings no more than the target, or to nothing if the book already does.
            fun volWith(x: Double) = bookVol(book + Holding(x, exposure), btcVar)
            if (volWith(w) > targetVol) {
                var lo = 0.0
                var hi = w
                repeat(40) {
                    val mid = (lo + hi) / 2
                    if (volWith(mid) > targetVol) hi = mid else lo = mid
                }
                w = lo
                reasons.add("the book is already swinging a lot")
            }
            val held = book.sumOf { it.weight * it.exposure.beta }
            if (exposure.beta > 0 && held + w * exposure.beta > maxBetaExposure) {
                w = maxOf(0.0, (maxBetaExposure - held) / exposure.beta)
                reasons.add("enough already rides on Bitcoin's direction")
            }
        }
        if (kelly != null) {
            val room = kelly - book.sumOf { it.weight }
            if (w > room) {
                w = maxOf(0.0, room)
                reasons.add("the live record caps the total")
            }
        }
        return Sized(w.coerceIn(0.0, Live.MAX_SIZE), plain, reasons)
    }
}
