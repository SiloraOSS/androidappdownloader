package io.github.edrock200.appdownloader

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.History
import io.github.edrock200.appdownloader.data.InstallRecord
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.InstallPolicy
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.data.SiloraUpdater
import io.github.edrock200.appdownloader.data.SelfUpdateStore
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.data.VersionUtils
import io.github.edrock200.appdownloader.install.ApkDownloader
import io.github.edrock200.appdownloader.install.ApkInfo
import io.github.edrock200.appdownloader.install.ApkInspector
import io.github.edrock200.appdownloader.install.ApkInstaller
import io.github.edrock200.appdownloader.install.InstalledApps
import io.github.edrock200.appdownloader.install.InstallRestoreState
import io.github.edrock200.appdownloader.install.InstallRestoreStore
import io.github.edrock200.appdownloader.install.PendingInstallRestore
import io.github.edrock200.appdownloader.install.ShizukuInstaller
import io.github.edrock200.appdownloader.install.PlaybackInstallPolicy
import io.github.edrock200.appdownloader.install.SiloraMediaSession
import io.github.edrock200.appdownloader.install.SiloraPlaybackReader
import io.github.edrock200.appdownloader.install.NotAnApkException
import io.github.edrock200.appdownloader.net.AftvResolver
import io.github.edrock200.appdownloader.net.GitHubClient
import io.github.edrock200.appdownloader.net.RemoteChange
import io.github.edrock200.appdownloader.net.RemoteFingerprint
import io.github.edrock200.appdownloader.net.RemoteProbe
import io.github.edrock200.appdownloader.work.Notifications
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** When opening the app should re-check every app for updates (pure, for tests). */
object UpdateCheckPolicy {
    const val MIN_INTERVAL_MS = 5 * 60_000L

    fun shouldCheckOnLaunch(checkRunning: Boolean, lastCheckAt: Long, now: Long): Boolean =
        !checkRunning && now - lastCheckAt >= MIN_INTERVAL_MS
}

/** A newer release of Silora Updater itself. */
data class SelfUpdate(
    val version: String,
    val notes: String?,
    val assetUrl: String,
    val releaseUrl: String?,
    val downloaded: Boolean = false,
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
    private val playback: SiloraPlaybackReader = SiloraMediaSession(context),
) {
    private val _ops = MutableStateFlow<Map<String, OpState>>(emptyMap())
    val ops: StateFlow<Map<String, OpState>> = _ops.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    private val jobs = mutableMapOf<String, Job>()
    private val restoreStore = InstallRestoreStore(context)
    private val _installRestore = MutableStateFlow(InstallRestoreState(pending = restoreStore.load()))
    val installRestore: StateFlow<InstallRestoreState> = _installRestore.asStateFlow()
    private var restoreJob: Job? = null

    companion object {
        /** Operation id used for updating this app itself. */
        const val SELF_UPDATE_ID = SiloraUpdater.ID
    }
    private val apkDir get() = File(context.cacheDir, "apks")

    private val selfStore = SelfUpdateStore(context)
    private var dismissedSelfTag: String? = null
    private val _selfUpdate = MutableStateFlow(selfOffer(selfStore.current))
    val selfUpdate: StateFlow<SelfUpdate?> = _selfUpdate.asStateFlow()

    private fun managedEntries() = repository.entries.value + selfStore.current
    private fun managedEntry(id: String) = if (id == SELF_UPDATE_ID) selfStore.current else repository.get(id)
    private suspend fun updateManagedEntry(id: String, transform: (AppEntry) -> AppEntry) {
        if (id == SELF_UPDATE_ID) {
            val entry = selfStore.update(transform)
            _selfUpdate.value = selfOffer(entry).takeUnless { entry.latestTag == dismissedSelfTag }
        } else repository.update(id, transform)
    }

    private fun selfOffer(entry: AppEntry): SelfUpdate? {
        if (!VersionUtils.hasUpdate(entry, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())) return null
        return SelfUpdate(entry.latestTag!!.removePrefix("v"), entry.latestReleaseNotes,
            entry.latestAssetUrl!!, entry.latestReleaseUrl,
            downloaded = entry.readyFile?.let { File(it).isFile } == true)
    }

    /** Only acknowledge alerts actually delivered, including the updater's separate saved state. */
    suspend fun markUpdatesNotified(entries: List<AppEntry>) {
        entries.forEach { entry -> updateManagedEntry(entry.id) {
            it.copy(notifiedTag = entry.latestTag, notifiedChangeKey = entry.remoteFingerprint?.key)
        } }
    }

    private fun setOp(id: String, state: OpState?) = _ops.update { if (state == null) it - id else it + (id to state) }

    fun clearOp(id: String) {
        jobs.remove(id)?.cancel()
        setOp(id, null)
    }

    fun isBusy(id: String) = (id == Silora.ID && _installRestore.value.active) ||
        _ops.value[id].let { it != null && it !is OpState.Failed }

    // ---------------------------------------------------------- past installs

    fun hasSavedApk(record: InstallRecord) = restoreStore.localApk(record) != null

    /** Downloads/copies and validates the complete APK BEFORE offering a destructive restore. */
    fun prepareInstallRestore(record: InstallRecord) {
        if (isBusy(Silora.ID) || _updatingAll.value || restoreJob?.isActive == true) return
        if (managedEntry(Silora.ID)?.history?.contains(record) != true) return
        _installRestore.value = InstallRestoreState(preparing = true)
        restoreJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    restoreStore.clear()
                    val local = restoreStore.localApk(record)
                    if (local != null) {
                        restoreStore.stage(local)
                    } else {
                        val tag = record.tag ?: throw IOException("No saved APK or GitHub release is recorded for this install.")
                        val match = GitHubClient.findSiloraRelease(tag,
                            io.github.edrock200.appdownloader.ui.Device.isTv(context), settings.current.githubToken)
                        val result = ApkDownloader.download(match.asset.downloadUrl, restoreStore.apk.parentFile!!, "download") { }
                        try { restoreStore.stage(result.file) } finally { result.file.delete() }
                    }
                    val checksum = InstallRestoreStore.sha256(restoreStore.apk)
                    InstallRestoreStore.validate(record, ApkInspector.inspect(context, restoreStore.apk), checksum)
                    val pending = PendingInstallRestore(record, checksum)
                    restoreStore.save(pending)
                    _installRestore.value = InstallRestoreState(pending = pending)
                }
            } catch (e: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { restoreStore.clear() }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { restoreStore.clear() }
                _installRestore.value = InstallRestoreState(error = e.message ?: "Could not prepare this past install")
            }
        }
    }

    fun cancelInstallRestore() {
        if (_installRestore.value.pending?.installing == true ||
            (_installRestore.value.pending != null && _installRestore.value.preparing)) return
        val previousJob = restoreJob
        previousJob?.cancel()
        _installRestore.update { it.copy(preparing = true) }
        restoreJob = scope.launch {
            previousJob?.join()
            updateManagedEntry(Silora.ID) { entry ->
                if (entry.readyFile == restoreStore.apk.absolutePath) entry.clearReady() else entry
            }
            withContext(Dispatchers.IO) { restoreStore.clear() }
            _installRestore.value = InstallRestoreState()
        }
    }

    fun restoreError(message: String) { _installRestore.update { it.copy(error = message) } }

    /** Called ONLY from the explicit data-loss confirmation. Returns whether Android must uninstall. */
    suspend fun confirmInstallRestore(expectedInstalledVersionCode: Long?): Boolean? {
        val pending = _installRestore.value.pending ?: return null
        if (pending.installing || _installRestore.value.preparing) return null
        _installRestore.update { it.copy(preparing = true, error = null) }
        return try {
            // Check installation access before removing the user's working copy.
            if (settings.current.installMethod == InstallMethod.SHIZUKU) {
                val state = context.app().shizuku.awaitReady()
                if (!state.ready) throw IOException(state.help)
            } else if (!io.github.edrock200.appdownloader.ui.SystemActions.canInstallPackages(context)) {
                throw IOException("Allow installs for Silora Updater before restoring. Open Settings → Allow installs, then return here.")
            }
            withContext(Dispatchers.IO) {
                val checksum = InstallRestoreStore.sha256(restoreStore.apk)
                require(checksum == pending.sha256) { "The saved restore APK has changed. Cancel and select the install again." }
                InstallRestoreStore.validate(pending.record, ApkInspector.inspect(context, restoreStore.apk), checksum)
            }
            val installed = InstalledApps.get(context, Silora.PACKAGE)
            if (installed?.versionCode != expectedInstalledVersionCode) {
                throw IOException("Silora's installed version changed. Review the restore warning and try again.")
            }
            val confirmed = pending.copy(confirmed = true, installing = false, attemptId = null,
                previousVersionName = installed?.versionName ?: pending.previousVersionName,
                previousVersionCode = installed?.versionCode ?: pending.previousVersionCode)
            withContext(Dispatchers.IO) { restoreStore.save(confirmed) }
            _installRestore.value = InstallRestoreState(pending = confirmed)
            installed != null
        } catch (e: CancellationException) {
            _installRestore.update { it.copy(preparing = false) }
            throw e
        } catch (e: Exception) {
            _installRestore.update { it.copy(preparing = false, error = e.message ?: "Could not start restore") }
            null
        }
    }

    /** A result code alone is insufficient: cancelled/blocked uninstalls must never install. */
    fun installAfterRestoreUninstall() {
        val pending = _installRestore.value.pending ?: return
        if (!pending.confirmed || pending.installing || _installRestore.value.preparing) return
        if (InstalledApps.get(context, Silora.PACKAGE) != null) {
            restoreError("Silora is still installed. The uninstall was cancelled or blocked; nothing was restored.")
            return
        }
        _installRestore.update { it.copy(preparing = true, error = null) }
        restoreJob = scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    val checksum = InstallRestoreStore.sha256(restoreStore.apk)
                    require(checksum == pending.sha256) { "The restore APK has changed. Cancel and select the install again." }
                    val info = ApkInspector.inspect(context, restoreStore.apk)
                    InstallRestoreStore.validate(pending.record, info, checksum)
                    DownloadedApk(restoreStore.apk, info)
                }
                val installing = pending.copy(installing = true, attemptId = java.util.UUID.randomUUID().toString())
                withContext(Dispatchers.IO) { restoreStore.save(installing) }
                _installRestore.value = InstallRestoreState(pending = installing)
                install(Silora.ID, apk, pending.record.tag, restoreAttemptId = installing.attemptId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setOp(Silora.ID, null)
                resetRestoreForRetry(e.message ?: "Restore installation failed. Try again.")
            }
        }
    }

    /** If a result was lost with the process, verify Android before offering an explicit retry. */
    fun refreshInstallRestore() {
        val pending = _installRestore.value.pending ?: return
        if (!pending.installing || restoreJob?.isActive == true || _ops.value[Silora.ID] == OpState.Installing) return
        val installed = InstalledApps.get(context, Silora.PACKAGE)
        if (installed?.versionCode == pending.record.versionCode) {
            onInstallSucceeded(Silora.ID, pending.record.tag, pending.record.versionCode, restoreStore.apk.absolutePath,
                restoreAttemptId = pending.attemptId)
        } else {
            if (context.packageManager.packageInstaller.mySessions.any { it.appPackageName == Silora.PACKAGE }) return
            scope.launch { resetRestoreForRetry("Restore did not finish. Choose Continue to try again.") }
        }
    }

    private suspend fun resetRestoreForRetry(message: String) {
        val pending = _installRestore.value.pending ?: return
        val retry = pending.copy(installing = false, attemptId = null)
        withContext(Dispatchers.IO) { restoreStore.save(retry) }
        _installRestore.value = InstallRestoreState(pending = retry, error = message)
    }

    // ---------------------------------------------------------------- install

    fun downloadAndInstall(entryId: String) {
        if (jobs[entryId]?.isActive == true || isBusy(entryId)) return
        setOp(entryId, OpState.Resolving)
        jobs[entryId] = scope.launch { runInstall(entryId) }
    }

    private suspend fun runInstall(entryId: String) {
        var entry = managedEntry(entryId) ?: return
        try {
            if (entryId == SELF_UPDATE_ID) {
                val apk = readyApk(entry) ?: prepareUpdate(entryId) ?: return
                entry = managedEntry(entryId) ?: return
                install(entryId, apk, entry.readyTag)
                return
            }
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

            if (entry.id == Silora.ID) {
                if (info.packageName != Silora.PACKAGE ||
                    info.info.versionCode != entry.latestVersionCode
                ) {
                    withContext(Dispatchers.IO) { info.file.delete() }
                    throw IOException("The download does not match the published Silora build. Please try again later.")
                }
            }
            val previousPackage = entry.packageName
            if (previousPackage != null && previousPackage != info.packageName) {
                _messages.tryEmit("Note: ${entry.name} now provides ${info.packageName} (was $previousPackage)")
            }
            updateManagedEntry(entryId) { e ->
                val useLabel = e.autoName && info.label != null
                e.copy(
                    packageName = info.packageName,
                    name = if (useLabel) info.label!! else e.name,
                    autoName = e.autoName && !useLabel,
                    pendingFingerprint = info.fingerprint,
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
        return DownloadedApk(result.file, info, result.fingerprint)
    }

    /** False means playback deferred this automatic install; the ready APK stays on disk. */
    internal suspend fun install(opId: String, apk: DownloadedApk, releaseTag: String?, background: Boolean = false,
        restoreAttemptId: String? = null): Boolean {
        val previous = InstalledApps.get(context, apk.packageName)
        if (restoreAttemptId != null) {
            check(previous == null) { "Silora is installed again. Confirm the uninstall and restore again before continuing." }
        }
        if (opId == SELF_UPDATE_ID) {
            require(apk.packageName == context.packageName && !apk.info.isSplit &&
                apk.info.versionCode == releaseTag?.let(SiloraUpdater::versionCode)) {
                "The download does not match the published Silora Updater build"
            }
            if (apk.info.versionCode <= BuildConfig.VERSION_CODE) {
                discardReady(selfStore.current)
                setOp(opId, null)
                return true
            }
        }
        if (opId == Silora.ID) {
            require(apk.packageName == Silora.PACKAGE) { "This is not a Silora APK" }
            if (previous != null && apk.info.versionCode <= previous.versionCode) {
                withContext(Dispatchers.IO) { apk.file.delete() }
                updateManagedEntry(opId) { it.clearReady() }
                setOp(opId, null)
                _messages.tryEmit("Silora is already up to date")
                return true
            }
        }
        // Check after downloading and installer readiness, immediately before replacement.
        // Both background workers use this path, as do Standard and Shizuku installs.
        if (!PlaybackInstallPolicy.allowsInstall(apk.packageName, background, playback)) {
            setOp(opId, null)
            android.util.Log.i("SiloraUpdater", "Deferred automatic Silora install: playback active or unavailable; retry on next scheduled check")
            return false
        }
        setOp(opId, OpState.Installing)
        if (settings.current.installMethod == InstallMethod.SHIZUKU) {
            // Keep manual downloads ready as well, so a lost Binder never forces a re-download.
            updateManagedEntry(opId) { it.copy(readyFile = apk.file.absolutePath,
                readyVersionName = apk.info.versionName, readyVersionCode = apk.info.versionCode, readyTag = releaseTag) }
            val callback = ApkInstaller.resultCallback(context, 0, apk.file, apk.info, opId, releaseTag,
                background, previous?.versionName, previous?.versionCode, viaShizuku = true,
                restoreAttemptId = restoreAttemptId)
            ShizukuInstaller.install(context, apk.file, apk.info, callback.intentSender)
            val complete = withTimeoutOrNull(15_000) { _ops.first { it[opId] != OpState.Installing } }
                ?: throw IOException("Installation finished but no result was received. Reopen the updater to check the installed version.")
            (complete[opId] as? OpState.Failed)?.let { throw IOException(it.message) }
            return true
        }
        withContext(Dispatchers.IO) {
            ApkInstaller.install(
                context, apk.file, apk.info, opId, releaseTag, background,
                previousVersionName = previous?.versionName,
                previousVersionCode = previous?.versionCode,
                restoreAttemptId = restoreAttemptId,
            )
        }
        return true
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
            if (apk != null && apk.info.versionCode > installedCode &&
                (entry.id != SELF_UPDATE_ID || (apk.packageName == context.packageName && !apk.info.isSplit &&
                    apk.info.versionCode == entry.readyTag?.let(SiloraUpdater::versionCode)))) apk
            else null.also { file.delete() }
        }
        if (apk == null) updateManagedEntry(entry.id) { it.clearReady() }
        return apk
    }

    private suspend fun discardReady(entry: AppEntry) {
        entry.readyFile?.let { path -> withContext(Dispatchers.IO) { File(path).delete() } }
        updateManagedEntry(entry.id) { it.clearReady() }
    }

    private fun AppEntry.clearReady() =
        copy(readyFile = null, readyVersionName = null, readyVersionCode = null, readyTag = null)

    internal class DownloadedApk(val file: File, val info: ApkInfo, val fingerprint: RemoteFingerprint? = null) {
        val packageName get() = info.packageName
        val label get() = info.label
    }

    /** Ignore late callbacks from a cancelled attempt once a different restore has started. */
    fun acceptsInstallResult(entryId: String, restoreAttemptId: String?): Boolean {
        val pending = _installRestore.value.pending.takeIf { entryId == Silora.ID }
        return if (restoreAttemptId != null) pending?.installing == true && restoreAttemptId == pending.attemptId
        else pending?.installing != true || pending.attemptId == null
    }

    fun onInstallSucceeded(
        entryId: String,
        releaseTag: String?,
        versionCode: Long,
        filePath: String?,
        background: Boolean = false,
        previousVersionName: String? = null,
        previousVersionCode: Long? = null,
        restoreAttemptId: String? = null,
    ) {
        if (!acceptsInstallResult(entryId, restoreAttemptId)) return
        scope.launch {
            if (entryId == SELF_UPDATE_ID) {
                updateManagedEntry(entryId) { it.clearReady() }
                if (settings.current.deleteApkAfterInstall && filePath != null)
                    withContext(Dispatchers.IO) { File(filePath).delete() }
                _selfUpdate.value = null
                setOp(entryId, null)
                Notifications.showWaitingUpdates(context, waitingUpdates())
                return@launch
            }
            val nowInstalled = InstalledApps.get(context, managedEntry(entryId)?.packageName)
            val restored = _installRestore.value.pending?.takeIf {
                entryId == Silora.ID && it.installing && filePath == restoreStore.apk.absolutePath
            }
            val previousName = restored?.previousVersionName ?: previousVersionName
            val previousCode = restored?.previousVersionCode ?: previousVersionCode
            // Keep the exact file's identity; retention is optional and must never hide a successful install.
            val artifact = withContext(Dispatchers.IO) {
                filePath?.let(::File)?.takeIf { it.isFile }?.let { file ->
                    runCatching {
                        val sha256 = InstallRestoreStore.sha256(file)
                        val retained = if (!settings.current.deleteApkAfterInstall)
                            runCatching { restoreStore.retain(file, sha256) }.getOrNull() else null
                        retained to sha256
                    }.getOrNull()
                }
            }
            val record = InstallRecord(
                at = System.currentTimeMillis(),
                versionName = nowInstalled?.versionName,
                versionCode = versionCode.takeIf { it >= 0 } ?: nowInstalled?.versionCode ?: 0,
                previousVersionName = previousName,
                previousVersionCode = previousCode,
                tag = releaseTag,
                kind = History.kindOf(previousCode, versionCode.takeIf { it >= 0 } ?: nowInstalled?.versionCode ?: 0),
                automatic = background,
                apkPath = artifact?.first,
                apkSha256 = artifact?.second,
            )
            updateManagedEntry(entryId) { e ->
                val installedFp = e.pendingFingerprint
                e.clearReady().copy(
                    history = History.recordInstall(e.history, record),
                    fingerprint = installedFp ?: e.fingerprint,
                    pendingFingerprint = null,
                    remoteChange = if (installedFp != null) RemoteChange.UNCHANGED else e.remoteChange,
                    remoteFingerprint = installedFp ?: e.remoteFingerprint,
                    installedTag = releaseTag ?: e.installedTag,
                    installedVersionCode = versionCode.takeIf { it >= 0 } ?: e.installedVersionCode,
                    notifiedTag = releaseTag ?: e.notifiedTag,
                )
            }
            if (restored != null) {
                // Avoid immediately upgrading away from the version the user deliberately restored.
                settings.update(settings.current.copy(autoInstallUpdates = false))
                withContext(Dispatchers.IO) { restoreStore.clear() }
                _installRestore.value = InstallRestoreState()
            }
            if (settings.current.deleteApkAfterInstall && filePath != null) {
                withContext(Dispatchers.IO) { File(filePath).delete() }
            }
            setOp(entryId, null)
            val entry = managedEntry(entryId)
            Notifications.cancelUpdates(context)
            _messages.tryEmit(if (restored != null) "Restored Silora. Automatic installation is now off."
                else "Installed ${entry?.name ?: "app"}")
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
    fun waitingUpdates(): List<AppEntry> = managedEntries().filter { it.readyFile != null && !isBusy(it.id) }

    fun onInstallFailed(entryId: String, status: Int, message: String?, keepDownload: Boolean = false,
        restoreAttemptId: String? = null) {
        if (!acceptsInstallResult(entryId, restoreAttemptId)) return
        if (entryId == Silora.ID && _installRestore.value.pending?.installing == true) {
            setOp(entryId, null)
            scope.launch { resetRestoreForRetry(if (status == PackageInstaller.STATUS_FAILURE_ABORTED)
                "Restore installation cancelled. Silora may be uninstalled; choose Continue to install the saved build."
                else "Restore installation failed. ${message.orEmpty()} The saved build is ready to retry.") }
            return
        }
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            setOp(entryId, null)
            _messages.tryEmit("Installation cancelled")
            return
        }
        // Don't keep offering a download that Android refused to install.
        if (!keepDownload) scope.launch { updateManagedEntry(entryId) { it.clearReady() } }
        val reason = when (status) {
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "The installed app conflicts with this update, possibly because it uses a different signing key. Contact support for help."
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
            updateManagedEntry(entry.id) {
                it.copy(
                    latestTag = match.release.tagName,
                    latestReleaseName = match.release.name,
                    latestReleaseUrl = match.release.htmlUrl,
                    latestReleaseNotes = match.release.body?.substringBefore("## How to install")?.trim()?.ifBlank { null },
                    latestAssetName = match.asset.name,
                    latestAssetUrl = match.asset.downloadUrl,
                    latestPublishedAt = match.release.publishedAt,
                    latestVersionCode = match.versionCode,
                    latestVersionName = match.versionName,
                    lastCheckedAt = now,
                    lastCheckError = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateManagedEntry(entry.id) { it.copy(lastCheckedAt = now, lastCheckError = e.message ?: "Check failed") }
        }
        return managedEntry(entry.id) ?: entry
    }

    /** Checks all GitHub entries sequentially. Returns entries that now have an update. */
    /**
     * Asks the server whether a link / aftv entry's file changed since the installed copy was
     * downloaded, without downloading it, and stores the result.
     */
    suspend fun checkLink(entry: AppEntry, resolvedUrl: String? = null): AppEntry {
        if (entry.type == SourceType.GITHUB) return entry
        val now = System.currentTimeMillis()
        try {
            val url = resolvedUrl ?: when (entry.type) {
                SourceType.AFTV_CODE -> AftvResolver.resolve(entry.source)
                else -> entry.source
            }
            val remote = RemoteProbe.fingerprint(url)
            val change = RemoteProbe.compare(entry.fingerprint, remote)
            updateManagedEntry(entry.id) {
                it.copy(remoteFingerprint = remote, remoteChange = change, remoteCheckedAt = now, lastCheckError = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateManagedEntry(entry.id) { it.copy(remoteCheckedAt = now, lastCheckError = e.message ?: "Check failed") }
        }
        return managedEntry(entry.id) ?: entry
    }

    /** Checks GitHub releases and link changes for every entry. Returns entries that have an update. */
    suspend fun checkAll(): List<AppEntry> {
        managedEntries().forEach { entry ->
            if (entry.id == SELF_UPDATE_ID && isBusy(SELF_UPDATE_ID)) return@forEach
            when {
                entry.type == SourceType.GITHUB -> checkGitHub(entry)
                // Only installed links are worth checking: there is nothing to compare otherwise.
                InstalledApps.get(context, entry.packageName) != null -> checkLink(entry)
            }
        }
        return entriesWithUpdates()
    }

    fun entriesWithUpdates(): List<AppEntry> = managedEntries().filter { e ->
        InstalledApps.get(context, e.packageName).let { installed ->
            VersionUtils.updateAvailable(e, installed?.versionName, installed?.versionCode)
        }
    }

    /**
     * Adds history records for version changes made outside App Downloader (or, for apps that have
     * no history yet, the version currently installed). Skips apps that are being installed now.
     */
    suspend fun syncHistoryWithDevice() {
        for (entry in managedEntries()) {
            if (isBusy(entry.id)) continue
            if (entry.readyFile != null && (!File(entry.readyFile).exists() ||
                    (InstalledApps.get(context, entry.packageName)?.versionCode ?: -1) >= (entry.readyVersionCode ?: Long.MAX_VALUE))) {
                discardReady(entry)
            }
            if (entry.id == SELF_UPDATE_ID) continue
            val pkg = entry.packageName ?: continue
            val installed = InstalledApps.get(context, pkg) ?: continue
            val updated = History.syncWithDevice(entry.history, installed.versionName, installed.versionCode, installed.installedAt)
            if (updated !== entry.history) updateManagedEntry(entry.id) { it.copy(history = updated) }
        }
    }

    /** When the last full check of all apps finished. */
    private var lastFullCheckAt = 0L

    /**
     * Called every time the app is opened: checks every app for a newer version, quietly (the list
     * shows the results). Skipped if a check is running or finished in the last few minutes, so
     * switching in and out of the app doesn't use up GitHub's request limit.
     */
    fun checkAllOnLaunch() {
        scope.launch { syncHistoryWithDevice() }
        if (!UpdateCheckPolicy.shouldCheckOnLaunch(_checking.value, lastFullCheckAt, System.currentTimeMillis())) return
        checkAllNow(quiet = true)
    }

    /** Checks every app for updates. [quiet] skips the "everything is up to date" message. */
    fun checkAllNow(quiet: Boolean = false) {
        if (!_checking.compareAndSet(false, true)) return
        scope.launch {
            try {
                val updates = checkAll()
                lastFullCheckAt = System.currentTimeMillis()
                if (quiet) return@launch
                val errors = managedEntries().count { it.lastCheckError != null }
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
        val entry = managedEntry(entryId) ?: return
        scope.launch {
            setOp(entryId, OpState.Resolving)
            val updated = if (entry.type == SourceType.GITHUB) checkGitHub(entry) else checkLink(entry)
            setOp(entryId, null)
            when {
                updated.lastCheckError != null -> _messages.tryEmit("${updated.name}: ${updated.lastCheckError}")
                updated.type != SourceType.GITHUB -> _messages.tryEmit(
                    "${updated.name}: " + when (updated.remoteChange) {
                        RemoteChange.CHANGED -> "the file has changed since you installed it"
                        RemoteChange.UNCHANGED -> "no change since you installed it"
                        else -> "the website doesn't say whether the file changed"
                    }
                )
            }
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

    /**
     * "Update all" at the top of the list: updates every app in [ids] (the ones marked as having
     * an update), one at a time so Android's install prompts don't pile up.
     */
    fun updateEntries(ids: List<String>) {
        if (_updatingAll.value || ids.isEmpty()) return
        scope.launch {
            _updatingAll.value = true
            var updated = 0
            var failed = 0
            var skipped = 0
            try {
                for (id in ids) {
                    val entry = managedEntry(id) ?: continue
                    if (isBusy(id)) continue
                    val before = InstalledApps.get(context, entry.packageName)?.versionCode
                    runInstall(id)
                    awaitInstallResult(id)
                    val after = InstalledApps.get(context, managedEntry(id)?.packageName)?.versionCode
                    when {
                        _ops.value[id] is OpState.Failed -> failed++
                        after != null && after != before -> updated++
                        else -> skipped++ // Cancelled, or the download was the same version.
                    }
                }
            } finally {
                _updatingAll.value = false
            }
            _messages.tryEmit(
                listOfNotNull(
                    "Updated $updated app${if (updated == 1) "" else "s"}",
                    "$failed failed".takeIf { failed > 0 },
                    "$skipped not updated".takeIf { skipped > 0 },
                ).joinToString(", ")
            )
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
     * (Android 12+ through the standard installer, or an authorized Shizuku connection).
     */
    suspend fun updateSelected(interactive: Boolean, shouldContinue: () -> Boolean = { true }): UpdateRunSummary {
        if (!_updatingAll.compareAndSet(false, true)) return UpdateRunSummary()
        val updated = mutableListOf<String>()
        val waiting = mutableListOf<String>()
        val installing = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var upToDate = 0
        try {
            // Finish player work first: replacing ourselves terminates this process.
            val candidates = managedEntries().filter {
                it.autoUpdate && InstalledApps.get(context, it.packageName) != null
            }
            for (candidate in candidates) {
                if (candidate.id == SELF_UPDATE_ID) {
                    if (isBusy(Silora.ID)) awaitInstallResult(Silora.ID)
                    if (isBusy(Silora.ID) || _installRestore.value.active) continue
                }
                if (isBusy(candidate.id) || !shouldContinue()) continue
                try {
                    val apk = prepareUpdate(candidate.id)
                    val entry = managedEntry(candidate.id) ?: continue
                    if (apk == null) {
                        upToDate++
                        continue
                    }
                    when {
                        !shouldContinue() -> waiting += entry.name
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
                        InstallPolicy.canInstallInBackground(settings.current.installMethod, Build.VERSION.SDK_INT,
                            io.github.edrock200.appdownloader.ui.SystemActions.canInstallPackages(context),
                            settings.current.installMethod == InstallMethod.SHIZUKU && context.app().shizuku.awaitReady().ready) -> {
                            val started = install(entry.id, apk, entry.readyTag, background = true)
                            if (!started) waiting += entry.name
                            else if ((InstalledApps.get(context, entry.packageName)?.versionCode ?: -1) >= apk.info.versionCode)
                                updated += entry.name
                            else installing += entry.name // Standard installer results can arrive later.
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
        var entry = managedEntry(entryId) ?: return null
        val installed = InstalledApps.get(context, entry.packageName) ?: return null
        // Links can't be checked without downloading, so reuse a waiting download for them.
        if (entry.type != SourceType.GITHUB) readyApk(entry)?.let { return it }

        setOp(entryId, OpState.Resolving)
        val url = when (entry.type) {
            SourceType.URL, SourceType.AFTV_CODE -> {
                val url = if (entry.type == SourceType.AFTV_CODE) AftvResolver.resolve(entry.source) else entry.source
                if (settings.current.updateOnlyIfChanged) {
                    entry = checkLink(entry, url)
                    // Only skip when the server says so; "can't tell" still downloads and compares versions.
                    if (entry.remoteChange == RemoteChange.UNCHANGED) {
                        setOp(entryId, null)
                        return null
                    }
                }
                url
            }
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
                    entry = managedEntry(entryId) ?: return null
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
        if (apk.packageName != entry.packageName ||
            ((entry.id == Silora.ID || entry.id == SELF_UPDATE_ID) && apk.info.versionCode != entry.latestVersionCode) ||
            (entry.id == SELF_UPDATE_ID && apk.info.isSplit)) {
            withContext(Dispatchers.IO) { apk.file.delete() }
            throw IOException("The download is a different app (${apk.packageName}); update it manually")
        }
        val tag = if (entry.type == SourceType.GITHUB) entry.latestTag else null
        if (apk.info.versionCode <= installed.versionCode) {
            withContext(Dispatchers.IO) { apk.file.delete() }
            if (tag == null && apk.fingerprint != null) {
                // Nothing newer in this file: use it as the baseline for future change checks.
                updateManagedEntry(entryId) {
                    it.copy(fingerprint = apk.fingerprint, remoteFingerprint = apk.fingerprint, remoteChange = RemoteChange.UNCHANGED)
                }
            }
            if (tag != null && apk.info.versionCode == installed.versionCode) {
                // Same build under a new tag: remember it so it isn't offered again.
                updateManagedEntry(entryId) { it.copy(installedTag = tag, installedVersionCode = installed.versionCode) }
            }
            return null
        }
        updateManagedEntry(entryId) {
            it.copy(
                readyFile = apk.file.absolutePath,
                readyVersionName = apk.info.versionName,
                readyVersionCode = apk.info.versionCode,
                readyTag = tag,
                pendingFingerprint = apk.fingerprint,
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

    /** Compatibility entry point; self-checks share the player's launch cooldown and schedule. */
    fun checkSelfUpdateOnLaunch() = checkAllOnLaunch()

    fun checkSelfUpdateNow() {
        if (isBusy(SELF_UPDATE_ID)) return
        dismissedSelfTag = null
        _selfUpdate.value = selfOffer(selfStore.current)
        scope.launch {
            val entry = checkGitHub(selfStore.current)
            when {
                entry.lastCheckError != null -> _messages.tryEmit("Silora Updater: ${entry.lastCheckError}")
                selfOffer(entry) == null -> _messages.tryEmit("Silora Updater is up to date (version ${BuildConfig.VERSION_NAME})")
            }
        }
    }

    fun dismissSelfUpdate() {
        // Dismiss only this launch's prompt; keep the APK and the next scheduled check.
        if (isBusy(SELF_UPDATE_ID)) return
        dismissedSelfTag = selfStore.current.latestTag
        _selfUpdate.value = null
        clearOp(SELF_UPDATE_ID)
    }

    fun installSelfUpdate() {
        if (_updatingAll.value || _installRestore.value.active || isBusy(Silora.ID)) {
            _messages.tryEmit("Wait for the Silora install to finish, then update Silora Updater.")
            return
        }
        downloadAndInstall(SELF_UPDATE_ID)
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }
}
