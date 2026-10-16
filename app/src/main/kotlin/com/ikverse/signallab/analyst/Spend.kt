package com.ikverse.signallab.analyst

import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.ledger.SettingRow
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset

/** Tokens and server-tool uses one or more requests used, as the API reports them. */
@Serializable
data class Usage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val searches: Long = 0,
    val fetches: Long = 0,
) {
    operator fun plus(o: Usage) = Usage(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite,
        searches + o.searches, fetches + o.fetches)
}

/** The models the analyst can run on, with their list prices in US dollars per million tokens. */
enum class ClaudeModel(val id: String, val label: String, val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double) {
    OPUS(Preferences.MODEL_OPUS, "Claude Opus 5.5", input = 4.0, output = 20.0, cacheRead = 0.20, cacheWrite = 5.0),
    SONNET(Preferences.MODEL_SONNET, "Claude Sonnet 5.5", input = 2.0, output = 10.0, cacheRead = 0.20, cacheWrite = 2.5);

    /** What [u] costs on this model: tokens at list price, and web searches at $10 per thousand (fetches cost only tokens). */
    fun cost(u: Usage): Double =
        (u.input * input + u.output * output + u.cacheRead * cacheRead + u.cacheWrite * cacheWrite) / 1_000_000.0 + u.searches * SEARCH

    companion object {
        const val SEARCH = 0.01

        fun of(id: String): ClaudeModel = entries.firstOrNull { it.id == id } ?: OPUS
    }
}

/**
 * What the analyst spent this calendar month (UTC), kept in the ledger's settings as `analyst_spend.<yyyy-MM>`. Every
 * request adds to it as soon as its usage is known, so the cap holds even when a run stops halfway.
 */
class SpendMeter(private val dao: LedgerDao, private val now: () -> Long = System::currentTimeMillis) {
    private fun key(t: Long): String {
        val d = Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC)
        return PREFIX + String.format(java.util.Locale.US, "%04d-%02d", d.year, d.monthValue)
    }

    suspend fun month(): Double = dao.setting(key(now()))?.toDoubleOrNull() ?: 0.0

    suspend fun add(usd: Double) {
        if (!(usd > 0)) return
        val k = key(now())
        val total = (dao.setting(k)?.toDoubleOrNull() ?: 0.0) + usd
        dao.putSetting(SettingRow(k, "%.6f".format(java.util.Locale.US, total), now()))
    }

    companion object {
        const val PREFIX = "analyst_spend."
    }
}
