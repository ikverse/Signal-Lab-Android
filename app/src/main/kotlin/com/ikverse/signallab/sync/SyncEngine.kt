package com.ikverse.signallab.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

/** How one round went. */
data class SyncRound(
    val merged: MergeResult,
    /** Other devices whose records are in the folder. */
    val devices: Int,
    val sent: Boolean,
    /** Records that could not be read (damaged, or written by a newer version of the app); they are tried again next round. */
    val unreadable: Int,
)

/**
 * One round of sync: read every other device's record that changed since it was last read, merge each into this one, then send this
 * device's record if it changed. Each device writes only its own file (`device-<id>.json.gz`), so two devices never write the same file
 * and nothing can be lost to a race between them. Because every record carries everything its device has merged, a device that only
 * ever meets one other still ends up with all of them.
 */
class SyncEngine(
    private val folder: RemoteFolder,
    private val record: RecordSync,
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
        val others = files.filter { it.name != own && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
        for (f in others) {
            if (memory.seen(f.id) == f.modified && f.modified.isNotEmpty()) continue
            val bytes = folder.download(f.id)
            val result = withContext(io) {
                try {
                    record.merge(JSONObject(gunzip(bytes)))
                } catch (e: org.json.JSONException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                } catch (e: java.io.IOException) {
                    null
                }
            }
            if (result == null) {
                unreadable++
                continue
            }
            merged += result
            memory.saveSeen(f.id, f.modified)
        }

        val (fingerprint, bytes) = withContext(io) {
            val export = record.export(device, clock())
            record.fingerprint(export) to gzip(export.toString())
        }
        var fileId = memory.ownFileId()?.takeIf { id -> files.any { it.id == id } } ?: files.firstOrNull { it.name == own }?.id
        val sent = if (fileId == null || memory.lastSent() != fingerprint) {
            if (fileId == null) {
                fileId = folder.create(own, bytes)
            } else {
                folder.update(fileId, bytes)
            }
            memory.saveOwnFileId(fileId)
            memory.saveLastSent(fingerprint)
            true
        } else {
            false
        }
        return SyncRound(merged, others.size, sent, unreadable)
    }

    companion object {
        const val PREFIX = "device-"
        const val SUFFIX = ".json.gz"

        fun fileName(deviceId: String) = "$PREFIX$deviceId$SUFFIX"

        fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

        fun gunzip(bytes: ByteArray): String = GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes()) }
    }
}
