package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import io.github.edrock200.appdownloader.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

internal enum class DeferredInstallReason(val initialDelayMinutes: Long) {
    PLAYBACK(15),
    CONFIRMATION(2),
}

/** Retries a particular staged APK without touching the recurring release checks. */
class DeferredInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val entryId = inputData.getString(ENTRY_ID) ?: return@withContext Result.failure()
        val path = inputData.getString(APK_PATH) ?: return@withContext Result.failure()
        val reason = DeferredInstallReason.entries.firstOrNull { it.name == inputData.getString(REASON) }
            ?: return@withContext Result.failure()
        val controller = applicationContext.app().controller
        val stillDeferred = runAttemptCount < MAX_CHECKS && controller.retryStagedInstall(entryId, path, reason)
        if (stillDeferred && canRetry(runAttemptCount)) return@withContext Result.retry()
        if (reason == DeferredInstallReason.CONFIRMATION) controller.finishConfirmationRetry(entryId, path)
        Result.success()
    }

    companion object {
        private const val ENTRY_ID = "entry-id"
        private const val APK_PATH = "staged-apk-path"
        private const val REASON = "deferral"
        private const val MAX_CHECKS = 3

        // WorkManager counts the first run as attempt zero.
        internal fun canRetry(runAttemptCount: Int) = runAttemptCount + 1 < MAX_CHECKS

        internal fun workName(entryId: String, path: String, reason: DeferredInstallReason) =
            "deferred-install-$entryId-${File(path).name}-${reason.name.lowercase()}"

        internal suspend fun enqueue(context: Context, entryId: String, path: String, reason: DeferredInstallReason) {
            val request = OneTimeWorkRequestBuilder<DeferredInstallWorker>()
                .setInputData(workDataOf(ENTRY_ID to entryId, APK_PATH to path, REASON to reason.name))
                .setInitialDelay(reason.initialDelayMinutes, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, DeferredInstallReason.PLAYBACK.initialDelayMinutes, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(entryId, path, reason), ExistingWorkPolicy.KEEP, request).await()
        }
    }
}
