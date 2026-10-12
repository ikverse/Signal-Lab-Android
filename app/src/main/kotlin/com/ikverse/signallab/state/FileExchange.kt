package com.ikverse.signallab.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** How a file the user chose turned out. */
sealed interface FileOutcome {
    data object Done : FileOutcome
    data object Cancelled : FileOutcome
    data class Failed(val message: String) : FileOutcome
}

/**
 * A file the user is asked to choose: one to save bytes the app makes into, or one to open and read. A model asks here; the activity,
 * which alone can show Android's picker and open what it returns, listens on [pending] and completes the request with the bytes moved.
 * One request at a time: a new one replaces one the user never answered.
 */
class FileExchange {
    sealed interface Request {
        /** Save, under a suggested [name]. */
        class Save(val name: String) : Request

        /** Open any file. */
        data object Open : Request
    }

    private val requests = MutableSharedFlow<Request>(extraBufferCapacity = 4)

    /** What the activity should show next. */
    val pending: SharedFlow<Request> = requests

    private class SaveJob(val produce: suspend () -> ByteArray, val finish: suspend (FileOutcome) -> Unit)
    private class OpenJob(val consume: suspend (ByteArray) -> Unit, val finish: suspend (FileOutcome) -> Unit)

    @Volatile
    private var save: SaveJob? = null

    @Volatile
    private var open: OpenJob? = null

    /** Asks for a place to save what [produce] makes; [finish] hears how it went. */
    fun requestSave(name: String, produce: suspend () -> ByteArray, finish: suspend (FileOutcome) -> Unit) {
        save = SaveJob(produce, finish)
        requests.tryEmit(Request.Save(name))
    }

    /** Asks for a file to read into [consume]; [finish] hears how it went. */
    fun requestOpen(consume: suspend (ByteArray) -> Unit, finish: suspend (FileOutcome) -> Unit) {
        open = OpenJob(consume, finish)
        requests.tryEmit(Request.Open)
    }

    /** The activity, once the picker closed: [write] puts bytes into the chosen file, or is null when the user backed out. */
    suspend fun completeSave(write: (suspend (ByteArray) -> Unit)?) {
        val job = save ?: return
        save = null
        job.finish(if (write == null) FileOutcome.Cancelled else attempt { write(job.produce()) })
    }

    /** The activity, once the picker closed: [read] gives the chosen file's bytes, or is null when the user backed out. */
    suspend fun completeOpen(read: (suspend () -> ByteArray)?) {
        val job = open ?: return
        open = null
        job.finish(if (read == null) FileOutcome.Cancelled else attempt { job.consume(read()) })
    }

    private suspend fun attempt(block: suspend () -> Unit): FileOutcome = try {
        block()
        FileOutcome.Done
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FileOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }
}
