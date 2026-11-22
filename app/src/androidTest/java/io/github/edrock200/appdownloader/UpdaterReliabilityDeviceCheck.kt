package io.github.edrock200.appdownloader

import android.os.Build
import android.os.Bundle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.data.SiloraUpdater
import io.github.edrock200.appdownloader.install.ApkDownloader
import io.github.edrock200.appdownloader.install.ApkInspector
import io.github.edrock200.appdownloader.install.ApkInstaller
import io.github.edrock200.appdownloader.install.ExpectedApk
import io.github.edrock200.appdownloader.install.InstallAttempt
import io.github.edrock200.appdownloader.install.InstallRestoreStore
import io.github.edrock200.appdownloader.install.InstalledApps
import io.github.edrock200.appdownloader.install.ShizukuInstaller
import io.github.edrock200.appdownloader.install.SiloraMediaSession
import io.github.edrock200.appdownloader.install.SiloraPlaybackReader
import io.github.edrock200.appdownloader.install.SiloraPlaybackStatus
import io.github.edrock200.appdownloader.net.GitHubClient
import io.github.edrock200.appdownloader.ui.SystemActions
import io.github.edrock200.appdownloader.work.DeferredInstallReason
import io.github.edrock200.appdownloader.work.DeferredInstallWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.UUID

/** Opt-in checks on real hardware. Uses the exact installed player for either installer test. */
internal object UpdaterReliabilityDeviceCheck {
    suspend fun run(app: App, check: String, output: Bundle) {
        when (check) {
            "downloads" -> verifyDownloads(app, output)
            "interruptedPrompt" -> exerciseInstaller(app, InstallMethod.STANDARD, output)
            "shizuku" -> exerciseInstaller(app, InstallMethod.SHIZUKU, output)
            "standardSilent" -> exerciseInstaller(app, InstallMethod.STANDARD, output,
                background = true, expectConfirmation = false)
            "standardThrottled" -> exerciseInstaller(app, InstallMethod.STANDARD, output, background = true)
            "playbackRetry" -> verifyPlaybackRetry(app, output)
            "confirmationRetry" -> exerciseInstaller(app, InstallMethod.STANDARD, output,
                background = true, verifyRetry = true)
            "standardPreflight", "standardPermissionDenied", "standardPlaying", "standardUnknown", "standardIdle" ->
                StandardSilentDeviceCheck.run(app, check, output)
            else -> error("Unknown reliability check: $check")
        }
    }

    private suspend fun verifyPlaybackRetry(app: App, output: Bundle) {
        val previous = app.repository.get(Silora.ID)!!
        val installed = InstalledApps.get(app, Silora.PACKAGE) ?: error("Silora must be installed")
        val settings = app.settings.current
        check(previous.readyFile == null && !app.controller.isBusy(Silora.ID))
        check(settings.autoInstallUpdates && settings.waitUntilPlaybackStops && settings.checkIntervalHours > 0)
        val source = File(app.packageManager.getApplicationInfo(Silora.PACKAGE, 0).sourceDir)
        val apk = File.createTempFile("playback-retry-check-", ".apk", app.cacheDir)
        val manager = WorkManager.getInstance(app)
        val workName = DeferredInstallWorker.workName(Silora.ID, apk.absolutePath, DeferredInstallReason.PLAYBACK)
        try {
            val (info, hash) = withContext(Dispatchers.IO) {
                source.copyTo(apk, overwrite = true)
                ApkInspector.inspect(app, apk) to InstallRestoreStore.sha256(apk)
            }
            check(info.packageName == Silora.PACKAGE && info.versionCode == installed.versionCode)
            val activeWork = manager.getWorkInfosForUniqueWorkFlow("github-update-check").first().single { !it.state.isFinished }
            val tag = checkNotNull(previous.latestTag)
            check(previous.latestVersionCode == installed.versionCode)
            check(previous.latestAssetDigest.equals("sha256:$hash", ignoreCase = true))
            // Simulate a newer release only for the playback decision. The bytes remain the exact
            // installed APK, so a worker reading their actual version could never reinstall it.
            val version = info.versionCode + 1
            app.repository.update(Silora.ID) {
                it.copy(latestVersionCode = version, readyFile = apk.absolutePath, readyTag = tag,
                    readyVersionCode = version, readySha256 = hash)
            }
            val controller = AppController(app, app.repository, app.settings, app.scope,
                SiloraPlaybackReader { SiloraPlaybackStatus.PLAYING })
            val startedAt = System.currentTimeMillis()
            withContext(Dispatchers.Main.immediate) {
                repeat(2) {
                    check(!controller.install(Silora.ID, AppController.DownloadedApk(apk, info.copy(versionCode = version),
                        sha256 = hash), tag, background = true))
                }
            }
            val work = manager.getWorkInfosForUniqueWorkFlow(workName).first().single { !it.state.isFinished }
            check(work.nextScheduleTimeMillis >= startedAt + 15 * 60_000)
            val periodic = manager.getWorkInfosForUniqueWorkFlow("github-update-check").first().single { !it.state.isFinished }
            check(periodic.id == activeWork.id && periodic.nextScheduleTimeMillis == activeWork.nextScheduleTimeMillis)
            check(InstalledApps.get(app, Silora.PACKAGE) == installed)
            output.putLong("retryDelayMs", work.nextScheduleTimeMillis - startedAt)
            output.putString("result", "PASS: two playback deferrals queued one 15-minute retry and preserved the periodic check")
        } finally {
            manager.cancelUniqueWork(workName).await()
            app.repository.update(Silora.ID) {
                if (it.readyFile != apk.absolutePath) it else it.copy(latestVersionCode = previous.latestVersionCode,
                    readyFile = null, readyVersionCode = null, readyTag = null, readySha256 = null,
                    playbackDeferral = previous.playbackDeferral)
            }
            apk.delete()
        }
    }

    private suspend fun verifyDownloads(app: App, output: Bundle) {
        val checks = app.controller.checkForWorker() ?: error("Another update check is running")
        check(checks.errors.isEmpty()) { checks.errors.values.joinToString { it.message.orEmpty() } }
        val directory = File(app.cacheDir, "verified-download-check-${UUID.randomUUID()}")
        try {
            for (id in listOf(Silora.ID, SiloraUpdater.ID)) {
                val entry = checks.entries.getValue(id)
                val url = checkNotNull(entry.latestAssetUrl)
                val digest = checkNotNull(entry.latestAssetDigest)
                val size = checkNotNull(entry.latestAssetSize)
                val verified = ApkDownloader.download(url, directory, id, ExpectedApk(size, digest)) {}
                val apk = withContext(Dispatchers.IO) { ApkInspector.inspect(app, verified.file) }
                check(apk.packageName == entry.packageName && apk.versionCode == entry.latestVersionCode && !apk.isSplit) {
                    "${entry.name} does not match its release metadata"
                }
                output.putString("${id}Tag", entry.latestTag)
                output.putString("${id}Sha256", verified.sha256)
                if (id == SiloraUpdater.ID) {
                    val invalid = runCatching {
                        ApkDownloader.download(url, directory, "bad-digest", ExpectedApk(size, "sha256:" + "0".repeat(64))) {}
                    }.exceptionOrNull()
                    check(invalid is IOException && invalid.message?.contains("SHA-256 checksum") == true) {
                        "The published APK should be rejected when its expected checksum differs"
                    }
                    check(directory.listFiles()!!.none { it.name.startsWith("bad-digest") }) {
                        "Checksum failure left a temporary APK behind"
                    }
                }
            }
            val fallback = GitHubClient.findLatest(SiloraUpdater.entry(), "silora-device-check-invalid-token",
                Build.SUPPORTED_ABIS.toList())
            check(fallback.release.tagName == checks.entries.getValue(SiloraUpdater.ID).latestTag) {
                "Anonymous retry did not find the expected Updater release"
            }
            output.putString("result", "PASS: both published APKs verified, checksum mismatch cleaned up, rejected token fell back anonymously")
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun exerciseInstaller(
        app: App, method: InstallMethod, output: Bundle, background: Boolean = false,
        expectConfirmation: Boolean = method == InstallMethod.STANDARD, verifyRetry: Boolean = false,
    ) {
        val before = InstalledApps.get(app, Silora.PACKAGE) ?: error("Silora must already be installed")
        val previous = app.repository.get(Silora.ID)!!
        check(previous.readyFile == null && !app.controller.isBusy(Silora.ID) &&
            !app.controller.updatingAll.value) { "Finish the existing Silora update or restore before running this check" }
        if (method == InstallMethod.STANDARD) {
            check(SystemActions.canInstallPackages(app))
            if (background) {
                check(Build.VERSION.SDK_INT >= 31 && app.settings.current.installMethod == InstallMethod.STANDARD)
                check(SiloraMediaSession(app).read() == SiloraPlaybackStatus.IDLE) { "Silora is not idle" }
            } else {
                check(Build.VERSION.SDK_INT < 31) { "The interrupted-prompt check requires Android 11 or older" }
            }
        } else {
            val status = app.shizuku.awaitReady()
            output.putString("shizuku", status.name)
            check(status.ready) { status.help }
            check(SiloraMediaSession(app).read() == SiloraPlaybackStatus.IDLE) {
                "Silora playback is active or could not be read"
            }
        }
        val installedInfo = app.packageManager.getPackageInfo(Silora.PACKAGE, 0)
        check(installedInfo.splitNames.isNullOrEmpty()) { "The check requires a single installed APK" }
        val apk = withContext(Dispatchers.IO) {
            File.createTempFile("reliability-check-", ".apk", app.cacheDir).also {
                File(installedInfo.applicationInfo!!.sourceDir).copyTo(it, overwrite = true)
            }
        }
        val id = UUID.randomUUID().toString()
        val tag = when {
            verifyRetry -> checkNotNull(previous.latestTag)
            background -> "standard-device-check-$id"
            else -> null
        }
        val manager = WorkManager.getInstance(app)
        val retryName = DeferredInstallWorker.workName(Silora.ID, apk.absolutePath, DeferredInstallReason.CONFIRMATION)
        var session: Int? = null
        try {
            val (info, sha256) = withContext(Dispatchers.IO) {
                ApkInspector.inspect(app, apk) to InstallRestoreStore.sha256(apk)
            }
            check(info.packageName == Silora.PACKAGE && info.versionCode == before.versionCode)
            if (verifyRetry) {
                check(previous.latestVersionCode == info.versionCode)
                check(previous.latestAssetDigest.equals("sha256:$sha256", ignoreCase = true))
            }
            val attempt = InstallAttempt(id, method, info.packageName, apk.absolutePath, info.versionName,
                info.versionCode, tag, background, before.versionName, before.versionCode, null, System.currentTimeMillis())
            app.repository.update(Silora.ID) { entry ->
                check(entry.readyFile == null && entry.installAttempt == null)
                entry.copy(readyFile = apk.absolutePath, readyVersionCode = info.versionCode,
                    readyVersionName = info.versionName, readyTag = tag, readySha256 = sha256, installAttempt = attempt)
            }
            if (method == InstallMethod.STANDARD) {
                withContext(Dispatchers.IO) {
                    ApkInstaller.install(app, apk, info, Silora.ID, tag, background = background, previousVersionName = before.versionName,
                        previousVersionCode = before.versionCode, attemptId = id) { sessionId ->
                        session = sessionId
                        app.repository.update(Silora.ID) { it.copy(installAttempt = attempt.copy(sessionId = sessionId)) }
                    }
                }
            } else {
                val callback = ApkInstaller.resultCallback(app, 0, apk, info, Silora.ID, null, false,
                    before.versionName, before.versionCode, viaShizuku = true, attemptId = id)
                ShizukuInstaller.install(app, apk, info, callback.intentSender)
            }
            withTimeout(25_000) {
                while (app.repository.get(Silora.ID)!!.installAttempt?.id == id) delay(100)
            }
            val after = InstalledApps.get(app, Silora.PACKAGE)!!
            val saved = app.repository.get(Silora.ID)!!
            check(after.versionCode == before.versionCode)
            if (expectConfirmation) {
                check(after.installedAt == before.installedAt) { "Silora changed while confirmation was unavailable" }
                check(session?.let(app.packageManager.packageInstaller::getSessionInfo) == null) {
                    "Android kept the abandoned session"
                }
                check(saved.readyFile == apk.absolutePath && apk.isFile && saved.readySha256 == sha256) {
                    "Interrupted Android confirmation lost the ready APK"
                }
                if (background) {
                    val block = saved.backgroundInstallBlock
                    check(block?.tag == tag && block?.needsConfirmation == true) {
                        "The tracked background attempt did not record Android's confirmation request"
                    }
                    output.putBoolean("recordedNeedsConfirmation", true)
                }
                if (verifyRetry) {
                    check(saved.backgroundInstallBlock?.confirmationRetryPending == true)
                    val queued = withTimeout(10_000) {
                        manager.getWorkInfosForUniqueWorkFlow(retryName).first { it.size == 1 }.single()
                    }
                    check(queued.nextScheduleTimeMillis >= attempt.startedAt + 120_000)
                    val completed = withTimeout(170_000) {
                        manager.getWorkInfoByIdFlow(queued.id).first { it?.state?.isFinished == true }!!
                    }
                    check(completed.state == WorkInfo.State.SUCCEEDED)
                    check(InstalledApps.get(app, Silora.PACKAGE) == before)
                    val entry = app.repository.get(Silora.ID)!!
                    check(entry.readyFile == null && entry.backgroundInstallBlock?.confirmationRetryPending == false)
                    output.putString("result", "PASS: Android confirmation queued one delayed retry; WorkManager ran it and skipped the already-installed APK")
                } else output.putString("result", "PASS: Android returned a real confirmation, its session was abandoned, and the verified APK stayed ready")
            } else {
                val record = saved.history.singleOrNull { it.attemptId == id }
                check(record?.apkSha256 == sha256 && saved.readyFile == null) {
                    "The install did not record the exact APK and clear its waiting state"
                }
                check(after.installedAt > before.installedAt && record.automatic == background)
                output.putString("result", "PASS: ${method.name} quietly reinstalled the same APK and its tracked completion reached history")
            }
            output.putLong("versionCode", after.versionCode)
            output.putString("testSha256", sha256)
        } finally {
            if (verifyRetry) manager.cancelUniqueWork(retryName).await()
            session?.let { runCatching { app.packageManager.packageInstaller.abandonSession(it) } }
            app.repository.update(Silora.ID) { saved ->
                if (saved.installAttempt?.id == id || saved.readyFile == apk.absolutePath) {
                    saved.copy(readyFile = previous.readyFile, readyVersionName = previous.readyVersionName,
                        readyVersionCode = previous.readyVersionCode, readyTag = previous.readyTag,
                        readySha256 = previous.readySha256, installAttempt = null,
                        backgroundInstallBlock = if (tag != null && saved.backgroundInstallBlock?.tag == tag)
                            previous.backgroundInstallBlock else saved.backgroundInstallBlock)
                } else if (verifyRetry && saved.backgroundInstallBlock?.tag == tag) {
                    saved.copy(backgroundInstallBlock = previous.backgroundInstallBlock)
                } else saved
            }
            apk.delete()
        }
    }
}
