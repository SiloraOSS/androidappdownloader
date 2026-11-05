package io.github.edrock200.appdownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.edrock200.appdownloader.App
import io.github.edrock200.appdownloader.OpState
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.Repeat
import io.github.edrock200.appdownloader.data.SettingsState
import io.github.edrock200.appdownloader.data.SourceParser
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.data.VersionUtils
import io.github.edrock200.appdownloader.install.InstalledApps
import io.github.edrock200.appdownloader.install.InstalledInfo
import io.github.edrock200.appdownloader.work.AutoUpdateScheduler
import io.github.edrock200.appdownloader.work.UpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class EntryUi(
    val entry: AppEntry,
    val installed: InstalledInfo?,
    val op: OpState?,
    val hasUpdate: Boolean,
) {
    /** A scheduled run downloaded an update that still needs to be installed. */
    val waiting get() = entry.readyFile != null
}

/** Values collected from the add/edit dialog. */
data class EntryForm(
    val input: String,
    val name: String,
    val includePrereleases: Boolean,
    val assetFilter: String,
    val packageName: String,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val installedTick = MutableStateFlow(0)

    val items = combine(app.repository.entries, app.controller.ops, installedTick) { entries, ops, _ ->
        entries.map { e ->
            val installed = InstalledApps.get(app, e.packageName)
            EntryUi(e, installed, ops[e.id], VersionUtils.updateAvailable(e, installed?.versionName, installed?.versionCode))
        }.sortedWith(compareByDescending<EntryUi> { it.hasUpdate }.thenBy { it.entry.name.lowercase() })
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val checking = app.controller.checking
    val ops = app.controller.ops
    val selfUpdate = app.controller.selfUpdate
    val updatingAll = app.controller.updatingAll

    /** Whether the "updates are waiting" prompt was already shown since the app was opened. */
    var waitingPromptShown = false
    val settings = app.settings.state
    val messages = app.controller.messages

    /** Text shared into the app from another app, waiting to be added. */
    val sharedText = MutableStateFlow<String?>(null)

    fun refreshInstalled() {
        installedTick.value++
    }

    sealed interface AddResult {
        data class Added(val id: String, val type: SourceType) : AddResult
        data class Invalid(val message: String) : AddResult
    }

    /** Adds a new entry and optionally starts installing it right away. */
    fun add(form: EntryForm, installNow: Boolean): AddResult {
        val parsed = SourceParser.parse(form.input)
            ?: return AddResult.Invalid("Enter a URL, an aftv.news code or a GitHub repository")
        if (app.repository.entries.value.any { it.type == parsed.type && it.source.equals(parsed.source, true) }) {
            return AddResult.Invalid("That source is already in your list")
        }
        val entry = AppEntry(
            name = form.name.trim().ifBlank { parsed.suggestedName },
            autoName = form.name.isBlank(),
            type = parsed.type,
            source = parsed.source,
            packageName = form.packageName.trim().ifBlank { null },
            includePrereleases = form.includePrereleases,
            assetFilter = form.assetFilter.trim().ifBlank { null },
        )
        viewModelScope.launch {
            app.repository.add(entry)
            when {
                installNow -> app.controller.downloadAndInstall(entry.id)
                entry.type == SourceType.GITHUB -> app.controller.checkOne(entry.id)
            }
        }
        return AddResult.Added(entry.id, entry.type)
    }

    fun edit(original: AppEntry, form: EntryForm): String? {
        val parsed = SourceParser.parse(form.input) ?: return "Enter a URL, an aftv.news code or a GitHub repository"
        val sourceChanged = parsed.type != original.type || parsed.source != original.source
        val filterChanged = form.assetFilter.trim().ifBlank { null } != original.assetFilter ||
            form.includePrereleases != original.includePrereleases
        viewModelScope.launch {
            app.repository.update(original.id) { e ->
                var updated = e.copy(
                    name = form.name.trim().ifBlank { parsed.suggestedName },
                    autoName = form.name.isBlank(),
                    type = parsed.type,
                    source = parsed.source,
                    packageName = form.packageName.trim().ifBlank { null },
                    includePrereleases = form.includePrereleases,
                    assetFilter = form.assetFilter.trim().ifBlank { null },
                )
                if (sourceChanged || filterChanged) {
                    updated = updated.copy(
                        latestTag = null, latestReleaseName = null, latestReleaseUrl = null,
                        latestAssetName = null, latestAssetUrl = null, latestPublishedAt = null,
                        lastCheckError = null, lastCheckedAt = null,
                    )
                }
                if (sourceChanged) updated = updated.copy(installedTag = null, installedVersionCode = null, notifiedTag = null)
                updated
            }
            if (parsed.type == SourceType.GITHUB && (sourceChanged || filterChanged)) {
                app.controller.checkOne(original.id)
            }
        }
        return null
    }

    fun delete(id: String) {
        app.controller.clearOp(id)
        viewModelScope.launch {
            app.repository.get(id)?.readyFile?.let { path -> withContext(Dispatchers.IO) { File(path).delete() } }
            app.repository.delete(id)
        }
    }

    fun install(id: String) = app.controller.downloadAndInstall(id)
    fun clearOp(id: String) = app.controller.clearOp(id)
    fun checkOne(id: String) = app.controller.checkOne(id)
    fun checkAll() = app.controller.checkAllNow()
    fun message(text: String) = app.controller.message(text)
    fun checkSelfUpdateOnLaunch() = app.controller.checkSelfUpdateOnLaunch()
    fun checkSelfUpdateNow() = app.controller.checkSelfUpdateNow()
    fun installSelfUpdate() = app.controller.installSelfUpdate()
    fun updateAllNow() = app.controller.updateAllNow()
    fun installWaitingUpdates() = app.controller.installWaitingUpdates()

    fun setSchedule(at: Long, repeat: Repeat) {
        saveSettings(app.settings.current.copy(scheduleAt = at, scheduleRepeat = repeat))
        if (at > 0) message("Updates scheduled for ${formatDateTime(app, at)}")
    }

    fun setUpdateOnlyIfChanged(enabled: Boolean) {
        saveSettings(app.settings.current.copy(updateOnlyIfChanged = enabled))
    }

    fun setAutoUpdate(id: String, enabled: Boolean) {
        viewModelScope.launch { app.repository.update(id) { it.copy(autoUpdate = enabled) } }
    }
    fun dismissSelfUpdate() = app.controller.dismissSelfUpdate()

    fun saveSettings(state: SettingsState) {
        val old = app.settings.current
        app.settings.update(state)
        if (old.checkIntervalHours != state.checkIntervalHours) {
            UpdateScheduler.schedule(app, state.checkIntervalHours)
        }
        if (old.scheduleAt != state.scheduleAt || old.scheduleRepeat != state.scheduleRepeat) {
            AutoUpdateScheduler.apply(app, state)
        }
    }
}
