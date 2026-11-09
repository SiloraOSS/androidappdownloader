package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.edrock200.appdownloader.app
import io.github.edrock200.appdownloader.data.UpdatePreferences

/** Periodically checks monitored GitHub repositories and notifies about new releases. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.app()
        if (app.settings.current.checkIntervalHours <= 0) return Result.success()
        if (UpdatePreferences.shouldInstall(app.settings.current)) {
            val summary = app.controller.updateSelected(interactive = false) { UpdatePreferences.shouldInstall(app.settings.current) }
            Notifications.showWaitingUpdates(applicationContext, app.controller.waitingUpdates())
            Notifications.showUpdateFailures(applicationContext, summary.failed)
        }
        val updates = app.controller.checkAll()
        val fresh = UpdatePreferences.unannouncedUpdates(updates)
        if (fresh.isNotEmpty() && Notifications.showUpdates(applicationContext, fresh)) {
            fresh.forEach { entry ->
                app.repository.update(entry.id) {
                    it.copy(notifiedTag = it.latestTag, notifiedChangeKey = it.remoteFingerprint?.key)
                }
            }
        }
        return Result.success()
    }
}
