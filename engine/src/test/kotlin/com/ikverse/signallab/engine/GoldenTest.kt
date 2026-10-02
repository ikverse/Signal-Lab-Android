package com.ikverse.signallab.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin engine against what the research version produced from the same real candles.
 * Signals, exits, fees and the deterministic statistics must agree; the random baselines are not
 * compared here (different generator) and are checked statistically in [RandomBaselineTest].
 */
class GoldenTest {
    private val tfs = listOf(Timeframe.D1, Timeframe.H4, Timeframe.H1)

    private val signalCache = HashMap<Timeframe, Map<SignalKey, Map<String, BooleanArray>>>()

    @Synchronized
    private fun signals(tf: Timeframe) = signalCache.getOrPut(tf) { Signals.compute(Golden.panel(tf)) }

    @Test
    fun indicatorsMatchOnRealBitcoin() {
        val ind = Golden.expected.getJSONObject("indicators")
        val c = Golden.panel(Timeframe.D1).getValue("BTCUSDT")
        val ma20 = Indicators.sma(c.close, 20)
        assertSeries(ind.getJSONArray("sma20").doubles(), ma20, "sma20")
        assertSeries(ind.getJSONArray("sma50").doubles(), Indicators.sma(c.close, 50), "sma50")
        assertSeries(ind.getJSONArray("sma200").doubles(), Indicators.sma(c.close, 200), "sma200")
        assertSeries(ind.getJSONArray("rolling_max_prior_20").doubles(), Indicators.rollingMaxPrior(c.high, 20), "rolling max")
        assertSeries(ind.getJSONArray("rolling_std_30_of_returns").doubles(),
            Indicators.rollingStd(Indicators.pctReturn(c.close, 1), 30), "rolling std")
        assertSeries(ind.getJSONArray("true_range").doubles(), Indicators.trueRange(c.high, c.low, c.close), "true range")
        assertSeries(ind.getJSONArray("atr14").doubles(), Indicators.atr(c.high, c.low, c.close, 14), "atr")
        assertSeries(ind.getJSONArray("pct_return_14").doubles(), Indicators.pctReturn(c.close, 14), "pct return")
        assertSeries(ind.getJSONArray("rsi14").doubles(), Indicators.rsi(c.close, 14), "rsi")
        val crossed = ind.getJSONArray("crossed_above_close_sma20")
        val got = Indicators.crossedAbove(c.close, ma20)
        for (i in got.indices) assertEquals(crossed.getBoolean(i), got[i], "crossed_above[$i]")
        val regime = ind.getJSONArray("regime").ints()
        assertTrue(regime.contentEquals(PaperTrading.regimeSeries(c, c.closeTime)), "regime series differs")
    }

    @Test
    fun everySignalFiresOnExactlyTheSameBars() {
        for (tf in tfs) {
            val expected = Golden.expected.getJSONObject("signals").getJSONObject(tf.label)
            val got = signals(tf).entries.associate { it.key.name to it }
            // The app now also runs patterns the research never had, and no longer runs 1-4 week momentum on 1h charts.
            val gone = expected.keyList().toSet() - got.keys
            assertEquals(if (tf == Timeframe.H1) expected.keyList().filter { it.startsWith("tsmom") }.toSet() else emptySet(), gone, "${tf.label}: research variants missing")
            val added = got.keys - expected.keyList().toSet()
            assertTrue(added.isNotEmpty() && added.all { it.startsWith("bullish_") || it.startsWith("intraday_breakout_") }, "${tf.label}: unexpected new variants $added")
            var totalFlags = 0
            for (name in expected.keyList()) {
                if (name in gone) continue
                val e = expected.getJSONObject(name)
                val (key, bySymbol) = got.getValue(name).let { it.key to it.value }
                assertEquals(e.getString("family"), key.family, "$name family")
                val params = e.getJSONObject("params")
                assertEquals(params.keyList().toSet(), key.params.keys, "$name params")
                for (p in params.keyList()) assertClose(params.getDouble(p), key.params.getValue(p), "$name param $p")
                val flags = e.getJSONObject("flags")
                assertEquals(flags.keyList().toSet(), bySymbol.keys, "$name coins")
                for (sym in flags.keyList()) {
                    val want = flags.getJSONArray(sym).ints()
                    val have = bySymbol.getValue(sym).indices.filter { bySymbol.getValue(sym)[it] }.toIntArray()
                    assertTrue(want.contentEquals(have), "$name $sym: signals differ (python ${want.size}, kotlin ${have.size})")
                    totalFlags += want.size
                }
            }
            assertTrue(totalFlags > 1000, "${tf.label}: golden data exercised only $totalFlags signals")
        }
    }

    @Test
    fun everyPaperTradeAndItsDeterministicStatisticsMatch() {
        var compared = 0
        for (tf in tfs) {
            val panel = Golden.panel(tf)
            val regimes = Golden.regimes(tf)
            val expTrades = Golden.expected.getJSONObject("trades").getJSONObject(tf.label)
            val expStats = Golden.expected.getJSONObject("stats").getJSONObject(tf.label)
            for ((key, flags) in signals(tf)) {
                if (!expTrades.has(key.name)) continue // a pattern the research did not have
                val run = Scorecard.runVariant(key, flags, panel, tf, regimes)
                val rows = expTrades.getJSONArray(key.name)
                assertEquals(rows.length(), run.trades.size, "${tf.label} ${key.name}: trade count")
                val byKey = run.trades.associateBy { it.symbol to it.barTime }
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONArray(i)
                    val t = byKey[r.getString(0) to r.getLong(1)] ?: error("${key.name}: no trade for ${r.getString(0)} at ${r.getLong(1)}")
                    val what = "${tf.label} ${key.name} ${t.symbol} ${t.barTime}"
                    assertEquals(r.getLong(2), t.entryTime, "$what entry time")
                    assertClose(r.getDouble(3), t.entryPrice, "$what entry price")
                    assertEquals(r.getLong(4), t.exitTime, "$what exit time")
                    assertClose(r.getDouble(5), t.exitPrice, "$what exit price")
                    assertEquals(r.getString(6), t.exitReason.label, "$what exit reason")
                    assertEquals(r.getInt(7), t.barsHeld, "$what bars held")
                    assertClose(r.getDouble(8), t.gross, "$what gross")
                    assertClose(r.getDouble(9), t.net, "$what net")
                    assertEquals(r.getInt(10), t.regime, "$what regime")
                    val h = r.getJSONObject(11)
                    assertEquals(h.keyList().map { it.toInt() }.toSet(), t.horizons.keys, "$what horizons")
                    for (k in h.keyList()) {
                        val want = if (h.isNull(k)) Double.NaN else h.getDouble(k)
                        assertClose(want, t.horizons.getValue(k.toInt()).net, "$what horizon $k")
                    }
                    compared++
                }
                compareStats(expStats.getJSONObject(key.name), run, "${tf.label} ${key.name}")
            }
        }
        assertTrue(compared > 20_000, "golden data exercised only $compared trades")
    }

    private fun compareStats(e: JSONObject, run: VariantRun, what: String) {
        assertEquals(e.getInt("n"), run.trades.size, "$what n")
        if (run.trades.isEmpty()) return
        val nets = run.trades.map { it.net }
        val wins = nets.filter { it > 0 }.sum()
        val losses = -nets.filter { it < 0 }.sum()
        assertClose(e.getDouble("hit"), nets.count { it > 0 }.toDouble() / nets.size, "$what hit")
        assertClose(e.getDouble("mean"), nets.sum() / nets.size, "$what mean")
        assertClose(e.getDouble("gross_mean"), run.trades.sumOf { it.gross } / nets.size, "$what gross mean")
        assertClose(e.getDouble("avg_bars_held"), run.trades.sumOf { it.barsHeld }.toDouble() / nets.size, "$what bars held")
        if (!e.isNull("profit_factor")) assertClose(e.getDouble("profit_factor"), wins / losses, "$what profit factor")
        val exits = e.getJSONObject("exits")
        for (reason in ExitReason.entries) {
            assertEquals(exits.getInt(reason.label), run.trades.count { it.exitReason == reason }, "$what exits ${reason.label}")
        }
        val sorted = nets.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        assertClose(e.getDouble("median"), median, "$what median")
        val score = Scorecard.score(SignalKey(what.substringAfter(' '), "x", emptyMap()), "x", Timeframe.D1, run)
        assertClose(e.getDouble("hit"), score.hit, "$what score hit")
        assertClose(e.getDouble("median"), score.median, "$what score median")
        assertEquals(e.getInt("n"), score.n, "$what score n")
    }

    @Test
    fun statisticsFunctionsMatchOnFixedInputs() {
        val stats = Golden.expected.getJSONObject("statistics")
        val cases: JSONArray = stats.getJSONArray("cluster_t")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val want = if (c.isNull("cluster_t")) Double.NaN else c.getDouble("cluster_t")
            assertClose(want, Statistics.clusterT(c.getJSONArray("x").doubles(), c.getJSONArray("clusters").longs()), "cluster t case $i")
        }
        val tiers = stats.getJSONObject("tiers")
        for (n in tiers.keyList()) assertEquals(tiers.getString(n), Statistics.tier(n.toInt()), "tier for $n")
        val judged = stats.getJSONArray("judged")
        for (i in 0 until judged.length()) {
            val j = judged.getJSONObject(i)
            val scores = j.getJSONArray("scores")
            val input = (0 until scores.length()).map {
                val s = scores.getJSONObject(it)
                Statistics.Judged(s.getString("variant"), s.getInt("n"),
                    if (s.isNull("t_cluster")) Double.NaN else s.getDouble("t_cluster"),
                    if (s.isNull("p")) Double.NaN else s.getDouble("p"))
            }
            val out = Statistics.judge(input, j.getInt("trials"))
            for (k in out.indices) {
                assertClose(j.getJSONArray("p_holm").getDouble(k), out[k].pHolm, "holm $i/$k")
                assertClose(j.getJSONArray("q_bh").getDouble(k), out[k].qBh, "bh $i/$k")
                assertEquals(j.getJSONArray("verdict").getString(k), out[k].verdict, "verdict $i/$k")
            }
        }
    }

    @Test
    fun configurationEqualsWhatTheResearchWasRunWith() {
        val c = Golden.expected.getJSONObject("config")
        assertClose(c.getDouble("COST_MAJORS"), EngineConfig.COST_MAJORS, "major cost")
        assertClose(c.getDouble("COST_OTHERS"), EngineConfig.COST_OTHERS, "other cost")
        assertTrue(c.getJSONArray("TREND_MA_WINDOWS").ints().contentEquals(EngineConfig.TREND_MA_WINDOWS))
        assertTrue(c.getJSONArray("DONCHIAN_LOOKBACKS").ints().contentEquals(EngineConfig.DONCHIAN_LOOKBACKS))
        assertTrue(c.getJSONArray("TS_MOMENTUM_WEEKS").ints().contentEquals(EngineConfig.TS_MOMENTUM_WEEKS))
        assertTrue(c.getJSONArray("XS_MOMENTUM_WEEKS").ints().contentEquals(EngineConfig.XS_MOMENTUM_WEEKS))
        assertTrue(c.getJSONArray("FADE_HOURS").ints().contentEquals(EngineConfig.FADE_HOURS))
        assertClose(c.getDouble("TS_MOMENTUM_TOP_QUANTILE"), EngineConfig.TS_MOMENTUM_TOP_QUANTILE, "ts quantile")
        assertEquals(c.getInt("TS_MOMENTUM_HISTORY_DAYS"), EngineConfig.TS_MOMENTUM_HISTORY_DAYS)
        assertClose(c.getDouble("XS_MOMENTUM_TOP_FRACTION"), EngineConfig.XS_MOMENTUM_TOP_FRACTION, "xs fraction")
        assertEquals(c.getInt("XS_MOMENTUM_MIN_COINS"), EngineConfig.XS_MOMENTUM_MIN_COINS)
        assertClose(c.getDouble("FADE_SIGMA"), EngineConfig.FADE_SIGMA, "fade sigma")
        assertEquals(c.getInt("FADE_VOL_DAYS"), EngineConfig.FADE_VOL_DAYS)
        assertClose(c.getDouble("INTRADAY_MOM_TOP_QUANTILE"), EngineConfig.INTRADAY_MOM_TOP_QUANTILE, "intraday quantile")
        assertEquals(c.getInt("INTRADAY_MOM_HISTORY_DAYS"), EngineConfig.INTRADAY_MOM_HISTORY_DAYS)
        assertEquals(c.getInt("ATR_WINDOW"), EngineConfig.ATR_WINDOW)
        assertClose(c.getDouble("TARGET_ATR"), EngineConfig.TARGET_ATR, "target")
        assertClose(c.getDouble("STOP_ATR"), EngineConfig.STOP_ATR, "stop")
        assertEquals(c.getInt("REGIME_MA_DAYS"), EngineConfig.REGIME_MA_DAYS)
        assertClose(c.getDouble("EDGE_T"), EngineConfig.EDGE_T, "edge t")
        assertClose(c.getDouble("LOSING_T"), EngineConfig.LOSING_T, "losing t")
        assertClose(c.getDouble("ALPHA"), EngineConfig.ALPHA, "alpha")
        assertEquals(c.getString("VERDICT_TOP"), EngineConfig.VERDICT_TOP)
        assertEquals(c.getString("FORWARD_ONLY_VERDICT"), EngineConfig.FORWARD_ONLY_VERDICT)
        assertEquals(c.getJSONArray("FORWARD_ONLY_VARIANTS").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
            EngineConfig.FORWARD_ONLY_VARIANTS)
        val tiers = c.getJSONArray("VERDICT_TIERS")
        assertEquals((0 until tiers.length()).map { tiers.getJSONArray(it).getInt(0) to tiers.getJSONArray(it).getString(1) },
            EngineConfig.VERDICT_TIERS)
        val limits = c.getJSONObject("TIME_LIMIT_BARS")
        val horizons = c.getJSONObject("HORIZON_BARS")
        for (tf in tfs) {
            assertEquals(limits.getInt(tf.label), EngineConfig.timeLimitBars(tf), "time limit ${tf.label}")
            assertTrue(horizons.getJSONArray(tf.label).ints().contentEquals(EngineConfig.horizonBars(tf)), "horizons ${tf.label}")
        }
        assertEquals(Golden.expected.getJSONArray("majors").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }, EngineConfig.MAJORS)
    }
}
