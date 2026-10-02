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

    suspend fun getBoolean(key: String, default: Boolean): Boolean = get(key)?.let { it == "true" } ?: default

    suspend fun setBoolean(key: String, value: Boolean) = set(key, value.toString())

    companion object {
        /** `https://api.binance.com` or `https://api.binance.us`. */
        const val DATA_HOST = "data_host"
    }
}
