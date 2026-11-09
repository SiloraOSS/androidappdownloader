package io.github.edrock200.appdownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.edrock200.appdownloader.App
import io.github.edrock200.appdownloader.OpState
import android.net.Uri
import io.github.edrock200.appdownloader.BuildConfig
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.Backup
import io.github.edrock200.appdownloader.data.BackupFile
import io.github.edrock200.appdownloader.net.AftvResolver
import io.github.edrock200.appdownloader.net.Http
import okhttp3.Request
import java.io.IOException
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
    val installRestore = app.controller.installRestore
    fun hasSavedApk(record: io.github.edrock200.appdownloader.data.InstallRecord) = app.controller.hasSavedApk(record)
    fun prepareInstallRestore(record: io.github.edrock200.appdownloader.data.InstallRecord) = app.controller.prepareInstallRestore(record)
    suspend fun confirmInstallRestore(expectedInstalledVersionCode: Long?) = app.controller.confirmInstallRestore(expectedInstalledVersionCode)
    fun installAfterRestoreUninstall() = app.controller.installAfterRestoreUninstall()
    fun cancelInstallRestore() = app.controller.cancelInstallRestore()
    fun refreshInstallRestore() = app.controller.refreshInstallRestore()
    fun restoreError(message: String) = app.controller.restoreError(message)

    /** Whether the "updates are waiting" prompt was already shown since the app was opened. */
    var waitingPromptShown = false
    val settings = app.settings.state
    val shizukuStatus = app.shizuku.state
    fun refreshShizuku() = app.shizuku.refresh()
    fun authorizeShizuku() { app.shizuku.requestPermission()?.let(::message) }
    val messages = app.controller.messages

    /** A backup that was read and is waiting for the user to confirm the restore. */
    val pendingRestore = MutableStateFlow<BackupFile?>(null)

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
    fun checkAllOnLaunch() = app.controller.checkAllOnLaunch()

    /** Apps marked with a star: a newer version was found, or one is downloaded and waiting. */
    fun appsWithUpdates(list: List<EntryUi> = items.value) =
        list.filter { (it.hasUpdate || it.waiting) && (it.op == null || it.op is OpState.Failed) }

    /** "Update all" at the top of the list. */
    fun updateAllAvailable() = app.controller.updateEntries(appsWithUpdates().map { it.entry.id })
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

    // ------------------------------------------------------------ backup & restore

    private fun makeBackup(includeToken: Boolean) = Backup.create(
        entries = app.repository.entries.value,
        settings = app.settings.current,
        includeToken = includeToken,
        appVersion = BuildConfig.VERSION_NAME,
        now = System.currentTimeMillis(),
    )

    /** Text for the settings QR code (never contains the GitHub token), or null if the list is too long. */
    fun qrText(): String? = Backup.toQrText(makeBackup(includeToken = false))

    fun appCount() = app.repository.entries.value.size

    fun saveBackupTo(uri: Uri, includeToken: Boolean) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { BackupStorage.write(app, uri, Backup.toJson(makeBackup(includeToken))) } }
            .onSuccess { message("Backup saved (${appCount()} apps)") }
            .onFailure { message("Couldn't save the backup: ${it.message}") }
    }

    fun saveBackupToDownloads(includeToken: Boolean) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                BackupStorage.saveToDownloads(app, BackupStorage.fileName(), Backup.toJson(makeBackup(includeToken)))
            }
        }
            .onSuccess { message("Backup saved to $it") }
            .onFailure { message("Couldn't save the backup: ${it.message}") }
    }

    suspend fun downloadsBackups(): List<StoredBackup> =
        withContext(Dispatchers.IO) { runCatching { BackupStorage.listDownloads(app) }.getOrDefault(emptyList()) }

    fun loadBackup(uri: Uri) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { BackupStorage.read(app, uri) } }
            .onSuccess { loadBackupText(it) }
            .onFailure { message("Couldn't read the file: ${it.message}") }
    }

    /** From a file's contents or a scanned QR code. */
    fun loadBackupText(text: String) {
        runCatching { Backup.parse(text) }
            .onSuccess { pendingRestore.value = it }
            .onFailure { message(it.message ?: "This isn't an App Downloader backup") }
    }

    /** Downloads a backup file from a link or aftv.news code (handy on TVs). */
    fun loadBackupFromLink(input: String) = viewModelScope.launch {
        runCatching {
            val parsed = SourceParser.parse(input) ?: throw IllegalArgumentException("Enter a link or an aftv.news code")
            val url = when (parsed.type) {
                SourceType.AFTV_CODE -> AftvResolver.resolve(parsed.source)
                SourceType.URL -> parsed.source
                SourceType.GITHUB -> throw IllegalArgumentException("Use the direct (\"raw\") link to the backup file")
            }
            withContext(Dispatchers.IO) {
                Http.execute(Request.Builder().url(url).build()).use { response ->
                    if (!response.isSuccessful) throw IOException("The link returned HTTP ${response.code}")
                    val body = response.body ?: throw IOException("The link returned nothing")
                    if (body.contentLength() > 2 * 1024 * 1024) throw IOException("That file is too large to be a backup")
                    body.string()
                }
            }
        }
            .onSuccess { loadBackupText(it) }
            .onFailure { message(it.message ?: "Couldn't download the backup") }
    }

    fun restore(backup: BackupFile, replace: Boolean, withSettings: Boolean) = viewModelScope.launch {
        val existing = app.repository.entries.value
        val result = Backup.restoreApps(existing, backup, replace)
        if (replace) {
            // Clean up downloads waiting for apps that are no longer in the list.
            val keptIds = result.entries.map { it.id }.toSet()
            existing.filter { it.id !in keptIds }.forEach { e ->
                app.controller.clearOp(e.id)
                e.readyFile?.let { path -> withContext(Dispatchers.IO) { File(path).delete() } }
            }
        }
        app.repository.replaceAll(result.entries)
        if (withSettings) saveSettings(Backup.restoreSettings(app.settings.current, backup.settings, System.currentTimeMillis()))
        pendingRestore.value = null
        message(
            "Restored ${result.added} app${if (result.added == 1) "" else "s"}" +
                (if (result.skipped > 0) " (${result.skipped} already in your list)" else "") +
                (if (withSettings) " and your settings" else "")
        )
        app.controller.checkAllNow() // Fill in release and change information for the restored apps.
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
        val saved = app.settings.current
        if (old.checkIntervalHours != saved.checkIntervalHours) {
            UpdateScheduler.schedule(app, saved.checkIntervalHours)
        }
        if (old.scheduleAt != state.scheduleAt || old.scheduleRepeat != state.scheduleRepeat) {
            AutoUpdateScheduler.apply(app, state)
        }
    }

    fun setCheckInterval(hours: Int) = saveSettings(app.settings.current.copy(checkIntervalHours = hours))
    fun setAutomaticInstallation(enabled: Boolean) = saveSettings(app.settings.current.copy(autoInstallUpdates = enabled))
    fun setInstallMethod(method: io.github.edrock200.appdownloader.data.InstallMethod) {
        saveSettings(app.settings.current.copy(installMethod = method))
        app.shizuku.refresh()
    }
    fun setTheme(mode: io.github.edrock200.appdownloader.data.ThemeMode) = saveSettings(app.settings.current.copy(themeMode = mode))
    fun setDeleteAfterInstall(enabled: Boolean) = saveSettings(app.settings.current.copy(deleteApkAfterInstall = enabled))
    val notificationPermissionAsked get() = app.settings.notificationPermissionAsked
    val notificationPromptSeen get() = app.settings.notificationPromptSeen
    fun markNotificationPromptSeen() = app.settings.markNotificationPromptSeen()
    fun markNotificationPermissionAsked() = app.settings.markNotificationPermissionAsked()
    val pendingInstallAfterPermission get() = app.settings.pendingInstallAfterPermission
    fun saveInstallPermissionRequest(installAfterPermission: Boolean?) =
        app.settings.saveInstallPermissionRequest(installAfterPermission)

    fun notificationPermissionGranted() = viewModelScope.launch {
        // Earlier versions marked notifications as sent even if permission was missing.
        app.repository.update(io.github.edrock200.appdownloader.data.Silora.ID) { it.copy(notifiedTag = null) }
    }
}
