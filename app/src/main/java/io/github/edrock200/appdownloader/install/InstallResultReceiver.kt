package io.github.edrock200.appdownloader.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import io.github.edrock200.appdownloader.app

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val controller = context.app().controller
        val entryId = intent.getStringExtra(ApkInstaller.EXTRA_ENTRY_ID) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    controller.onInstallFailed(entryId, status, "Installer did not provide a confirmation screen")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    controller.onInstallFailed(entryId, status, "Could not show the install prompt: ${e.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> controller.onInstallSucceeded(
                entryId = entryId,
                releaseTag = intent.getStringExtra(ApkInstaller.EXTRA_TAG),
                versionCode = intent.getLongExtra(ApkInstaller.EXTRA_VERSION_CODE, -1),
                filePath = intent.getStringExtra(ApkInstaller.EXTRA_FILE),
            )
            else -> controller.onInstallFailed(
                entryId,
                status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
        }
    }
}
