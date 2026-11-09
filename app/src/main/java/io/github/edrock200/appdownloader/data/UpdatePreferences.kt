package io.github.edrock200.appdownloader.data

/** Shared rules for the settings UI and background worker. */
object UpdatePreferences {
    fun normalize(state: SettingsState): SettingsState {
        val interval = state.checkIntervalHours.takeIf { it in Settings.INTERVAL_OPTIONS } ?: 12
        return state.copy(
            githubToken = state.githubToken.trim(),
            checkIntervalHours = interval,
            checkDaily = interval > 0 && state.checkDaily,
            checkHour = state.checkHour.takeIf { it in 0..23 } ?: 3,
            autoInstallUpdates = interval > 0 && state.autoInstallUpdates,
        )
    }

    fun shouldInstall(state: SettingsState): Boolean = state.checkIntervalHours > 0 && state.autoInstallUpdates

    fun unannouncedUpdates(entries: List<AppEntry>): List<AppEntry> = entries.filter {
        it.lastCheckError == null && it.readyFile == null && it.latestTag != null && it.notifiedTag != it.latestTag
    }
}
