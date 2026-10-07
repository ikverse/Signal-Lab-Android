package com.ikverse.signallab.data

import com.ikverse.signallab.engine.LabPatterns
import com.ikverse.signallab.engine.VariantLabels
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A lab pattern in the record. [definition] is its written form (see the analyst's LabFormat); [stoppedAt] is null while it runs. */
class LabRecord(
    val id: Long,
    val startedAt: Long,
    val reportId: Long?,
    val title: String,
    val reason: String?,
    val definition: String,
    val stoppedAt: Long?,
) {
    val running: Boolean get() = stoppedAt == null
}

/**
 * Plain-words names of patterns, for alerts, rows and the analyst's data: the engine's for the built-in ones, and each lab pattern's own
 * title, which only the record knows. [LabStore] adds to [labTitles] whenever it reads or writes.
 */
object PatternLabels {
    @Volatile
    var labTitles: Map<Long, String> = emptyMap()

    /**
     * Adds [titles] to what is known. Lab patterns are never changed or removed, so a title once known stays true: adding, never replacing,
     * means a reader that sees fewer patterns (one that started earlier) cannot wipe a name another has just learned.
     */
    @Synchronized
    fun remember(titles: Map<Long, String>) {
        labTitles = labTitles + titles
    }

    fun describe(variant: String): String {
        val id = LabPatterns.idOf(variant) ?: return VariantLabels.describe(variant)
        return "Lab, forward-only: " + (labTitles[id] ?: "pattern $id")
    }
}

/** The lab patterns being, or once, forward-tested, in the record database. Nothing in it is ever changed or removed. */
class LabStore(
    private val db: RecordDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val changes = MutableStateFlow(0L)

    /** Goes up by one after every pattern started or stopped. */
    val version: StateFlow<Long> = changes.asStateFlow()

    private suspend fun <T> access(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        lock.withLock { withContext(io) { block(db.writableDatabase) } }

    /** Begins the forward test of a pattern and returns its number. */
    suspend fun start(title: String, reason: String?, definition: String, reportId: Long?): Long {
        val id = access { d ->
            val cv = android.content.ContentValues().apply {
                put("started_at", clock()); put("report_id", reportId); put("title", title); put("reason", reason); put("definition", definition)
            }
            d.insertOrThrow("lab_patterns", null, cv)
        }
        changes.value = changes.value + 1
        all()
        return id
    }

    /** Ends the forward test of pattern [id]; false when it had already ended or does not exist. */
    suspend fun stop(id: Long): Boolean {
        val done = access { d ->
            val exists = d.rawQuery("SELECT 1 FROM lab_patterns WHERE id=?", arrayOf(id.toString())).use { it.moveToFirst() }
            exists && d.insertWithOnConflict("lab_stops", null, android.content.ContentValues().apply {
                put("pattern_id", id); put("stopped_at", clock())
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE) != -1L
        }
        if (done) changes.value = changes.value + 1
        return done
    }

    /** Every lab pattern, newest first. Reading also tells [PatternLabels] their titles. */
    suspend fun all(): List<LabRecord> {
        val list = access { d ->
            d.rawQuery(
                "SELECT p.id, p.started_at, p.report_id, p.title, p.reason, p.definition, s.stopped_at " +
                    "FROM lab_patterns p LEFT JOIN lab_stops s ON s.pattern_id=p.id ORDER BY p.id DESC", null,
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(LabRecord(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getString(3),
                            if (c.isNull(4)) null else c.getString(4), c.getString(5), if (c.isNull(6)) null else c.getLong(6)))
                    }
                }
            }
        }
        PatternLabels.remember(list.associate { it.id to it.title })
        return list
    }
}
