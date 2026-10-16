package com.ikverse.signallab.sync

import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.SettingRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** What sync remembers on this device between rounds. None of it is ever sent. */
interface SyncMemory {
    suspend fun deviceId(): String
    suspend fun ownFileId(): String?
    suspend fun saveOwnFileId(id: String)
    /** The fingerprint of the record last sent, so an unchanged record is not sent again. */
    suspend fun lastSent(): String?
    suspend fun saveLastSent(fingerprint: String)
    /** When another device's file had last changed when it was read, so an unchanged one is not read again. */
    suspend fun seen(fileId: String): String?
    suspend fun saveSeen(fileId: String, modified: String)
}

/** [SyncMemory] in the ledger's settings, under keys that are never synced themselves. Signal Lab 1's device id is kept. */
class SettingsSyncMemory(private val dao: LedgerDao, private val now: () -> Long = System::currentTimeMillis) : SyncMemory {
    private suspend fun put(key: String, value: String) = dao.putSetting(SettingRow(key, value, now()))

    override suspend fun deviceId(): String = dao.setting(DeviceSync.DEVICE_ID) ?: UUID.randomUUID().toString().also { put(DeviceSync.DEVICE_ID, it) }
    override suspend fun ownFileId(): String? = dao.setting(DeviceSync.OWN_FILE)
    override suspend fun saveOwnFileId(id: String) = put(DeviceSync.OWN_FILE, id)
    override suspend fun lastSent(): String? = dao.setting(DeviceSync.LAST_SENT)
    override suspend fun saveLastSent(fingerprint: String) = put(DeviceSync.LAST_SENT, fingerprint)
    override suspend fun seen(fileId: String): String? = dao.setting(DeviceSync.SEEN_PREFIX + fileId)
    override suspend fun saveSeen(fileId: String, modified: String) = put(DeviceSync.SEEN_PREFIX + fileId, modified)
}

/** How one round went. */
data class SyncRound(
    val merged: MergeResult,
    /** Other devices whose records are in the folder. */
    val devices: Int,
    val sent: Boolean,
    /** Records that could not be read (damaged, or written by a newer version); they are tried again next round. */
    val unreadable: Int,
)

/**
 * One round of sync: read every other device's record that changed since it was last read, merge each into this ledger,
 * then send this device's record if it changed. Each device writes only its own file, so two devices never write the same
 * file. Signal Lab 2 writes `device-<id>.v2.json.gz` in format 2 and reads every device's file, Signal Lab 1's
 * `device-<id>.json.gz` (format 1) included; Signal Lab 1 finds a format it does not know in the new files and leaves them.
 */
class SyncEngine(
    private val folder: RemoteFolder,
    private val record: LedgerSync,
    private val memory: SyncMemory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun round(): SyncRound {
        val device = memory.deviceId()
        val own = fileName(device)
        val files = folder.list()
        var merged = MergeResult()
        var unreadable = 0
        val others = files.filter { it.name != own && it.name.startsWith(PREFIX) && it.name.endsWith(".json.gz") }
        for (f in others) {
            if (memory.seen(f.id) == f.modified && f.modified.isNotEmpty()) continue
            val bytes = folder.download(f.id)
            val result = try {
                record.merge(JSONObject(withContext(io) { gunzip(bytes) }))
            } catch (e: org.json.JSONException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: java.io.IOException) {
                null
            }
            if (result == null) {
                unreadable++
                continue
            }
            merged += result
            memory.saveSeen(f.id, f.modified)
        }

        val export = record.export(device, clock())
        val fingerprint = record.fingerprint(export)
        val bytes = withContext(io) { gzip(export.toString()) }
        var fileId = memory.ownFileId()?.takeIf { id -> files.any { it.id == id } } ?: files.firstOrNull { it.name == own }?.id
        val sent = if (fileId == null || memory.lastSent() != fingerprint) {
            if (fileId == null) fileId = folder.create(own, bytes) else folder.update(fileId, bytes)
            memory.saveOwnFileId(fileId)
            memory.saveLastSent(fingerprint)
            true
        } else {
            false
        }
        // Signal Lab 1's own old file of this device is one of the others: a device that left it behind still counts once.
        val devices = others.map { it.name.removePrefix(PREFIX).substringBefore('.') }.toSet().minus(device).size
        return SyncRound(merged, devices, sent, unreadable)
    }

    companion object {
        const val PREFIX = "device-"
        const val SUFFIX = ".v2.json.gz"

        fun fileName(deviceId: String) = "$PREFIX$deviceId$SUFFIX"

        fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

        fun gunzip(bytes: ByteArray): String = GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes()) }
    }
}
