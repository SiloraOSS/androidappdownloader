package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.edrock200.appdownloader.app
import io.github.edrock200.appdownloader.data.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs at the user's scheduled time and updates every app that has auto-update switched on. */
class AutoUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val app = applicationContext.app()
        val settings = app.settings.current
        if (!settings.hasSchedule) return@withContext Result.success() // Turned off after this run was queued.

        val summary = app.controller.updateSelected(interactive = false)

        val next = Schedule.nextRun(settings.scheduleAt, settings.scheduleRepeat, System.currentTimeMillis())
        // Re-read in case the user changed the schedule while the run was in progress.
        val latest = app.settings.current
        if (latest.scheduleAt == settings.scheduleAt) {
            val updated = latest.copy(scheduleAt = next ?: 0)
            app.settings.update(updated)
            AutoUpdateScheduler.apply(applicationContext, updated, fromWorker = true)
        }

        Notifications.showWaitingUpdates(applicationContext, app.controller.waitingUpdates())
        Notifications.showUpdateFailures(applicationContext, summary.failed)
        Result.success()
    }
}
