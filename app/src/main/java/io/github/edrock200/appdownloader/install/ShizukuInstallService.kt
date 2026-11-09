package io.github.edrock200.appdownloader.install

import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import io.github.edrock200.appdownloader.data.Silora
import kotlin.system.exitProcess
import java.util.Timer
import java.util.TimerTask

/** Loaded by Shizuku, not an exported Android service. Only installs the fixed Silora package. */
@Keep
class ShizukuInstallService : IShizukuInstaller.Stub() {
    private var cleanup = stopAfter(30_000)

    private fun stopAfter(delay: Long) = Timer("shizuku-cleanup", true).apply {
        schedule(object : TimerTask() { override fun run() = exitProcess(0) }, delay)
    }

    override fun getUid(): Int = Process.myUid()

    @Synchronized
    override fun install(apks: Array<ParcelFileDescriptor>, names: Array<String>, sizes: LongArray, result: IntentSender) {
        cleanup.cancel()
        val status = try {
            require(apks.size == names.size && apks.size == sizes.size) { "Invalid APK input" }
            val sources = apks.mapIndexed { index, fd ->
                InstallSource(names[index], sizes[index], ParcelFileDescriptor.AutoCloseInputStream(fd))
            }
            // Preserve the caller's Android user/profile, including when Shizuku runs as root.
            ShellPackageInstaller(InstallProcessRunner()).install(sources, Silora.PACKAGE, Binder.getCallingUid() / 100_000)
            PackageInstaller.STATUS_SUCCESS
        } catch (error: Exception) {
            val message = error.message ?: "Shizuku could not install Silora"
            Log.w("ShizukuInstall", message.take(700))
            when {
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in message || "INSTALL_FAILED_CONFLICTING_PROVIDER" in message -> PackageInstaller.STATUS_FAILURE_CONFLICT
                "INSTALL_FAILED_INSUFFICIENT_STORAGE" in message -> PackageInstaller.STATUS_FAILURE_STORAGE
                "INSTALL_FAILED_NO_MATCHING_ABIS" in message || "INSTALL_FAILED_OLDER_SDK" in message -> PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                else -> PackageInstaller.STATUS_FAILURE
            }
        } finally {
            apks.forEach { runCatching { it.close() } }
        }
        // No normal app Context exists here. Sending no fill-in Intent avoids ContentResolver;
        // the result code carries the status, and the immutable PendingIntent keeps the metadata.
        try {
            result.sendIntent(null, status, null, null, null)
        } catch (error: Exception) {
            throw IllegalStateException("Installation finished but its result could not be recorded. Reopen the updater.", error)
        } finally {
            // Also exit if the caller died and cannot unbind this daemon after completion.
            cleanup = stopAfter(10_000)
        }
    }

    override fun destroy() = exitProcess(0)
}
