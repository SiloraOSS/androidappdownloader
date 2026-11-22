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
        val restoreAttemptId = intent.getStringExtra(ApkInstaller.EXTRA_RESTORE_ATTEMPT)
        val attemptId = intent.getStringExtra(ApkInstaller.EXTRA_ATTEMPT)
        val status = if (intent.getBooleanExtra(ApkInstaller.EXTRA_SHIZUKU, false)) resultCode
            else intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val background = intent.getBooleanExtra(ApkInstaller.EXTRA_BACKGROUND, false)

        val pending = goAsync()
        val job = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> controller.onInstallNeedsConfirmation(
                entryId, attemptId, intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java),
                background, restoreAttemptId,
            )
            PackageInstaller.STATUS_SUCCESS -> controller.onInstallSucceeded(
                entryId = entryId,
                releaseTag = intent.getStringExtra(ApkInstaller.EXTRA_TAG),
                versionCode = intent.getLongExtra(ApkInstaller.EXTRA_VERSION_CODE, -1),
                filePath = intent.getStringExtra(ApkInstaller.EXTRA_FILE),
                background = background,
                previousVersionName = intent.getStringExtra(ApkInstaller.EXTRA_PREV_VERSION_NAME),
                previousVersionCode = intent.getLongExtra(ApkInstaller.EXTRA_PREV_VERSION_CODE, -1L).takeIf { it >= 0 },
                restoreAttemptId = restoreAttemptId,
                attemptId = attemptId,
                versionName = intent.getStringExtra(ApkInstaller.EXTRA_VERSION_NAME),
            )
            else -> controller.onInstallFailed(
                entryId,
                status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                keepDownload = intent.getBooleanExtra(ApkInstaller.EXTRA_KEEP_DOWNLOAD, false),
                restoreAttemptId = restoreAttemptId,
                attemptId = attemptId,
            )
        }
        job.invokeOnCompletion { pending.finish() }
    }
}
