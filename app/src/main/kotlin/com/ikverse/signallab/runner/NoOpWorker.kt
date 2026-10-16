package com.ikverse.signallab.runner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * A worker that does nothing, so the build proves WorkManager compiles here before the daily scan
 * is written on it. Nothing enqueues it. The scan worker replaces it.
 */
class NoOpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}
