package com.ikverse.signallab.runner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/** The application gives the worker and receivers the app's pieces. */
interface RunnerHost {
    val graph: com.ikverse.signallab.Graph
}

/**
 * One daily pass of the strategy runner. A failure (no network, Binance busy) is retried with WorkManager's backoff a few
 * times; the next alarm is armed whatever happens.
 */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? RunnerHost ?: return Result.failure()
        return try {
            val report = run(host)
            if (report.problem != null && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            runCatching { host.graph.scheduler.arm() }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3

        /** What a pass does; tests replace it. */
        @Volatile
        var run: suspend (RunnerHost) -> RunReport = { host -> host.graph.runner.run().also { host.graph.afterPass() } }
    }
}
