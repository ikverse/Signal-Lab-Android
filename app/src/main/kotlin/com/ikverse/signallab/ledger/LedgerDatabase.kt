package com.ikverse.signallab.ledger

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Signal Lab 2's ledger: every call, how it ended, every strategy ever tried, custom strategies, reports, pins and settings,
 * and the log of what was imported from Signal Lab 1.
 *
 * The record is append-only, and the database enforces it: triggers refuse any UPDATE or DELETE on the tables below, so
 * the record cannot be rewritten after the fact, even by a bug. Room's schema files do not record triggers, so they are
 * created here and must be created again by any migration that rebuilds a table.
 */
@Database(
    entities = [
        CallRow::class, ExitRow::class, StrategyRow::class, CustomStrategyRow::class, CustomStopRow::class,
        ReportRow::class, PinRow::class, SettingRow::class, LegacyListRow::class, ImportLogRow::class, CopyRow::class,
    ],
    version = 3,
    exportSchema = true,
    // 1 → 2 (Signal Lab 2.3): calls gain plain_size and veto. Adding columns never rebuilds the table, so the triggers stay.
    // 2 → 3 (Signal Lab 2.4): the owner's copies of calls, a new table.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        const val FILE = "ledger.db"

        /** The tables nothing may change or delete from once written. */
        val APPEND_ONLY = listOf("calls", "exits", "strategies", "custom_strategies", "custom_stops", "import_log")

        fun triggers(table: String) = listOf(
            "CREATE TRIGGER IF NOT EXISTS ${table}_no_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT, '$table is append-only'); END",
            "CREATE TRIGGER IF NOT EXISTS ${table}_no_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT, '$table is append-only'); END",
        )

        private val appendOnly = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                for (t in APPEND_ONLY) triggers(t).forEach(db::execSQL)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Also on every open, so a file whose triggers went missing gets them back.
                for (t in APPEND_ONLY) triggers(t).forEach(db::execSQL)
            }
        }

        /** The ledger file; [name] null keeps it in memory (tests). */
        fun open(context: Context, name: String? = FILE): LedgerDatabase {
            val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            else Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            return builder.addCallback(appendOnly).build()
        }
    }
}
