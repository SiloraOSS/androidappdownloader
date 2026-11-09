package io.github.edrock200.appdownloader

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.edrock200.appdownloader.data.Schedule
import io.github.edrock200.appdownloader.data.SettingsState
import io.github.edrock200.appdownloader.work.UpdateScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class UpdateSchedulerAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = WorkManager.getInstance(context)
    private fun active() = manager.getWorkInfosForUniqueWork("github-update-check")
        .get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }.single()
    private fun apply(state: SettingsState, keep: Boolean = false) {
        UpdateScheduler.schedule(context, state, keep).result.get(10, TimeUnit.SECONDS)
    }

    @Test fun customTimeSurvivesStartupAndCanBeChangedReplacedOrCancelled() {
        WorkManager.initialize(context, Configuration.Builder().build())
        val state = SettingsState(checkIntervalHours = 24, checkDaily = true, checkHour = 21)
        val expected = Schedule.nextDailyCheck(21, System.currentTimeMillis())
        apply(state)
        val first = active()
        assertEquals(expected, first.nextScheduleTimeMillis)
        apply(state.copy(checkHour = 22), keep = true)
        assertEquals(first.id, active().id)
        assertEquals(expected, active().nextScheduleTimeMillis)
        apply(state.copy(checkHour = 22))
        assertEquals(Schedule.nextDailyCheck(22, System.currentTimeMillis()), active().nextScheduleTimeMillis)

        val daily = active()
        apply(SettingsState(checkIntervalHours = 6))
        val interval = active()
        assertEquals(daily.id, interval.id)
        assertTrue(interval.nextScheduleTimeMillis < System.currentTimeMillis() + 16 * 60_000L)
        apply(SettingsState(checkIntervalHours = 0))
        assertTrue(manager.getWorkInfosForUniqueWork("github-update-check").get(10, TimeUnit.SECONDS)
            .all { it.state == WorkInfo.State.CANCELLED })
    }
}
