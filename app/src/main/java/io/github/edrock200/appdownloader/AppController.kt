package io.github.edrock200.appdownloader

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.data.VersionUtils
import io.github.edrock200.appdownloader.install.ApkDownloader
import io.github.edrock200.appdownloader.install.ApkInfo
import io.github.edrock200.appdownloader.install.ApkInspector
import io.github.edrock200.appdownloader.install.ApkInstaller
import io.github.edrock200.appdownloader.install.InstalledApps
import io.github.edrock200.appdownloader.install.NotAnApkException
import io.github.edrock200.appdownloader.net.AftvResolver
import io.github.edrock200.appdownloader.net.GitHubClient
import io.github.edrock200.appdownloader.work.Notifications
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Progress of a download/install for one entry. */
sealed interface OpState {
    data object Resolving : OpState
    data class Downloading(val progress: Float?) : OpState
    data object Installing : OpState
    data class Failed(val message: String, val openUrl: String? = null) : OpState
}

/** Outcome of an "update all" run. */
data class UpdateRunSummary(
    val updated: List<String> = emptyList(),
    val waiting: List<String> = emptyList(),
    /** Silent installs handed to Android; their results arrive later. */
    val installing: List<String> = emptyList(),
    val upToDate: Int = 0,
    val failed: List<String> = emptyList(),
) {
    fun describe(): String = buildList {
        if (updated.isNotEmpty()) add("Updated ${updated.size}")
        if (installing.isNotEmpty()) add("installing ${installing.size}")
        if (waiting.isNotEmpty()) add("${waiting.size} waiting to install")
        if (upToDate > 0) add("$upToDate up to date")
        if (failed.isNotEmpty()) add("${failed.size} failed")
    }.joinToString(", ").ifEmpty { "No apps are selected for auto-update" }
}

/** A newer release of App Downloader itself. */
data class SelfUpdate(
    val version: String,
    val notes: String?,
    val assetUrl: String,
    val releaseUrl: String?,
)

/**
 * Application-wide coordinator for downloads, installs and GitHub update checks.
 * Lives as long as the process so work continues across configuration changes.
 */
class AppController(
    private val context: Context,
    private val repository: EntryRepository,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _ops = MutableStateFlow<Map<String, OpState>>(emptyMap())
    val ops: StateFlow<Map<String, OpState>> = _ops.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    private val jobs = mutableMapOf<String, Job>()

    companion object {
        /** Operation id used for updating this app itself. */
        const val SELF_UPDATE_ID = "self-update"
    }
    private val apkDir get() = File(context.cacheDir, "apks")

    private fun setOp(id: String, state: OpState?) = _ops.update { if (state == null) it - id else it + (id to state) }

    fun clearOp(id: String) {
        jobs.remove(id)?.cancel()
        setOp(id, null)
    }

    fun isBusy(id: String) = _ops.value[id].let { it != null && it !is OpState.Failed }

    // ---------------------------------------------------------------- install

    fun downloadAndInstall(entryId: String) {
        if (jobs[entryId]?.isActive == true) return
        jobs[entryId] = scope.launch { runInstall(entryId) }
    }

    private suspend fun runInstall(entryId: String) {
        var entry = repository.get(entryId) ?: return
        try {
            // An update downloaded by a scheduled run is waiting: install that file.
            readyApk(entry)?.let { apk ->
                install(entryId, apk, entry.readyTag)
                return
            }
            setOp(entryId, OpState.Resolving)
            val url = when (entry.type) {
                SourceType.URL -> entry.source
                SourceType.AFTV_CODE -> AftvResolver.resolve(entry.source)
                SourceType.GITHUB -> {
                    entry = checkGitHub(entry)
                    entry.lastCheckError?.let { throw IOException(it) }
                    entry.latestAssetUrl ?: throw IOException("No APK found in the latest release")
                }
            }

            val info = downloadAndInspect(entryId, url)

            val previousPackage = entry.packageName
            if (previousPackage != null && previousPackage != info.packageName) {
                _messages.tryEmit("Note: ${entry.name} now provides ${info.packageName} (was $previousPackage)")
            }
            repository.update(entryId) { e ->
                val useLabel = e.autoName && info.label != null
                e.copy(
                    packageName = info.packageName,
                    name = if (useLabel) info.label!! else e.name,
                    autoName = e.autoName && !useLabel,
                )
            }

            val tag = if (entry.type == SourceType.GITHUB) entry.latestTag else null
            install(entryId, info, tag)
        } catch (e: CancellationException) {
            setOp(entryId, null)
            throw e
        } catch (e: NotAnApkException) {
            setOp(entryId, OpState.Failed(e.message ?: "Not an APK", e.pageUrl))
        } catch (e: Exception) {
            setOp(entryId, OpState.Failed(e.message ?: e.javaClass.simpleName))
        } finally {
            jobs.remove(entryId)
        }
    }

    /** Downloads [url] for the operation [opId] and reads the package details. */
    private suspend fun downloadAndInspect(opId: String, url: String): DownloadedApk {
        setOp(opId, OpState.Downloading(null))
        val dir = apkDir
        withContext(Dispatchers.IO) {
            dir.listFiles { f -> f.name.startsWith(opId) }?.forEach { it.delete() }
        }
        val result = ApkDownloader.download(url, dir, opId) { p -> setOp(opId, OpState.Downloading(p)) }
        val info = withContext(Dispatchers.IO) { ApkInspector.inspect(context, result.file) }
        return DownloadedApk(result.file, info)
    }

    private suspend fun install(opId: String, apk: DownloadedApk, releaseTag: String?, background: Boolean = false) {
        setOp(opId, OpState.Installing)
        withContext(Dispatchers.IO) {
            ApkInstaller.install(context, apk.file, apk.info, opId, releaseTag, background)
        }
    }

    /**
     * The waiting downloaded update for [entry], if its file is still there and is newer than
     * what is installed now. A stale one is deleted.
     */
    private suspend fun readyApk(entry: AppEntry): DownloadedApk? {
        val path = entry.readyFile ?: return null
        val installedCode = InstalledApps.get(context, entry.packageName)?.versionCode ?: -1
        val apk = withContext(Dispatchers.IO) {
            val file = File(path)
            val apk = if (file.exists()) {
                runCatching { DownloadedApk(file, ApkInspector.inspect(context, file)) }.getOrNull()
            } else {
                null
            }
            if (apk != null && apk.info.versionCode > installedCode) apk else null.also { file.delete() }
        }
        if (apk == null) repository.update(entry.id) { it.clearReady() }
        return apk
    }

    private suspend fun discardReady(entry: AppEntry) {
        entry.readyFile?.let { path -> withContext(Dispatchers.IO) { File(path).delete() } }
        repository.update(entry.id) { it.clearReady() }
    }

    private fun AppEntry.clearReady() =
        copy(readyFile = null, readyVersionName = null, readyVersionCode = null, readyTag = null)

    private class DownloadedApk(val file: File, val info: ApkInfo) {
        val packageName get() = info.packageName
        val label get() = info.label
    }

    fun onInstallSucceeded(
        entryId: String,
        releaseTag: String?,
        versionCode: Long,
        filePath: String?,
        background: Boolean = false,
    ) {
        setOp(entryId, null)
        scope.launch {
            repository.update(entryId) { e ->
                e.clearReady().copy(
                    installedTag = releaseTag ?: e.installedTag,
                    installedVersionCode = versionCode.takeIf { it >= 0 } ?: e.installedVersionCode,
                    notifiedTag = releaseTag ?: e.notifiedTag,
                )
            }
            if (settings.current.deleteApkAfterInstall && filePath != null) {
                withContext(Dispatchers.IO) { File(filePath).delete() }
            }
            val entry = repository.get(entryId)
            _messages.tryEmit("Installed ${entry?.name ?: "app"}")
            if (background && entry != null) {
                Notifications.showAutoUpdated(context, entry, InstalledApps.get(context, entry.packageName)?.versionName)
            }
            Notifications.showWaitingUpdates(context, waitingUpdates())
        }
    }

    /** A scheduled install needs the user to confirm it; keep the download for later. */
    fun onBackgroundInstallNeedsUser(entryId: String) {
        setOp(entryId, null)
        Notifications.showWaitingUpdates(context, waitingUpdates())
    }

    /** Entries with a downloaded update waiting to be installed. */
    fun waitingUpdates(): List<AppEntry> = repository.entries.value.filter { it.readyFile != null && !isBusy(it.id) }

    fun onInstallFailed(entryId: String, status: Int, message: String?) {
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            setOp(entryId, null)
            _messages.tryEmit("Installation cancelled")
            return
        }
        // Don't keep offering a download that Android refused to install.
        scope.launch { repository.update(entryId) { it.clearReady() } }
        val reason = when (status) {
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "Conflicts with the installed app (it may be signed with a different key — uninstall it first)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This app is not compatible with your device"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation was blocked"
            PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is invalid"
            else -> "Installation failed"
        }
        setOp(entryId, OpState.Failed(if (message.isNullOrBlank()) reason else "$reason: $message"))
    }

    // ---------------------------------------------------------------- GitHub

    /** Checks one GitHub entry for a new release and stores the result. */
    suspend fun checkGitHub(entry: AppEntry): AppEntry {
        if (entry.type != SourceType.GITHUB) return entry
        val now = System.currentTimeMillis()
        try {
            val match = GitHubClient.findLatest(entry, settings.current.githubToken, Build.SUPPORTED_ABIS.toList())
            repository.update(entry.id) {
                it.copy(
                    latestTag = match.release.tagName,
                    latestReleaseName = match.release.name,
                    latestReleaseUrl = match.release.htmlUrl,
                    latestAssetName = match.asset.name,
                    latestAssetUrl = match.asset.downloadUrl,
                    latestPublishedAt = match.release.publishedAt,
                    lastCheckedAt = now,
                    lastCheckError = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repository.update(entry.id) { it.copy(lastCheckedAt = now, lastCheckError = e.message ?: "Check failed") }
        }
        return repository.get(entry.id) ?: entry
    }

    /** Checks all GitHub entries sequentially. Returns entries that now have an update. */
    suspend fun checkAllGitHub(): List<AppEntry> {
        repository.entries.value.filter { it.type == SourceType.GITHUB }.forEach { checkGitHub(it) }
        return entriesWithUpdates()
    }

    fun entriesWithUpdates(): List<AppEntry> = repository.entries.value.filter { e ->
        e.type == SourceType.GITHUB && InstalledApps.get(context, e.packageName).let { installed ->
            VersionUtils.hasUpdate(e, installed?.versionName, installed?.versionCode)
        }
    }

    fun checkAllNow() {
        if (_checking.value) return
        scope.launch {
            _checking.value = true
            try {
                val updates = checkAllGitHub()
                val errors = repository.entries.value.count { it.type == SourceType.GITHUB && it.lastCheckError != null }
                _messages.tryEmit(
                    buildString {
                        append(
                            when (updates.size) {
                                0 -> "Everything is up to date"
                                1 -> "1 update available"
                                else -> "${updates.size} updates available"
                            }
                        )
                        if (errors > 0) append(" ($errors check${if (errors > 1) "s" else ""} failed)")
                    }
                )
                Notifications.cancelUpdates(context)
            } finally {
                _checking.value = false
            }
        }
    }

    fun checkOne(entryId: String) {
        val entry = repository.get(entryId) ?: return
        scope.launch {
            setOp(entryId, OpState.Resolving)
            val updated = checkGitHub(entry)
            setOp(entryId, null)
            updated.lastCheckError?.let { _messages.tryEmit("${updated.name}: $it") }
        }
    }

    // ------------------------------------------------------ update all / scheduled

    private val _updatingAll = MutableStateFlow(false)
    val updatingAll: StateFlow<Boolean> = _updatingAll.asStateFlow()

    /** "Update all now" from the UI: installs one app after another, showing Android's prompts. */
    fun updateAllNow() {
        if (_updatingAll.value) return
        scope.launch {
            val summary = updateSelected(interactive = true)
            _messages.tryEmit(summary.describe())
        }
    }

    /** Installs every waiting downloaded update, one after another. */
    fun installWaitingUpdates() {
        if (_updatingAll.value) return
        scope.launch {
            _updatingAll.value = true
            try {
                for (entry in waitingUpdates()) {
                    runInstall(entry.id)
                    awaitInstallResult(entry.id)
                }
            } finally {
                _updatingAll.value = false
            }
        }
    }

    /**
     * Checks every installed app that has auto-update switched on and installs newer versions.
     * [interactive] runs happen while the user is watching, so Android's confirmation prompts
     * are shown one at a time. Scheduled runs install silently where Android allows it
     * (Android 12+, apps this app installed) and otherwise leave the download waiting.
     */
    suspend fun updateSelected(interactive: Boolean): UpdateRunSummary {
        if (_updatingAll.value) return UpdateRunSummary()
        _updatingAll.value = true
        val updated = mutableListOf<String>()
        val waiting = mutableListOf<String>()
        val installing = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var upToDate = 0
        try {
            val candidates = repository.entries.value.filter {
                it.autoUpdate && InstalledApps.get(context, it.packageName) != null
            }
            for (candidate in candidates) {
                if (isBusy(candidate.id)) continue
                try {
                    val apk = prepareUpdate(candidate.id)
                    val entry = repository.get(candidate.id) ?: continue
                    if (apk == null) {
                        upToDate++
                        continue
                    }
                    when {
                        interactive -> {
                            install(entry.id, apk, entry.readyTag)
                            awaitInstallResult(entry.id)
                            val nowInstalled = InstalledApps.get(context, entry.packageName)?.versionCode ?: -1
                            when {
                                nowInstalled >= apk.info.versionCode -> updated += entry.name
                                _ops.value[entry.id] is OpState.Failed -> failed += entry.name
                                else -> waiting += entry.name // Prompt was cancelled; the download stays.
                            }
                        }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                            install(entry.id, apk, entry.readyTag, background = true)
                            installing += entry.name // The receiver reports success or "waiting".
                        }
                        else -> waiting += entry.name
                    }
                } catch (e: CancellationException) {
                    setOp(candidate.id, null)
                    throw e
                } catch (e: Exception) {
                    setOp(candidate.id, OpState.Failed(e.message ?: e.javaClass.simpleName))
                    failed += candidate.name
                }
            }
        } finally {
            _updatingAll.value = false
        }
        return UpdateRunSummary(updated, waiting, installing, upToDate, failed)
    }

    /**
     * Downloads a newer version of the entry's app if there is one, records it as waiting and
     * returns it. Returns null when the installed version is already current.
     */
    private suspend fun prepareUpdate(entryId: String): DownloadedApk? {
        var entry = repository.get(entryId) ?: return null
        val installed = InstalledApps.get(context, entry.packageName) ?: return null
        // Links can't be checked without downloading, so reuse a waiting download for them.
        if (entry.type != SourceType.GITHUB) readyApk(entry)?.let { return it }

        setOp(entryId, OpState.Resolving)
        val url = when (entry.type) {
            SourceType.URL -> entry.source
            SourceType.AFTV_CODE -> AftvResolver.resolve(entry.source)
            SourceType.GITHUB -> {
                entry = checkGitHub(entry)
                entry.lastCheckError?.let { throw IOException(it) }
                if (entry.readyFile != null) {
                    // Reuse the waiting download only if it is still the latest release.
                    if (entry.readyTag == entry.latestTag) {
                        readyApk(entry)?.let { setOp(entryId, null); return it }
                    } else {
                        discardReady(entry)
                    }
                    entry = repository.get(entryId) ?: return null
                }
                if (!VersionUtils.hasUpdate(entry, installed.versionName, installed.versionCode)) {
                    setOp(entryId, null)
                    return null
                }
                entry.latestAssetUrl ?: throw IOException("No APK found in the latest release")
            }
        }
        val apk = downloadAndInspect(entryId, url)
        setOp(entryId, null)
        if (apk.packageName != entry.packageName) {
            withContext(Dispatchers.IO) { apk.file.delete() }
            throw IOException("The download is a different app (${apk.packageName}); update it manually")
        }
        val tag = if (entry.type == SourceType.GITHUB) entry.latestTag else null
        if (apk.info.versionCode <= installed.versionCode) {
            withContext(Dispatchers.IO) { apk.file.delete() }
            if (tag != null && apk.info.versionCode == installed.versionCode) {
                // Same build under a new tag: remember it so it isn't offered again.
                repository.update(entryId) { it.copy(installedTag = tag, installedVersionCode = installed.versionCode) }
            }
            return null
        }
        repository.update(entryId) {
            it.copy(
                readyFile = apk.file.absolutePath,
                readyVersionName = apk.info.versionName,
                readyVersionCode = apk.info.versionCode,
                readyTag = tag,
            )
        }
        return apk
    }

    /** Waits until Android reports the outcome of the install started for [id]. */
    private suspend fun awaitInstallResult(id: String) {
        withTimeoutOrNull(10.minutes) { _ops.first { it[id] != OpState.Installing } }
        if (_ops.value[id] == OpState.Installing) setOp(id, null)
    }

    // ---------------------------------------------------------- self-update

    private val _selfUpdate = MutableStateFlow<SelfUpdate?>(null)
    /** A newer version of this app that the user hasn't dismissed yet. */
    val selfUpdate: StateFlow<SelfUpdate?> = _selfUpdate.asStateFlow()

    /** Called when the app is opened; checks only if the user hasn't turned it off. */
    fun checkSelfUpdateOnLaunch() {
        if (settings.current.checkSelfUpdates) scope.launch { checkSelfUpdate(manual = false) }
    }

    fun checkSelfUpdateNow() {
        scope.launch { checkSelfUpdate(manual = true) }
    }

    private suspend fun checkSelfUpdate(manual: Boolean) {
        val current = BuildConfig.VERSION_NAME
        try {
            val self = AppEntry(
                name = "App Downloader",
                type = SourceType.GITHUB,
                source = BuildConfig.UPDATE_REPO,
                assetFilter = "^AppDownloader.*\\.apk$",
            )
            val match = GitHubClient.findLatest(self, settings.current.githubToken, Build.SUPPORTED_ABIS.toList())
            val latest = match.release.tagName
            if ((VersionUtils.compare(latest, current) ?: 0) > 0) {
                _selfUpdate.value = SelfUpdate(
                    version = latest.removePrefix("v"),
                    notes = match.release.body?.substringBefore("## How to install")?.trim()?.ifBlank { null },
                    assetUrl = match.asset.downloadUrl,
                    releaseUrl = match.release.htmlUrl,
                )
            } else if (manual) {
                _messages.tryEmit("App Downloader is up to date (version $current)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (manual) _messages.tryEmit("Couldn't check for app updates: ${e.message}")
        }
    }

    fun dismissSelfUpdate() {
        _selfUpdate.value = null
        clearOp(SELF_UPDATE_ID)
    }

    fun installSelfUpdate() {
        val update = _selfUpdate.value ?: return
        if (jobs[SELF_UPDATE_ID]?.isActive == true) return
        jobs[SELF_UPDATE_ID] = scope.launch {
            try {
                val apk = downloadAndInspect(SELF_UPDATE_ID, update.assetUrl)
                if (apk.packageName != context.packageName) {
                    throw IOException("The downloaded file is not an App Downloader update")
                }
                install(SELF_UPDATE_ID, apk, "v${update.version}")
            } catch (e: CancellationException) {
                setOp(SELF_UPDATE_ID, null)
                throw e
            } catch (e: Exception) {
                setOp(SELF_UPDATE_ID, OpState.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                jobs.remove(SELF_UPDATE_ID)
            }
        }
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }
}
