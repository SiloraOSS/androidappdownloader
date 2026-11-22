package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.await
import io.github.edrock200.appdownloader.app
import io.github.edrock200.appdownloader.data.UpdatePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Checks both public releases once, then uses the same results for downloads and notifications. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val app = applicationContext.app()
        val state = app.settings.current
        if (state.checkIntervalHours <= 0) return@withContext Result.success()
        // Book the next local-time run before installing, because updating ourselves kills this process.
        if (state.hasDailyCheck) UpdateScheduler.schedule(applicationContext, state).await()

        app.controller.reconcileInstallAttempts().join()
        val checks = app.controller.checkForWorker()
        val summary = if (checks != null && UpdatePreferences.shouldInstall(app.settings.current)) {
            app.controller.updateSelected(interactive = false, checkedEntries = checks.entries) {
                UpdatePreferences.shouldInstall(app.settings.current)
            }.also {
                Notifications.showWaitingUpdates(applicationContext, app.controller.waitingUpdates())
                Notifications.showUpdateFailures(applicationContext, it.failed)
            }
        } else null

        val fresh = UpdatePreferences.unannouncedUpdates(app.controller.entriesWithUpdates())
            .filter { checks?.entries?.containsKey(it.id) == true }
        if (checks != null && fresh.isNotEmpty() && Notifications.showUpdates(applicationContext, fresh)) {
            app.controller.markUpdatesNotified(fresh)
        }

        val shouldRetry = checks == null || checks.retryableErrors.isNotEmpty() || summary?.retryDownload == true
        val current = app.settings.current
        if (shouldRetry && runAttemptCount < MAX_RETRIES && current.checkIntervalHours > 0) {
            if (current.hasDailyCheck || checks?.retryAt != null) {
                // A daily override takes precedence over WorkManager backoff. Replace it for this
                // retry, then book the configured local hour again at the start of the next attempt.
                val retryAt = maxOf(checks?.retryAt ?: 0, System.currentTimeMillis() + RETRY_DELAY_MS)
                UpdateScheduler.schedule(applicationContext, current, retryAt = retryAt).await()
            }
            return@withContext Result.retry()
        }
        Result.success()
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 15 * 60_000L
    }
}
