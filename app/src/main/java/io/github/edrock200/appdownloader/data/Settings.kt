package io.github.edrock200.appdownloader.data

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val githubToken: String = "",
    /** 0 disables background update checks. */
    val checkIntervalHours: Int = 12,
    /** Custom checks run once a day at checkHour in the device's local time. */
    val checkDaily: Boolean = false,
    val checkHour: Int = 3,
    /** Download and attempt to install newer Silora builds at each background check. */
    val autoInstallUpdates: Boolean = false,
    /** Device-specific: Shizuku authorization is never restored from a backup. */
    val installMethod: InstallMethod = InstallMethod.STANDARD,
    val deleteApkAfterInstall: Boolean = true,
    /** Check this app's own GitHub releases when it is opened. */
    val checkSelfUpdates: Boolean = true,
    /** Next scheduled "update all" run (epoch millis), or 0 when none is scheduled. */
    val scheduleAt: Long = 0,
    val scheduleRepeat: Repeat = Repeat.ONCE,
    /** Scheduled / "update all" runs skip links whose file the server says hasn't changed. */
    val updateOnlyIfChanged: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    val hasDailyCheck get() = checkIntervalHours > 0 && checkDaily
    val hasSchedule get() = scheduleAt > 0
}

@Serializable
enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** How a scheduled update repeats. [hours] > 0 means "every N hours throughout the day". */
enum class Repeat(val label: String, val hours: Int = 0) {
    ONCE("Once"),
    DAILY("Every day"),
    WEEKLY("Every week"),
    EVERY_1H("Every hour", 1),
    EVERY_2H("Every 2 hours", 2),
    EVERY_4H("Every 4 hours", 4),
    EVERY_8H("Every 8 hours", 8),
    EVERY_12H("Every 12 hours", 12),
    ;

    val isHourly get() = hours > 0
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UpdatePreferences.normalize(read()))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val current: SettingsState get() = _state.value

    fun update(newState: SettingsState) {
        val normalized = UpdatePreferences.normalize(newState)
        write(normalized)
        _state.value = normalized
    }

    val notificationPermissionAsked: Boolean get() = prefs.getBoolean("notification_permission_asked", false)
    val notificationPromptSeen: Boolean get() = prefs.getBoolean("notification_prompt_seen", false)

    fun markNotificationPromptSeen() {
        prefs.edit().putBoolean("notification_prompt_seen", true).apply()
    }

    fun markNotificationPermissionAsked() {
        prefs.edit().putBoolean("notification_permission_asked", true).apply()
    }

    /** Null means no request; false means setup only; true requires an explicit Continue to install. */
    val pendingInstallAfterPermission: Boolean?
        get() = if (prefs.contains(KEY_INSTALL_PERMISSION_REQUEST)) {
            prefs.getBoolean(KEY_INSTALL_PERMISSION_REQUEST, false)
        } else null

    @SuppressLint("ApplySharedPref")
    fun saveInstallPermissionRequest(installAfterPermission: Boolean?) {
        val editor = prefs.edit()
        if (installAfterPermission == null) editor.remove(KEY_INSTALL_PERMISSION_REQUEST)
        else editor.putBoolean(KEY_INSTALL_PERMISSION_REQUEST, installAfterPermission)
        // Android can kill us as soon as install access changes, even without onSaveInstanceState.
        // Finish this small write before handing off to Settings (or starting the confirmed install).
        editor.commit()
    }

    private fun write(newState: SettingsState) {
        prefs.edit()
            .putString(KEY_TOKEN, newState.githubToken.trim())
            .putInt(KEY_INTERVAL, newState.checkIntervalHours)
            .putBoolean(KEY_CHECK_DAILY, newState.checkDaily)
            .putInt(KEY_CHECK_HOUR, newState.checkHour)
            .putBoolean(KEY_AUTO_INSTALL, newState.autoInstallUpdates)
            .putString(KEY_INSTALL_METHOD, newState.installMethod.name)
            .putBoolean(KEY_DELETE, newState.deleteApkAfterInstall)
            .putBoolean(KEY_SELF_UPDATE, newState.checkSelfUpdates)
            .putLong(KEY_SCHEDULE_AT, newState.scheduleAt)
            .putString(KEY_SCHEDULE_REPEAT, newState.scheduleRepeat.name)
            .putBoolean(KEY_ONLY_IF_CHANGED, newState.updateOnlyIfChanged)
            .putString(KEY_THEME_MODE, newState.themeMode.name)
            .apply()
    }

    private fun read() = SettingsState(
        githubToken = prefs.getString(KEY_TOKEN, "") ?: "",
        checkIntervalHours = prefs.getInt(KEY_INTERVAL, 12),
        checkDaily = prefs.getBoolean(KEY_CHECK_DAILY, false),
        checkHour = prefs.getInt(KEY_CHECK_HOUR, 3),
        autoInstallUpdates = prefs.getBoolean(KEY_AUTO_INSTALL, false),
        installMethod = runCatching { InstallMethod.valueOf(prefs.getString(KEY_INSTALL_METHOD, null)!!) }
            .getOrDefault(InstallMethod.STANDARD),
        deleteApkAfterInstall = prefs.getBoolean(KEY_DELETE, true),
        checkSelfUpdates = prefs.getBoolean(KEY_SELF_UPDATE, true),
        scheduleAt = prefs.getLong(KEY_SCHEDULE_AT, 0),
        scheduleRepeat = runCatching { Repeat.valueOf(prefs.getString(KEY_SCHEDULE_REPEAT, null)!!) }
            .getOrDefault(Repeat.ONCE),
        updateOnlyIfChanged = prefs.getBoolean(KEY_ONLY_IF_CHANGED, true),
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, null)!!) }
            .getOrDefault(ThemeMode.SYSTEM),
    )

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_TOKEN = "github_token"
        private const val KEY_CHECK_DAILY = "check_daily"
        private const val KEY_CHECK_HOUR = "check_hour"
        private const val KEY_INTERVAL = "check_interval_hours"
        private const val KEY_AUTO_INSTALL = "auto_install_updates"
        private const val KEY_INSTALL_METHOD = "install_method"
        private const val KEY_INSTALL_PERMISSION_REQUEST = "install_permission_request"
        private const val KEY_DELETE = "delete_after_install"
        private const val KEY_SELF_UPDATE = "check_self_updates"
        private const val KEY_SCHEDULE_AT = "schedule_at"
        private const val KEY_SCHEDULE_REPEAT = "schedule_repeat"
        private const val KEY_ONLY_IF_CHANGED = "update_only_if_changed"
        val INTERVAL_OPTIONS = listOf(0, 3, 6, 12, 24)
    }
}
