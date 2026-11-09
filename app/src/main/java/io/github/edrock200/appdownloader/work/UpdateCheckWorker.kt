package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.await
import io.github.edrock200.appdownloader.app
import io.github.edrock200.appdownloader.data.UpdatePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Periodically checks monitored GitHub repositories and notifies about new releases. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.app()
        if (app.settings.current.checkIntervalHours <= 0) return Result.success()
        try {
            return checkUpdates()
        } finally {
            // UPDATE keeps the running worker alive. Wait for the override to be stored before
            // finishing, or WorkManager could calculate the next run using a fixed 24-hour interval.
            withContext(Dispatchers.Main.immediate) {
                ensureActive()
                val current = app.settings.current
                if (current.hasDailyCheck) UpdateScheduler.schedule(applicationContext, current).await()
            }
        }
    }

    private suspend fun checkUpdates(): Result {
        val app = applicationContext.app()
        if (UpdatePreferences.shouldInstall(app.settings.current)) {
            val summary = app.controller.updateSelected(interactive = false) { UpdatePreferences.shouldInstall(app.settings.current) }
            Notifications.showWaitingUpdates(applicationContext, app.controller.waitingUpdates())
            Notifications.showUpdateFailures(applicationContext, summary.failed)
        }
        val updates = app.controller.checkAll()
        val fresh = UpdatePreferences.unannouncedUpdates(updates)
        if (fresh.isNotEmpty() && Notifications.showUpdates(applicationContext, fresh)) {
            app.controller.markUpdatesNotified(fresh)
        }
        android.util.Log.i("SiloraUpdater", "Background update check completed")
        return Result.success()
    }
}
