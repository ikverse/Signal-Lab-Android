package com.ikverse.signallab.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The record: watchlists, settings, live paper trades and what happened to them, alerts, every
 * signal variant ever tried, and the analyst's reports. This is the file the Drive backup carries, so what is in it is what a
 * restore brings back.
 *
 * **Schema changes are steps in [MIGRATIONS], and a step is never edited once it has shipped.** A
 * fresh install runs every step in order, exactly as an upgrade does, so there is no second copy of
 * the schema to keep in step (EGX Analyzer kept two, and forgot one on version 20). A test freezes
 * version 1's SQL and opens it with the current code.
 *
 * Trades, exits and variants are append-only, and the database itself enforces it: triggers refuse
 * any UPDATE or DELETE, so the log cannot be rewritten after the fact, even by a bug.
 */
class RecordDatabase(context: Context?, name: String? = "signal_lab.db") :
    SQLiteOpenHelper(context, name, null, SCHEMA_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        if (databaseName != null) setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (step in MIGRATIONS) step.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (v in oldVersion until newVersion) MIGRATIONS[v].forEach(db::execSQL)
    }

    /**
     * Folds the write-ahead log into the main file so that the file alone is complete and safe to
     * copy. (Android 10's SQLite has no `VACUUM INTO`, so the backup copies the file after this.)
     */
    fun checkpoint() {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    companion object {
        /** Step 1: the first shipped schema. Step n takes the database from version n-1 to n. */
        val MIGRATIONS: List<List<String>> = listOf(
            listOf(
                """CREATE TABLE watchlists (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE,
                    active INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, position INTEGER NOT NULL)""",
                """CREATE TABLE watchlist_coins (
                    list_id INTEGER NOT NULL REFERENCES watchlists(id) ON DELETE CASCADE,
                    symbol TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (list_id, symbol)) WITHOUT ROWID""",
                "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID",
                // list_id is 0 for a signal that belongs to the coin, and a list's id for one that depends on
                // the list (cross-sectional momentum ranks within a list), so the same bar is never traded twice.
                """CREATE TABLE live_trades (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, variant TEXT NOT NULL, family TEXT NOT NULL,
                    symbol TEXT NOT NULL, tf TEXT NOT NULL, list_id INTEGER NOT NULL DEFAULT 0,
                    bar_time INTEGER NOT NULL, detected_at INTEGER NOT NULL, regime INTEGER NOT NULL,
                    entry_time INTEGER NOT NULL, entry_price REAL NOT NULL, target REAL, stop REAL,
                    hold_bars INTEGER NOT NULL, exit_due INTEGER NOT NULL, opened_at INTEGER NOT NULL,
                    UNIQUE (variant, symbol, bar_time, list_id))""",
                """CREATE TABLE live_exits (
                    trade_id INTEGER PRIMARY KEY REFERENCES live_trades(id),
                    exit_time INTEGER NOT NULL, exit_price REAL NOT NULL, exit_reason TEXT NOT NULL,
                    bars_held INTEGER NOT NULL, gross REAL NOT NULL, net REAL NOT NULL,
                    random_mean REAL, excess REAL, closed_at INTEGER NOT NULL)""",
                """CREATE TABLE alerts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, kind TEXT NOT NULL,
                    symbol TEXT, tf TEXT, title TEXT NOT NULL, body TEXT NOT NULL, link TEXT)""",
                "CREATE INDEX alerts_by_time ON alerts (ts)",
                """CREATE TABLE variants (
                    variant TEXT PRIMARY KEY, family TEXT NOT NULL, tf TEXT NOT NULL, params TEXT NOT NULL,
                    first_seen_at INTEGER NOT NULL) WITHOUT ROWID""",
                "CREATE INDEX trades_by_symbol ON live_trades (symbol, detected_at)",
            ) + listOf("live_trades", "live_exits", "variants").flatMap { t ->
                listOf(
                    "CREATE TRIGGER ${t}_no_update BEFORE UPDATE ON $t BEGIN SELECT RAISE(ABORT, '$t is append-only'); END",
                    "CREATE TRIGGER ${t}_no_delete BEFORE DELETE ON $t BEGIN SELECT RAISE(ABORT, '$t is append-only'); END",
                )
            },
            // Step 2: charts chosen per list, how each trade exits, what it cost, and what it did on the way. Every column is new
            // and nullable (or has a default), so a trade from before reads as it was: no stored cost means the old costs,
            // no stored mode means the classic exit (a target) or a hold (none). Lists that existed keep 1h, 4h and 1d.
            listOf(
                "ALTER TABLE watchlists ADD COLUMN timeframes TEXT NOT NULL DEFAULT '1h,4h,1d'",
                "ALTER TABLE live_trades ADD COLUMN cost REAL",
                "ALTER TABLE live_trades ADD COLUMN exit_mode TEXT",
                "ALTER TABLE live_trades ADD COLUMN atr REAL",
                "ALTER TABLE live_exits ADD COLUMN max_up REAL",
                "ALTER TABLE live_exits ADD COLUMN max_down REAL",
                "ALTER TABLE live_exits ADD COLUMN bars_to_peak INTEGER",
            ),
            // Step 3: the analyst's reports, each an answer the user shared back from the Claude app. [card] is the question it
            // answered when the answer says so, and null for any other text the user chose to keep.
            listOf(
                """CREATE TABLE analyst_reports (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, received_at INTEGER NOT NULL, card TEXT,
                    title TEXT NOT NULL, body TEXT NOT NULL)""",
                "CREATE INDEX reports_by_time ON analyst_reports (received_at)",
            ),
            // Step 4: the pattern lab. A row in lab_patterns is a pattern whose forward test began; a row in lab_stops ends it. Both are
            // append-only like the trades: a lab pattern counts among the patterns tested for good, so its record can never be rewritten.
            listOf(
                """CREATE TABLE lab_patterns (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, report_id INTEGER,
                    title TEXT NOT NULL, reason TEXT, definition TEXT NOT NULL)""",
                """CREATE TABLE lab_stops (
                    pattern_id INTEGER PRIMARY KEY REFERENCES lab_patterns(id), stopped_at INTEGER NOT NULL)""",
            ) + listOf("lab_patterns", "lab_stops").flatMap { t ->
                listOf(
                    "CREATE TRIGGER ${t}_no_update BEFORE UPDATE ON $t BEGIN SELECT RAISE(ABORT, '$t is append-only'); END",
                    "CREATE TRIGGER ${t}_no_delete BEFORE DELETE ON $t BEGIN SELECT RAISE(ABORT, '$t is append-only'); END",
                )
            },
            // Step 5: the numbers behind an alert about a trade (its pattern, prices and result), as JSON, so the inbox shows them as figures.
            // Nullable: alerts from before, and alerts that are not about a trade, have none and show their words as they always did.
            listOf("ALTER TABLE alerts ADD COLUMN facts TEXT"),
        )

        val SCHEMA_VERSION: Int = MIGRATIONS.size
    }
}
