package io.github.edrock200.appdownloader

import android.app.Instrumentation
import android.os.Bundle
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.install.ApkInspector
import io.github.edrock200.appdownloader.install.ApkInstaller
import io.github.edrock200.appdownloader.install.InstalledApps
import io.github.edrock200.appdownloader.install.ShizukuInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import androidx.work.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Opt-in signed checks for player installs, production background checks and updater upgrades. */
class ShizukuDeviceCheck : Instrumentation() {
    private var reinstallCurrent = false
    private var selfUpdateApk: String? = null
    private var verifySelfRecovery = false
    private var runMonitoringNow = false
    private var playbackOnly = false
    private var standardInstall = false
    private var automaticUpdateCheck = false
    private var expectedPlayback: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        selfUpdateApk = arguments?.getString("selfUpdateApk")
        verifySelfRecovery = arguments?.getString("verifySelfRecovery") == "true"
        runMonitoringNow = arguments?.getString("runMonitoringNow") == "true"
        reinstallCurrent = arguments?.getString("reinstallCurrent") == "true"
        playbackOnly = arguments?.getString("playbackOnly") == "true"
        standardInstall = arguments?.getString("installer") == "standard"
        automaticUpdateCheck = arguments?.getString("automaticUpdateCheck") == "true"
        expectedPlayback = arguments?.getString("expectedPlayback")
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            // onStart runs on the instrumentation thread; wait for Application.onCreate to finish.
            runOnMainSync { }
            runBlocking {
                val app = targetContext.app()
                if (playbackOnly) {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val status = io.github.edrock200.appdownloader.install.SiloraMediaSession(app).read()
                    output.putString("playback", status.name)
                    output.putString("selectedInstaller", app.settings.current.installMethod.name)
                    output.putBoolean("waitUntilPlaybackStops", app.settings.current.waitUntilPlaybackStops)
                    output.putBoolean("standardBackgroundEligible",
                        io.github.edrock200.appdownloader.data.InstallPolicy.canInstallInBackground(
                            io.github.edrock200.appdownloader.data.InstallMethod.STANDARD,
                            android.os.Build.VERSION.SDK_INT,
                            io.github.edrock200.appdownloader.ui.SystemActions.canInstallPackages(app), false))
                    output.putLong("queryDurationMs", android.os.SystemClock.elapsedRealtime() - started)
                    output.putBoolean("allowsAutomaticSiloraInstall",
                        io.github.edrock200.appdownloader.install.PlaybackInstallPolicy.allowsInstall(
                            Silora.PACKAGE, true,
                            io.github.edrock200.appdownloader.install.SiloraPlaybackReader { status },
                            waitUntilPlaybackStops = app.settings.current.waitUntilPlaybackStops))
                    output.putString("result", "Playback query only; no download or installation requested")
                    recordUpdateState(app, output)
                    check(status != io.github.edrock200.appdownloader.install.SiloraPlaybackStatus.UNKNOWN) {
                        "Silora's playback state could not be read; automatic player installs would defer"
                    }
                    return@runBlocking
                }
                if (automaticUpdateCheck) {
                    val saved = app.settings.current
                    check(saved.autoInstallUpdates && saved.waitUntilPlaybackStops && saved.checkIntervalHours > 0) {
                        "Enable automatic installation, playback protection and background checks before this test"
                    }
                    check(saved.installMethod == io.github.edrock200.appdownloader.data.InstallMethod.STANDARD) {
                        "This check requires the Standard installer"
                    }
                    check(expectedPlayback == "PLAYING" || expectedPlayback == "IDLE") {
                        "Pass expectedPlayback=PLAYING or IDLE to explicitly opt into a real automatic update"
                    }
                    check(!app.controller.installRestore.value.active && !app.controller.updatingAll.value) {
                        "An install/restore is already active"
                    }
                    val before = InstalledApps.get(app, Silora.PACKAGE) ?: error("Silora must be installed")
                    val playback = io.github.edrock200.appdownloader.install.SiloraMediaSession(app).read()
                    check(playback.name == expectedPlayback) { "Expected $expectedPlayback but read $playback; no work enqueued" }
                    val initial = app.repository.get(Silora.ID)!!
                    if (expectedPlayback == "IDLE") {
                        check(initial.playbackDeferral == io.github.edrock200.appdownloader.data.PlaybackDeferral.ACTIVE &&
                            initial.readyFile?.let { File(it).isFile } == true) { "Retry requires a real playback-deferred APK" }
                        output.putString("readySha256Before", io.github.edrock200.appdownloader.install.InstallRestoreStore.sha256(File(initial.readyFile!!)))
                    }
                    val startedAt = System.currentTimeMillis()
                    val manager = androidx.work.WorkManager.getInstance(app)
                    val request = androidx.work.OneTimeWorkRequestBuilder<io.github.edrock200.appdownloader.work.UpdateCheckWorker>()
                        .setConstraints(androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                        .build()
                    // The production worker performs discovery, download, playback gating and installation.
                    // A separate one-time request avoids changing the user's periodic cadence or its minimum interval.
                    var finished = false
                    try {
                        manager.enqueueUniqueWork("device-check-automatic-update",
                            androidx.work.ExistingWorkPolicy.REPLACE, request).await()
                        val work = withTimeout(120_000) {
                            manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }!!
                        }
                        finished = true
                        output.putString("workerState", work.state.name)
                        output.putString("workId", request.id.toString())
                        check(work.state == androidx.work.WorkInfo.State.SUCCEEDED) { "Worker did not succeed: ${work.state}" }
                        if (expectedPlayback == "PLAYING") {
                            val entry = app.repository.get(Silora.ID)!!
                            check(InstalledApps.get(app, Silora.PACKAGE)?.versionCode == before.versionCode) { "Player changed during playback" }
                            check(entry.playbackDeferral == io.github.edrock200.appdownloader.data.PlaybackDeferral.ACTIVE &&
                                entry.readyFile?.let { File(it).isFile } == true) { "Expected a retained APK deferred for active playback" }
                            check(app.packageManager.packageInstaller.mySessions.none { it.appPackageName == Silora.PACKAGE }) {
                                "Unexpected Silora install session while playback was active"
                            }
                        } else {
                            val targetCode = app.repository.get(Silora.ID)!!.latestVersionCode ?: error("No release version")
                            check(targetCode > before.versionCode) { "No newer Silora version to install" }
                            withTimeout(30_000) {
                                while (InstalledApps.get(app, Silora.PACKAGE)?.versionCode != targetCode ||
                                    app.repository.get(Silora.ID)!!.history.none {
                                        it.at >= startedAt && it.versionCode == targetCode && it.byApp && it.automatic
                                    }) delay(100)
                            }
                            check(app.repository.get(Silora.ID)!!.readyFile == null &&
                                app.repository.get(Silora.ID)!!.playbackDeferral == null) { "Successful install left stale waiting state" }
                            val installedApk = File(app.packageManager.getApplicationInfo(Silora.PACKAGE, 0).sourceDir)
                            output.putString("installedSha256", io.github.edrock200.appdownloader.install.InstallRestoreStore.sha256(installedApk))
                        }
                        check(app.settings.current == saved) { "Saved update settings changed during this check" }
                        recordUpdateState(app, output)
                        output.putString("result", if (expectedPlayback == "PLAYING")
                            "PASS: production worker downloaded a newer release and deferred installation for real playback"
                            else "PASS: production worker retried the deferred release, installed it, cleared waiting state and recorded an automatic install")
                    } finally {
                        if (!finished) manager.cancelWorkById(request.id).await()
                    }
                    return@runBlocking
                }
                val marker = File(app.filesDir, "self-update-device-check-start")
                if (runMonitoringNow) {
                    val state = app.settings.current
                    check(state.checkIntervalHours > 0) { "Background checks are disabled" }
                    // Advance one occurrence of the EXISTING periodic work. ADB's JobScheduler
                    // force flag alone cannot bypass WorkManager's own next-run-time guard.
                    val request = androidx.work.PeriodicWorkRequestBuilder<io.github.edrock200.appdownloader.work.UpdateCheckWorker>(
                        if (state.hasDailyCheck) 24L else state.checkIntervalHours.toLong(), java.util.concurrent.TimeUnit.HOURS)
                        .setConstraints(androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                        .setNextScheduleTimeOverride(System.currentTimeMillis())
                        .build()
                    androidx.work.WorkManager.getInstance(app).enqueueUniquePeriodicWork("github-update-check",
                        androidx.work.ExistingPeriodicWorkPolicy.UPDATE, request).await()
                    output.putString("result", "Advanced one monitoring occurrence; saved settings are unchanged")
                    return@runBlocking
                }
                if (verifySelfRecovery) {
                    val (startedAt, targetCode) = marker.readLines().map(String::toLong)
                    check(BuildConfig.VERSION_CODE.toLong() == targetCode) { "The newer updater was not installed" }
                    val self = io.github.edrock200.appdownloader.data.SelfUpdateStore(app).current
                    val player = app.repository.get(Silora.ID)!!
                    check(self.readyFile == null) { "The completed self-update is still pending" }
                    for (entry in listOf(player, self)) {
                        check((entry.lastCheckedAt ?: 0) >= startedAt && entry.lastCheckError == null) {
                            "${entry.name} did not successfully check GitHub after the upgrade: ${entry.lastCheckError}"
                        }
                    }
                    val work = androidx.work.WorkManager.getInstance(app)
                        .getWorkInfosForUniqueWork("github-update-check").get()
                    check(work.count { !it.state.isFinished } == 1) { "Monitoring is not scheduled exactly once" }
                    output.putString("result", "PASS: upgraded process, cleared staged APK, successful player/updater GitHub checks, monitoring still scheduled")
                    marker.delete()
                    return@runBlocking
                }
                // Standard checks must not initialize, authorize or use Shizuku.
                val state = if (standardInstall) null else app.shizuku.awaitReady()
                selfUpdateApk?.let { path ->
                    check(state?.ready == true) { state?.help ?: "Self-upgrade check requires Shizuku" }
                    check(app.settings.current.checkIntervalHours > 0) { "Enable background checks before testing monitoring recovery" }
                    val source = File(path)
                    val info = withContext(Dispatchers.IO) { ApkInspector.inspect(app, source) }
                    check(info.packageName == app.packageName && info.versionCode > BuildConfig.VERSION_CODE) {
                        "Pass a newer, signed Silora Updater APK to the opt-in self-upgrade check"
                    }
                    val tag = "v${info.versionName}"
                    check(io.github.edrock200.appdownloader.data.SiloraUpdater.versionCode(tag) == info.versionCode)
                    val cached = File(app.cacheDir, "self-update-device-check.apk")
                    withContext(Dispatchers.IO) { source.copyTo(cached, overwrite = true) }
                    io.github.edrock200.appdownloader.data.SelfUpdateStore(app).update {
                        it.copy(latestTag = tag, latestVersionCode = info.versionCode,
                            latestAssetUrl = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/$tag/SiloraUpdater-tv.apk",
                            readyFile = cached.absolutePath, readyVersionCode = info.versionCode,
                            readyVersionName = info.versionName, readyTag = tag)
                    }
                    app.settings.update(app.settings.current.copy(
                        installMethod = io.github.edrock200.appdownloader.data.InstallMethod.SHIZUKU))
                    marker.writeText("${System.currentTimeMillis()}\n${info.versionCode}")
                    // Recreate the production controller from its durable state, as on an offline launch.
                    val controller = AppController(app, app.repository, app.settings, app.scope)
                    // Install callbacks must reach this same recreated coordinator.
                    App::class.java.getDeclaredField("controller").apply { isAccessible = true }.set(app, controller)
                    runOnMainSync { controller.installSelfUpdate() }
                    withTimeout(60_000) {
                        controller.ops.first { it[AppController.SELF_UPDATE_ID] is OpState.Failed }.let {
                            error("Self-install failed: ${it[AppController.SELF_UPDATE_ID]}")
                        }
                    }
                    error("The self-install should kill this instrumentation process; verify recovery from ADB")
                }

                check(reinstallCurrent) { "Pass -e reinstallCurrent true to explicitly opt into reinstalling the existing APK" }
                if (standardInstall) {
                    check(app.settings.current.installMethod == io.github.edrock200.appdownloader.data.InstallMethod.STANDARD) {
                        "Select Standard installation before running the Standard device check"
                    }
                    check(io.github.edrock200.appdownloader.data.InstallPolicy.canInstallInBackground(
                        io.github.edrock200.appdownloader.data.InstallMethod.STANDARD,
                        android.os.Build.VERSION.SDK_INT,
                        io.github.edrock200.appdownloader.ui.SystemActions.canInstallPackages(app), false)) {
                        "Standard background installation needs Android 12+ and install access"
                    }
                    check(app.repository.get(Silora.ID)?.readyFile == null && !app.controller.installRestore.value.active) {
                        "Finish any pending Silora install/restore before running a reinstall check"
                    }
                    output.putString("installer", "STANDARD")
                } else {
                    output.putString("shizuku", state!!.name)
                    check(state.ready) { state.help }
                }
                val installed = InstalledApps.get(app, Silora.PACKAGE) ?: error("Silora must already be installed")
                val packageInfo = app.packageManager.getPackageInfo(Silora.PACKAGE, 0)
                check(packageInfo.splitNames.isNullOrEmpty()) { "This check requires the normal single-APK Silora build" }
                val file = File(packageInfo.applicationInfo!!.sourceDir)
                val info = withContext(Dispatchers.IO) { ApkInspector.inspect(app, file) }
                check(info.packageName == Silora.PACKAGE && info.versionCode == installed.versionCode)
                val startedAt = System.currentTimeMillis()
                // Own temporary copy: production cleanup must never touch the installed base.apk.
                val cached = File(app.cacheDir, "device-check-silora.apk")
                withContext(Dispatchers.IO) { file.copyTo(cached, overwrite = true) }
                try {
                    if (standardInstall) {
                        val playback = io.github.edrock200.appdownloader.install.SiloraMediaSession(app).read()
                        output.putString("playback", playback.name)
                        check(io.github.edrock200.appdownloader.install.PlaybackInstallPolicy.allowsInstall(
                            Silora.PACKAGE, true,
                            io.github.edrock200.appdownloader.install.SiloraPlaybackReader { playback },
                            waitUntilPlaybackStops = app.settings.current.waitUntilPlaybackStops)) {
                            "Playback protection deferred the Standard install: $playback"
                        }
                        withContext(Dispatchers.IO) {
                            ApkInstaller.install(app, cached, info, Silora.ID, null, background = true,
                                previousVersionName = installed.versionName, previousVersionCode = installed.versionCode)
                        }
                    } else {
                        val callback = ApkInstaller.resultCallback(app, 0, cached, info, Silora.ID, null,
                            false, installed.versionName, installed.versionCode, viaShizuku = true)
                        ShizukuInstaller.install(app, cached, info, callback.intentSender)
                    }
                    withTimeout(15_000) {
                        while (app.repository.get(Silora.ID)?.history?.none {
                                it.at >= startedAt && it.versionCode == installed.versionCode && it.byApp
                            } != false) delay(100)
                    }
                    check(InstalledApps.get(app, Silora.PACKAGE)?.versionCode == installed.versionCode)
                    output.putString("result", if (standardInstall)
                        "PASS: playback gate, Standard background install, result receiver, and history; no Shizuku"
                        else "PASS: Shizuku session, APK stream, commit, result receiver, and history")
                    output.putLong("versionCode", installed.versionCode)
                } finally {
                    withContext(Dispatchers.IO) { cached.delete() }
                }
            }
            finish(-1, output)
        } catch (error: Exception) {
            output.putString("result", "FAIL: ${error.message}")
            output.putString("error", error.stackTraceToString().take(8000))
            finish(0, output)
        }
    }

    private fun recordUpdateState(app: App, output: Bundle) {
        val entry = app.repository.get(Silora.ID)!!
        val installed = InstalledApps.get(app, Silora.PACKAGE)
        val settings = app.settings.current
        output.putLong("installedVersionCode", installed?.versionCode ?: -1)
        output.putString("installedVersionName", installed?.versionName)
        output.putLong("installedAt", installed?.installedAt ?: 0)
        output.putString("latestTag", entry.latestTag)
        output.putLong("latestVersionCode", entry.latestVersionCode ?: -1)
        output.putString("lastCheckError", entry.lastCheckError ?: "none")
        output.putString("playbackDeferral", entry.playbackDeferral?.name ?: "none")
        val ready = entry.readyFile?.let(::File)?.takeIf { it.isFile }
        output.putBoolean("readyFileExists", ready != null)
        output.putLong("readyVersionCode", entry.readyVersionCode ?: -1)
        ready?.let { output.putString("readySha256", io.github.edrock200.appdownloader.install.InstallRestoreStore.sha256(it)) }
        output.putBoolean("automaticInstallation", settings.autoInstallUpdates)
        output.putBoolean("waitUntilPlaybackStops", settings.waitUntilPlaybackStops)
        output.putBoolean("dailyCheck", settings.hasDailyCheck)
        output.putInt("checkHour", settings.checkHour)
        output.putInt("checkIntervalHours", settings.checkIntervalHours)
        entry.history.firstOrNull()?.let {
            output.putLong("lastHistoryVersionCode", it.versionCode)
            output.putBoolean("lastHistoryAutomatic", it.automatic)
        }
        val periodic = androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork("github-update-check").get()
            .filter { !it.state.isFinished }
        output.putInt("activePeriodicChecks", periodic.size)
        periodic.singleOrNull()?.let {
            output.putString("periodicWorkId", it.id.toString())
            output.putLong("nextPeriodicCheckAt", it.nextScheduleTimeMillis)
        }
    }
}
