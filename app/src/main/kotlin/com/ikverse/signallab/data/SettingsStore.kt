package com.ikverse.signallab.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Small named settings, in the record database so they travel with the backup. */
class SettingsStore(
    private val db: RecordDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()

    suspend fun get(key: String): String? = lock.withLock {
        withContext(io) {
            db.writableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
        }
    }

    suspend fun set(key: String, value: String) {
        lock.withLock {
            withContext(io) {
                db.writableDatabase.execSQL("INSERT OR REPLACE INTO settings VALUES (?,?)", arrayOf<Any?>(key, value))
            }
        }
    }

    suspend fun getDouble(key: String, default: Double): Double = get(key)?.toDoubleOrNull() ?: default

    suspend fun setDouble(key: String, value: Double) = set(key, value.toString())

    suspend fun getBoolean(key: String, default: Boolean): Boolean = get(key)?.let { it == "true" } ?: default

    suspend fun setBoolean(key: String, value: Boolean) = set(key, value.toString())

    companion object {
        /** `https://api.binance.com` or `https://api.binance.us`. */
        const val DATA_HOST = "data_host"

        /** Keep a foreground service and an alarm so candles are scanned with the app closed. On unless the user turns it off. */
        const val SCAN_IN_BACKGROUND = "scan_in_background"

        /** Follow charts under an hour with the service awake. On unless the user turns it off; it costs battery. */
        const val FOLLOW_FAST_CHARTS = "follow_fast_charts"

        /** The exchange fee one way, as a fraction. Binance's entry tier is 0.001; BNB and VIP rates are lower. */
        const val FEE_PER_SIDE = "fee_per_side"

        /** Extra cost per round trip for thin coins, as a fraction. Off (0) unless the user turns it on. */
        const val EXTRA_COST_MAJORS = "extra_cost_majors"
        const val EXTRA_COST_OTHERS = "extra_cost_others"

        /** When the updater last heard from GitHub successfully, in phone-clock milliseconds. */
        const val LAST_UPDATE_CHECK = "last_update_check"

        /** Which permission prompts have already been shown, so none is asked twice. */
        const val ASKED_PREFIX = "asked_"
    }
}
