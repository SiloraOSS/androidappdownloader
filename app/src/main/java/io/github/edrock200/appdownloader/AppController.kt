package io.github.edrock200.appdownloader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.BackgroundInstallBlock
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
import io.github.edrock200.appdownloader.data.UpdatePreferences
import io.github.edrock200.appdownloader.data.VersionUtils
import io.github.edrock200.appdownloader.install.ApkDownloader
import io.github.edrock200.appdownloader.install.ExpectedApk
import io.github.edrock200.appdownloader.install.ApkInfo
import io.github.edrock200.appdownloader.install.ApkInspector
import io.github.edrock200.appdownloader.install.ApkInstaller
import io.github.edrock200.appdownloader.install.InstalledApps
import io.github.edrock200.appdownloader.install.InstallRestoreState
import io.github.edrock200.appdownloader.install.InstallAttempt
import io.github.edrock200.appdownloader.install.InstallRestoreStore
import io.github.edrock200.appdownloader.install.PendingInstallRestore
import io.github.edrock200.appdownloader.install.ShizukuInstaller
import io.github.edrock200.appdownloader.install.PlaybackInstallPolicy
import io.github.edrock200.appdownloader.install.SiloraMediaSession
import io.github.edrock200.appdownloader.install.SiloraPlaybackReader
import io.github.edrock200.appdownloader.install.NotAnApkException
import io.github.edrock200.appdownloader.net.AftvResolver
import io.github.edrock200.appdownloader.net.GitHubClient
import io.github.edrock200.appdownloader.net.GitHubRetryException
import io.github.edrock200.appdownloader.net.RemoteChange
import io.github.edrock200.appdownloader.net.RemoteFingerprint
import io.github.edrock200.appdownloader.net.RemoteProbe
import io.github.edrock200.appdownloader.work.Notifications
import io.github.edrock200.appdownloader.work.DeferredInstallReason
import io.github.edrock200.appdownloader.work.DeferredInstallWorker
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
    val retryDownload: Boolean = false,
) {
    fun describe(): String = buildList {
        if (updated.isNotEmpty()) add("Updated ${updated.size}")
        if (installing.isNotEmpty()) add("installing ${installing.size}")
        if (waiting.isNotEmpty()) add("${waiting.size} waiting to install")
        if (upToDate > 0) add("$upToDate up to date")
        if (failed.isNotEmpty()) add("${failed.size} failed")
    }.joinToString(", ").ifEmpty { "No apps are selected for auto-update" }
}

internal data class UpdateCheckRun(
    val entries: Map<String, AppEntry>,
    val errors: Map<String, Exception>,
) {
    val retryableErrors get() = errors.filterValues(GitHubRetryException::isRetryable)
    val retryAt get() = retryableErrors.values.mapNotNull { (it as? GitHubRetryException)?.retryAt }.maxOrNull()
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
    private val dispatchedAttempts = mutableSetOf<String>()
    private val installResults = Mutex()
    private val githubChecks = ConcurrentHashMap<String, Mutex>()
    private var confirmationHost: ((Intent) -> Unit)? = null
    private var confirmationResume = 0
    private var openedConfirmation: Pair<String, Int>? = null
    private var reconcileJob: Job? = null
    private var shizukuRecoveryJob: Job? = null
    private val installResultErrors = CoroutineExceptionHandler { _, error ->
        android.util.Log.w("SiloraUpdater", "Could not save the install result", error)
        _messages.tryEmit("Could not save the install result. Free up storage if needed, then reopen Silora Updater.")
    }
    private val restoreStore = InstallRestoreStore(context)
    private val _installRestore = MutableStateFlow(InstallRestoreState(pending = restoreStore.load()))
    val installRestore: StateFlow<InstallRestoreState> = _installRestore.asStateFlow()
    private var restoreJob: Job? = null

    companion object {
        /** Operation id used for updating this app itself. */
        const val SELF_UPDATE_ID = SiloraUpdater.ID
        private const val SHIZUKU_RECOVERY_MS = 10 * 60_000L
    }
    private val apkDir get() = File(context.cacheDir, "apks")

    private val selfStore = SelfUpdateStore(context)
    private var dismissedSelfTag: String? = null
    private val _selfUpdate = MutableStateFlow(selfOffer(selfStore.current))
    val selfUpdate: StateFlow<SelfUpdate?> = _selfUpdate.asStateFlow()

    init {
        managedEntries().filter { it.installAttempt != null }.forEach { setOp(it.id, OpState.Installing) }
    }

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
        if (managedEntry(id)?.installAttempt != null) return
        jobs[id]?.cancel()
        setOp(id, null)
    }

    fun isBusy(id: String) = (id == Silora.ID && _installRestore.value.active) ||
        managedEntry(id)?.installAttempt != null ||
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
                        val result = ApkDownloader.download(match.asset.downloadUrl, restoreStore.downloadDirectory,
                            "download", match.asset.expectedApk) { }
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
                if (managedEntry(Silora.ID)?.installAttempt == null) {
                    setOp(Silora.ID, null)
                    resetRestoreForRetry(e.message ?: "Restore installation failed. Try again.")
                } else reconcileInstallAttempts()
            }
        }
    }

    /** If a result was lost with the process, verify Android before offering an explicit retry. */
    fun refreshInstallRestore() {
        val pending = _installRestore.value.pending ?: return
        if (!pending.installing || restoreJob?.isActive == true || managedEntry(Silora.ID)?.installAttempt != null) return
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
        if (jobs[entryId]?.isCompleted == false || _updatingAll.value || isBusy(entryId)) return
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

            val info = downloadAndInspect(entry, url)

            if (entry.id == Silora.ID) {
                if (info.packageName != Silora.PACKAGE || info.info.isSplit ||
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
            if (managedEntry(entryId)?.installAttempt == null) setOp(entryId, null)
            throw e
        } catch (e: NotAnApkException) {
            setOp(entryId, OpState.Failed(e.message ?: "Not an APK", e.pageUrl))
        } catch (e: Exception) {
            if (managedEntry(entryId)?.installAttempt == null) {
                setOp(entryId, OpState.Failed(e.message ?: e.javaClass.simpleName))
            } else reconcileInstallAttempts()
        } finally {
            jobs.remove(entryId)
        }
    }

    /** Downloads [url] for the operation [opId] and reads the package details. */
    private suspend fun downloadAndInspect(entry: AppEntry, url: String): DownloadedApk {
        val opId = entry.id
        setOp(opId, OpState.Downloading(null))
        val referenced = managedEntries().flatMap { saved ->
            listOfNotNull(saved.readyFile, saved.installAttempt?.filePath) + saved.history.mapNotNull { it.apkPath }
        }.toSet()
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
            apkDir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff && it.absolutePath !in referenced }
                ?.forEach { it.delete() }
        }
        val expected = if (entry.type == SourceType.GITHUB) ExpectedApk(entry.latestAssetSize, entry.latestAssetDigest) else null
        val result = ApkDownloader.download(url, apkDir, opId, expected) { p -> setOp(opId, OpState.Downloading(p)) }
        try {
            val info = withContext(Dispatchers.IO) { ApkInspector.inspect(context, result.file) }
            return DownloadedApk(result.file, info, result.fingerprint, result.sha256)
        } catch (e: Exception) {
            result.file.delete()
            throw e
        }
    }

    /** False means playback deferred this automatic install; the ready APK stays on disk. */
    internal suspend fun install(opId: String, apk: DownloadedApk, releaseTag: String?, background: Boolean = false,
        restoreAttemptId: String? = null, retry: DeferredInstallReason? = null): Boolean {
        val retryMethod = if (retry != null) settings.current.installMethod else null
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
        val deferral = PlaybackInstallPolicy.deferralReason(apk.packageName, background, playback,
            settings.current.waitUntilPlaybackStops)
        if (deferral != null) {
            updateManagedEntry(opId) { it.copy(playbackDeferral = deferral) }
            setOp(opId, null)
            if (retry == null) managedEntry(opId)?.let { queueDeferredInstall(it, DeferredInstallReason.PLAYBACK) }
            android.util.Log.i("SiloraUpdater", "Deferred automatic Silora install: playback active or unavailable")
            return false
        }
        if (retry != null && (retryMethod != settings.current.installMethod ||
                !canRetryStagedApk(managedEntry(opId), apk.file.absolutePath, retry))) {
            setOp(opId, null)
            return true
        }
        val attempt = InstallAttempt(
            id = java.util.UUID.randomUUID().toString(), method = settings.current.installMethod,
            packageName = apk.packageName, filePath = apk.file.absolutePath, versionName = apk.info.versionName,
            versionCode = apk.info.versionCode, tag = releaseTag, background = background,
            previousVersionName = previous?.versionName, previousVersionCode = previous?.versionCode,
            restoreId = restoreAttemptId, startedAt = System.currentTimeMillis(),
        )
        setOp(opId, OpState.Installing)
        dispatchedAttempts += attempt.id
        try {
            updateManagedEntry(opId) { entry ->
                check(entry.installAttempt == null) { "An installation is already in progress" }
                val staged = if (restoreAttemptId == null) entry.copy(
                    readyFile = attempt.filePath, readyVersionName = attempt.versionName,
                    readyVersionCode = attempt.versionCode, readyTag = releaseTag, readySha256 = apk.sha256,
                ) else entry
                val block = if (retry == DeferredInstallReason.CONFIRMATION)
                    staged.backgroundInstallBlock?.copy(confirmationRetryPending = false) else staged.backgroundInstallBlock
                staged.copy(installAttempt = attempt, playbackDeferral = null, backgroundInstallBlock = block)
            }
            if (attempt.method == InstallMethod.SHIZUKU) {
                val callback = ApkInstaller.resultCallback(context, 0, apk.file, apk.info, opId, releaseTag,
                    background, previous?.versionName, previous?.versionCode, viaShizuku = true,
                    restoreAttemptId = restoreAttemptId, attemptId = attempt.id)
                ShizukuInstaller.install(context, apk.file, apk.info, callback.intentSender)
                withTimeoutOrNull(15_000) { _ops.first { it[opId] != OpState.Installing } }
                (_ops.value[opId] as? OpState.Failed)?.let { throw IOException(it.message) }
            } else withContext(Dispatchers.IO) {
                ApkInstaller.install(
                    context, apk.file, apk.info, opId, releaseTag, background,
                    previousVersionName = previous?.versionName, previousVersionCode = previous?.versionCode,
                    restoreAttemptId = restoreAttemptId, attemptId = attempt.id,
                ) { sessionId ->
                    updateManagedEntry(opId) { it.copy(installAttempt = attempt.copy(sessionId = sessionId)) }
                }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                // Shizuku or Android may have committed before the transport reported a failure.
                val saved = managedEntry(opId)?.installAttempt
                val sessionExists = saved?.sessionId?.let(::hasInstallSession) == true
                val installed = InstalledApps.get(context, attempt.packageName)
                if (saved?.id == attempt.id && !sessionExists && installed?.versionCode != attempt.versionCode) {
                    updateManagedEntry(opId) { it.copy(installAttempt = null) }
                }
            }
            throw e
        } finally {
            dispatchedAttempts -= attempt.id
        }
        if (attempt.method == InstallMethod.SHIZUKU && _ops.value[opId] == OpState.Installing) reconcileInstallAttempts()
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
            val apk = if (file.isFile) {
                runCatching {
                    val sha256 = if (entry.readySha256 != null || entry.latestAssetDigest != null) {
                        InstallRestoreStore.sha256(file)
                    } else null
                    require(entry.readySha256 == null || entry.readySha256 == sha256)
                    if (entry.readyTag == entry.latestTag && entry.type == SourceType.GITHUB) {
                        ExpectedApk(entry.latestAssetSize, entry.latestAssetDigest).verify(file.length(), sha256.orEmpty())
                    }
                    DownloadedApk(file, ApkInspector.inspect(context, file), sha256 = sha256)
                }.getOrNull()
            } else {
                null
            }
            if (apk != null && apk.info.versionCode > installedCode &&
                (entry.readyVersionCode == null || apk.info.versionCode == entry.readyVersionCode) &&
                (entry.lastCheckError != null || entry.latestTag == null || entry.latestTag == entry.readyTag) &&
                (entry.id != Silora.ID || (apk.packageName == Silora.PACKAGE && !apk.info.isSplit)) &&
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
        copy(readyFile = null, readyVersionName = null, readyVersionCode = null, readyTag = null,
            readySha256 = null, playbackDeferral = null,
            backgroundInstallBlock = backgroundInstallBlock?.copy(confirmationRetryPending = false))

    internal class DownloadedApk(
        val file: File, val info: ApkInfo, val fingerprint: RemoteFingerprint? = null, val sha256: String? = null,
    ) {
        val packageName get() = info.packageName
        val label get() = info.label
    }

    /** Ignore late callbacks from a cancelled attempt once a different restore has started. */
    fun acceptsInstallResult(entryId: String, restoreAttemptId: String?): Boolean {
        val pending = _installRestore.value.pending.takeIf { entryId == Silora.ID }
        return if (restoreAttemptId != null) pending?.installing == true && restoreAttemptId == pending.attemptId
        else pending?.installing != true || pending.attemptId == null
    }

    private fun hasInstallSession(id: Int) = context.packageManager.packageInstaller.getSessionInfo(id) != null

    fun setInstallConfirmationHost(host: ((Intent) -> Unit)?) {
        confirmationHost = host
        if (host != null) {
            confirmationResume++
            reconcileInstallAttempts()
        }
    }

    fun reconcileInstallAttempts(): Job {
        reconcileJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch(installResultErrors) {
            // Let the normal result win over device reconciliation during an activity transition.
            delay(750)
            installResults.withLock {
                for (entry in managedEntries()) {
                    val attempt = entry.installAttempt ?: continue
                    if (attempt.id in dispatchedAttempts) continue
                    val installed = InstalledApps.get(context, attempt.packageName)
                    if (installed?.versionCode == attempt.versionCode) {
                        recordSuccessfulInstall(entry.id, attempt.tag, attempt.versionCode, attempt.filePath,
                            attempt.background, attempt.previousVersionName, attempt.previousVersionCode,
                            attempt.restoreId, attempt.id, attempt.versionName)
                        continue
                    }
                    val session = attempt.sessionId?.let(context.packageManager.packageInstaller::getSessionInfo)
                    if (session != null) {
                        val opened = openedConfirmation
                        if (opened?.first == attempt.id &&
                            (confirmationHost == null || opened.second == confirmationResume)) continue
                        val sealed = if (Build.VERSION.SDK_INT >= 26) session.isSealed else session.isActive
                        if (!attempt.needsConfirmation && sealed) continue
                    }
                    // Shizuku can still be writing the APK after a process restart. Its normal
                    // dispatch already waits for the result before reaching this point.
                    val remaining = SHIZUKU_RECOVERY_MS - (System.currentTimeMillis() - attempt.startedAt)
                    if (attempt.method == InstallMethod.SHIZUKU && remaining in 1..SHIZUKU_RECOVERY_MS) {
                        if (shizukuRecoveryJob?.isActive != true) shizukuRecoveryJob = scope.launch {
                            delay(remaining)
                            reconcileInstallAttempts()
                        }
                        continue
                    }
                    if (session != null) abandonInstallSession(session.sessionId)
                    releaseInstallForRetry(entry.id, attempt, "Installation did not finish. Try again.")
                }
            }
            refreshInstallRestore()
        }.also { reconcileJob = it }
    }

    private fun abandonInstallSession(sessionId: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    fun onInstallNeedsConfirmation(
        entryId: String, attemptId: String?, sessionId: Int, confirm: Intent?,
        background: Boolean, restoreAttemptId: String?,
    ): Job = scope.launch(installResultErrors) {
        installResults.withLock {
            val attempt = managedEntry(entryId)?.installAttempt
            if (!matchesInstallResult(entryId, attemptId, restoreAttemptId)) return@withLock
            if (attempt == null) {
                // Callback from a version installed before attempt tracking, or a device check.
                val host = confirmationHost
                if (background || host == null || confirm == null || runCatching { host(confirm) }.isFailure) {
                    if (sessionId >= 0) abandonInstallSession(sessionId)
                    onBackgroundInstallNeedsUser(entryId)
                }
                return@withLock
            }
            val host = confirmationHost
            if (!background && host != null && confirm != null) {
                updateManagedEntry(entryId) { it.copy(installAttempt = attempt.copy(needsConfirmation = true)) }
                val currentHost = confirmationHost
                if (currentHost != null && runCatching { currentHost(confirm) }.isSuccess) {
                    openedConfirmation = attempt.id to confirmationResume
                    return@withLock
                }
            }
            attempt.sessionId?.let(::abandonInstallSession)
            releaseInstallForRetry(entryId, attempt, "Open Silora Updater to confirm the install.",
                needsConfirmation = background)
        }
    }

    private fun matchesInstallResult(entryId: String, attemptId: String?, restoreAttemptId: String?): Boolean =
        acceptsInstallResult(entryId, restoreAttemptId) && managedEntry(entryId)?.installAttempt?.id == attemptId

    private suspend fun releaseInstallForRetry(
        entryId: String, attempt: InstallAttempt, message: String, needsConfirmation: Boolean = false,
    ) {
        updateManagedEntry(entryId) { entry ->
            if (entry.installAttempt?.id != attempt.id) return@updateManagedEntry entry
            val block = if (needsConfirmation) {
                val confirmation = attempt.backgroundBlock(entry.readySha256, true)
                val alreadyOffered = entry.backgroundInstallBlock?.copy(confirmationRetryPending = false) == confirmation
                confirmation.copy(confirmationRetryPending = !alreadyOffered && attempt.method == InstallMethod.STANDARD &&
                    entry.readyFile == attempt.filePath && canRetryVerifiedApk(entry))
            } else entry.backgroundInstallBlock
            entry.copy(installAttempt = null, backgroundInstallBlock = block)
        }
        if (attempt.restoreId != null) resetRestoreForRetry(message)
        setOp(entryId, null)
        managedEntry(entryId)?.takeIf { needsConfirmation && it.backgroundInstallBlock?.confirmationRetryPending == true }
            ?.let { queueDeferredInstall(it, DeferredInstallReason.CONFIRMATION) }
        Notifications.showWaitingUpdates(context, waitingUpdates())
    }

    private fun InstallAttempt.backgroundBlock(sha256: String?, needsConfirmation: Boolean) =
        BackgroundInstallBlock(versionCode, tag, method, previousVersionCode, sha256, needsConfirmation)

    fun onInstallSucceeded(
        entryId: String,
        releaseTag: String?,
        versionCode: Long,
        filePath: String?,
        background: Boolean = false,
        previousVersionName: String? = null,
        previousVersionCode: Long? = null,
        restoreAttemptId: String? = null,
        attemptId: String? = null,
        versionName: String? = null,
    ): Job = scope.launch(installResultErrors) {
        installResults.withLock {
            recordSuccessfulInstall(entryId, releaseTag, versionCode, filePath, background,
                previousVersionName, previousVersionCode, restoreAttemptId, attemptId, versionName)
        }
    }

    private suspend fun recordSuccessfulInstall(
        entryId: String, releaseTag: String?, versionCode: Long, filePath: String?, background: Boolean,
        previousVersionName: String?, previousVersionCode: Long?, restoreAttemptId: String?,
        attemptId: String?, versionName: String?,
    ) {
        val entry = managedEntry(entryId) ?: return
        val current = matchesInstallResult(entryId, attemptId, restoreAttemptId)
        if (!current && (attemptId == null || restoreAttemptId != null || entryId == SELF_UPDATE_ID)) return
        if (attemptId != null && entry.history.any { it.attemptId == attemptId }) return
        if (entryId == SELF_UPDATE_ID) {
            updateManagedEntry(entryId) { it.clearReady().copy(installAttempt = null, backgroundInstallBlock = null) }
            if (settings.current.deleteApkAfterInstall && filePath != null) {
                withContext(Dispatchers.IO) { File(filePath).delete() }
            }
            _selfUpdate.value = null
            setOp(entryId, null)
            Notifications.showWaitingUpdates(context, waitingUpdates())
            return
        }
        val nowInstalled = InstalledApps.get(context, entry.packageName)
        val restored = _installRestore.value.pending?.takeIf {
            current && entryId == Silora.ID && it.installing && filePath == restoreStore.apk.absolutePath
        }
        val previousName = restored?.previousVersionName ?: previousVersionName
        val previousCode = restored?.previousVersionCode ?: previousVersionCode
        val code = versionCode.takeIf { it >= 0 } ?: nowInstalled?.versionCode ?: 0
        // Keep the exact file's identity even when retaining a separate copy is optional.
        val artifact = withContext(Dispatchers.IO) {
            filePath?.let(::File)?.takeIf { it.isFile }?.let { file ->
                runCatching {
                    val sha256 = entry.readySha256?.takeIf { current && entry.readyFile == filePath }
                        ?: InstallRestoreStore.sha256(file)
                    val retained = if (!settings.current.deleteApkAfterInstall)
                        runCatching { restoreStore.retain(file, sha256) }.getOrNull() else null
                    retained to sha256
                }.getOrNull()
            }
        }
        val record = InstallRecord(
            at = if (current) System.currentTimeMillis() else nowInstalled?.installedAt
                ?.takeIf { nowInstalled.versionCode == code } ?: System.currentTimeMillis(),
            versionName = versionName ?: nowInstalled?.versionName?.takeIf { nowInstalled.versionCode == code },
            versionCode = code,
            previousVersionName = previousName,
            previousVersionCode = previousCode,
            tag = releaseTag,
            kind = History.kindOf(previousCode, code),
            automatic = background,
            apkPath = artifact?.first,
            apkSha256 = artifact?.second,
            attemptId = attemptId,
        )
        updateManagedEntry(entryId) { saved ->
            val history = History.recordInstall(saved.history, record)
            if (!current) saved.copy(history = history)
            else {
                val installedFp = saved.pendingFingerprint
                val cleared = if (saved.readyFile == filePath) saved.clearReady() else saved
                cleared.copy(
                    history = history,
                    installAttempt = null,
                    backgroundInstallBlock = null,
                    fingerprint = installedFp ?: saved.fingerprint,
                    pendingFingerprint = null,
                    remoteChange = if (installedFp != null) RemoteChange.UNCHANGED else saved.remoteChange,
                    remoteFingerprint = installedFp ?: saved.remoteFingerprint,
                    installedTag = releaseTag ?: saved.installedTag,
                    installedVersionCode = code.takeIf { it > 0 } ?: saved.installedVersionCode,
                    notifiedTag = releaseTag ?: saved.notifiedTag,
                )
            }
        }
        // A late success is still real, but only the current attempt owns the dashboard and APK.
        if (!current) {
            if (filePath != null && entry.readyFile != filePath && settings.current.deleteApkAfterInstall) {
                withContext(Dispatchers.IO) { File(filePath).delete() }
            }
            return
        }
        if (restored != null) {
            settings.update(settings.current.copy(autoInstallUpdates = false))
            withContext(Dispatchers.IO) { restoreStore.clear() }
            _installRestore.value = InstallRestoreState()
        }
        if (settings.current.deleteApkAfterInstall && filePath != null) {
            withContext(Dispatchers.IO) { File(filePath).delete() }
        }
        setOp(entryId, null)
        Notifications.cancelUpdates(context)
        _messages.tryEmit(if (restored != null) "Restored Silora. Automatic installation is now off."
            else "Installed ${entry.name}")
        if (background) Notifications.showAutoUpdated(context, entry, record.versionName)
        Notifications.showWaitingUpdates(context, waitingUpdates())
    }

    /** A scheduled install needs the user to confirm it; keep the download for later. */
    fun onBackgroundInstallNeedsUser(entryId: String) {
        setOp(entryId, null)
        Notifications.showWaitingUpdates(context, waitingUpdates())
    }

    /** Entries with a downloaded update waiting to be installed. */
    fun waitingUpdates(): List<AppEntry> = managedEntries().filter { it.readyFile != null && !isBusy(it.id) }

    fun onInstallFailed(
        entryId: String, status: Int, message: String?, keepDownload: Boolean = false,
        restoreAttemptId: String? = null, attemptId: String? = null,
    ): Job = scope.launch(installResultErrors) {
        installResults.withLock {
            if (!matchesInstallResult(entryId, attemptId, restoreAttemptId)) return@withLock
            val entry = managedEntry(entryId) ?: return@withLock
            val attempt = entry.installAttempt
            if (entryId == Silora.ID && _installRestore.value.pending?.installing == true) {
                updateManagedEntry(entryId) { it.copy(installAttempt = null) }
                setOp(entryId, null)
                resetRestoreForRetry(if (status == PackageInstaller.STATUS_FAILURE_ABORTED)
                    "Restore installation cancelled. Silora may be uninstalled; choose Continue to install the saved build."
                else "Restore installation failed. ${message.orEmpty()} The saved build is ready to retry.")
                return@withLock
            }
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                updateManagedEntry(entryId) { it.copy(installAttempt = null) }
                setOp(entryId, null)
                if (attempt?.background != true) _messages.tryEmit("Installation cancelled")
                return@withLock
            }
            val permanent = status in setOf(PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
            val reason = when (status) {
                PackageInstaller.STATUS_FAILURE_CONFLICT ->
                    "The installed app conflicts with this update, possibly because it uses a different signing key. Contact support for help."
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This app is not compatible with your device"
                PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space"
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation was blocked"
                PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is invalid"
                else -> "Installation failed"
            }
            updateManagedEntry(entryId) { saved ->
                val block = if (attempt?.background == true && permanent) attempt.backgroundBlock(saved.readySha256, false)
                    else saved.backgroundInstallBlock
                val discard = permanent || (attempt == null && !keepDownload)
                (if (discard) saved.clearReady() else saved).copy(installAttempt = null, backgroundInstallBlock = block)
            }
            if (permanent && attempt != null) withContext(Dispatchers.IO) { File(attempt.filePath).delete() }
            setOp(entryId, OpState.Failed(if (message.isNullOrBlank()) reason else "$reason: $message"))
            if (attempt?.background == true) Notifications.showUpdateFailures(context, listOf(entry.name))
        }
    }

    // ---------------------------------------------------------------- GitHub

    /** Checks one GitHub entry for a new release and stores the result. */
    suspend fun checkGitHub(entry: AppEntry): AppEntry = refreshGitHub(entry).first

    private suspend fun refreshGitHub(entry: AppEntry): Pair<AppEntry, Exception?> =
        githubChecks.getOrPut(entry.id) { Mutex() }.withLock { fetchGitHub(entry) }

    private suspend fun fetchGitHub(entry: AppEntry): Pair<AppEntry, Exception?> {
        if (entry.type != SourceType.GITHUB) return entry to null
        val now = System.currentTimeMillis()
        var failure: Exception? = null
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
                    latestAssetSize = match.asset.size.takeIf { it > 0 },
                    latestAssetDigest = match.asset.digest,
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
            failure = e
            updateManagedEntry(entry.id) { it.copy(lastCheckedAt = now, lastCheckError = e.message ?: "Check failed") }
        }
        return (managedEntry(entry.id) ?: entry) to failure
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

    private suspend fun checkEntries(ids: Set<String>? = null): UpdateCheckRun {
        val checked = mutableMapOf<String, AppEntry>()
        val errors = mutableMapOf<String, Exception>()
        for (entry in managedEntries()) {
            if ((ids != null && entry.id !in ids) || isBusy(entry.id)) continue
            when {
                entry.type == SourceType.GITHUB -> {
                    val (updated, error) = refreshGitHub(entry)
                    checked[entry.id] = updated
                    if (error != null) errors[entry.id] = error
                }
                // Only installed links are worth checking: there is nothing to compare otherwise.
                InstalledApps.get(context, entry.packageName) != null -> checked[entry.id] = checkLink(entry)
            }
        }
        return UpdateCheckRun(checked, errors)
    }

    internal suspend fun checkForWorker(): UpdateCheckRun? {
        if (!_checking.compareAndSet(false, true)) return null
        try {
            return checkEntries()
        } finally {
            _checking.value = false
        }
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
            updateManagedEntry(entry.id) { current ->
                if (current.installAttempt != null) current else current.copy(
                    history = History.syncWithDevice(current.history, installed.versionName, installed.versionCode, installed.installedAt)
                )
            }
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
                var checked = checkEntries()
                if (quiet) repeat(2) {
                    val retryIds = checked.retryableErrors.filterValues { (it as? GitHubRetryException)?.retryAt == null }.keys
                    if (retryIds.isNotEmpty()) {
                        delay(3_000)
                        val retry = checkEntries(retryIds)
                        checked = UpdateCheckRun(checked.entries + retry.entries,
                            (checked.errors - retry.entries.keys) + retry.errors)
                    }
                }
                if (checked.entries.isNotEmpty() && checked.errors.isEmpty()) lastFullCheckAt = System.currentTimeMillis()
                if (quiet) return@launch
                val errors = checked.entries.values.count { it.lastCheckError != null }
                val updates = entriesWithUpdates().filter { it.lastCheckError == null }
                _messages.tryEmit(
                    buildString {
                        append(
                            when (updates.size) {
                                0 -> if (errors > 0) "Could not complete the update check" else "Everything is up to date"
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
    suspend fun updateSelected(
        interactive: Boolean,
        checkedEntries: Map<String, AppEntry>? = null,
        shouldContinue: () -> Boolean = { true },
    ): UpdateRunSummary {
        if (!_updatingAll.compareAndSet(false, true)) return UpdateRunSummary()
        val updated = mutableListOf<String>()
        val waiting = mutableListOf<String>()
        val installing = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var retryDownload = false
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
                val checked = checkedEntries?.get(candidate.id)
                if (checkedEntries != null && checked == null) continue
                if (checked?.lastCheckError != null) {
                    failed += candidate.name
                    continue
                }
                try {
                    val apk = prepareUpdate(candidate.id, checked, background = !interactive)
                    val entry = managedEntry(candidate.id) ?: continue
                    if (apk == null) {
                        upToDate++
                        continue
                    }
                    when {
                        !shouldContinue() -> {
                            setOp(entry.id, null)
                            waiting += entry.name
                        }
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
                        canInstallInBackground(entry, apk) -> {
                            val started = install(entry.id, apk, entry.readyTag, background = true)
                            if (!started) waiting += entry.name
                            else if ((InstalledApps.get(context, entry.packageName)?.versionCode ?: -1) >= apk.info.versionCode)
                                updated += entry.name
                            else installing += entry.name // Standard installer results can arrive later.
                        }
                        else -> {
                            setOp(entry.id, null)
                            waiting += entry.name
                        }
                    }
                } catch (e: CancellationException) {
                    if (managedEntry(candidate.id)?.installAttempt == null) setOp(candidate.id, null)
                    throw e
                } catch (e: Exception) {
                    if (managedEntry(candidate.id)?.installAttempt == null) {
                        setOp(candidate.id, OpState.Failed(e.message ?: e.javaClass.simpleName))
                    } else reconcileInstallAttempts()
                    failed += candidate.name
                    if (GitHubRetryException.isRetryable(e)) retryDownload = true
                }
            }
        } finally {
            _updatingAll.value = false
        }
        return UpdateRunSummary(updated, waiting, installing, upToDate, failed, retryDownload)
    }

    private suspend fun canInstallInBackground(
        entry: AppEntry, apk: DownloadedApk, confirmationRetry: Boolean = false,
    ): Boolean {
        val method = settings.current.installMethod
        if (!confirmationRetry && matchesBackgroundBlock(entry, needsConfirmation = true)) return false
        val standardAllowed = method == InstallMethod.STANDARD &&
            io.github.edrock200.appdownloader.ui.SystemActions.canInstallPackages(context) &&
            InstallPolicy.standardCanUpdateSilently(context, apk.info)
        val shizukuReady = method == InstallMethod.SHIZUKU && context.app().shizuku.awaitReady().ready
        return InstallPolicy.canInstallInBackground(method, Build.VERSION.SDK_INT, standardAllowed, shizukuReady)
    }

    private fun canRetryVerifiedApk(entry: AppEntry): Boolean =
        UpdatePreferences.shouldInstall(settings.current) && entry.autoUpdate &&
            entry.readyFile != null && entry.readyTag != null && entry.readyTag == entry.latestTag &&
            entry.readyVersionCode != null && entry.readyVersionCode == entry.latestVersionCode &&
            entry.readySha256 != null &&
            (entry.latestAssetDigest == null || entry.latestAssetDigest.equals("sha256:${entry.readySha256}", ignoreCase = true))

    private fun canRetryStagedApk(entry: AppEntry?, path: String, reason: DeferredInstallReason): Boolean {
        if (entry == null || entry.readyFile != path || !canRetryVerifiedApk(entry)) return false
        return when (reason) {
            DeferredInstallReason.PLAYBACK -> entry.playbackDeferral != null
            DeferredInstallReason.CONFIRMATION -> entry.backgroundInstallBlock?.confirmationRetryPending == true &&
                settings.current.installMethod == InstallMethod.STANDARD && matchesBackgroundBlock(entry, needsConfirmation = true)
        }
    }

    private suspend fun queueDeferredInstall(entry: AppEntry, reason: DeferredInstallReason) {
        val path = entry.readyFile ?: return
        if (!canRetryStagedApk(entry, path, reason)) return
        try {
            DeferredInstallWorker.enqueue(context, entry.id, path, reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SiloraUpdater", "Could not schedule a retry for ${entry.name}", e)
        }
    }

    internal suspend fun resumeConfirmationRetries() {
        // The result can be saved just before Android kills the process or replaces the updater.
        managedEntries().forEach { queueDeferredInstall(it, DeferredInstallReason.CONFIRMATION) }
    }

    internal suspend fun finishConfirmationRetry(entryId: String, path: String) {
        updateManagedEntry(entryId) { entry ->
            if (entry.readyFile != path || entry.backgroundInstallBlock?.confirmationRetryPending != true) entry
            else entry.copy(backgroundInstallBlock = entry.backgroundInstallBlock.copy(confirmationRetryPending = false))
        }
    }

    /** Returns true only when the worker should check again, without fetching a release or APK. */
    internal suspend fun retryStagedInstall(entryId: String, path: String, reason: DeferredInstallReason): Boolean {
        val entry = managedEntry(entryId) ?: return false
        if (!canRetryStagedApk(entry, path, reason)) return false
        if (_installRestore.value.active || managedEntries().any { isBusy(it.id) }) return true
        if (!_updatingAll.compareAndSet(false, true)) return true
        setOp(entryId, OpState.Resolving)
        try {
            val apk = readyApk(entry) ?: return false
            val method = settings.current.installMethod
            val confirmation = reason == DeferredInstallReason.CONFIRMATION
            if (!canInstallInBackground(entry, apk, confirmationRetry = confirmation) ||
                method != settings.current.installMethod ||
                !canRetryStagedApk(managedEntry(entryId), path, reason)) return false
            return !install(entryId, apk, entry.readyTag, background = true, retry = reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (managedEntry(entryId)?.installAttempt == null) setOp(entryId, OpState.Failed(e.message ?: "Installation failed"))
            else reconcileInstallAttempts()
            return false
        } finally {
            if (_ops.value[entryId] == OpState.Resolving) setOp(entryId, null)
            _updatingAll.value = false
        }
    }

    private fun matchesBackgroundBlock(entry: AppEntry, needsConfirmation: Boolean): Boolean {
        val block = entry.backgroundInstallBlock ?: return false
        return block.needsConfirmation == needsConfirmation && block.method == settings.current.installMethod &&
            block.versionCode == entry.latestVersionCode && block.tag == entry.latestTag &&
            block.installedVersionCode == InstalledApps.get(context, entry.packageName)?.versionCode &&
            (entry.latestAssetDigest == null || entry.latestAssetDigest.equals("sha256:${block.sha256}", ignoreCase = true))
    }

    /**
     * Downloads a newer version of the entry's app if there is one, records it as waiting and
     * returns it. Returns null when the installed version is already current.
     */
    private suspend fun prepareUpdate(entryId: String, checked: AppEntry? = null, background: Boolean = false): DownloadedApk? {
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
                entry = if (checked == null) checkGitHub(entry) else managedEntry(entryId) ?: return null
                entry.lastCheckError?.let { throw IOException(it) }
                if (background && matchesBackgroundBlock(entry, needsConfirmation = false)) {
                    setOp(entryId, null)
                    return null
                }
                if (entry.readyFile != null) {
                    // Reuse the waiting download only if it is still the latest release.
                    if (entry.readyTag == entry.latestTag) {
                        readyApk(entry)?.let { return it }
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
        val apk = downloadAndInspect(entry, url)
        if (apk.packageName != entry.packageName ||
            ((entry.id == Silora.ID || entry.id == SELF_UPDATE_ID) && apk.info.versionCode != entry.latestVersionCode) ||
            ((entry.id == Silora.ID || entry.id == SELF_UPDATE_ID) && apk.info.isSplit)) {
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
            setOp(entryId, null)
            return null
        }
        updateManagedEntry(entryId) {
            it.copy(
                readyFile = apk.file.absolutePath,
                readyVersionName = apk.info.versionName,
                readyVersionCode = apk.info.versionCode,
                readyTag = tag,
                readySha256 = apk.sha256,
                playbackDeferral = null,
                pendingFingerprint = apk.fingerprint,
            )
        }
        return apk
    }

    /** Waits until Android reports the outcome of the install started for [id]. */
    private suspend fun awaitInstallResult(id: String) {
        withTimeoutOrNull(10.minutes) { _ops.first { it[id] != OpState.Installing } }
        if (_ops.value[id] == OpState.Installing) reconcileInstallAttempts()
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
