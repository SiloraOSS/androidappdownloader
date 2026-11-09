package io.github.edrock200.appdownloader

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.FirstRunStep
import io.github.edrock200.appdownloader.data.SettingsState
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.data.ThemeMode
import io.github.edrock200.appdownloader.work.Notifications
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class UpdateSettingsAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun firstRunProgressSurvivesRestartWithoutBecomingAnInstallRequest() {
        val settings = Settings(context)
        assertEquals(FirstRunStep.WELCOME, settings.firstRunStep)
        settings.saveFirstRunStep(FirstRunStep.INSTALL_PERMISSION)
        val reopened = Settings(context)
        assertEquals(FirstRunStep.INSTALL_PERMISSION, reopened.firstRunStep)
        assertNull(reopened.pendingInstallAfterPermission)
        reopened.saveFirstRunStep(FirstRunStep.NOTIFICATIONS)
        assertEquals(FirstRunStep.NOTIFICATIONS, Settings(context).firstRunStep)
    }

    @Test fun completedFirstRunSurvivesSettingsChangesAndRestart() {
        val settings = Settings(context)
        settings.saveFirstRunStep(FirstRunStep.COMPLETE)
        settings.update(settings.current.copy(themeMode = ThemeMode.DARK))
        assertEquals(FirstRunStep.COMPLETE, Settings(context).firstRunStep)
    }

    @Test fun updateSettingsSurviveReopeningTheApp() {
        assertTrue(Settings(context).current.autoInstallUpdates)
        Settings(context).update(SettingsState(checkIntervalHours = 6, autoInstallUpdates = true,
            themeMode = ThemeMode.DARK, deleteApkAfterInstall = false))
        val reopened = Settings(context).current
        assertEquals(6, reopened.checkIntervalHours)
        assertTrue(reopened.autoInstallUpdates)
        assertEquals(ThemeMode.DARK, reopened.themeMode)
        assertFalse(reopened.deleteApkAfterInstall)
        Settings(context).update(reopened.copy(autoInstallUpdates = false))
        assertFalse(Settings(context).current.autoInstallUpdates)
    }

    @Test fun customDailyHourSurvivesRestartAndReturningToIntervalMode() {
        val settings = Settings(context)
        settings.update(settings.current.copy(checkIntervalHours = 24, checkDaily = true,
            checkHour = 21, autoInstallUpdates = true))
        val reopened = Settings(context).current
        assertTrue(reopened.hasDailyCheck)
        assertEquals(21, reopened.checkHour)
        assertTrue(reopened.autoInstallUpdates)
        settings.update(reopened.copy(checkDaily = false, checkIntervalHours = 6))
        assertFalse(Settings(context).current.hasDailyCheck)
        assertEquals(21, Settings(context).current.checkHour)
        settings.update(reopened.copy(checkIntervalHours = 0))
        assertFalse(Settings(context).current.hasDailyCheck)
        assertFalse(Settings(context).current.autoInstallUpdates)
    }

    @Test fun disablingChecksPersistsWithAutomaticInstallationOff() {
        val settings = Settings(context)
        settings.update(settings.current.copy(autoInstallUpdates = true))
        settings.update(settings.current.copy(checkIntervalHours = 0))
        val reopened = Settings(context).current
        assertEquals(0, reopened.checkIntervalHours)
        assertFalse(reopened.autoInstallUpdates)
    }

    @Test fun skippingTheExplanationDoesNotConsumeTheRuntimePermissionRequest() {
        val settings = Settings(context)
        settings.markNotificationPromptSeen()
        assertTrue(Settings(context).notificationPromptSeen)
        assertFalse(Settings(context).notificationPermissionAsked)
        settings.markNotificationPermissionAsked()
        assertTrue(Settings(context).notificationPermissionAsked)
    }

    @Test fun pendingInstallPermissionSurvivesRestartUntilContinuedOrCancelled() {
        val settings = Settings(context)
        assertNull(settings.pendingInstallAfterPermission)
        settings.saveInstallPermissionRequest(true)
        val reopened = Settings(context)
        assertEquals(true, reopened.pendingInstallAfterPermission)
        reopened.saveInstallPermissionRequest(null)
        assertNull(Settings(context).pendingInstallAfterPermission)
    }

    @Test fun setupOnlyPermissionRequestDoesNotBecomeAnInstallAfterRestart() {
        val settings = Settings(context)
        settings.saveInstallPermissionRequest(false)
        settings.update(settings.current.copy(themeMode = ThemeMode.DARK))
        assertEquals(false, Settings(context).pendingInstallAfterPermission)
    }

    @Test fun notificationDeliveryFailsWhileDeniedAndSucceedsAfterPermissionIsGranted() {
        Notifications.createChannel(context)
        val update = Silora.entries(emptyList(), false).single().copy(latestTag = "new-build")
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(Notifications.areEnabled(context))
        assertFalse(Notifications.showUpdates(context, listOf(update)))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(0, manager.activeNotifications.size)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(Notifications.areEnabled(context))
        assertTrue(Notifications.showUpdates(context, listOf(update)))
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun blockedNotificationChannelDoesNotCountAsDelivered() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_NONE))
        assertFalse(Notifications.areEnabled(context))
        assertFalse(Notifications.showUpdates(context, Silora.entries(emptyList(), false)))
    }
}
