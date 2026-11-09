package io.github.edrock200.appdownloader.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.edrock200.appdownloader.data.Schedule
import io.github.edrock200.appdownloader.data.SettingsState
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    private const val WORK_NAME = "github-update-check"

    fun schedule(context: Context, state: SettingsState, keepExisting: Boolean = false): androidx.work.Operation {
        val wm = WorkManager.getInstance(context)
        if (state.checkIntervalHours <= 0) {
            return wm.cancelUniqueWork(WORK_NAME)
        }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            if (state.hasDailyCheck) 24L else state.checkIntervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .apply {
                if (state.hasDailyCheck) {
                    setNextScheduleTimeOverride(Schedule.nextDailyCheck(state.checkHour, System.currentTimeMillis()))
                } else {
                    setInitialDelay(15, TimeUnit.MINUTES)
                    clearNextScheduleTimeOverride()
                }
            }
            .build()
        // Startup must preserve an overdue check (including after a reboot).
        val policy = if (keepExisting) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE
        return wm.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
    }
}
