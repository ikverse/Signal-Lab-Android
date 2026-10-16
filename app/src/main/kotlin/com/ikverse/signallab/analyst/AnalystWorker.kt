package com.ikverse.signallab.analyst

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ikverse.signallab.ledger.LedgerDao
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.ledger.ReportRow
import com.ikverse.signallab.ledger.SettingRow
import com.ikverse.signallab.runner.RunnerHost
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The daily brief or the weekly review, run in the background when the owner has asked for it in Settings. */
class AnalystWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? RunnerHost ?: return Result.failure()
        val kind = runCatching { RunKind.valueOf(inputData.getString(KIND) ?: "") }.getOrNull() ?: return Result.failure()
        val graph = host.graph
        return try {
            val analyst = graph.analyst
            if (analyst.blocked() != null) return Result.success()
            val result = analyst.run(kind, if (kind == RunKind.BRIEF) AnalystPrompt.BRIEF else AnalystPrompt.REVIEW)
            if (result.log.ending.startsWith("failed")) {
                return if (runAttemptCount < 2) Result.retry() else Result.success()
            }
            val title = if (kind == RunKind.BRIEF) "Daily brief" else "Weekly review"
            graph.ledger.dao().insertReport(ReportRow(syncId = UUID.randomUUID().toString(), receivedAt = System.currentTimeMillis(),
                card = kind.name.lowercase(), title = title, body = result.answer))
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KIND = "kind"
        const val BRIEF_DAY = "analyst_brief_day"
        const val REVIEW_WEEK = "analyst_review_week"
        private const val DAY = 86_400_000L

        /**
         * After a pass: queues the day's brief once per daily close and the review once per week (on Sunday, UTC), each only
         * when the owner has switched it on. Both need a network and wait for one.
         */
        suspend fun queueDue(context: Context, dao: LedgerDao, prefs: Preferences, now: Long) {
            val today = Math.floorDiv(now, DAY)
            if (prefs.analystBrief && dao.setting(BRIEF_DAY)?.toLongOrNull() != today) {
                dao.putSetting(SettingRow(BRIEF_DAY, today.toString(), now))
                enqueue(context, RunKind.BRIEF)
            }
            val sunday = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).dayOfWeek == DayOfWeek.SUNDAY
            val week = Math.floorDiv(today + 3, 7)
            if (prefs.analystReview && sunday && dao.setting(REVIEW_WEEK)?.toLongOrNull() != week) {
                dao.putSetting(SettingRow(REVIEW_WEEK, week.toString(), now))
                enqueue(context, RunKind.REVIEW)
            }
        }

        private fun enqueue(context: Context, kind: RunKind) {
            val request = OneTimeWorkRequestBuilder<AnalystWorker>()
                .setInputData(workDataOf(KIND to kind.name))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("analyst_${kind.name.lowercase()}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
