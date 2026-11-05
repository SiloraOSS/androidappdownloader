package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.edrock200.appdownloader.data.SettingsState
import java.util.concurrent.TimeUnit

/** Schedules the user's "update all apps" run for the date and time set in Settings. */
object AutoUpdateScheduler {
    private const val WORK_NAME = "scheduled-update-all"

    /**
     * Enqueues (or cancels) the next run. [fromWorker] is true when called by the run that is
     * just finishing, so the next one is queued behind it instead of cancelling it.
     */
    fun apply(context: Context, state: SettingsState, fromWorker: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!state.hasSchedule) {
            if (!fromWorker) wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = (state.scheduleAt - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        val policy = if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        wm.enqueueUniqueWork(WORK_NAME, policy, request)
    }
}
