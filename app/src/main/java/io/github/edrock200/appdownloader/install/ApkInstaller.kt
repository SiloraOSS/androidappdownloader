package io.github.edrock200.appdownloader.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/** Installs APKs and split bundles through the PackageInstaller session API. */
object ApkInstaller {

    const val EXTRA_ENTRY_ID = "entry_id"
    const val EXTRA_TAG = "release_tag"
    const val EXTRA_VERSION_CODE = "version_code"
    const val EXTRA_FILE = "file"
    const val EXTRA_PACKAGE = "package"
    /** Set for scheduled runs: don't pop up the confirmation screen, leave the update waiting instead. */
    const val EXTRA_BACKGROUND = "background"

    fun install(
        context: Context,
        file: File,
        info: ApkInfo,
        entryId: String,
        releaseTag: String?,
        background: Boolean = false,
    ) {
        val installer = context.packageManager.packageInstaller
        // Drop earlier sessions for the same app that were never confirmed.
        installer.mySessions.filter { it.appPackageName == info.packageName }.forEach {
            runCatching { installer.abandonSession(it.sessionId) }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(info.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Lets updates of apps we installed proceed without a prompt where allowed.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            if (info.isSplit) {
                ZipFile(file).use { zip ->
                    info.splitEntries.forEachIndexed { index, name ->
                        val entry = zip.getEntry(name)
                        val splitName = "${index}_" + name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                        session.openWrite(splitName, 0, entry.size).use { out ->
                            zip.getInputStream(entry).use { it.copyTo(out) }
                            session.fsync(out)
                        }
                    }
                }
            } else {
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }

            val callback = Intent(context, InstallResultReceiver::class.java)
                .putExtra(EXTRA_ENTRY_ID, entryId)
                .putExtra(EXTRA_TAG, releaseTag)
                .putExtra(EXTRA_VERSION_CODE, info.versionCode)
                .putExtra(EXTRA_FILE, file.absolutePath)
                .putExtra(EXTRA_PACKAGE, info.packageName)
                .putExtra(EXTRA_BACKGROUND, background)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
            session.close()
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }
}
