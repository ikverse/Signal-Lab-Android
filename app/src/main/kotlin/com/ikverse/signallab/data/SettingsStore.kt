package com.ikverse.signallab.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Small named settings, in the record database. Each remembers when it was last set, so that of two devices' copies of a
 * [SYNCED] setting the newer one wins.
 */
class SettingsStore(
    private val db: RecordDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val changes = MutableStateFlow(0L)

    /** Goes up by one after every change to a [SYNCED] setting, so sync knows there is something to send. */
    val version: StateFlow<Long> = changes.asStateFlow()

    suspend fun get(key: String): String? = lock.withLock {
        withContext(io) {
            db.writableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
        }
    }

    suspend fun set(key: String, value: String) {
        lock.withLock {
            withContext(io) {
                db.writableDatabase.execSQL("INSERT OR REPLACE INTO settings (key, value, updated_at) VALUES (?,?,?)", arrayOf<Any?>(key, value, clock()))
            }
        }
        if (key in SYNCED) changes.value = changes.value + 1
    }

    /** Tells whoever watches [version] that sync wrote settings straight to the database. */
    fun changedElsewhere() {
        changes.value = changes.value + 1
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

        /** Keep the screen on and dim it while the app is showing. Off unless the user turns it on. */
        const val DIM_SCREEN = "dim_screen"

        /** How dim: the name of one of the three levels. */
        const val DIM_LEVEL = "dim_level"

        /** Show a notification for each alert. On unless the user turns it off; the alerts are saved to the inbox either way. */
        const val ALERT_NOTIFICATIONS = "alert_notifications"

        /** Put the side bar (shown when the phone is held sideways, or the screen is wide) on the right edge. Off unless the user turns it on. */
        const val RAIL_ON_RIGHT = "rail_on_right"

        /** The exchange fee one way, as a fraction. Binance's entry tier is 0.001; BNB and VIP rates are lower. */
        const val FEE_PER_SIDE = "fee_per_side"

        /** Extra cost per round trip for thin coins, as a fraction. Off (0) unless the user turns it on. */
        const val EXTRA_COST_MAJORS = "extra_cost_majors"
        const val EXTRA_COST_OTHERS = "extra_cost_others"

        /** When the updater last heard from GitHub successfully, in phone-clock milliseconds. */
        const val LAST_UPDATE_CHECK = "last_update_check"

        /** Which permission prompts have already been shown, so none is asked twice. */
        const val ASKED_PREFIX = "asked_"

        /**
         * The settings that follow the user from device to device. Everything else (scanning, the screen, panel sizes, scan cursors,
         * what was asked) belongs to the device it was set on and is never sent.
         */
        val SYNCED: Set<String> = setOf(FEE_PER_SIDE, EXTRA_COST_MAJORS, EXTRA_COST_OTHERS, DATA_HOST)
    }
}
