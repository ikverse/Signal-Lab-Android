package com.ikverse.signallab.ledger

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One call: a strategy's signal on a coin, turned into a paper trade, with everything its exit will later need. Append-only:
 * how it ended is a separate [ExitRow]. Calls imported from Signal Lab 1 carry [source] "v1" and are archived history.
 */
@Entity(
    tableName = "calls",
    indices = [Index(value = ["strategy_id", "symbol", "signal_time", "scope"], unique = true), Index(value = ["symbol"])],
)
data class CallRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "strategy_id") val strategyId: String,
    val symbol: String,
    /** The chart label, such as "1d". */
    val tf: String,
    /** Open time of the candle the signal fired on. */
    @ColumnInfo(name = "signal_time") val signalTime: Long,
    @ColumnInfo(name = "entry_time") val entryTime: Long,
    @ColumnInfo(name = "entry_price") val entryPrice: Double,
    val atr: Double?,
    /** The safety stop price, if the plan has one. */
    val stop: Double?,
    /** Signal Lab 1's target; Signal Lab 2 has none. */
    val target: Double?,
    @ColumnInfo(name = "max_bars") val maxBars: Int,
    /** 1 Bitcoin above its 200-day average at the signal, 0 below, -1 not known. */
    val regime: Int,
    /** Share of the paper account the call holds; 0 when the account had no room, null for Signal Lab 1. */
    @ColumnInfo(name = "size_fraction") val sizeFraction: Double?,
    val cost: Double,
    /** The cost was not recorded with the trade and is the research's default. */
    @ColumnInfo(name = "cost_assumed") val costAssumed: Boolean = false,
    /** Signal Lab 1's exit mode ("classic", "trail", "learned", "held"); null for Signal Lab 2. */
    @ColumnInfo(name = "exit_mode") val exitMode: String? = null,
    /** What the signal belonged to beyond the coin (a Signal Lab 1 list); empty when nothing. */
    val scope: String = "",
    val source: String,
    val archived: Boolean,
    /** "live" for a Signal Lab 2 call, "v1" for an imported trade. Whether it has ended is whether it has an exit. */
    val status: String,
    @ColumnInfo(name = "legacy_id") val legacyId: Long? = null,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
    /**
     * The share the call would have held with plain ATR sizing, before Signal Lab 3's sizing engine; null for calls made
     * before 2.3. Kept beside [sizeFraction] so the ledger can say whether the engine helps.
     */
    @ColumnInfo(name = "plain_size") val plainSize: Double? = null,
    /** Why the call was skipped (recorded with no size), such as "leverage is overheated"; null when it was not. */
    val veto: String? = null,
)

/** How a call ended. One per call, written once. */
@Entity(
    tableName = "exits",
    foreignKeys = [ForeignKey(entity = CallRow::class, parentColumns = ["id"], childColumns = ["call_id"])],
)
data class ExitRow(
    @PrimaryKey @ColumnInfo(name = "call_id") val callId: Long,
    @ColumnInfo(name = "exit_time") val exitTime: Long,
    @ColumnInfo(name = "exit_price") val exitPrice: Double,
    /** "stop", "signal", "time", or Signal Lab 1's "target". */
    val reason: String,
    @ColumnInfo(name = "bars_held") val barsHeld: Int,
    val gross: Double,
    val net: Double,
    @ColumnInfo(name = "random_mean") val randomMean: Double?,
    val excess: Double?,
    @ColumnInfo(name = "max_up") val maxUp: Double?,
    @ColumnInfo(name = "max_down") val maxDown: Double?,
    @ColumnInfo(name = "bars_to_peak") val barsToPeak: Int?,
    @ColumnInfo(name = "closed_at") val closedAt: Long,
)

/**
 * Every strategy ever run or tested, once each. Their count is the multiple-testing denominator, so a strategy is never
 * removed from here, retired or not.
 */
@Entity(tableName = "strategies")
data class StrategyRow(
    @PrimaryKey val id: String,
    val family: String,
    val tf: String,
    /** Its settings, as JSON. */
    val params: String,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    /** Signal Lab 1's patterns, which no longer run. */
    val retired: Boolean,
    val source: String,
)

/** A strategy a person or the analyst defined. Append-only; stopping it is a [CustomStopRow]. */
@Entity(tableName = "custom_strategies", indices = [Index(value = ["sync_id"], unique = true)])
data class CustomStrategyRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sync_id") val syncId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val title: String,
    val reason: String?,
    /** A Signal Lab 1 lab pattern, verbatim. */
    @ColumnInfo(name = "definition_v1") val definitionV1: String?,
    @ColumnInfo(name = "definition_v2") val definitionV2: String?,
    val archived: Boolean,
    @ColumnInfo(name = "legacy_id") val legacyId: Long? = null,
    @ColumnInfo(name = "report_sync_id") val reportSyncId: String? = null,
)

@Entity(tableName = "custom_stops")
data class CustomStopRow(
    @PrimaryKey @ColumnInfo(name = "custom_id") val customId: Long,
    @ColumnInfo(name = "stopped_at") val stoppedAt: Long,
)

/** An analyst report. */
@Entity(tableName = "reports", indices = [Index(value = ["sync_id"], unique = true)])
data class ReportRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sync_id") val syncId: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    val card: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "legacy_id") val legacyId: Long? = null,
)

/** A coin the person always wants watched ("pin") or never ("exclude"). */
@Entity(tableName = "pins")
data class PinRow(
    @PrimaryKey val symbol: String,
    val kind: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** Where it came from, such as the name of a Signal Lab 1 list. */
    val tag: String? = null,
)

@Entity(tableName = "settings")
data class SettingRow(
    @PrimaryKey val key: String,
    val value: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** A Signal Lab 1 list, kept only so an imported trade can still name the list it came from. */
@Entity(tableName = "legacy_lists")
data class LegacyListRow(
    @PrimaryKey @ColumnInfo(name = "sync_id") val syncId: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    val name: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

/** One run of the importer: which file it read (by its SHA-256) and what it brought over. */
@Entity(tableName = "import_log", indices = [Index(value = ["source_sha256"], unique = true)])
data class ImportLogRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_sha256") val sourceSha256: String,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    /** Where the copy that was read is kept. */
    @ColumnInfo(name = "copy_path") val copyPath: String,
    val calls: Int,
    val exits: Int,
    val strategies: Int,
    val customs: Int,
    val reports: Int,
    val pins: Int,
    val settings: Int,
    @ColumnInfo(name = "alerts_dropped") val alertsDropped: Int,
)
