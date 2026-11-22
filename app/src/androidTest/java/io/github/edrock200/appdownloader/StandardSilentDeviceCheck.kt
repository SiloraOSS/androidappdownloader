package io.github.edrock200.appdownloader

import android.os.Build
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.InstallPolicy
import io.github.edrock200.appdownloader.data.PlaybackDeferral
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.install.ApkInspector
import io.github.edrock200.appdownloader.install.InstallRestoreStore
import io.github.edrock200.appdownloader.install.InstalledApps
import io.github.edrock200.appdownloader.install.SiloraMediaSession
import io.github.edrock200.appdownloader.install.SiloraPlaybackReader
import io.github.edrock200.appdownloader.install.SiloraPlaybackStatus
import io.github.edrock200.appdownloader.ui.SystemActions
import io.github.edrock200.appdownloader.work.UpdateCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Standard-installer checks with one real PackageInstaller upgrade of a published Silora APK. */
internal object StandardSilentDeviceCheck {
    suspend fun run(app: App, mode: String, output: Bundle) {
        check(Build.VERSION.SDK_INT >= 31) { "Standard silent installation requires Android 12+" }
        val settings = app.settings.current
        check(settings.installMethod == InstallMethod.STANDARD && settings.waitUntilPlaybackStops) {
            "Select Standard and enable playback protection to run this check"
        }
        check(!app.controller.isBusy(Silora.ID) && !app.controller.updatingAll.value) {
            "Resolve the current install or restore before running this check"
        }
        val before = InstalledApps.get(app, Silora.PACKAGE) ?: error("Silora must already be installed")
        val source = app.packageManager.getInstallSourceInfo(Silora.PACKAGE)
        val installedFile = File(app.packageManager.getApplicationInfo(Silora.PACKAGE, 0).sourceDir)
        val info = withContext(Dispatchers.IO) { ApkInspector.inspect(app, installedFile) }
        val access = SystemActions.canInstallPackages(app)
        val eligible = InstallPolicy.standardCanUpdateSilently(app, info)
        val sdkFloor = InstallPolicy.minimumSilentTarget(Build.VERSION.SDK_INT)
        output.putInt("androidSdk", Build.VERSION.SDK_INT)
        output.putInt("playerTargetSdk", info.targetSdk)
        output.putInt("minimumSilentTargetSdk", sdkFloor)
        output.putString("installerOfRecord", source.installingPackageName ?: "none")
        output.putString("updateOwner", if (Build.VERSION.SDK_INT >= 34) source.updateOwnerPackageName ?: "none" else "unavailable")
        output.putBoolean("unknownAppAccess", access)
        output.putBoolean("installerAndTargetEligible", eligible)
        if (mode == "standardPreflight") {
            check(!InstallPolicy.standardCanUpdateSilently(app, info.copy(targetSdk = sdkFloor - 1)))
            output.putString("result", "PASS: current installer attribution and Android target requirement checked")
            return
        }
        check(eligible) { "Expected the updater to own the current Silora install" }
        check(access == (mode != "standardPermissionDenied")) { "Install access differs from the expected test state" }

        val production = app.controller
        val playback = when (mode) {
            "standardPlaying" -> SiloraPlaybackStatus.PLAYING
            "standardUnknown" -> SiloraPlaybackStatus.UNKNOWN
            else -> null
        }
        val controller = playback?.let { state ->
            AppController(app, app.repository, app.settings, app.scope, SiloraPlaybackReader { state })
        } ?: production
        val checks = withContext(Dispatchers.Main.immediate) {
            controller.checkForWorker() ?: error("Another update check is running")
        }
        val release = checks.entries.getValue(Silora.ID)
        check(release.lastCheckError == null) { release.lastCheckError.orEmpty() }
        val target = checkNotNull(release.latestVersionCode)
        check(target > before.versionCode) { "This device already has the newest Silora APK" }
        val digest = checkNotNull(release.latestAssetDigest)
        output.putLong("beforeVersionCode", before.versionCode)
        output.putLong("targetVersionCode", target)

        if (mode != "standardIdle") {
            val summary = withContext(Dispatchers.Main.immediate) {
                controller.updateSelected(interactive = false, checkedEntries = mapOf(Silora.ID to release))
            }
            check(summary.waiting == listOf("Silora") && summary.installing.isEmpty() && summary.failed.isEmpty()) {
                "Expected a staged APK without starting an install. ${summary.describe()}"
            }
            val entry = app.repository.get(Silora.ID)!!
            val expectedDeferral = when (playback) {
                SiloraPlaybackStatus.PLAYING -> PlaybackDeferral.ACTIVE
                SiloraPlaybackStatus.UNKNOWN -> PlaybackDeferral.UNAVAILABLE
                else -> null
            }
            check(entry.playbackDeferral == expectedDeferral)
            check(entry.readyVersionCode == target && digest.equals("sha256:${entry.readySha256}", ignoreCase = true))
            check(entry.readyFile?.let { File(it).isFile } == true)
            check(entry.installAttempt == null && app.packageManager.packageInstaller.mySessions.none { it.appPackageName == Silora.PACKAGE })
            check(InstalledApps.get(app, Silora.PACKAGE) == before)
            output.putString("playbackDeferral", entry.playbackDeferral?.name ?: "none")
            output.putString("readySha256", entry.readySha256)
            output.putString("result", "PASS: APK stayed verified and ready, with no install session or change to Silora")
            return
        }

        check(settings.autoInstallUpdates && settings.checkIntervalHours > 0)
        val actualPlayback = SiloraMediaSession(app).read()
        check(actualPlayback == SiloraPlaybackStatus.IDLE) { "Real playback state is $actualPlayback; install was not attempted" }
        output.putString("actualPlayback", actualPlayback.name)
        val staged = app.repository.get(Silora.ID)!!
        check(staged.readyVersionCode == target && staged.readyFile?.let { File(it).isFile } == true) {
            "Expected the same APK staged by the preceding checks"
        }
        output.putString("readySha256Before", staged.readySha256)
        val startedAt = System.currentTimeMillis()
        val manager = WorkManager.getInstance(app)
        val attempt = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        try {
            manager.enqueue(attempt).await()
            val job = withTimeout(90_000) { manager.getWorkInfoByIdFlow(attempt.id).first { it?.state?.isFinished == true }!! }
            check(job.state == WorkInfo.State.SUCCEEDED) { "Worker reported ${job.state}" }
            output.putString("workerId", attempt.id.toString())
            val completed = withTimeout(35_000) {
                while (true) {
                    val entry = app.repository.get(Silora.ID)!!
                    val block = entry.backgroundInstallBlock
                    if (block != null && block.tag == release.latestTag) {
                        error("Android blocked this build, needsConfirmation=${block.needsConfirmation}")
                    }
                    if (entry.history.any { it.versionCode == target && it.automatic && it.byApp && it.at >= startedAt }) break
                    delay(100)
                }
                app.repository.get(Silora.ID)!!
            }
            val installed = InstalledApps.get(app, Silora.PACKAGE)
            check(installed?.versionCode == target && installed.installedAt > before.installedAt)
            check(completed.readyFile == null && completed.installAttempt == null && completed.playbackDeferral == null)
            val newFile = File(app.packageManager.getApplicationInfo(Silora.PACKAGE, 0).sourceDir)
            val installedSha256 = withContext(Dispatchers.IO) { InstallRestoreStore.sha256(newFile) }
            check(digest.equals("sha256:$installedSha256", ignoreCase = true))
            check(app.settings.current == settings)
            output.putString("installedSha256", installedSha256)
            output.putString("result", "PASS: the real background worker reused the staged APK and silently installed it through Standard")
        } finally {
            manager.cancelWorkById(attempt.id).await()
        }
    }
}
