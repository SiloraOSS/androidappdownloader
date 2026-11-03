package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.edrock200.appdownloader.app

/** Periodically checks monitored GitHub repositories and notifies about new releases. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.app()
        val updates = app.controller.checkAllGitHub()
        val fresh = updates.filter { it.notifiedTag != it.latestTag }
        if (fresh.isNotEmpty()) {
            Notifications.showUpdates(applicationContext, updates)
            fresh.forEach { entry ->
                app.repository.update(entry.id) { it.copy(notifiedTag = it.latestTag) }
            }
        }
        return Result.success()
    }
}
