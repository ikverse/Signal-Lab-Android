package com.ikverse.signallab.ledger

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import kotlinx.serialization.Serializable

/**
 * Signal Lab 2's ledger database, empty for now. It exists so the build proves that Room's code
 * generator (KSP) and the serialization plugin both work under this toolchain before anything
 * depends on them. Nothing opens it yet, so no file is created on a phone. The real tables (calls,
 * exits, the strategy registry, the importer's log) replace [ToolchainProbe] when the ledger is built.
 */
@Database(entities = [ToolchainProbe::class], version = 1, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase()

/** A placeholder row type: Room needs at least one table to generate a database. */
@Serializable
@Entity(tableName = "toolchain_probe")
data class ToolchainProbe(@PrimaryKey val id: Long)
