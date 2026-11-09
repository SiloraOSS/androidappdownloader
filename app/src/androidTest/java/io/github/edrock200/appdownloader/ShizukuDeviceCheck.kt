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

/** Opt-in signed checks: reinstall the current player, or perform a staged updater upgrade. */
class ShizukuDeviceCheck : Instrumentation() {
    private var reinstallCurrent = false
    private var selfUpdateApk: String? = null
    private var verifySelfRecovery = false
    private var runMonitoringNow = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        selfUpdateApk = arguments?.getString("selfUpdateApk")
        verifySelfRecovery = arguments?.getString("verifySelfRecovery") == "true"
        runMonitoringNow = arguments?.getString("runMonitoringNow") == "true"
        reinstallCurrent = arguments?.getString("reinstallCurrent") == "true"
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            // onStart runs on the instrumentation thread; wait for Application.onCreate to finish.
            runOnMainSync { }
            runBlocking {
                val app = targetContext.app()
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
                val state = app.shizuku.awaitReady()
                selfUpdateApk?.let { path ->
                    check(state.ready) { state.help }
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

                output.putString("shizuku", state.name)
                check(state.ready) { state.help }
                check(reinstallCurrent) { "Pass -e reinstallCurrent true to explicitly opt into reinstalling the existing APK" }
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
                    val callback = ApkInstaller.resultCallback(app, 0, cached, info, Silora.ID, null,
                        false, installed.versionName, installed.versionCode, viaShizuku = true)
                    ShizukuInstaller.install(app, cached, info, callback.intentSender)
                    withTimeout(15_000) {
                        while (app.repository.get(Silora.ID)?.history?.none {
                                it.at >= startedAt && it.versionCode == installed.versionCode && it.byApp
                            } != false) delay(100)
                    }
                    check(InstalledApps.get(app, Silora.PACKAGE)?.versionCode == installed.versionCode)
                    output.putString("result", "PASS: Shizuku session, APK stream, commit, result receiver, and history")
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
}
