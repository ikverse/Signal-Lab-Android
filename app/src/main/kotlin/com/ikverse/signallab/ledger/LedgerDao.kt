package com.ikverse.signallab.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** What can happen to the ledger. Inserts that meet an existing row are ignored (and return -1): the record is never rewritten. */
@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCall(row: CallRow): Long

    @Query("SELECT id FROM calls WHERE strategy_id = :strategyId AND symbol = :symbol AND signal_time = :signalTime AND scope = :scope")
    suspend fun callId(strategyId: String, symbol: String, signalTime: Long, scope: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExit(row: ExitRow): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStrategy(row: StrategyRow): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCustom(row: CustomStrategyRow): Long

    @Query("SELECT id FROM custom_strategies WHERE sync_id = :syncId")
    suspend fun customId(syncId: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCustomStop(row: CustomStopRow): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReport(row: ReportRow): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPin(row: PinRow): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(row: SettingRow)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSettingIfAbsent(row: SettingRow): Long

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun setting(key: String): String?

    /** Settings are the one table that forgets: a context line past its day, a choice taken back. */
    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLegacyList(row: LegacyListRow): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImportLog(row: ImportLogRow): Long

    @Query("SELECT * FROM import_log WHERE source_sha256 = :sha")
    suspend fun importLog(sha: String): ImportLogRow?

    @Query("SELECT * FROM import_log ORDER BY imported_at")
    suspend fun importLogs(): List<ImportLogRow>

    // --- reading for the runner and the screens ---------------------------------------------------------------------

    /** Signal Lab 2 calls with no exit yet, oldest first. */
    @Query("SELECT * FROM calls WHERE status = 'live' AND id NOT IN (SELECT call_id FROM exits) ORDER BY entry_time")
    suspend fun openCalls(): List<CallRow>

    @Query("SELECT * FROM calls WHERE strategy_id = :strategyId AND symbol = :symbol ORDER BY signal_time DESC LIMIT 1")
    suspend fun latestCall(strategyId: String, symbol: String): CallRow?

    @Query("SELECT * FROM exits WHERE call_id = :callId")
    suspend fun exitOf(callId: Long): ExitRow?

    @Query("SELECT * FROM calls ORDER BY signal_time")
    suspend fun allCalls(): List<CallRow>

    @Query("SELECT * FROM exits")
    suspend fun allExits(): List<ExitRow>

    @Query("SELECT COUNT(*) FROM strategies")
    suspend fun strategiesTried(): Int

    @Query("SELECT * FROM pins")
    suspend fun pins(): List<PinRow>

    @Query("SELECT COUNT(*) FROM calls WHERE source = :source")
    suspend fun countCalls(source: String): Int

    @Query("SELECT COUNT(*) FROM reports")
    suspend fun countReports(): Int

    @Query("SELECT COUNT(*) FROM custom_strategies")
    suspend fun countCustoms(): Int

    // --- what the screens watch: each emits again whenever its tables change -------------------------------------------

    @Query("SELECT * FROM calls WHERE source = 'v2' ORDER BY signal_time")
    fun watchLiveCalls(): kotlinx.coroutines.flow.Flow<List<CallRow>>

    @Query("SELECT exits.* FROM exits JOIN calls ON calls.id = exits.call_id WHERE calls.source = 'v2'")
    fun watchLiveExits(): kotlinx.coroutines.flow.Flow<List<ExitRow>>

    @Query("SELECT * FROM settings")
    fun watchSettings(): kotlinx.coroutines.flow.Flow<List<SettingRow>>

    @Query("SELECT * FROM settings")
    suspend fun watchSettingsOnce(): List<SettingRow>

    @Query("SELECT * FROM pins")
    fun watchPins(): kotlinx.coroutines.flow.Flow<List<PinRow>>

    @Query("SELECT COUNT(*) FROM strategies")
    fun watchStrategiesTried(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT COUNT(*) FROM calls WHERE source = 'v1'")
    fun watchImportedCalls(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT COUNT(*) FROM custom_strategies")
    fun watchCustoms(): kotlinx.coroutines.flow.Flow<Int>

    /** Custom strategies written in Signal Lab 2's rule language, oldest first. */
    @Query("SELECT * FROM custom_strategies WHERE definition_v2 IS NOT NULL ORDER BY created_at, id")
    fun watchCustomRules(): kotlinx.coroutines.flow.Flow<List<CustomStrategyRow>>

    @Query("SELECT * FROM custom_strategies WHERE definition_v2 IS NOT NULL ORDER BY created_at, id")
    suspend fun customRules(): List<CustomStrategyRow>

    @Query("SELECT * FROM reports ORDER BY received_at DESC LIMIT :limit")
    fun watchReports(limit: Int): kotlinx.coroutines.flow.Flow<List<ReportRow>>

    @Query("SELECT * FROM strategies WHERE id = :id")
    suspend fun strategy(id: String): StrategyRow?

    // --- for sync between devices ---------------------------------------------------------------------------------------

    @Query("SELECT * FROM strategies ORDER BY id")
    suspend fun allStrategies(): List<StrategyRow>

    @Query("SELECT * FROM custom_strategies ORDER BY created_at, id")
    suspend fun allCustoms(): List<CustomStrategyRow>

    @Query("SELECT * FROM custom_stops")
    suspend fun allCustomStops(): List<CustomStopRow>

    @Query("SELECT * FROM reports ORDER BY received_at, id")
    suspend fun allReports(): List<ReportRow>

    @Query("SELECT * FROM settings ORDER BY `key`")
    suspend fun allSettings(): List<SettingRow>

    @Query("DELETE FROM pins WHERE symbol = :symbol")
    suspend fun removePin(symbol: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPin(row: PinRow)
}
