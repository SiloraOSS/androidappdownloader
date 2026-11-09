package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.SettingsState
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.data.UpdatePreferences
import org.junit.Assert.*
import org.junit.Test

class UpdatePreferencesTest {
    @Test fun checksAreEnabledByDefaultButInstallationIsOptIn() {
        val defaults = SettingsState()
        assertEquals(12, defaults.checkIntervalHours)
        assertFalse(UpdatePreferences.shouldInstall(defaults))
    }

    @Test fun automaticInstallationFollowsEveryAvailableInterval() {
        for (interval in Settings.INTERVAL_OPTIONS.filter { it > 0 }) {
            val saved = UpdatePreferences.normalize(SettingsState(checkIntervalHours = interval, autoInstallUpdates = true))
            assertEquals(interval, saved.checkIntervalHours)
            assertTrue(UpdatePreferences.shouldInstall(saved))
        }
    }

    @Test fun switchingToManualModeAlsoTurnsOffAutomaticInstallation() {
        val saved = UpdatePreferences.normalize(SettingsState(checkIntervalHours = 0, autoInstallUpdates = true))
        assertFalse(saved.autoInstallUpdates)
        assertFalse(UpdatePreferences.shouldInstall(saved))
        assertFalse(UpdatePreferences.shouldInstall(saved.copy(checkIntervalHours = 12)))
    }

    @Test fun invalidIntervalsRecoverToTheDefault() {
        assertEquals(12, UpdatePreferences.normalize(SettingsState(checkIntervalHours = -1)).checkIntervalHours)
        assertEquals(12, UpdatePreferences.normalize(SettingsState(checkIntervalHours = 1)).checkIntervalHours)
    }

    @Test fun customChecksAllowAutomaticInstallationAndValidateTheHour() {
        for (hour in 0..23) {
            val saved = UpdatePreferences.normalize(SettingsState(checkIntervalHours = 24,
                checkDaily = true, checkHour = hour, autoInstallUpdates = true))
            assertTrue(saved.hasDailyCheck)
            assertEquals(hour, saved.checkHour)
            assertTrue(UpdatePreferences.shouldInstall(saved))
        }
        for (hour in listOf(-1, 24)) {
            assertEquals(3, UpdatePreferences.normalize(SettingsState(checkHour = hour)).checkHour)
        }
        val manual = UpdatePreferences.normalize(SettingsState(checkIntervalHours = 0,
            checkDaily = true, checkHour = 21, autoInstallUpdates = true))
        assertFalse(manual.checkDaily)
        assertFalse(manual.autoInstallUpdates)
        assertEquals(21, manual.checkHour)
    }

    private fun update() = Silora.entries(emptyList(), false).single().copy(
        latestTag = "new-build", latestAssetUrl = "https://example.com/silora-phone.apk",
    )

    @Test fun failedNotificationDeliveryRemainsEligibleForNextCheck() {
        val pending = update()
        assertEquals(listOf(pending), UpdatePreferences.unannouncedUpdates(listOf(pending)))
        // A delivery failure leaves the saved marker untouched, so the next run retries.
        assertEquals(listOf(pending), UpdatePreferences.unannouncedUpdates(listOf(pending)))
        assertTrue(UpdatePreferences.unannouncedUpdates(listOf(pending.copy(notifiedTag = pending.latestTag))).isEmpty())
    }

    @Test fun errorsAndReadyDownloadsDoNotGetMisleadingAvailableUpdateAlerts() {
        assertTrue(UpdatePreferences.unannouncedUpdates(listOf(update().copy(lastCheckError = "Offline"))).isEmpty())
        assertTrue(UpdatePreferences.unannouncedUpdates(listOf(update().copy(readyFile = "/downloaded.apk"))).isEmpty())
    }

    @Test fun aLaterReleaseGetsAnotherAlert() {
        val newer = update().copy(notifiedTag = "old-build")
        assertEquals(listOf(newer), UpdatePreferences.unannouncedUpdates(listOf(newer)))
    }
}
