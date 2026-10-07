package com.ikverse.signallab.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One answer kept from the Claude app. [card] names the question it answered; null when it did not say. */
class Report(val id: Long, val receivedAt: Long, val card: String?, val title: String, val body: String)

/** The analyst's reports, in the record database so they travel with the backup. */
class ReportLog(
    private val db: RecordDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val changes = MutableStateFlow(0L)

    /** Goes up by one after every report saved, so a screen knows to read again. */
    val version: StateFlow<Long> = changes.asStateFlow()

    private suspend fun <T> access(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        lock.withLock { withContext(io) { block(db.writableDatabase) } }

    /** Keeps a report and returns its id. */
    suspend fun add(card: String?, title: String, body: String): Long {
        val id = access { d ->
            val cv = android.content.ContentValues().apply {
                put("received_at", clock()); put("card", card); put("title", title); put("body", body)
            }
            d.insert("analyst_reports", null, cv)
        }
        changes.value = changes.value + 1
        return id
    }

    /** Newest first. */
    suspend fun all(limit: Int = 200): List<Report> = access { d ->
        d.rawQuery("SELECT id, received_at, card, title, body FROM analyst_reports ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Report(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getString(2), c.getString(3), c.getString(4))) }
        }
    }
}
