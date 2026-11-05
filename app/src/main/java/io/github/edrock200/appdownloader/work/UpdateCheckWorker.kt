package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.edrock200.appdownloader.app
import io.github.edrock200.appdownloader.data.SourceType

/** Periodically checks monitored GitHub repositories and notifies about new releases. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.app()
        val updates = app.controller.checkAll()
        val fresh = updates.filter { e ->
            if (e.type == SourceType.GITHUB) e.notifiedTag != e.latestTag
            else e.notifiedChangeKey != e.remoteFingerprint?.key
        }
        if (fresh.isNotEmpty()) {
            Notifications.showUpdates(applicationContext, updates)
            fresh.forEach { entry ->
                app.repository.update(entry.id) {
                    it.copy(notifiedTag = it.latestTag, notifiedChangeKey = it.remoteFingerprint?.key)
                }
            }
        }
        return Result.success()
    }
}
