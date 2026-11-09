package io.github.edrock200.appdownloader

import android.app.Instrumentation
import android.os.Bundle
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.install.ApkInspector
import io.github.edrock200.appdownloader.install.ApkInstaller
import io.github.edrock200.appdownloader.install.InstalledApps
import io.github.edrock200.appdownloader.install.ShizukuInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Opt-in, signed-release device check. Reinstalls only the device's existing Silora APK. */
class ShizukuDeviceCheck : Instrumentation() {
    private var reinstallCurrent = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
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
                val state = app.shizuku.awaitReady()
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
