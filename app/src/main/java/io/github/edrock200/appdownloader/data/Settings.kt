package io.github.edrock200.appdownloader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val githubToken: String = "",
    /** 0 disables background update checks. */
    val checkIntervalHours: Int = 12,
    val deleteApkAfterInstall: Boolean = true,
    /** Check this app's own GitHub releases when it is opened. */
    val checkSelfUpdates: Boolean = true,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val current: SettingsState get() = _state.value

    fun update(newState: SettingsState) {
        prefs.edit()
            .putString(KEY_TOKEN, newState.githubToken.trim())
            .putInt(KEY_INTERVAL, newState.checkIntervalHours)
            .putBoolean(KEY_DELETE, newState.deleteApkAfterInstall)
            .putBoolean(KEY_SELF_UPDATE, newState.checkSelfUpdates)
            .apply()
        _state.value = newState.copy(githubToken = newState.githubToken.trim())
    }

    private fun read() = SettingsState(
        githubToken = prefs.getString(KEY_TOKEN, "") ?: "",
        checkIntervalHours = prefs.getInt(KEY_INTERVAL, 12),
        deleteApkAfterInstall = prefs.getBoolean(KEY_DELETE, true),
        checkSelfUpdates = prefs.getBoolean(KEY_SELF_UPDATE, true),
    )

    companion object {
        private const val KEY_TOKEN = "github_token"
        private const val KEY_INTERVAL = "check_interval_hours"
        private const val KEY_DELETE = "delete_after_install"
        private const val KEY_SELF_UPDATE = "check_self_updates"
        val INTERVAL_OPTIONS = listOf(0, 3, 6, 12, 24)
    }
}
